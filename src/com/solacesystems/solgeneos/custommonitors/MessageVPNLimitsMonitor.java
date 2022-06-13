package com.solacesystems.solgeneos.custommonitors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.Vector;
import java.util.stream.Collectors;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.HttpParams;
import com.solacesystems.solgeneos.custommonitors.util.MonitorConstants;
import com.solacesystems.solgeneos.custommonitors.util.MultiFieldSEMPParser;
import com.solacesystems.solgeneos.custommonitors.util.SampleHttpSEMPResponse;
import com.solacesystems.solgeneos.custommonitors.util.SampleResponseHandler;
import com.solacesystems.solgeneos.custommonitors.util.SampleSEMPParser;
import com.solacesystems.solgeneos.custommonitors.util.VPNRecordSEMPParser;
import com.solacesystems.solgeneos.solgeneosagent.SolGeneosAgent;
import com.solacesystems.solgeneos.solgeneosagent.UserPropertiesConfig;
import com.solacesystems.solgeneos.solgeneosagent.monitor.BaseMonitor;
import com.solacesystems.solgeneos.solgeneosagent.monitor.View;

public class MessageVPNLimitsMonitor extends BaseMonitor implements MonitorConstants {
  
	// What version of the monitor?
	static final public String MONITOR_VERSION = "1.1.1";
	
	// The SEMP queries to execute:
    static final public String SHOW_VPN_DETAILS_REQUEST = 
            "<rpc>" + 
            "	<show>" +
            "		<message-vpn>" +
            "			<vpn-name>*</vpn-name>" +
            "			<detail></detail>" +
            "		</message-vpn>" +
            "	</show>" +
            "</rpc>";
    
    static final public String SHOW_VPN_SPOOL_DETAILS_REQUEST = 
            "<rpc>" + 
            "	<show>" +
            "		<message-spool>" +
            "			<vpn-name>*</vpn-name>" +
            "			<detail></detail>" +
            "		</message-spool>" +
            "	</show>" +
            "</rpc>";

    static final public String SHOW_SPOOL_DETAILS_REQUEST = 
            "<rpc>" + 
            "	<show>" +
            "		<message-spool>" +
            "			<detail></detail>" +
            "		</message-spool>" +
            "	</show>" +
            "</rpc>";
 
    static final public String SHOW_SERVICE_DETAILS_REQUEST = 
            "<rpc>" + 
            "	<show>" +
            "		<service>" +
            "		</service>" +
            "	</show>" +
            "</rpc>";    
    
    // The elements of interest/exclusion within the VPN Details SEMP response processing:
    static final private String VPN_DETAILS_RESPONSE_ELEMENT_NAME_ROWS = "vpn";
    static final private  List<String> VPN_DETAILS_RESPONSE_COLUMNS = 
    		Arrays.asList("name", "locally-configured", "local-status", 
    				"total-unique-subscriptions", "max-subscriptions", 
    				"connections-service-smf", "max-connections-service-smf");
    static final private  List<String> VPN_DETAILS_RESPONSE_ELEMENT_NAMES_IGNORE = Arrays.asList("authentication", "semp-over-message-bus-configuration", "event-configuration", "certificate-revocation-check-stats");
    
    // The elements of interest/exclusion within the Spool-VPN Details SEMP response processing:
    static final private String SPOOL_DETAILS_RESPONSE_ELEMENT_NAME_ROWS = "vpn";
    static final private  List<String> SPOOL_DETAILS_RESPONSE_COLUMNS = 
    		Arrays.asList("name",
    				"current-queues-and-topic-endpoints", "maximum-queues-and-topic-endpoints", 
    				"current-spool-usage-mb", "maximum-spool-usage-mb", 
    				"current-transacted-sessions", "maximum-transacted-sessions", 
    				"current-transactions", "maximum-transactions",
    				"current-egress-flows", "maximum-egress-flows",
    				"current-ingress-flows", "maximum-ingress-flows");
    static final private  List<String> SPOOL_DETAILS_RESPONSE_ELEMENT_NAMES_IGNORE = Arrays.asList("event-configuration");

    // From the limits lookup queries, which fields are of interest?
    final private  List<String> SERVICE_DETAILS_LOOKUP_FIELDS = 
    		Arrays.asList("max-connections-service-smf", "max-disk-usage",
    				"message-spool-entities-allowed-by-qendpt", "max-transacted-sessions", 
    				"max-transactions", "ingress-flows-allowed", "flows-allowed");
    
    // What should be the formatting style?
    static final private String FLOAT_FORMAT_STYLE = "%.0f";	// 0 decimal places. Wanted to add thousandth separator but Geneos fails to recognise it as numbers for rule purposes!
    
    // This limit not currently queried for, need to return back to this workaround
    static final private int MAX_SUBSCRIPTIONS_LIMIT = 5000000;
    
    // What is the desired order of columns?
    private List<Integer> desiredColumnOrder;
    
    private Integer localConfigurationStatusColumnID;	// Save this to prevent lookup on each sample
    
    // Override the column names to more human friendly
    static final private List<String> VPN_LIMITS_DATAVIEW_COLUMN_NAMES = 
    		Arrays.asList("Message VPN", "Status", "Subscriptions - Current", "Subscriptions - Max",
    				"Connections (SMF) - Current", "Connections (SMF) - Max", "Queue and TEs - Current", "Queue and TEs - Max",
    				"Spool Usage (MB) - Current", "Spool Usage (MB) - Max", 
    				"Transactions - Current", "Transactions - Max",
    				"Transacted Sessions - Current", "Transacted Sessions - Max",
    				"Egress Flows - Current", "Egress Flows - Max", "Ingress Flows - Current", "Ingress Flows - Max",
    				"Utilisation Score"
    				);
    
    static final private List<String> BROKER_LIMITS_DATAVIEW_COLUMN_NAMES = 
    		Arrays.asList("Resource Type", "Current Usage", "Allocated Limit", "Broker Limit"
    				);
    
    static final String[] RESOURCES = {"Subscriptions", "Connections (SMF)", "Queue and TEs", "Spool Usage (MB)", "Transactions", "Transacted Sessions", "Egress Flows", "Ingress Flows"};
    
    private DefaultHttpClient httpClient;
    private ResponseHandler<SampleHttpSEMPResponse> responseHandler;
    private VPNRecordSEMPParser multiRecordParserVpn;
    private VPNRecordSEMPParser multiRecordParserSpool;


    private Vector<Object> vpnLimitsTableContent;
    private Vector<Object> tempVpnLimitsTableContent;		// Used in the various stages of manipulating the received table
    
    private Vector<Object> brokerLimitsTableContent;
    
    private Map<String, Integer> brokerLimitsLookup;
    
    private LinkedHashMap<String, Object> globalHeadlines = new LinkedHashMap<String, Object>();
    // Is the monitor creating a dataview per VPN or everything is in one view?
    // What is the maximum number of rows to limit the dataview to? Default 200 unless overridden.
    private int maxRows = 200;

    
    // When sorting the table rows before limiting to maxrows, how to prioritise the top of the cut?
    // This comparator is used to sort the table so the highest utilisation score is at the top of the table
    // Note: Assuming the sort is done after columns re-ordered and computed columns added. So use lookup via COLUMN_NAME_OVERRIDE
    static class UtilisationComparator implements Comparator<Object>
    {
        @SuppressWarnings("unchecked")
		public int compare(Object tableRow1, Object tableRow2)
        {
        	int value1 = Integer.parseInt( ((ArrayList<String>)tableRow1).get(VPN_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf("Utilisation Score") ));
        	int value2 = Integer.parseInt( ((ArrayList<String>)tableRow2).get(VPN_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf("Utilisation Score") ));

            return Double.compare(value2, value1);	
        }
    }
    
    /**
     * This method is called after initialisation but before the monitor is started.
     * 
     * It is a good place to initialise any instance variables.
     */
	@Override
	protected void onPostInitialize() throws Exception {
		// use logger provided by the BaseMonitor class
		if (getLogger().isInfoEnabled()) {
			getLogger().info("Initializing http client");
		}
		
		
		// Setup global headlines once to use on each onCollect()
		
		// (1) Are there global headlines to apply to the views created by this monitor?
		UserPropertiesConfig globalHeadlinesPropsConfig = SolGeneosAgent.onlyInstance.
				getUserPropertiesConfig(GLOBAL_HEADLINES_PROPERTIES_FILE_NAME);
		// If the file exists and its not empty, add each property as a headline:
		if (globalHeadlinesPropsConfig != null && globalHeadlinesPropsConfig.getProperties() != null) {
			globalHeadlinesPropsConfig.getProperties().forEach((key, value) -> globalHeadlines.put(key.toString() , value.toString()));
		}
		
		// (2) Add this monitor's important details as headlines
		globalHeadlines.put("Custom Monitor", this.getName() + " v" + MONITOR_VERSION);
		globalHeadlines.put("Sampling Interval (secs)", this.getSamplingRate());		

		// (3) Are there properties specific to this monitor in its config file?
		// This monitor can be operated in two modes: (1) single view, or (2) multi view.
		// In single view, all the queues are listed in the same dataview. In multi view mode, queues are reported in per-VPN dataviews
		
		UserPropertiesConfig monitorPropsConfig = SolGeneosAgent.onlyInstance.
				getUserPropertiesConfig(MONITOR_PROPERTIES_FILE_NAME_PREFIX + this.getName() +MONITOR_PROPERTIES_FILE_NAME_SUFFIX);
		// If the file exists and its not empty, add each property as a headline:
		if (monitorPropsConfig != null && monitorPropsConfig.getProperties() != null) {
			maxRows = Integer.parseInt(monitorPropsConfig.getProperties().get("maxrows").toString());
		}
		globalHeadlines.put("Maximum rows to display", maxRows);
		
		
		// (4) Retrieve SEMP over HTTP properties from global properties
		Properties props = SolGeneosAgent.onlyInstance.getGlobalProperties();
        String host = props.getProperty(MGMT_IP_ADDRESS_PROPERTY_NAME);
        int port = 80;
        try {
        	port = Integer.parseInt(props.getProperty(MGMT_PORT_PROPERTY_NAME));
        } catch (NumberFormatException e) {
    		if (getLogger().isInfoEnabled()) {
    			getLogger().info("Invalid port number, using default 80");
    		}
        }
        String username = props.getProperty(MGMT_USERNAME_PROPERTY_NAME);
        String password = SolGeneosAgent.onlyInstance.getEncryptedProperty(MGMT_ENCRYPTED_PASSWORD_PROPERTY_NAME,MGMT_PASSWORD_PROPERTY_NAME);
		
		// create a http client
		httpClient = new DefaultHttpClient();
		HttpParams httpParams = httpClient.getParams();
				
		// set connection target
	    HttpHost target = new HttpHost(host, port);
	    httpParams.setParameter(ClientPNames.DEFAULT_HOST, target);
        
        // set connection credential
	    httpClient.getCredentialsProvider().setCredentials(
	    		new AuthScope(host, port),
                new UsernamePasswordCredentials(username, password));
	    
	    // response handler used http client to process http response and release
	    // associated resources
        responseHandler = new SampleResponseHandler();
        
        // create SEMP parser with the name of the element that contains the records
		multiRecordParserVpn = new VPNRecordSEMPParser(VPN_DETAILS_RESPONSE_ELEMENT_NAME_ROWS, VPN_DETAILS_RESPONSE_COLUMNS, VPN_DETAILS_RESPONSE_ELEMENT_NAMES_IGNORE);
		multiRecordParserSpool = new VPNRecordSEMPParser(SPOOL_DETAILS_RESPONSE_ELEMENT_NAME_ROWS, SPOOL_DETAILS_RESPONSE_COLUMNS, SPOOL_DETAILS_RESPONSE_ELEMENT_NAMES_IGNORE);
		
		setBrokerLimits();
	}
	
	private void setDesiredColumnOrder (List<String> currentColumnNames) {
	    
		desiredColumnOrder = Arrays.asList(
			currentColumnNames.indexOf("name"), currentColumnNames.indexOf("local-status"),  
    		currentColumnNames.indexOf("total-unique-subscriptions"), currentColumnNames.indexOf("max-subscriptions"),
    		currentColumnNames.indexOf("connections-service-smf"), currentColumnNames.indexOf("max-connections-service-smf"),
    		
    		currentColumnNames.indexOf("current-queues-and-topic-endpoints"), currentColumnNames.indexOf("maximum-queues-and-topic-endpoints"),
    		currentColumnNames.indexOf("current-spool-usage-mb"), currentColumnNames.indexOf("maximum-spool-usage-mb"),
    		currentColumnNames.indexOf("current-transactions"), currentColumnNames.indexOf("maximum-transactions"),
    		currentColumnNames.indexOf("current-transacted-sessions"), currentColumnNames.indexOf("maximum-transacted-sessions"),
    		
    		currentColumnNames.indexOf("current-egress-flows"), currentColumnNames.indexOf("maximum-egress-flows"),
    		currentColumnNames.indexOf("current-ingress-flows"), currentColumnNames.indexOf("maximum-ingress-flows")
		);
	}
	
	private void submitSEMPQuery (String sempQuery, SampleSEMPParser sempParser) throws Exception {
		
		// Get the first SEMP query response...
		HttpPost post = new HttpPost(HTTP_REQUEST_URI);
		post.setHeader(HEADER_CONTENT_TYPE_UTF8);
		post.setEntity(new ByteArrayEntity(sempQuery.getBytes("UTF-8")));
		
		
		SampleHttpSEMPResponse resp = httpClient.execute(post, responseHandler);
        if (resp.getStatusCode() != 200) {
        	throw new Exception("Error occurred while sending request: " + resp.getStatusCode() 
        			+ " - " + resp.getReasonPhrase());
        }	        
        String respBody = resp.getRespBody();        
        sempParser.parse(respBody);
	}
    
	private void setBrokerLimits () throws Exception  {

		// Need to first get the various limits from SEMP queries, then map back to a friendly name
		//     		Arrays.asList("max-connections-service-smf", "max-disk-usage",
		//		"message-spool-entities-allowed-by-qendpt", "max-transacted-sessions", 
		//		"max-transactions", "ingress-flows-allowed", "flows-allowed");

		HashMap<String, String> interestedFields = new HashMap<String, String>();
		
		this.SERVICE_DETAILS_LOOKUP_FIELDS.forEach(
				field -> interestedFields.put(field, ""));
		
	    MultiFieldSEMPParser multiFieldParser = new MultiFieldSEMPParser(interestedFields);
	    
		submitSEMPQuery(SHOW_SERVICE_DETAILS_REQUEST, multiFieldParser);
		submitSEMPQuery(SHOW_SPOOL_DETAILS_REQUEST, multiFieldParser);
		
		brokerLimitsLookup = new HashMap<String, Integer>();
		brokerLimitsLookup.put("Connections (SMF) - Limit", Integer.parseInt(interestedFields.get("max-connections-service-smf")));
		brokerLimitsLookup.put("Ingress Flows - Limit", Integer.parseInt(interestedFields.get("ingress-flows-allowed")));
		brokerLimitsLookup.put("Egress Flows - Limit", Integer.parseInt(interestedFields.get("flows-allowed")));
		brokerLimitsLookup.put("Transactions - Limit", Integer.parseInt(interestedFields.get("max-transactions")));
		brokerLimitsLookup.put("Transacted Sessions - Limit", Integer.parseInt(interestedFields.get("max-transacted-sessions")));
		brokerLimitsLookup.put("Queue and TEs - Limit", Integer.parseInt(interestedFields.get("message-spool-entities-allowed-by-qendpt")));
		brokerLimitsLookup.put("Spool Usage (MB) - Limit", Integer.parseInt(interestedFields.get("max-disk-usage")));
		brokerLimitsLookup.put("Subscriptions - Limit", MAX_SUBSCRIPTIONS_LIMIT);
		
	}
	
	/**
	 * This method is responsible to collect data required for a view.
	 * @return The next monitor state which should be State.REPORTING_QUEUE.
	 */

	@SuppressWarnings({ "unchecked", "static-access" })
	@Override
	protected State onCollect() throws Exception {

		TreeMap<String, View> viewMap = getViewMap();
		
		LinkedHashMap<String, Object> headlinesVpnLimits;
		LinkedHashMap<String, Object> headlinesBrokerLimits;
		
		// Get the first SEMP query response...
		submitSEMPQuery(SHOW_VPN_DETAILS_REQUEST, multiRecordParserVpn);
		// Get the second SEMP query response...
		submitSEMPQuery(SHOW_VPN_SPOOL_DETAILS_REQUEST, multiRecordParserSpool);
		
		// Has the desired column order been determined yet? (Done on the first time responses and their columns came back.)
		if (this.desiredColumnOrder == null) {
			// First get the column names from the VPN details response
			List<String> currentColumnNames = multiRecordParserVpn.getColumnNames();
			// Then add the column names from the Spool details response after removing the VPN name column
			List<String> tempColumnNames = multiRecordParserSpool.getColumnNames();
			tempColumnNames.remove(0);
			currentColumnNames.addAll(tempColumnNames);
			
			// Then use this merged columns information to set the final display order
			this.setDesiredColumnOrder(currentColumnNames);
			
			// Save the ID for the local-configuration column too
			localConfigurationStatusColumnID = currentColumnNames.indexOf("locally-configured");
		}
		
		// Have the broker limits been successfully initialized? Expected to be done in onPostInitialize() but double check before using it
		if (this.brokerLimitsLookup == null) {
			getLogger().error("Broker limits were not initialized in onPostInitialize(). Will attempt again now inside onCollect()");
			
			try {
				this.setBrokerLimits();
			}
			catch (Exception e) {
    			getLogger().error("An exception occurred during broker limits initialisation attempt inside onCollect(). " + e.getMessage());
    			// Whatever is going wrong here, likely will affect remainder of onCollect() so just give up this run
    			throw new Exception("Broker limits is failing to initialize, purposefully aborting onCollect()");
			}
		}
		
		// First remove VPN entries that are 'locally-configured=false', they are not full VPN entries, but discovered from the Multi-Node Routing Network
		// Will iterate the initial 'dirty' VPN data and filter rows to create the 'clean' VPN data.
		HashMap<String, ArrayList<String>> vpnData = new LinkedHashMap<String, ArrayList<String>>();
		HashMap<String, ArrayList<String>> vpnDataDirty;
		ArrayList<String> tableRowVpn ;
		String localConfigurationStatus;
		
		vpnDataDirty = multiRecordParserVpn.getData();

		for (Map.Entry<String, ArrayList<String>> entry : vpnDataDirty.entrySet()){			
			tableRowVpn = entry.getValue();
			
			localConfigurationStatus = tableRowVpn.get(localConfigurationStatusColumnID);
			if (localConfigurationStatus.equalsIgnoreCase("true")) {
				vpnData.put(entry.getKey(), tableRowVpn);
			}
		}
		
		// Now merge the two responses, keyed on the vpn-name, into a combined table
		HashMap<String, ArrayList<String>> spoolData = multiRecordParserSpool.getData();		
		vpnLimitsTableContent = new Vector<Object>();				
		ArrayList<String> tableRowSpool;

		for (Map.Entry<String, ArrayList<String>> entry : vpnData.entrySet()){			
			tableRowVpn = entry.getValue();
			tableRowSpool = spoolData.get(entry.getKey());
			
			// Remove the first column that is VPN name from second dataset
			tableRowSpool.remove(0);
			
			// Merge second dataset with first
			tableRowVpn.addAll(tableRowSpool);
			
			vpnLimitsTableContent.add(tableRowVpn);
		}	
		
		// Reorder the merged table into the column order we want. 
		// (Will also drop any such as 'locally-configured' that are not needed in the final output.)
		tempVpnLimitsTableContent = new Vector<Object>();
		
		// Iterate to each row in the table contents
		for (int index = 0; index < vpnLimitsTableContent.size(); index++) {
			
			// Build a new tableRow by adding to it in the right order 
			ArrayList<String> tableRow = new ArrayList<String>();
			
			for (Integer columnNumber : this.desiredColumnOrder){
				tableRow.add( ((ArrayList<String>)vpnLimitsTableContent.get(index)).get(columnNumber));
			}
			// Add the newly created row to reorderedTableContent
			tempVpnLimitsTableContent.add(tableRow); 
		}  
		
		vpnLimitsTableContent = tempVpnLimitsTableContent;
		
		// From the table remove VPNs that are Disabled as not participating in limit usage or allocation
		// Also remove the system VPNs beginning with # where they are auto configured and cannot be edited anyway
		// While here, add a new calculated column too on utilisation score...
		
		Iterator<Object> itr = vpnLimitsTableContent.iterator();	
		while (itr.hasNext()) {		
			ArrayList<String> tempTableRow = (ArrayList<String>) itr.next();
			String tempVpnName = tempTableRow.get(VPN_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf("Message VPN"));
			String tempVpnStatus = tempTableRow.get(VPN_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf("Status"));
			
			if (tempVpnName.startsWith("#") || tempVpnStatus.equalsIgnoreCase("Disabled")) {
				itr.remove();
			}
			else
			{
				// Add a new utlisation score calculated column
				double score = 0;
				int nUnusedResource = 0;
				for (String resourceName : RESOURCES) {
					double current = Double.parseDouble(tempTableRow.get(VPN_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf(resourceName + " - Current")));
					double max = Double.parseDouble(tempTableRow.get(VPN_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf(resourceName + " - Max")));
					
					if (max == 0) {
						nUnusedResource++;
					}
					else {
						score += current / max;
					}
				}
				tempTableRow.add(String.format(FLOAT_FORMAT_STYLE, ((score / (RESOURCES.length - nUnusedResource) ) * 100 )) );
			}
		}  
		vpnLimitsTableContent = tempVpnLimitsTableContent;
		
		// Now calculate the headlines
		headlinesVpnLimits = new LinkedHashMap<String, Object>();
		headlinesVpnLimits.putAll(globalHeadlines);	
		
		String lastSampleTime = SolGeneosAgent.onlyInstance.getCurrentTimeString();
		headlinesVpnLimits.put("Last Sample Time", lastSampleTime);
		
		headlinesBrokerLimits = new LinkedHashMap<String, Object>();
		headlinesBrokerLimits.putAll(globalHeadlines);		
		headlinesBrokerLimits.put("Last Sample Time", SolGeneosAgent.onlyInstance.getCurrentTimeString());
		
		// Calculate summaries for the broker limits dataview
		brokerLimitsTableContent = new Vector<Object>();
		
		ArrayList<String> tableRowBrokerLimits;
		
		for (String resourceName : RESOURCES) {
			
			tableRowBrokerLimits = new ArrayList<String>();
			tableRowBrokerLimits.add(resourceName);
			
			double totalCurrent = 
					vpnLimitsTableContent
					.stream()
					.map( thisTableRow -> ((ArrayList<String>)thisTableRow).get( VPN_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf(resourceName + " - Current") ) )	
					.mapToDouble(Double::parseDouble)
					.sum() ;
			tableRowBrokerLimits.add(String.format(FLOAT_FORMAT_STYLE, totalCurrent));
			
			double totalMax = 
					vpnLimitsTableContent
					.stream()
					.map( thisTableRow -> ((ArrayList<String>)thisTableRow).get( VPN_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf(resourceName + " - Max") ) )	
					.mapToDouble(Double::parseDouble)
					.sum() ;
			tableRowBrokerLimits.add(String.format(FLOAT_FORMAT_STYLE, totalMax));
			
			tableRowBrokerLimits.add(brokerLimitsLookup.get(resourceName + " - Limit").toString());
			
			brokerLimitsTableContent.add(tableRowBrokerLimits);
		}

		// Sort the list by the utilisation score. Then also limit to the max row count if exceeding it...
		tempVpnLimitsTableContent = new Vector<Object>();
		
		tempVpnLimitsTableContent.addAll(
				vpnLimitsTableContent
				.stream()
				.sorted(new UtilisationComparator())	
				.limit(maxRows)				// Then cut the rows at max limit
				.collect(Collectors.toCollection(Vector<Object>::new))
				);
		vpnLimitsTableContent = tempVpnLimitsTableContent;

		// Main table content all complete now for publishing. Just add the column names too.
		vpnLimitsTableContent.add(0, this.VPN_LIMITS_DATAVIEW_COLUMN_NAMES);	// No longer as received from parser in receivedColumnNames
		
		// Setup HWM dataview table content too, column names first
		brokerLimitsTableContent.add(0, this.BROKER_LIMITS_DATAVIEW_COLUMN_NAMES);
				
		
		// Now ready to publish tables to the view map
    	if (viewMap != null && viewMap.size() > 0) {
    		for (Iterator<String> viewIt = viewMap.keySet().iterator(); viewIt.hasNext();) 
    		{
    			View view = viewMap.get(viewIt.next());	
    			if (view.isActive()) {
    				switch (view.getName()) {
						case "msgVpnLimits":
							view.setHeadlines(headlinesVpnLimits);
	        				view.setTableContent(vpnLimitsTableContent);
							break;
						case "brokerLimits":
							view.setHeadlines(headlinesBrokerLimits);
							view.setTableContent(brokerLimitsTableContent);
							break;
						default:
							// Do nothing
    				}
    			}
    		}
    	}
        return State.REPORTING_QUEUE;
	}
}

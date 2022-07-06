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
import com.solacesystems.solgeneos.custommonitors.util.TargetedMultiRecordSEMPParser;
import com.solacesystems.solgeneos.custommonitors.util.VPNRecordSEMPParser;
import com.solacesystems.solgeneos.solgeneosagent.SolGeneosAgent;
import com.solacesystems.solgeneos.solgeneosagent.UserPropertiesConfig;
import com.solacesystems.solgeneos.solgeneosagent.monitor.BaseMonitor;
import com.solacesystems.solgeneos.solgeneosagent.monitor.View;

public class ClientProfileLimitsMonitor extends BaseMonitor implements MonitorConstants {
  
	// What version of the monitor?
	static final public String MONITOR_VERSION = "1.0.1";
	
	// The SEMP queries to execute:
    static final public String SHOW_CP_DETAILS_REQUEST = 
            "<rpc>" + 
            "	<show>" +
            "		<client-profile>" +
            "			<name>*</name>" +
            "			<detail></detail>" +
            "		</client-profile>" +
            "	</show>" +
            "</rpc>";
    
    static final public String SHOW_CLIENT_DETAILS_REQUEST = 
            "<rpc>" + 
            "	<show>" +
            "		<client>" +
            "			<name>*</name>" +
            "			<detail></detail>" +
            "		</client>" +
            "	</show>" +
            "</rpc>";
    
    // The elements of interest/exclusion within the Client Profile Details SEMP response processing:
    static final private String CP_DETAILS_RESPONSE_ELEMENT_NAME_ROWS = "profile";
    static final private  List<String> CP_DETAILS_RESPONSE_COLUMNS = 
    		Arrays.asList("RowUID", "name", "message-vpn", "maximum-transacted-sessions", "maximum-transactions", "maximum-endpoints-per-client-username",
    				"maximum-egress-flows", "maximum-ingress-flows", "max-connections-per-client-username", "num-users", "max-subscriptions");
    static final private  List<String> CP_DETAILS_RESPONSE_ELEMENT_NAMES_IGNORE = Arrays.asList("profile-users", "tcp", "ssl", "compression", "event-configuration");
    
    // The elements of interest/exclusion within the Client Details SEMP response processing:
    static final private String CLIENT_DETAILS_RESPONSE_ELEMENT_NAME_ROWS = "client";
    static final private  List<String> CLIENT_DETAILS_RESPONSE_COLUMNS = 
    		Arrays.asList("RowUID", "name", "message-vpn", "profile", "num-subscriptions", "total-ingress-flows", "total-egress-flows");
    static final private  List<String> CLIENT_DETAILS_RESPONSE_ELEMENT_NAMES_IGNORE = Arrays.asList("event-configuration");
    
    // What should be the formatting style?
    static final private String FLOAT_FORMAT_STYLE = "%.0f";	// 0 decimal places. Wanted to add thousandth separator but Geneos fails to recognise it as numbers for rule purposes!
        
    // What is the desired order of columns?
    private List<Integer> desiredColumnOrder;
    
    // Override the column names to more human friendly
    static final private ArrayList<String> CP_LIMITS_DATAVIEW_COLUMN_NAMES = new ArrayList<String>(
    		Arrays.asList("RowUID", "Client Profile", "Message VPN", 
    				"Subscriptions - Current", "Subscriptions - Max",
    				"Connections - Current", "Connections - Max", 
    				"Ingress Flows - Current", "Ingress Flows - Max",
    				"Egress Flows - Current", "Egress Flows - Max", 
    				"Queue and TEs - Max",
    				"Transactions - Max",
    				"Transacted Sessions - Max",
    				"Number of Users",
    				"Utilisation Score"
    				));
    
    static final String[] RESOURCES = {"Subscriptions", "Connections", "Egress Flows", "Ingress Flows"};
    
    private DefaultHttpClient httpClient;
    private ResponseHandler<SampleHttpSEMPResponse> responseHandler;
    private TargetedMultiRecordSEMPParser multiRecordParserClientProfile;
    private TargetedMultiRecordSEMPParser multiRecordParserClientDetail;
    
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
        	int value1 = Integer.parseInt( ((ArrayList<String>)tableRow1).get(CP_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf("Utilisation Score") ));
        	int value2 = Integer.parseInt( ((ArrayList<String>)tableRow2).get(CP_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf("Utilisation Score") ));

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
		// In single view, all the profiles are listed in the same dataview. In multi view mode, profiles are reported in per-VPN dataviews
		
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
		multiRecordParserClientProfile = new TargetedMultiRecordSEMPParser(CP_DETAILS_RESPONSE_ELEMENT_NAME_ROWS, CP_DETAILS_RESPONSE_COLUMNS, CP_DETAILS_RESPONSE_ELEMENT_NAMES_IGNORE);
		multiRecordParserClientDetail = new TargetedMultiRecordSEMPParser(CLIENT_DETAILS_RESPONSE_ELEMENT_NAME_ROWS, CLIENT_DETAILS_RESPONSE_COLUMNS, CLIENT_DETAILS_RESPONSE_ELEMENT_NAMES_IGNORE);
		
	}
	
	private void setDesiredColumnOrder (List<String> currentColumnNames) {
	    
		desiredColumnOrder = Arrays.asList(
			currentColumnNames.indexOf("RowUID"), currentColumnNames.indexOf("name"), currentColumnNames.indexOf("message-vpn"),  
    		currentColumnNames.indexOf("max-subscriptions"), 
    		currentColumnNames.indexOf("max-connections-per-client-username"),
    		currentColumnNames.indexOf("maximum-ingress-flows"),
    		currentColumnNames.indexOf("maximum-egress-flows"),
    		currentColumnNames.indexOf("maximum-endpoints-per-client-username"), 
    		currentColumnNames.indexOf("maximum-transactions"),
    		currentColumnNames.indexOf("maximum-transacted-sessions"), 
    		currentColumnNames.indexOf("num-users")
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
    
	/**
	 * This method is responsible to collect data required for a view.
	 * @return The next monitor state which should be State.REPORTING_QUEUE.
	 */

	@SuppressWarnings({ "unchecked", "static-access" })
	@Override
	protected State onCollect() throws Exception {

		TreeMap<String, View> viewMap = getViewMap();
		
		LinkedHashMap<String, Object> headlines;
		
		// Get the first SEMP query response...
		submitSEMPQuery(SHOW_CP_DETAILS_REQUEST, multiRecordParserClientProfile);
		// Get the second SEMP query response...
		submitSEMPQuery(SHOW_CLIENT_DETAILS_REQUEST, multiRecordParserClientDetail);
		
		// Has the desired column order been determined yet? (Done on the first time responses and their columns came back.)
		if (this.desiredColumnOrder == null) {
			// First get the column names from the VPN details response
			List<String> currentColumnNames = multiRecordParserClientProfile.getColumnNames();
			
			// Then use this merged columns information to set the final display order
			this.setDesiredColumnOrder(currentColumnNames);
		}
				
		// Will take what is received as the table contents and then do further Java8 Streams based processing... 
		Vector<ArrayList<String>> clientProfileData;
		Vector<ArrayList<String>> clientProfileDataTemp;
		ArrayList<String> tableRowClientProfile;
		
		Vector<ArrayList<String>> clientData;
		ArrayList<String> tableRowClient;
		
		clientProfileData = multiRecordParserClientProfile.getTableContent();
		clientData = multiRecordParserClientDetail.getTableContent();
		List<String> clientDataColumnNames = multiRecordParserClientDetail.getColumnNames();
		
		// Reorder the merged table into the column order we want. 
		clientProfileDataTemp = new Vector<ArrayList<String>>();
		
		// Iterate to each row in the table contents
		for (int index = 0; index < clientProfileData.size(); index++) {
			
			// Build a new tableRow by adding to it in the right order 
			ArrayList<String> tableRow = new ArrayList<String>();
			
			for (Integer columnNumber : this.desiredColumnOrder){
				tableRow.add( (clientProfileData.get(index)).get(columnNumber));
			}
			
			// Add the empty columns for computed values later on
			tableRow.add(CP_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf("Subscriptions - Current"), "");
			tableRow.add(CP_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf("Connections - Current"), "");
			tableRow.add(CP_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf("Ingress Flows - Current"), "");
			tableRow.add(CP_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf("Egress Flows - Current"), "");
			
			// Add the newly created row to temp
			clientProfileDataTemp.add(tableRow); 
		}  
		
		clientProfileData = clientProfileDataTemp;
		
		// From the table remove client profiles that begin with # as those are system managed
		// Also remove the ones that have no configured username referencing it
		// While iterating here, add the missing computed column values
		
		Iterator<ArrayList<String>> itr = clientProfileData.iterator();	
		while (itr.hasNext()) {		
			ArrayList<String> tempTableRow = itr.next();
			String cpName = tempTableRow.get(CP_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf("Client Profile"));
			String vpnName = tempTableRow.get(CP_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf("Message VPN"));
			int userCount = Integer.parseInt(tempTableRow.get(CP_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf("Number of Users")));
			
			if (cpName.startsWith("#") || userCount == 0) {
				itr.remove();
			}
			else
			{	
				// Only if a 1:1 mapping of client profiles to usernames, calculate the following:
				// (Since limits are 'per-username', cannot easily display limit usage at each username referencing this profile)
				if (userCount == 1) {
					// How many connections present using this client profile?
					long nConnections = 
							clientData
							.stream()
							.filter(tableRow -> vpnName.equalsIgnoreCase( tableRow.get( clientDataColumnNames.indexOf("message-vpn") )   ))
							.filter(tableRow -> cpName.equalsIgnoreCase( tableRow.get( clientDataColumnNames.indexOf("profile") )   ))
							.count();	
					tempTableRow.set(CP_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf("Connections - Current"), Long.toString(nConnections));
					
					// How many subscriptions used by clients with this client profile?
					long nSubscriptions = 
							clientData
							.stream()
							.filter(tableRow -> vpnName.equalsIgnoreCase( tableRow.get( clientDataColumnNames.indexOf("message-vpn") )   ))
							.filter(tableRow -> cpName.equalsIgnoreCase( tableRow.get( clientDataColumnNames.indexOf("profile") )   ))
							.map   (tableRow -> tableRow.get( clientDataColumnNames.indexOf("num-subscriptions")  ) )	// Only that one column)
							.mapToLong(Long::parseLong)
							.sum();	
					tempTableRow.set(CP_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf("Subscriptions - Current"), Long.toString(nSubscriptions));
					
					// How many ingress flows used by clients with this client profile?
					long nIngressFlows = 
							clientData
							.stream()
							.filter(tableRow -> vpnName.equalsIgnoreCase( tableRow.get( clientDataColumnNames.indexOf("message-vpn") )   ))
							.filter(tableRow -> cpName.equalsIgnoreCase( tableRow.get( clientDataColumnNames.indexOf("profile") )   ))
							.map   (tableRow -> tableRow.get( clientDataColumnNames.indexOf("total-ingress-flows")  ) )	// Only that one column)
							.mapToLong(Long::parseLong)
							.sum();	
					tempTableRow.set(CP_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf("Ingress Flows - Current"), Long.toString(nIngressFlows));
					
					// How many egress flows used by clients with this client profile?
					long nEgressFlows = 
							clientData
							.stream()
							.filter(tableRow -> vpnName.equalsIgnoreCase( tableRow.get( clientDataColumnNames.indexOf("message-vpn") )   ))
							.filter(tableRow -> cpName.equalsIgnoreCase( tableRow.get( clientDataColumnNames.indexOf("profile") )   ))
							.map   (tableRow -> tableRow.get( clientDataColumnNames.indexOf("total-egress-flows")  ) )	// Only that one column)
							.mapToLong(Long::parseLong)
							.sum();	
					tempTableRow.set(CP_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf("Egress Flows - Current"), Long.toString(nEgressFlows));
				}

				// Add a new utilisation score calculated column
				double score = 0;
				int nUnusedResource = 0;
				for (String resourceName : RESOURCES) {
					double current = 
							tempTableRow.get(CP_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf(resourceName + " - Current")).isEmpty() ? 0 : Double.parseDouble(tempTableRow.get(CP_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf(resourceName + " - Current")));
					double max = Double.parseDouble(tempTableRow.get(CP_LIMITS_DATAVIEW_COLUMN_NAMES.indexOf(resourceName + " - Max")));
					
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
		
		// Now calculate the headlines
		headlines = new LinkedHashMap<String, Object>();
		headlines.putAll(globalHeadlines);	
		
		String lastSampleTime = SolGeneosAgent.onlyInstance.getCurrentTimeString();
		headlines.put("Last Sample Time", lastSampleTime);
		
		// Sort the list by the utilisation score. Then also limit to the max row count if exceeding it...
		clientProfileDataTemp = new Vector<ArrayList<String>>();
		
		clientProfileDataTemp.addAll(
				clientProfileData
				.stream()
				.sorted(new UtilisationComparator())	
				.limit(maxRows)				// Then cut the rows at max limit
				.collect(Collectors.toCollection(Vector<ArrayList<String>>::new))
				);
		clientProfileData = clientProfileDataTemp;

		// Main table content all complete now for publishing. Just add the column names too.
		clientProfileData.add(0, this.CP_LIMITS_DATAVIEW_COLUMN_NAMES);	// No longer as received from parser in receivedColumnNames
				
		// Now ready to publish tables to the view map
    	if (viewMap != null && viewMap.size() > 0) {
    		for (Iterator<String> viewIt = viewMap.keySet().iterator(); viewIt.hasNext();) 
    		{
    			View view = viewMap.get(viewIt.next());	
    			if (view.isActive()) {
					view.setHeadlines(headlines);
					// The .setTableContent() method forces a Vector of Object!
					Vector<Object> submitTable = new Vector<Object>();
					submitTable.addAll(clientProfileData);
    				view.setTableContent(submitTable);
    			}
    		}
    	}
        return State.REPORTING_QUEUE;
	}
}

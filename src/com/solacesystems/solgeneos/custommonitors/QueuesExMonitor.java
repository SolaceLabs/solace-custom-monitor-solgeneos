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
import com.solacesystems.solgeneos.custommonitors.util.SampleHttpSEMPResponse;
import com.solacesystems.solgeneos.custommonitors.util.SampleResponseHandler;
import com.solacesystems.solgeneos.custommonitors.util.SampleSEMPParser;
import com.solacesystems.solgeneos.custommonitors.util.TargetedMultiRecordSEMPParser;
import com.solacesystems.solgeneos.solgeneosagent.SolGeneosAgent;
import com.solacesystems.solgeneos.solgeneosagent.UserPropertiesConfig;
import com.solacesystems.solgeneos.solgeneosagent.monitor.BaseMonitor;
import com.solacesystems.solgeneos.solgeneosagent.monitor.View;

public class QueuesExMonitor extends BaseMonitor implements MonitorConstants {
  
	// What version of the monitor?
	static final public String MONITOR_VERSION = "1.2.0";
	
	// The SEMP query to execute:
    static final public String SHOW_QUEUES_REQUEST = 
            "<rpc>" + 
            "	<show>" +
            "		<queue>" +
            "			<name>*</name>" + 
            "			<vpn-name>*</vpn-name>" +
            "			<detail></detail>" +
            "		</queue>" +
            "	</show>" +
            "</rpc>";
    
    // The elements of interest/exclusion within the SEMP response processing:
    static final private String RESPONSE_ELEMENT_NAME_ROWS = "queue";
    static final private  List<String> RESPONSE_COLUMNS = 
    		Arrays.asList("RowUID", "name", "message-vpn", "durable", "ingress-config-status", "egress-config-status", "access-type", "owner", "quota", 
    				"respect-ttl", "max-ttl", "reject-msg-to-sender-on-discard", "num-messages-spooled", "current-spool-usage-in-mb", "high-water-mark-in-mb",
    				"total-delivered-unacked-msgs", "max-redelivery", "oldest-msg-id", "newest-msg-id", "bind-count", "max-bind-count", "dead-message-queue");
    // NOTE: "RowUID" is not expected in the SEMP response, but will be added by the parser. However adding it here allows this list to be used as an index where the column number can be searched by name
    
    // For SEMP rows nested a further level into the response, what is of interest?
    static final private String RESPONSE_ELEMENT_NAME_ROWS_L2 = "clients";
    static final private  List<String> RESPONSE_COLUMNS_L2 = 
    		Arrays.asList("name", "is-active", "window-size", "connect-time", "flow-id", "last-msg-id-delivered");
    
    static final private  List<String> RESPONSE_ELEMENT_NAMES_IGNORE = Arrays.asList("event"); 
    
    // When pretty printing numbers, which columns should be formatted? 
    static final private List<String> RESPONSE_NUMBER_FORMAT_COLUMNS = Arrays.asList(
    		"Spool Usage (MB)", "Spool Usage HWM (MB)"); 	
    
    // What should be the formatting style?
    static final private String FLOAT_FORMAT_STYLE = "%.2f";	// 2 decimal places
    
    // What is the desired order of columns? (Will be set after first getting a response)
    private List<Integer> desiredColumnOrder;
    
    // When fixing the response for the disjointed table due to msg-id columns not always being present for some rows, which are those to shift it up?
    static final private List<String> RESPONSE_OPTIONAL_COLUMNS = Arrays.asList(
    		"oldest-msg-id", "newest-msg-id"); 
    
    // Override the column names to more human friendly
    static final private ArrayList<String> COLUMN_NAME_OVERRIDE = new ArrayList<String>(
    		Arrays.asList("RowUID", "Queue Name", "Message VPN", "Messages Spooled", "Spool Usage (MB)", "Spool Quota (MB)", "Spool Usage HWM (MB)", 
    				"Delivered Messages Unacked", "Bind Count", "Bind Count - Max", "Access Type",
    				"Durable", "Owner", "Ingress Status", "Egress Status",
    				"Oldest Msg ID", "Newest Msg ID", "Respect TTL", "Max TTL",
    				"Reject to Sender on Discard", "Max Redelivery Attempts", "Dead Message Queue",
    				"Client ID", "Is Active?", "Window Size", "Connect Time", "Flow ID", "Last Msg ID Delivered", 
    				"Last Seen Client ID", "Last Seen Connect Time"));
    
    private DefaultHttpClient httpClient;
    private ResponseHandler<SampleHttpSEMPResponse> responseHandler;
    private TargetedMultiRecordSEMPParser multiRecordParser;

    private Vector<ArrayList<String>> receivedTableContent;
    
    private LinkedHashMap<String, Object> globalHeadlines = new LinkedHashMap<String, Object>();
    // Is the monitor creating a dataview per VPN or everything is in one view?
    private boolean multiview = false;
    // What is the maximum number of rows to limit the dataview to? Default 200 unless overridden.
    private int maxRows = 200;
    
    // If in multiview mode, list of detected VPN names
    private List<String> detectedVpns;
    
    // A map of per view table contents and headlines
    private Map<String, Vector<ArrayList<String>>> tablesPerView = new HashMap<String, Vector<ArrayList<String>>>();
    private Map<String, LinkedHashMap<String, Object>> headlinesPerView = new HashMap<String, LinkedHashMap<String, Object>>();
    
    // If multi-view mode and a view is to be deleted, cannot delete and clear it in one update. So need two samples for this, marking it for delete on the first sample.
    List<String> viewMarkedForDelete = new ArrayList<String>();		
    
    // What is the configured name of the dataview?
    private String dataViewName = "";
    private String defaultDataViewKey = "";
    
    // Track whether the Active/Standby Role has changed, if so, dataview needs a reset. Unset: -1, True: 1, False: 0.
    int isStandbyBrokerBefore = -1;
    int isStandbyBrokerNow = -1;
    
    // When sorting the table rows before limiting to maxrows, how to prioritise the top of the cut?
    // This comparator is used to sort the table so the highest spool utilisation percentage against the quota is at the top.
    static class QueuesComparator implements Comparator<Object>
    {
        @SuppressWarnings("unchecked")
		public int compare(Object tableRow1, Object tableRow2)
        {
        	double spoolUsage1 = Double.parseDouble( ((ArrayList<String>)tableRow1).get( COLUMN_NAME_OVERRIDE.indexOf("Spool Usage (MB)") ));
        	double spoolQuota1 = Double.parseDouble( ((ArrayList<String>)tableRow1).get( COLUMN_NAME_OVERRIDE.indexOf("Spool Quota (MB)") ));
        	double utilisation1 = (spoolQuota1 > 0) ? (spoolUsage1 / spoolQuota1) * 100 : 0;

        	double spoolUsage2 = Double.parseDouble( ((ArrayList<String>)tableRow2).get( COLUMN_NAME_OVERRIDE.indexOf("Spool Usage (MB)") ));
        	double spoolQuota2 = Double.parseDouble( ((ArrayList<String>)tableRow2).get( COLUMN_NAME_OVERRIDE.indexOf("Spool Quota (MB)") ));
        	double utilisation2 = (spoolQuota2 > 0) ? (spoolUsage2 / spoolQuota2) * 100 : 0;

            return Double.compare(utilisation2, utilisation1);	
        }
    }
    
    // For connected clients of non-exclusive queues, how to sort the flow ID to only show the first entry?
    static class FlowIDComparator implements Comparator<Object>
    {
        @SuppressWarnings("unchecked")
		public int compare(Object tableRow1, Object tableRow2)
        {
        	long flowID1 = Long.parseLong( ((ArrayList<String>)tableRow1).get( RESPONSE_COLUMNS_L2.indexOf("flow-id") ));
        	long flowID2 = Long.parseLong( ((ArrayList<String>)tableRow2).get( RESPONSE_COLUMNS_L2.indexOf("flow-id") ));
        	
        	return Long.compare(flowID1, flowID2);	
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
			multiview = Boolean.parseBoolean(monitorPropsConfig.getProperties().get("multiview").toString());
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
        
		TreeMap<String, View> viewMap = getViewMap();
		
		// Operating in single view or multiview?
		defaultDataViewKey = getViewMap().firstKey();
		if (multiview) {
			// Grab the name of the current view name
			dataViewName = viewMap.get(defaultDataViewKey).getViewName();
			
			// Remove that default view with name v0 as not going to be used.
			viewMap.remove(defaultDataViewKey);
			// Will add per-VPN ones after discovering them later...
		}
        
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
        
        // create SEMP parser with the name of the element that contains the records, and the element name for nested level 2 records
		multiRecordParser = new TargetedMultiRecordSEMPParser(
				RESPONSE_ELEMENT_NAME_ROWS, RESPONSE_COLUMNS, RESPONSE_ELEMENT_NAMES_IGNORE, 
				RESPONSE_ELEMENT_NAME_ROWS_L2, RESPONSE_COLUMNS_L2);
		
	}
	
	private void setDesiredColumnOrder (List<String> currentColumnNames) {
	    
		desiredColumnOrder = Arrays.asList(
				currentColumnNames.indexOf("RowUID"), currentColumnNames.indexOf("name"), currentColumnNames.indexOf("message-vpn"),
				currentColumnNames.indexOf("num-messages-spooled"), currentColumnNames.indexOf("current-spool-usage-in-mb"), currentColumnNames.indexOf("quota"), currentColumnNames.indexOf("high-water-mark-in-mb"),
				currentColumnNames.indexOf("total-delivered-unacked-msgs"), currentColumnNames.indexOf("bind-count"), currentColumnNames.indexOf("max-bind-count"), currentColumnNames.indexOf("access-type"),
				currentColumnNames.indexOf("durable"), currentColumnNames.indexOf("owner"), currentColumnNames.indexOf("ingress-config-status"), currentColumnNames.indexOf("egress-config-status"),
				currentColumnNames.indexOf("oldest-msg-id"), currentColumnNames.indexOf("newest-msg-id"), currentColumnNames.indexOf("respect-ttl"), currentColumnNames.indexOf("max-ttl"), 
				currentColumnNames.indexOf("reject-msg-to-sender-on-discard"), currentColumnNames.indexOf("max-redelivery"), currentColumnNames.indexOf("dead-message-queue")
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
		
		submitSEMPQuery(SHOW_QUEUES_REQUEST, multiRecordParser);
		
		List<String> currentColumnNames = multiRecordParser.getColumnNames();
				
		// Will take what is received as the table contents and then do further Java8 Streams based filtering... 
		receivedTableContent = multiRecordParser.getTableContent();
		
		// Need to do some cleanup on the received table as two optional output fields cause some disjointed column alignment
		Vector<ArrayList<String>> goodTableContent = new Vector<ArrayList<String>>();
		Vector<ArrayList<String>> brokenTableContent = new Vector<ArrayList<String>>();

		// To detect that, the expected number of columns is the column names list size if everything got provided		
		goodTableContent.addAll(
				receivedTableContent
				.stream()
				.filter( tableRow -> tableRow.size() == RESPONSE_COLUMNS.size())
				.collect(Collectors.toCollection(Vector<ArrayList<String>>::new))
			);
		
		brokenTableContent.addAll(
				receivedTableContent
				.stream()
				.filter( tableRow -> tableRow.size() == (RESPONSE_COLUMNS.size() - RESPONSE_OPTIONAL_COLUMNS.size()))
				.collect(Collectors.toCollection(Vector<ArrayList<String>>::new))
			);
		
		// For the rows in the broken table, fix it by padding into the correct column position
		// Note: Columns in the original response order still
		
		// First add the optional column names if missing from current response columns even
		if (currentColumnNames.indexOf(RESPONSE_OPTIONAL_COLUMNS.get(0)) < 0) {
			for (String optionalColumn : RESPONSE_OPTIONAL_COLUMNS) {
				currentColumnNames.add(optionalColumn);	
			}
		}
		
		// Because of the optional columns being present or not changing between polls, need to set the column order each time:
		setDesiredColumnOrder(currentColumnNames);	
				
		for (String optionalColumn : RESPONSE_OPTIONAL_COLUMNS) {			
			brokenTableContent.forEach(tableRow -> tableRow.add(currentColumnNames.indexOf(optionalColumn), ""));	
		}
	    
		// Merge it all together now...
		goodTableContent.addAll(brokenTableContent);
		
		// Reorder the merged table into the column order we want. 
		Vector<ArrayList<String>> tempVpnLimitsTableContent = new Vector<ArrayList<String>>();
		
		// Iterate to each row in the table contents
		for (int index = 0; index < goodTableContent.size(); index++) {
			
			// Build a new tableRow by adding to it in the right order 
			ArrayList<String> tableRow = new ArrayList<String>();
			
			for (Integer columnNumber : this.desiredColumnOrder){
				tableRow.add( goodTableContent.get(index).get(columnNumber));
			}
			// Add the newly created row to reorderedTableContent
			tempVpnLimitsTableContent.add(tableRow); 
		} 
		goodTableContent = tempVpnLimitsTableContent;
		
		// NOTE: Table has been reordered from this point onwards. No further references to 'desiredColumnOrder' for column lookups
		// All lookups should be based off 'COLUMN_NAME_OVERRIDE' and the user friendly column names, not the SEMP field name.
		
	    // If connected to the broker that is in Active-Standby Role of 'Standby', several other columns are missing in the response. e.g. bind-count.
	    // This situation can be detected if the received table content was non-zero, yet the final 'good' table content is empty.
		// Create a message to explain this in the dataview, instructing the user to view the broker in the 'Active' role instead.
		
		isStandbyBrokerNow = (receivedTableContent.size() > 0 && goodTableContent.size() == 0) ? 1 : 0;
		isStandbyBrokerBefore = (isStandbyBrokerBefore == -1) ? isStandbyBrokerNow : isStandbyBrokerBefore;
				
		// Reset any previously saved table state
		tablesPerView = new HashMap<String, Vector<ArrayList<String>>>();		
	
		// Split into per-VPN datasets if split mode selected
		if (multiview)
		{
			
			// Get the distinct set of VPN names...
			detectedVpns = new ArrayList<String>();
			detectedVpns.addAll(
				goodTableContent
				.stream()
				.map( tableRow -> tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Message VPN") ) )
				.distinct()
				.collect(Collectors.toCollection(ArrayList<String>::new))
				);

			// Check if a view already exists for it, create it if not.
			for (String vpnName : detectedVpns) {
				if (!viewMap.containsKey(vpnName)){
					// Add a view just for it
			        this.addView(vpnName);
			        // Set them up and activate
					viewMap.get(vpnName).setViewName(dataViewName + " - " + vpnName);
					viewMap.get(vpnName).setActive(true);
					
					// If second sampler requested, add a view for that too
					this.addView(vpnName + "");
					
				}
				
				// Create a filtered set of table rows just for this VPN
				tablesPerView.put(vpnName, 
						goodTableContent
						.stream()
						.filter(tableRow -> vpnName.equalsIgnoreCase( tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Message VPN") )    ) )
						.collect(Collectors.toCollection(Vector<ArrayList<String>>::new))
					);	
			}
						
		}
		else
		{
			// There isn't a table per VPN, there is just one table for the default view name too.
			tablesPerView.put(defaultDataViewKey, goodTableContent);
		}
		
		// Now for each view, calculate the specific headlines just for that
		for (String viewKey : tablesPerView.keySet())
		{
			
			headlines = new LinkedHashMap<String, Object>();
			headlines.putAll(globalHeadlines);		
			headlines.put("Last Sample Time", SolGeneosAgent.onlyInstance.getCurrentTimeString());
			

			goodTableContent = tablesPerView.get(viewKey);
			
			// Is there any content even for this particular view?
			if (goodTableContent.size() > 0) 
			{
				
				// Build up some summary headlines on the data. 
				long queuesWithMsgs = 
						goodTableContent
						.stream()
						.map( tableRow -> tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Messages Spooled") ) )	// Only num-messages-spooled column
						.mapToInt(Integer::parseInt)
						.filter(x -> x > 0) 		// Only the non-zero entries
						.count() ;
				
				long queuesWithBinds = 
						goodTableContent
						.stream()
						.map( tableRow -> tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Bind Count") ) )	// Only bind-count column
						.mapToInt(Integer::parseInt)
						.filter(x -> x > 0) 		// Only the non-zero entries
						.count() ;
				
				long queuesWithZeroBinds = 
						goodTableContent
						.stream()
						.map( tableRow -> tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Bind Count") ) )	// Only bind-count column
						.mapToInt(Integer::parseInt)
						.filter(x -> x == 0) 		// Only the non-zero entries
						.count() ;
				
				long queuesWithUnackedMsgs = 
						goodTableContent
						.stream()
						.filter( tableRow -> !tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Delivered Messages Unacked") ).equalsIgnoreCase("0") )
						.count() ;
				
				long queuesWithIngressDown = 
						goodTableContent
						.stream()
						.filter( tableRow -> tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Ingress Status") ).equalsIgnoreCase("Down") )
						.count() ;
				
				long queuesWithEgressDown = 
						goodTableContent
						.stream()
						.filter( tableRow -> tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Egress Status") ).equalsIgnoreCase("Down") )
						.count() ;
				
				long sumQueuedMsgs = 
						goodTableContent
						.stream()
						.map( tableRow -> tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Messages Spooled") ) )	// Only num-messages-spooled column
						.mapToLong(Long::parseLong) 	
						.sum() ;
				
				double sumSpoolUsage = 
						goodTableContent
						.stream()
						.map( tableRow -> tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Spool Usage (MB)") ) )	// Only current-spool-usage-in-mb column
						.mapToDouble(Double::parseDouble)	
						.sum() ;
				
				// (Geneos does not support long values.)
				headlines.put("Queues with Pending Messages", Long.toString(queuesWithMsgs) );
				headlines.put("Queues with Bound Clients", Long.toString(queuesWithBinds) );
				headlines.put("Queues with Zero Bound Clients", Long.toString(queuesWithZeroBinds) );
				headlines.put("Queues with Unacked Messages", Long.toString(queuesWithUnackedMsgs) );
				headlines.put("Queues with Ingress State Down", Long.toString(queuesWithIngressDown) );
				headlines.put("Queues with Egress State Down", Long.toString(queuesWithEgressDown) );
				headlines.put("Total Messages Pending", Long.toString(sumQueuedMsgs) );
				headlines.put("Total Spool Usage (MB)", String.format(FLOAT_FORMAT_STYLE, sumSpoolUsage));
				headlines.put("Total Queues Count", goodTableContent.size());
				
				// When there are more endpoints than the allowed row limit, how to prioritise what makes the cut?
				// Endpoints with Unacknowledged messages at the top. Then sort the remainder entries by spool utilisation. 
				// Sort the list to show entries needing attention near to the top. Then also limit to the max row count if exceeding it...
				Vector<ArrayList<String>> tempTableContent = new Vector<ArrayList<String>>();
				Vector<ArrayList<String>> tempRemainderTableContent = new Vector<ArrayList<String>>();
				Vector<ArrayList<String>> tempTableContentClients;
				
				tempTableContent.addAll(
						goodTableContent
						.stream()
						.filter(tableRow -> !tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Delivered Messages Unacked")).equalsIgnoreCase("0"))
						.collect(Collectors.toCollection(Vector<ArrayList<String>>::new))
						);
				
				tempRemainderTableContent = 
						goodTableContent
						.stream()
						.filter(tableRow -> tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Delivered Messages Unacked")).equalsIgnoreCase("0"))
						.collect(Collectors.toCollection(Vector<ArrayList<String>>::new));
				
				long rowsAllowance = (queuesWithUnackedMsgs < maxRows)? maxRows - queuesWithUnackedMsgs : maxRows;
				
				tempTableContent.addAll(
						tempRemainderTableContent
						.stream()
						.sorted(new QueuesComparator())
						.limit(rowsAllowance)
						.collect(Collectors.toCollection(Vector<ArrayList<String>>::new))
						);
				
				goodTableContent = tempTableContent;
				
				// Pretty up the MB usage numbers. Get the value, format it, set it back. NOTE: Do it as close to the final step towards publishing the table
			    for (String columnToNumberFormat : RESPONSE_NUMBER_FORMAT_COLUMNS) 
			    { 		      
			    	goodTableContent.forEach(tableRow -> tableRow
			    			.set(COLUMN_NAME_OVERRIDE.indexOf(columnToNumberFormat), 
			    					String.format(FLOAT_FORMAT_STYLE, 
			    							Double.parseDouble(
			    									tableRow.get(COLUMN_NAME_OVERRIDE.indexOf(columnToNumberFormat))
			    									)
			    							) 
			    					)
							);	
			    }
			   				
				// Finally, for the final list of queues, annotate with extra columns with details of the bound consumer only if bind-count=1
				tempTableContent = new Vector<ArrayList<String>>();
				List<String> tempL2ColumnNames;
				String rowUID;
					
				for (int index = 0; index < goodTableContent.size(); index++) {
					
					// Build a new tableRow by adding to it with necessary annotations 
					ArrayList<String> tableRow = goodTableContent.get(index);
					String bindCount = tableRow.get(COLUMN_NAME_OVERRIDE.indexOf("Bind Count"));
					String accessType = tableRow.get(COLUMN_NAME_OVERRIDE.indexOf("Access Type"));
										
					String clientID = "";
					String isActive = "";
					String windowSize = "";
					String connectTime = "";
					String flowID = "";
					String lastMsgIDDelivered = "";
					// These last two will stay empty fields since geneos rules populate them, not this monitor directly
					String lastSeenClientID = "";
					String lastSeenConnectTime = "";
				
					rowUID = tableRow.get(COLUMN_NAME_OVERRIDE.indexOf("RowUID"));
					tempL2ColumnNames = multiRecordParser.getColumnNamesLevel2();
					tempTableContentClients = multiRecordParser.getTableContentLevel2(rowUID);
					
					if (!bindCount.equalsIgnoreCase("0")) {
						
						if (tempTableContentClients.size() > 0) {
						
							// Which client to show in a single line available?
							// If exclusive mode, show the one that is Active-Consumer
							// If non-exclusive mode, show the lowest flow id?
							
							ArrayList<String> tableRowClient;
							
							if (bindCount.equalsIgnoreCase("1")) {
								// Just one bind, so first (and only) entry in the table
								tableRowClient = tempTableContentClients.get(0);
							}
							else {
								// Bit more complicated, several to pick from but need to be deterministic between polls
								if (accessType.equalsIgnoreCase("exclusive")) {
									// Get the client that is active consuming
									tableRowClient = tempTableContentClients
											.stream()
											.filter(x -> x.get(RESPONSE_COLUMNS_L2.indexOf("is-active")).equalsIgnoreCase("Active-Consumer"))
											.limit(1)
											.collect(Collectors.toList()).get(0);
								}
								else {
									// Get the lowest flow ID (so earliest connected) of the set
									tableRowClient = tempTableContentClients
											.stream()
											.sorted(new FlowIDComparator())
											.limit(1)
											.collect(Collectors.toList()).get(0);								
								}
							}
							
							clientID = tableRowClient.get(tempL2ColumnNames.indexOf("name"));
							isActive = tableRowClient.get(tempL2ColumnNames.indexOf("is-active"));
							windowSize = tableRowClient.get(tempL2ColumnNames.indexOf("window-size"));
							connectTime = tableRowClient.get(tempL2ColumnNames.indexOf("connect-time"));
							flowID = tableRowClient.get(tempL2ColumnNames.indexOf("flow-id"));
							lastMsgIDDelivered = tableRowClient.get(tempL2ColumnNames.indexOf("last-msg-id-delivered"));
						}
					}					
					tableRow.add(clientID);
					tableRow.add(isActive);
					tableRow.add(windowSize);
					tableRow.add(connectTime);
					tableRow.add(flowID);
					tableRow.add(lastMsgIDDelivered);
					tableRow.add(lastSeenClientID);
					tableRow.add(lastSeenConnectTime);
					
					// Add the newly created row to tempTableContent
					tempTableContent.add(tableRow); 
				}  
				goodTableContent = tempTableContent;
				
				// Table content all complete now for publishing. Just add the column names too.
				goodTableContent.add(0, this.COLUMN_NAME_OVERRIDE);	// No longer as received from parser in receivedColumnNames
				tablesPerView.put(viewKey, goodTableContent);
				headlinesPerView.put(viewKey, headlines);
			}
			else
			{
				// There is no content for this view, either no endpoints in the VPN, or its a Standby role broker
				// If Standby broker, add a message to signal this clearly
				if (isStandbyBrokerNow == 1) {
					Vector<ArrayList<String>> messageTableContent = new Vector<ArrayList<String>>();
					ArrayList<String> tableRow; 
					
					tableRow = new ArrayList<String>();
					tableRow.add("Message");
					messageTableContent.add(tableRow);
					
					tableRow = new ArrayList<String>();
					tableRow.add("This broker is in the 'Standby' role. View the 'Active' broker for details.");
					messageTableContent.add(tableRow);
					
					tablesPerView.put(viewKey, messageTableContent);
					headlinesPerView.put(viewKey, headlines);
				}
			}
			
		}
		
		// Now ready to publish each table to the available views... (Either one per VPN, or a default combined one.)
    	if (viewMap != null && viewMap.size() > 0) {
    		for (Iterator<String> viewIt = viewMap.keySet().iterator(); viewIt.hasNext();) 
    		{
    			
    			View view = viewMap.get(viewIt.next());
    			
    			if (view.isActive()) {
    				
    				// Check if data available for the view. If none, then clear it and plan to delete it.
    				if (tablesPerView.containsKey(view.getName())) {
    					
    					// If a role switch has happened, can end up with stale headlines. 
    					// Also gateway complains of the column name changes. So reset the view.
    					if (isStandbyBrokerNow != isStandbyBrokerBefore) {
        					view.setReset(true);
        					isStandbyBrokerBefore = isStandbyBrokerNow;
    					}

    					view.setHeadlines(headlinesPerView.get(view.getName()));
    					
    					// The .setTableContent() method forces a Vector of Object!
    					Vector<Object> submitTable = new Vector<Object>();
    					submitTable.addAll(tablesPerView.get(view.getName()));
        				view.setTableContent(submitTable);
    				}
    				else
    				{
    					view.setActive(false);		// This will tell the Geneos gateway to remove it in this collect() update
    												// Then will delete from the map next time. Otherwise update not sent.
    				}	
    			}
    			else
    			{
    				// Would have been made inactive on a previous sample. Delete it now from the map.
    				viewMarkedForDelete.add(view.getName());
    			}
    		}
    		
    		
    	}
    	
    	if (viewMarkedForDelete.size() > 0)
    	{
    		viewMarkedForDelete.forEach(view -> viewMap.remove(view));
			viewMarkedForDelete.clear();
    	}
    	
        return State.REPORTING_QUEUE;
	}

}

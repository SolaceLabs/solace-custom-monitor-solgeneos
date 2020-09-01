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
import com.solacesystems.solgeneos.custommonitors.util.TargetedMultiRecordSEMPParser;
import com.solacesystems.solgeneos.solgeneosagent.SolGeneosAgent;
import com.solacesystems.solgeneos.solgeneosagent.UserPropertiesConfig;
import com.solacesystems.solgeneos.solgeneosagent.monitor.BaseMonitor;
import com.solacesystems.solgeneos.solgeneosagent.monitor.View;

public class QueuesMonitor extends BaseMonitor implements MonitorConstants {
  
	// What version of the monitor?
	static final public String MONITOR_VERSION = "1.0";
	
	// The SEMP query to execute:
    static final public String SHOW_USERS_REQUEST = 
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
    
    static final private  List<String> RESPONSE_ELEMENT_NAMES_IGNORE = Arrays.asList("event", "clients");
     
    // When fixing the response for the disjointed table due to some columns not always being present for some rows, which are those to shift it up?
    static final private List<Integer> RESPONSE_PAD_COLUMN_NUMBERS = Arrays.asList(
    		RESPONSE_COLUMNS.indexOf("oldest-msg-id"),
    		RESPONSE_COLUMNS.indexOf("newest-msg-id")); 
    
    // When pretty printing numbers, which columns should be formatted? 
    static final private List<Integer> RESPONSE_NUMBER_FORMAT_COLUMN_NUMBERS = Arrays.asList(
    		RESPONSE_COLUMNS.indexOf("current-spool-usage-in-mb"), 
    		RESPONSE_COLUMNS.indexOf("high-water-mark-in-mb")); 	
    
    // What should be the formatting style?
    static final private String FLOAT_FORMAT_STYLE = "%.2f";	// 2 decimal places
    		
    // What is the desired order of columns?
    static final private List<Integer> DESIRED_COLUMN_ORDER = Arrays.asList(
    		RESPONSE_COLUMNS.indexOf("RowUID"), RESPONSE_COLUMNS.indexOf("name"), RESPONSE_COLUMNS.indexOf("message-vpn"),
    		RESPONSE_COLUMNS.indexOf("num-messages-spooled"), RESPONSE_COLUMNS.indexOf("current-spool-usage-in-mb"), RESPONSE_COLUMNS.indexOf("quota"), RESPONSE_COLUMNS.indexOf("high-water-mark-in-mb"),
    		RESPONSE_COLUMNS.indexOf("total-delivered-unacked-msgs"), RESPONSE_COLUMNS.indexOf("bind-count"), RESPONSE_COLUMNS.indexOf("max-bind-count"), RESPONSE_COLUMNS.indexOf("access-type"),
    		RESPONSE_COLUMNS.indexOf("durable"), RESPONSE_COLUMNS.indexOf("owner"), RESPONSE_COLUMNS.indexOf("ingress-config-status"), RESPONSE_COLUMNS.indexOf("egress-config-status"),
    		RESPONSE_COLUMNS.indexOf("oldest-msg-id"), RESPONSE_COLUMNS.indexOf("newest-msg-id"), RESPONSE_COLUMNS.indexOf("respect-ttl"), RESPONSE_COLUMNS.indexOf("max-ttl"), 
    		RESPONSE_COLUMNS.indexOf("reject-msg-to-sender-on-discard"), RESPONSE_COLUMNS.indexOf("max-redelivery"), RESPONSE_COLUMNS.indexOf("dead-message-queue"));
    // Override the column names to more human friendly
    static final private List<String> COLUMN_NAME_OVERRIDE = 
    		Arrays.asList("RowUID", "Queue Name", "Message VPN", "Messages Spooled", "Spool Usage (MB)", "Spool Quota (MB)", "Spool Usage HWM (MB)", 
    				"Delivered Messages Unacked", "Bind Count", "Bind Count - Max", "Access Type",
    				"Durable", "Owner", "Ingress Status", "Egress Status",
    				"Oldest Msg ID", "Newest Msg ID", "Respect TTL", "Max TTL",
    				"Reject to Sender on Discard", "Max Redelivery Attempts", "Dead Message Queue");
    
    private DefaultHttpClient httpClient;
    private ResponseHandler<SampleHttpSEMPResponse> responseHandler;
    private TargetedMultiRecordSEMPParser multiRecordParser;

    private Vector<Object> receivedTableContent;
    
    private LinkedHashMap<String, Object> globalHeadlines = new LinkedHashMap<String, Object>();
    // Is the monitor creating a dataview per VPN or everything is in one view?
    private boolean multiview = false;
    // What is the maximum number of rows to limit the dataview to? Default 200 unless overridden.
    private int maxRows = 200;
    
    // If in multiview mode, list of detected VPN names
    private List<String> detectedVpns;
    
    // A map of per view table contents and headlines
    private Map<String, Vector<Object>> tablesPerView = new HashMap();
    private Map<String, LinkedHashMap<String, Object>> headlinesPerView = new HashMap();
    
    // If multi-view mode and a view is to be deleted, cannot delete and clear it in one update. So need two samples for this, marking it for delete on the first sample.
    List<String> viewMarkedForDelete = new ArrayList<String>();		
    
    // What is the configured name of the dataview?
    private String dataViewName = "";
    private String defaultDataViewKey = "";
    
    // When sorting the table rows before limiting to maxrows, how to prioritise the top of the cut?
    // This comparator is used to sort the table so the highest spool utilisation percentage against the quota is at the top.
    static class QueuesComparator implements Comparator<Object>
    {
        @SuppressWarnings("unchecked")
		public int compare(Object tableRow1, Object tableRow2)
        {
        	double spoolUsage1 = Double.parseDouble( ((ArrayList<String>)tableRow1).get( RESPONSE_COLUMNS.indexOf("current-spool-usage-in-mb") ));
        	double spoolQuota1 = Double.parseDouble( ((ArrayList<String>)tableRow1).get( RESPONSE_COLUMNS.indexOf("quota") ));
        	double utilisation1 = (spoolQuota1 > 0) ? (spoolUsage1 / spoolQuota1) * 100 : 0;

        	double spoolUsage2 = Double.parseDouble( ((ArrayList<String>)tableRow2).get( RESPONSE_COLUMNS.indexOf("current-spool-usage-in-mb") ));
        	double spoolQuota2 = Double.parseDouble( ((ArrayList<String>)tableRow2).get( RESPONSE_COLUMNS.indexOf("quota") ));
        	double utilisation2 = (spoolQuota2 > 0) ? (spoolUsage2 / spoolQuota2) * 100 : 0;

            return Double.compare(utilisation2, utilisation1);	
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
        
        // create SEMP parser with the name of the element that contains the records
		multiRecordParser = new TargetedMultiRecordSEMPParser(RESPONSE_ELEMENT_NAME_ROWS, RESPONSE_COLUMNS, RESPONSE_ELEMENT_NAMES_IGNORE);
		
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
		
		// Construct table content
		HttpPost post = new HttpPost(HTTP_REQUEST_URI);
		post.setHeader(HEADER_CONTENT_TYPE_UTF8);
		post.setEntity(new ByteArrayEntity(SHOW_USERS_REQUEST.getBytes("UTF-8")));
		
		
		SampleHttpSEMPResponse resp = httpClient.execute(post, responseHandler);
		
        if (resp.getStatusCode() != 200) {

        	throw new Exception("Error occurred while sending request: " + resp.getStatusCode() 
        			+ " - " + resp.getReasonPhrase());
        }	        
        String respBody = resp.getRespBody();        
		multiRecordParser.parse(respBody);
		
		
		// Will take what is received as the table contents and then do further Java8 Streams based filtering... 
		this.receivedTableContent = multiRecordParser.getTableContent();
		
		
		// Need to do some cleanup on the received table as two optional output fields cause some disjointed column alignment
		Vector<Object> goodTableContent = new Vector<Object>();
		Vector<Object> brokenTableContent = new Vector<Object>();

		// To detect that, the expected number of columns is the column names list size if everything got provided
		
		goodTableContent.addAll(
				receivedTableContent
				.stream()
				.filter( tableRow -> ((ArrayList<String>)tableRow).size() == RESPONSE_COLUMNS.size())
				.collect(Collectors.toCollection(Vector<Object>::new))
			);
				
		brokenTableContent.addAll(
				receivedTableContent
				.stream()
				.filter( tableRow -> ((ArrayList<String>)tableRow).size() < RESPONSE_COLUMNS.size())
				.collect(Collectors.toCollection(Vector<Object>::new))
			);
		
		// For the rows in the broken table, fix it by padding where necessary
	    for (Integer columnNumberToPad : RESPONSE_PAD_COLUMN_NUMBERS) 
	    { 		      
			brokenTableContent.forEach(tableRow -> ((ArrayList<String>)tableRow).add(columnNumberToPad, ""));		
	    }


		// Merge it all together now...
		goodTableContent.addAll(brokenTableContent);
		
		// Reset any previously saved table state
		tablesPerView = new HashMap<String, Vector<Object>>();		
	
		// Split into per-VPN datasets if split mode selected
		if (multiview)
		{
			
			// Get the distinct set of VPN names...
			detectedVpns = new ArrayList<String>();
			detectedVpns.addAll(
				goodTableContent
				.stream()
				.map( tableRow -> ((ArrayList<String>)tableRow).get( RESPONSE_COLUMNS.indexOf("message-vpn") ) )
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
				}
				
				// Create a filtered set of table rows just for this VPN
				tablesPerView.put(vpnName, 
						goodTableContent
						.stream()
						.filter(tableRow -> vpnName.equalsIgnoreCase( ((ArrayList<String>)tableRow).get( RESPONSE_COLUMNS.indexOf("message-vpn") )    ) )
						.collect(Collectors.toCollection(Vector<Object>::new))
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
						.map( tableRow -> ((ArrayList<String>)tableRow).get( RESPONSE_COLUMNS.indexOf("num-messages-spooled") ) )	// Only num-messages-spooled column
						.mapToInt(Integer::parseInt)
						.filter(x -> x > 0) 		// Only the non-zero entries
						.count() ;
				
				long queuesWithBinds = 
						goodTableContent
						.stream()
						.map( tableRow -> ((ArrayList<String>)tableRow).get( RESPONSE_COLUMNS.indexOf("bind-count") ) )	// Only bind-count column
						.mapToInt(Integer::parseInt)
						.filter(x -> x > 0) 		// Only the non-zero entries
						.count() ;
				
				long queuesWithZeroBinds = 
						goodTableContent
						.stream()
						.map( tableRow -> ((ArrayList<String>)tableRow).get( RESPONSE_COLUMNS.indexOf("bind-count") ) )	// Only bind-count column
						.mapToInt(Integer::parseInt)
						.filter(x -> x == 0) 		// Only the non-zero entries
						.count() ;
				
				long sumQueuedMsgs = 
						goodTableContent
						.stream()
						.map( tableRow -> ((ArrayList<String>)tableRow).get( RESPONSE_COLUMNS.indexOf("num-messages-spooled") ) )	// Only num-messages-spooled column
						.mapToLong(Long::parseLong) 	
						.sum() ;
				
				double sumSpoolUsage = 
						goodTableContent
						.stream()
						.map( tableRow -> ((ArrayList<String>)tableRow).get( RESPONSE_COLUMNS.indexOf("current-spool-usage-in-mb") ) )	// Only current-spool-usage-in-mb column
						.mapToDouble(Double::parseDouble)	
						.sum() ;
				
				// (Geneos does not support long values.)
				headlines.put("Queues with Pending Messages", Long.toString(queuesWithMsgs) );
				headlines.put("Queues with Bound Clients", Long.toString(queuesWithBinds) );
				headlines.put("Queues with Zero Bound Clients", Long.toString(queuesWithZeroBinds) );
				headlines.put("Total Messages Pending", Long.toString(sumQueuedMsgs) );
				headlines.put("Total Spool Usage (MB)", String.format(FLOAT_FORMAT_STYLE, sumSpoolUsage));
				headlines.put("Total Queues Count", goodTableContent.size());
				
				// Sort the list to show entries needing attention near to the top. Then also limit to the max row count if exceeding it...
				Vector<Object> tempTableContent = new Vector<Object>();
				
				tempTableContent.addAll(
						goodTableContent
						.stream()
						.sorted(new QueuesComparator())
						.limit(maxRows)				// Then cut the rows at max limit
						.collect(Collectors.toCollection(Vector<Object>::new))
						);
				
				goodTableContent = tempTableContent;
				
				// Pretty up the MB usage numbers. Get the value, format it, set it back. NOTE: Do it as close to the final step towards publishing the table
			    for (Integer columnNumberToFormat : RESPONSE_NUMBER_FORMAT_COLUMN_NUMBERS) 
			    { 		      
			    	goodTableContent.forEach(tableRow -> ((ArrayList<String>)tableRow)
			    			.set(columnNumberToFormat, 
			    					String.format(FLOAT_FORMAT_STYLE, 
			    							Double.parseDouble(
			    									((ArrayList<String>)tableRow).get(columnNumberToFormat)
			    									)
			    							) 
			    					)
							);	
			    }
			    
				// Finally, reorder data into the column order we want versus what came from the parser
				Vector<Object> reorderedTableContent = new Vector<Object>();
				// Iterate to each row in the table contents
				for (int index = 0; index < goodTableContent.size(); index++) {
					
					// Build a new tableRow by adding to it in the right order 
					ArrayList<String> tableRow = new ArrayList<String>();
					
					for (Integer columnNumber : this.DESIRED_COLUMN_ORDER){
						tableRow.add( ((ArrayList<String>)goodTableContent.get(index)).get(columnNumber));
					}
					// Add the newly created row to reorderedTableContent
					reorderedTableContent.add(tableRow); 
				}  
				
				// Table content all complete now for publishing. Just add the column names too.
				reorderedTableContent.add(0, this.COLUMN_NAME_OVERRIDE);	// No longer as received from parser in receivedColumnNames
				tablesPerView.put(viewKey, reorderedTableContent);
				headlinesPerView.put(viewKey, headlines);		
			}
			else
			{
				// There is no content for this view, need to do something about it...
				// Will detect the same and delete it later.
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
    					view.setHeadlines(headlinesPerView.get(view.getName()));
        				view.setTableContent(tablesPerView.get(view.getName()));
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

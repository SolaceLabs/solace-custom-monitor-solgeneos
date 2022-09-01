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

public class ConfigSyncStatusMonitor extends BaseMonitor implements MonitorConstants {
  
	// What version of the monitor?
	static final public String MONITOR_VERSION = "1.0.0";
	
	// The SEMP queries to execute:
    static final public String SHOW_CONFIG_SYNC_REQUEST = 
            "<rpc>" + 
            "	<show>" +
            "		<config-sync>" +
            "			<database/>" + 
            "			<detail/>" +
            "		</config-sync>" +
            "	</show>" +
            "</rpc>";
    
    static final public String SHOW_CONFIG_SYNC_REMOTE_REQUEST = 
            "<rpc>" + 
            "	<show>" +
            "		<config-sync>" +
            "			<database/>" + 
            "			<remote/>" +
            "		</config-sync>" +
            "	</show>" +
            "</rpc>";
    
    // The elements of interest/exclusion within the SEMP response processing:
    static final private String RESPONSE_ELEMENT_NAME_ROWS = "table";
    static final private  List<String> RESPONSE_COLUMNS = 
    		Arrays.asList("RowUID", "name", "type", "sync-state", "ownership", "time-in-state", "time-in-state-seconds");
    // NOTE: "RowUID" is not expected in the SEMP response, but will be added by the parser. However adding it here allows this list to be used as an index where the column number can be searched by name
    
    // For SEMP rows nested a further level into the response, what is of interest?
    static final private String RESPONSE_ELEMENT_NAME_ROWS_L2 = "source-router";
    static final private  List<String> RESPONSE_COLUMNS_L2 = 
    		Arrays.asList("name", "ownership", "sync-state", "time-in-state", "stale", "time-last-msg-received");
    
    static final private  List<String> RESPONSE_ELEMENT_NAMES_IGNORE = Arrays.asList("not-used"); 	
    
    // What is the desired order of columns? (Will be set after first getting a response)
    private List<Integer> desiredColumnOrder;
    
    // Override the column names to more human friendly
    static final private ArrayList<String> COLUMN_NAME_OVERRIDE = new ArrayList<String>(
    		Arrays.asList("RowUID", "Name", "Type", "Ownership", "Sync State", "Time In State", "Time In State (secs)",
    				"Remote Routers Count", "Remote Routers Count (In-Sync)", 
    				"Remote Routers Count (Out-Of-Sync)", "Remote Routers Count (Stale)", "Remote Routers List"
    				));
    
    private DefaultHttpClient httpClient;
    private ResponseHandler<SampleHttpSEMPResponse> responseHandler;
    private TargetedMultiRecordSEMPParser multiRecordParserDetails;
    private TargetedMultiRecordSEMPParser multiRecordParserRemote;
    private Vector<ArrayList<String>> receivedTableContent;
    private Vector<ArrayList<String>> tempTableContent;
    private Vector<ArrayList<String>> tempTableContentL2;
    
    private LinkedHashMap<String, Object> globalHeadlines = new LinkedHashMap<String, Object>();

    // What is the maximum number of rows to limit the dataview to? Default 200 unless overridden.
    private int maxRows = 200;
    
    // When sorting the table rows before limiting to maxrows, how to prioritise the top of the cut?    
    // This comparator is used to sort the table so the rows with the lowest time in state are at the top
    static class TimeInStateSecsComparator implements Comparator<Object>
    {
        @SuppressWarnings("unchecked")
		public int compare(Object tableRow1, Object tableRow2)
        {
        	int nTimeInStatsSecs1 = Integer.parseInt( ((ArrayList<String>)tableRow1).get( COLUMN_NAME_OVERRIDE.indexOf("Time In State (secs)") ));
        	int nTimeInStatsSecs2 = Integer.parseInt( ((ArrayList<String>)tableRow2).get( COLUMN_NAME_OVERRIDE.indexOf("Time In State (secs)") ));
        	
            return Integer.compare(nTimeInStatsSecs1, nTimeInStatsSecs2);	
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
        
        // create SEMP parser with the name of the element that contains the records, and the element name for nested level 2 records
		multiRecordParserDetails = new TargetedMultiRecordSEMPParser(
				RESPONSE_ELEMENT_NAME_ROWS, RESPONSE_COLUMNS, RESPONSE_ELEMENT_NAMES_IGNORE);
		
		multiRecordParserRemote = new TargetedMultiRecordSEMPParser(
				RESPONSE_ELEMENT_NAME_ROWS, RESPONSE_COLUMNS, RESPONSE_ELEMENT_NAMES_IGNORE, 
				RESPONSE_ELEMENT_NAME_ROWS_L2, RESPONSE_COLUMNS_L2);
		
		// Tell the parser how to construct the RowUID. Default its 'name' and 'message-vpn' concatenated. 
		// In this case 'name' will hold the message-vpn name, can use the 'type' to ensure uniqueness of the RowUID 
		multiRecordParserDetails.setSempVpnTag("name");
		multiRecordParserDetails.setSempNameTag("type");
		multiRecordParserRemote.setSempVpnTag("name");
		multiRecordParserRemote.setSempNameTag("type");
	}
	
	private void setDesiredColumnOrder (List<String> currentColumnNames) {
	    
		desiredColumnOrder = Arrays.asList(
				currentColumnNames.indexOf("RowUID"), currentColumnNames.indexOf("name"), currentColumnNames.indexOf("type"),
				currentColumnNames.indexOf("ownership"), currentColumnNames.indexOf("sync-state"), 
				currentColumnNames.indexOf("time-in-state"), currentColumnNames.indexOf("time-in-state-seconds")
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

	@SuppressWarnings({ "static-access" })
	@Override
	protected State onCollect() throws Exception {

		TreeMap<String, View> viewMap = getViewMap();
		LinkedHashMap<String, Object> headlines;
		
		submitSEMPQuery(SHOW_CONFIG_SYNC_REQUEST, multiRecordParserDetails);
		submitSEMPQuery(SHOW_CONFIG_SYNC_REMOTE_REQUEST, multiRecordParserRemote);
		
		List<String> currentColumnNames = multiRecordParserDetails.getColumnNames();
		List<String> currentL2ColumnNames = multiRecordParserRemote.getColumnNamesLevel2();
				
		receivedTableContent = multiRecordParserDetails.getTableContent();
		
		// Has the desired column order been determined yet? (Done on the first time responses and their columns came back.)
		if (this.desiredColumnOrder == null) {
			this.setDesiredColumnOrder(currentColumnNames);
		}
		
		// Reorder the table into the column order we want. 
		tempTableContent = new Vector<ArrayList<String>>();
		
		// Iterate to each row in the table contents
		for (int index = 0; index < receivedTableContent.size(); index++) {
			
			// Build a new tableRow by adding to it in the right order 
			ArrayList<String> tableRow = new ArrayList<String>();
			
			for (Integer columnNumber : this.desiredColumnOrder){
				tableRow.add( receivedTableContent.get(index).get(columnNumber));
			}
			// Add the newly created row to reorderedTableContent
			tempTableContent.add(tableRow); 
		} 
		receivedTableContent = tempTableContent;
		
		// NOTE: Table has been reordered from this point onwards. No further references to 'desiredColumnOrder' for column lookups
		// All lookups should be based off 'COLUMN_NAME_OVERRIDE' and the user friendly column names, not the SEMP field name.
		
		// Add the additional columns of data from the second SEMP query results
		tempTableContent = new Vector<ArrayList<String>>();
		tempTableContentL2 = new Vector<ArrayList<String>>();
		
		String rowUID;
		for (int index = 0; index < receivedTableContent.size(); index++) {
			
			ArrayList<String> tableRow = receivedTableContent.get(index);
			rowUID = tableRow.get(COLUMN_NAME_OVERRIDE.indexOf("RowUID"));
			tempTableContentL2 = multiRecordParserRemote.getTableContentLevel2(rowUID);
			
			Integer nRemoteRouters = tempTableContentL2.size();
			tableRow.add(nRemoteRouters.toString());
			
			// If remote routers present, get details of their states
			if (nRemoteRouters > 0) {
				Long nRemoteRoutersInSync = tempTableContentL2
						.stream()
						.filter(x -> x.get(currentL2ColumnNames.indexOf("sync-state")).equalsIgnoreCase("In-Sync"))
						.count();
				
				Long nRemoteRoutersOutOfSync = tempTableContentL2
						.stream()
						.filter(x -> x.get(currentL2ColumnNames.indexOf("sync-state")).equalsIgnoreCase("Out-Of-Sync"))
						.count();
				
				Long nRemoteRoutersStale = tempTableContentL2
						.stream()
						.filter(x -> x.get(currentL2ColumnNames.indexOf("stale")).equalsIgnoreCase("Yes"))
						.count();
				
				String remoteRoutersNames = tempTableContentL2
						.stream()
						.map( x -> x.get( currentL2ColumnNames.indexOf("name") ) )	// Get just the name column
						.collect(Collectors.toList())
						.toString()
						.replace("[", "")
						.replace("]", "");
						
				tableRow.add(nRemoteRoutersInSync.toString());
				tableRow.add(nRemoteRoutersOutOfSync.toString());
				tableRow.add(nRemoteRoutersStale.toString());
				tableRow.add(remoteRoutersNames);
			}
			else
			{
				// Just pad with zeroes
				tableRow.add("0");
				tableRow.add("0");
				tableRow.add("0");
				tableRow.add("");
			}
			
			// Add the newly created row to reorderedTableContent
			tempTableContent.add(tableRow); 
		} 
		receivedTableContent = tempTableContent;
		
		long nTotalEntries = receivedTableContent.size();
		long nInSyncEntries = receivedTableContent
				.stream()
				.filter(tableRow -> tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Sync State")).equalsIgnoreCase("In-Sync"))
				.count();
		
		long nOutOfSyncEntries = receivedTableContent
				.stream()
				.filter(tableRow -> tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Sync State")).equalsIgnoreCase("Out-Of-Sync"))
				.count();
		
		long nRemoteOutOfSyncEntries = receivedTableContent
				.stream()
				.filter(tableRow -> Integer.parseInt(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Remote Routers Count (Out-Of-Sync)"))) > 0)
				.count();
		
		long nRemoteStaleEntries = receivedTableContent
				.stream()
				.filter(tableRow -> Integer.parseInt(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Remote Routers Count (Stale)"))) > 0)
				.count();
		
		// Does the number of rows exceed the max rows limit? If so, how to prioritise what shows?
		if (nTotalEntries > maxRows) {
			
			tempTableContent = new Vector<ArrayList<String>>();
			Vector<ArrayList<String>> tempRemainderTableContent1 = new Vector<ArrayList<String>>();
			Vector<ArrayList<String>> tempRemainderTableContent2 = new Vector<ArrayList<String>>();
			
			// (1) Get the Out-Of-Sync
			tempTableContent.addAll(
					receivedTableContent
					.stream()
					.filter(tableRow -> tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Sync State")).equalsIgnoreCase("Out-Of-Sync"))
					.collect(Collectors.toCollection(Vector<ArrayList<String>>::new))
					);
			
			tempRemainderTableContent1 = 
					receivedTableContent
					.stream()
					.filter(tableRow -> !tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Sync State")).equalsIgnoreCase("Out-Of-Sync"))
					.collect(Collectors.toCollection(Vector<ArrayList<String>>::new));
			
			// (2) Get the Remote Stale
			tempTableContent.addAll(
					tempRemainderTableContent1
					.stream()
					.filter(tableRow -> Integer.parseInt(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Remote Routers Count (Stale)"))) > 0)
					.collect(Collectors.toCollection(Vector<ArrayList<String>>::new))
					);
			
			tempRemainderTableContent2 = 
					tempRemainderTableContent1
					.stream()
					.filter(tableRow -> Integer.parseInt(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Remote Routers Count (Stale)"))) == 0)
					.collect(Collectors.toCollection(Vector<ArrayList<String>>::new));
			
			// (3) Get the Remote Out-Of-Sync
			tempTableContent.addAll(
					tempRemainderTableContent2
					.stream()
					.filter(tableRow -> Integer.parseInt(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Remote Routers Count (Out-Of-Sync)"))) > 0)
					.collect(Collectors.toCollection(Vector<ArrayList<String>>::new))
					);
			
			tempRemainderTableContent1 = 
					tempRemainderTableContent2
					.stream()
					.filter(tableRow -> Integer.parseInt(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Remote Routers Count (Out-Of-Sync)"))) == 0)
					.collect(Collectors.toCollection(Vector<ArrayList<String>>::new));
			
			// (4) Get the remainder, sorted by time-in-state-seconds as something changed and might be noteworthy
			tempTableContent.addAll(
					tempRemainderTableContent1
					.stream()
					.sorted(new TimeInStateSecsComparator())
					.collect(Collectors.toCollection(Vector<ArrayList<String>>::new))
					);
			
			// Now that prioritised list, cut at the row limit:
			receivedTableContent = 
					tempTableContent
					.stream()
					.limit(maxRows)
					.collect(Collectors.toCollection(Vector<ArrayList<String>>::new));
		}
		
			
		headlines = new LinkedHashMap<String, Object>();
		headlines.putAll(globalHeadlines);		
		headlines.put("Last Sample Time", SolGeneosAgent.onlyInstance.getCurrentTimeString());
		// (Geneos does not support long values.)
		headlines.put("Total Entries", Long.toString(nTotalEntries));
		headlines.put("Entries In-Sync", Long.toString(nInSyncEntries));	
		headlines.put("Entries Out-Of-Sync", Long.toString(nOutOfSyncEntries));
		headlines.put("Entries with Remote Out-Of-Sync", Long.toString(nRemoteOutOfSyncEntries));
		headlines.put("Entries with Remote Stale", Long.toString(nRemoteStaleEntries));
		
		// Table content all complete now for publishing. Just add the column names too.
		receivedTableContent.add(0, this.COLUMN_NAME_OVERRIDE);	// No longer as received from parser in receivedColumnNames
		
		// Now ready to publish each table to the available views... (Either one per VPN, or a default combined one.)
    	if (viewMap != null && viewMap.size() > 0) {
    		for (Iterator<String> viewIt = viewMap.keySet().iterator(); viewIt.hasNext();) 
    		{
    			View view = viewMap.get(viewIt.next());
    			if (view.isActive()) {
					view.setHeadlines(headlines);    					
					Vector<Object> submitTable = new Vector<Object>();
					submitTable.addAll(receivedTableContent);
    				view.setTableContent(submitTable);
    			}
    		}    		
    	}
        return State.REPORTING_QUEUE;
	}

}

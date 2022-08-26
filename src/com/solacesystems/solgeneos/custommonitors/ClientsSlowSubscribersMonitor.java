package com.solacesystems.solgeneos.custommonitors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.Vector;
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

public class ClientsSlowSubscribersMonitor extends BaseMonitor implements MonitorConstants {
  
	// What version of the monitor?
	static final public String MONITOR_VERSION = "1.1.0";
	
	// The SEMP queries to execute:
    static final public String SHOW_CLIENTS_REQUEST_DETAIL = 
        "<rpc>" + 
        "	<show>" +
        "		<client>" +
        "			<name>*</name>" +
        "			<detail/>" +
        "			<slow-subscriber/>" +
        "		</client>" +
        "	</show>" +
        "</rpc>";

    static final public String SHOW_CLIENTS_REQUEST_STATS = 
            "<rpc>" + 
            "	<show>" +
            "		<client>" +
            "			<name>*</name>" +
            "			<stats/>" +
            "			<slow-subscriber/>" +
            "		</client>" +
            "	</show>" +
            "</rpc>";
    
    // The elements of interest/exclusion within the SEMP response processing:
    static final private String RESPONSE_ELEMENT_NAME_ROWS = "client";
    static final private  List<String> RESPONSE_COLUMNS_DETAIL = 
    		Arrays.asList("RowUID", "name", "message-vpn", 
    				"client-address", "num-subscriptions", "eliding-enabled", "eliding-topics", "uptime", 
    				"client-username", "profile", "user", "description", "platform");

    static final private  List<String> RESPONSE_COLUMNS_STATS = 
    		Arrays.asList("RowUID", "name", "message-vpn", 
    				"client-data-messages-sent", "client-data-bytes-sent", 
    				"current-egress-rate-per-second", "average-egress-rate-per-minute",
    				"current-egress-byte-rate-per-second", "average-egress-byte-rate-per-minute",
    				"total-egress-discards");

    // NOTE: "RowUID" is not expected in the SEMP response, but will be added by the parser. However adding it here allows this list to be used as an index where the column number can be searched by name
    
    static final private  List<String> RESPONSE_ELEMENT_NAMES_IGNORE = Arrays.asList("");
       
    // What should be the formatting style?
    static final private String FLOAT_FORMAT_STYLE = "%.3f";	// 3 decimal places. Wanted to add thousandth separator but Geneos fails to recognise it as numbers for rule purposes!

    // Override the column names to more human friendly
    static final private ArrayList<String> DATAVIEW_COLUMN_NAMES = new ArrayList<String>(
    		Arrays.asList("RowUID", "Client ID", "Message VPN", "Host Address", "Username", "Client Profile", "Process Owner", "Process Uptime", 
    				"Description", "Platform", "Subscriptions", "Eliding Enabled?", "Elided Topics",
    				"Current Egress Msg Rate", "Average Egress Msg Rate",
    				"Current Egress MByte Rate", "Average Egress MByte Rate",
    				"Total Msgs Sent", "Total MBytes Sent", "Total Egress Discards"));
    
    static final private List<String> COLUMNS_IN_MBYTES = Arrays.asList("Current Egress MByte Rate", "Total MBytes Sent", "Average Egress MByte Rate");
    
    static final private int BYTE_TO_MBYTE = 1048576;

    // What is the desired order of columns?
    private List<Integer> desiredColumnOrder;	// To be set later once actual response is seen for the first time
    
    private DefaultHttpClient httpClient;
    private ResponseHandler<SampleHttpSEMPResponse> responseHandler;
    private TargetedMultiRecordSEMPParser multiRecordParserDetail;
    private TargetedMultiRecordSEMPParser multiRecordParserStats;

    private Vector<ArrayList<String>> tableContent;
    private Vector<ArrayList<String>> tempTableContent;
    private LinkedHashMap<String, Object> globalHeadlines = new LinkedHashMap<String, Object>();
        
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
	
		// (3) Retrieve SEMP over HTTP properties from global properties
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
		multiRecordParserDetail = new TargetedMultiRecordSEMPParser(RESPONSE_ELEMENT_NAME_ROWS, RESPONSE_COLUMNS_DETAIL, RESPONSE_ELEMENT_NAMES_IGNORE);
		multiRecordParserStats  = new TargetedMultiRecordSEMPParser(RESPONSE_ELEMENT_NAME_ROWS, RESPONSE_COLUMNS_STATS, RESPONSE_ELEMENT_NAMES_IGNORE);

	}
	
	// This is called once the actual returned order of columns from the SEMP response is known
	private void setDesiredColumnOrder (List<String> currentColumnNames) {
	    
		desiredColumnOrder = Arrays.asList(
			currentColumnNames.indexOf("RowUID"), currentColumnNames.indexOf("name"), currentColumnNames.indexOf("message-vpn"),
    		currentColumnNames.indexOf("client-address"), currentColumnNames.indexOf("client-username"), currentColumnNames.indexOf("profile"), 
    		currentColumnNames.indexOf("user"), currentColumnNames.indexOf("uptime"),
    		 currentColumnNames.indexOf("description"), currentColumnNames.indexOf("platform"),
    		currentColumnNames.indexOf("num-subscriptions"), currentColumnNames.indexOf("eliding-enabled"), currentColumnNames.indexOf("eliding-topics"),
    		currentColumnNames.indexOf("current-egress-rate-per-second"), currentColumnNames.indexOf("average-egress-rate-per-minute"), 
    		currentColumnNames.indexOf("current-egress-byte-rate-per-second"), currentColumnNames.indexOf("average-egress-byte-rate-per-minute"), 
    		currentColumnNames.indexOf("client-data-messages-sent"), currentColumnNames.indexOf("client-data-bytes-sent"), 
    		currentColumnNames.indexOf("total-egress-discards")
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
		LinkedHashMap<String, Object> headlines = new LinkedHashMap<String, Object>();
		
		submitSEMPQuery(this.SHOW_CLIENTS_REQUEST_DETAIL, multiRecordParserDetail);
		submitSEMPQuery(this.SHOW_CLIENTS_REQUEST_STATS, multiRecordParserStats);
		
		HashMap<String, ArrayList<String>> clientDetailsTableMap; 
		HashMap<String, ArrayList<String>> clientStatsTableMap;
		
		ArrayList<String> clientDetailsTempRow1;
		ArrayList<String> clientDetailsTempRow2;
		
		List<String> clientsColumnNamesDetail = multiRecordParserDetail.getColumnNames();
		List<String> clientsColumnNamesStats  = multiRecordParserStats.getColumnNames();
		
		// Has the desired column order been determined yet? (Done on the first time responses and their columns came back.)
		if (this.desiredColumnOrder == null) {
			// First get the column names from the client details response then merge with columns from stats response
			// Since will be modifying the column names as supplied, do it to a copy of the List, not the actual one.
			List<String> tempColumnNamesDetail = new ArrayList<String>();
			tempColumnNamesDetail.addAll(multiRecordParserDetail.getColumnNames());
			List<String> tempColumnNamesStats  = new ArrayList<String>();
			tempColumnNamesStats.addAll(multiRecordParserStats.getColumnNames());
			
			tempColumnNamesStats.remove("RowUID");
			tempColumnNamesStats.remove("name");
			tempColumnNamesStats.remove("message-vpn");
			tempColumnNamesDetail.addAll(tempColumnNamesStats);
			
			// Then use this merged columns information to set the final display order
			this.setDesiredColumnOrder(tempColumnNamesDetail);
		}
				
		// Merge the two results together to create the final table for publish...
		
		tableContent = new Vector<ArrayList<String>>();

		clientDetailsTableMap = multiRecordParserDetail.getData();
		clientStatsTableMap = multiRecordParserStats.getData();
		
		for (Map.Entry<String, ArrayList<String>> entry : clientDetailsTableMap.entrySet()){			
			clientDetailsTempRow1 = entry.getValue();
			clientDetailsTempRow2 = clientStatsTableMap.get(entry.getKey());
			
			// Remove the first column that is VPN name from second dataset
			clientDetailsTempRow2.remove(clientsColumnNamesStats.indexOf("message-vpn"));
			clientDetailsTempRow2.remove(clientsColumnNamesStats.indexOf("name"));
			clientDetailsTempRow2.remove(clientsColumnNamesStats.indexOf("RowUID"));
			
			// Merge second dataset with first
			clientDetailsTempRow1.addAll(clientDetailsTempRow2);
			
			tableContent.add(clientDetailsTempRow1);
		}
		
		headlines.putAll(globalHeadlines);
		String lastSampleTime = SolGeneosAgent.onlyInstance.getCurrentTimeString();
		headlines.put("Last Sample Time", lastSampleTime);
		headlines.put("Number of Slow Subscribers", tableContent.size());

		// Rearrange the table into the column order we want
		tempTableContent = new Vector<ArrayList<String>>();
		
		// Iterate to each row in the table contents
		for (int index = 0; index < tableContent.size(); index++) {
			
			// Build a new tableRow by adding to it in the right order 
			ArrayList<String> tableRow = new ArrayList<String>();
			
			for (Integer columnNumber : this.desiredColumnOrder){
				tableRow.add( (tableContent.get(index)).get(columnNumber));
			}
			// Add the newly created row to tempTableContent
			tempTableContent.add(tableRow); 
		} 
		tableContent = tempTableContent;
		
		// NOTE: Columns re-ordered from this point onwards, lookup with DATAVIEW_COLUMN_NAMES.
				
		// Convert fields in bytes to MBytes
		tempTableContent = new Vector<ArrayList<String>>();
		Iterator<ArrayList<String>> itr = tableContent.iterator();	
		
		while (itr.hasNext()) {		
			ArrayList<String> tempTableRow = itr.next();
			for (String columnName : COLUMNS_IN_MBYTES) {
				double bytes = Double.parseDouble(tempTableRow.get(DATAVIEW_COLUMN_NAMES.indexOf(columnName)));
				tempTableRow.set(DATAVIEW_COLUMN_NAMES.indexOf(columnName), String.format(FLOAT_FORMAT_STYLE, (bytes / BYTE_TO_MBYTE)));
			}	
			tempTableContent.add(tempTableRow);
		}  

		tableContent = tempTableContent;		
		tableContent.add(0, this.DATAVIEW_COLUMN_NAMES);			
		
		// Now ready to publish tables to the view map
    	if (viewMap != null && viewMap.size() > 0) {
    		for (Iterator<String> viewIt = viewMap.keySet().iterator(); viewIt.hasNext();) 
    		{
    			View view = viewMap.get(viewIt.next());	
    			if (view.isActive()) {
    				view.setHeadlines(headlines);
    				// The .setTableContent() method forces a Vector of Object!
					Vector<Object> submitTable = new Vector<Object>();
					submitTable.addAll(tableContent);
    				view.setTableContent(submitTable);
    			}
    		}
    	}
        return State.REPORTING_QUEUE;
	}
}

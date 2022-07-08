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
import com.solacesystems.solgeneos.custommonitors.util.MultiRecordSEMPParser;
import com.solacesystems.solgeneos.custommonitors.util.SampleHttpSEMPResponse;
import com.solacesystems.solgeneos.custommonitors.util.SampleResponseHandler;
import com.solacesystems.solgeneos.custommonitors.util.SampleSEMPParser;
import com.solacesystems.solgeneos.custommonitors.util.TargetedMultiRecordSEMPParser;
import com.solacesystems.solgeneos.solgeneosagent.SolGeneosAgent;
import com.solacesystems.solgeneos.solgeneosagent.UserPropertiesConfig;
import com.solacesystems.solgeneos.solgeneosagent.monitor.BaseMonitor;
import com.solacesystems.solgeneos.solgeneosagent.monitor.View;

public class MessageVPNBridgeRatesMonitor extends BaseMonitor implements MonitorConstants {
  
	// What version of the monitor?
	static final public String MONITOR_VERSION = "1.0.0";
	
	// The SEMP queries to execute:
    static final public String SHOW_BRIDGES_REQUEST = 
        "<rpc>" + 
        "	<show>" +
        "		<bridge>" +
        "			<bridge-name-pattern>*</bridge-name-pattern>" +
        "			<detail></detail>" +
        "		</bridge>" +
        "	</show>" +
        "</rpc>";

    static final public String SHOW_BRIDGES_STATS_REQUEST = 
        "<rpc>" + 
        "	<show>" +
        "		<client>" +
        "			<name>#bridge/local*</name>" +
        "			<stats></stats>" +
        "			<detail></detail>" +
        "		</client>" +
        "	</show>" +
        "</rpc>";
    
    static final public String SHOW_BRIDGES_CONNS_REQUEST = 
        "<rpc>" + 
        "	<show>" +
        "		<client>" +
        "			<name>#bridge/local*</name>" +
        "			<connections></connections>" +
        "			<wide></wide>" +
        "		</client>" +
        "	</show>" +
        "</rpc>";
    
    // The elements of interest/exclusion within the SEMP response processing:
    static final private String BRIDGE_DETAILS_RESPONSE_ELEMENT_NAME = "bridge";
    
    static final private String BRIDGE_STATS_RESPONSE_ELEMENT_NAME = "client";
    
    static final private String BRIDGE_CONNS_RESPONSE_ELEMENT_NAME = "client";
    
    static final private  List<String> BRIDGE_DETAILS_RESPONSE_COLUMNS = 
    		Arrays.asList("RowUID", "bridge-name", "local-vpn-name", "connected-remote-vpn-name", "connected-remote-router-name", "admin-state", 
    				"inbound-operational-state", "outbound-operational-state", "queue-operational-state", "connection-uptime-in-seconds", "client-name");
    // NOTE: "RowUID" is not expected in the SEMP response, but will be added by the parser. However adding it here allows this list to be used as an index where the column number can be searched by name
    
    static final private  List<String> BRIDGE_STATS_RESPONSE_COLUMNS = 
    		Arrays.asList("RowUID", "name", "message-vpn", "client-data-messages-received", "client-data-messages-sent", 
    				"client-data-bytes-received", "client-data-bytes-sent",
    				"current-ingress-rate-per-second", "current-egress-rate-per-second", 
    				"average-ingress-rate-per-minute", "average-egress-rate-per-minute",
    				"current-ingress-byte-rate-per-second", "current-egress-byte-rate-per-second", 
    				"average-ingress-byte-rate-per-minute", "average-egress-byte-rate-per-minute",
    				
    				"current-ingress-compressed-rate-per-second", "current-egress-compressed-rate-per-second",
    				"average-ingress-compressed-rate-per-minute", "average-egress-compressed-rate-per-minute",
    				"ingress-compression-ratio", "egress-compression-ratio");


    static final private  List<String> BRIDGE_CONNS_RESPONSE_COLUMNS = 
    		Arrays.asList("RowUID", "name", "message-vpn", "is-zip", "is-ssl", 
    				"receive-queue-bytes", "receive-queue-segments",
    				"send-queue-bytes", "send-queue-segments",
    				"retransmit-time-ms", "round-trip-time-smooth-us", "round-trip-time-variance-us",
    				"advertised-window-size", "transmit-window-size",
					"bandwidth-window-size", "congestion-window-size",
					"slow-start-threshold-size", "segments-received-out-of-order",
					"fast-retransmits", "timed-retransmits", "blocked-cycles-percent");
 
    static final private  List<String> RESPONSE_ELEMENT_NAMES_IGNORE = Arrays.asList("remote-message-vpn-list"); 
    
    // What should be the formatting style?
    static final private String FLOAT_FORMAT_STYLE = "%.3f";	// 3 decimal places. Wanted to add thousandth separator but Geneos fails to recognise it as numbers for rule purposes!
    
    // What is the desired order of columns? (Will be set after first getting a response)
    private List<Integer> desiredColumnOrder;
    
    // Override the column names to more human friendly
    static final private ArrayList<String> COLUMN_NAME_OVERRIDE = new ArrayList<String>(
    		Arrays.asList("RowUID", "Bridge Name", "Local VPN", "Remote VPN", "Remote Router", 
    				"Admin State", "Inbound State", "Outbound State", "Queue Bind State", "Uptime (secs)",
    				
    				"Current Msg Rate", "Average Msg Rate", "Current MByte Rate", "Average MByte Rate",	
    				"Current MByte Rate  (Compressed)", "Average MByte Rate (Compressed)", // Calculated columns these 6
    				
    				"Ingress Compression Ratio", "Egress Compression Ratio",
    				"Current Ingress Msg Rate", "Average Ingress Msg Rate", "Current Ingress MByte Rate", "Average Ingress MByte Rate",
    				"Current Egress Msg Rate", "Average Egress Msg Rate", "Current Egress MByte Rate", "Average Egress MByte Rate",
    				"Total Msgs Received", "Total Msgs Sent", "Total MBytes Received", "Total MBytes Sent",
    				"Compressed?", "TLS?",
    				"Recv-Q Bytes", "Send-Q Bytes", "Recv-Q Segments", "Send-Q Segments",
    				"Retransmit Time (ms)", "Round Trip Time (us)", "Round Trip Variance (us)",
    				"Advertised Window", "Transmit Window Size", "Bandwidth Window Size", 
    				"Congestion Window Size", "Slow Start Threshold Size", 
    				"Out of Order Segments", "Fast Retransmits", "Timed Retransmits", "Blocked%"));
    // NOTE: Any changes to the column names needs to match the order being set in setDesiredColumnOrder()
    
    static final private List<String> COLUMNS_IN_MBYTES = Arrays.asList(
    				"Current MByte Rate", "Average MByte Rate", 
    				"Current MByte Rate  (Compressed)", "Average MByte Rate (Compressed)",
    				"Current Ingress MByte Rate", "Average Ingress MByte Rate",
    				"Current Egress MByte Rate", "Average Egress MByte Rate",
    				"Total MBytes Received", "Total MBytes Sent");
    
    
    static final private int BYTE_TO_MBYTE = 1048576;
    
    private DefaultHttpClient httpClient;
    private ResponseHandler<SampleHttpSEMPResponse> responseHandler;
    
    private TargetedMultiRecordSEMPParser multiRecordParserBridge;
    private TargetedMultiRecordSEMPParser multiRecordParserBridgeStats;
    private TargetedMultiRecordSEMPParser multiRecordParserBridgeConns;

    private LinkedHashMap<String, Object> globalHeadlines = new LinkedHashMap<String, Object>();
    private Vector<ArrayList<String>> vpnBridgesTableContent;
    private Vector<ArrayList<String>> vpnBridgesTableContentTemp;
        
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
        
        // create SEMP parser with the name of the element that contains the records, and the element name for nested level 2 records
		multiRecordParserBridge = new TargetedMultiRecordSEMPParser(
				BRIDGE_DETAILS_RESPONSE_ELEMENT_NAME, BRIDGE_DETAILS_RESPONSE_COLUMNS, RESPONSE_ELEMENT_NAMES_IGNORE);
		
		multiRecordParserBridgeStats = new TargetedMultiRecordSEMPParser(
				BRIDGE_STATS_RESPONSE_ELEMENT_NAME, BRIDGE_STATS_RESPONSE_COLUMNS, RESPONSE_ELEMENT_NAMES_IGNORE);
		
		multiRecordParserBridgeConns = new TargetedMultiRecordSEMPParser(
				BRIDGE_CONNS_RESPONSE_ELEMENT_NAME, BRIDGE_CONNS_RESPONSE_COLUMNS, RESPONSE_ELEMENT_NAMES_IGNORE);
		
		// How to generate the RowUID? Join up the values of these two columns:
		// (Parser when not set defaults to 'name' and 'message-vpn', which works for nearly all other SEMP responses like show-queues.)
		multiRecordParserBridge.setSempNameTag("bridge-name");
		multiRecordParserBridge.setSempVpnTag("local-vpn-name");
		
	}
	
	private void setDesiredColumnOrder (List<String> currentColumnNames, boolean isReducedColumns) {
		
		if (isReducedColumns) {
			desiredColumnOrder = Arrays.asList(
					currentColumnNames.indexOf("RowUID"), currentColumnNames.indexOf("bridge-name"), currentColumnNames.indexOf("local-vpn-name"), currentColumnNames.indexOf("connected-remote-vpn-name"),
					currentColumnNames.indexOf("connected-remote-router-name"), currentColumnNames.indexOf("admin-state"), currentColumnNames.indexOf("inbound-operational-state"), currentColumnNames.indexOf("outbound-operational-state"),
					currentColumnNames.indexOf("queue-operational-state"), currentColumnNames.indexOf("connection-uptime-in-seconds")
					);
		}
		else {
			
			desiredColumnOrder = Arrays.asList(
					// 10:
					currentColumnNames.indexOf("RowUID"), currentColumnNames.indexOf("bridge-name"), currentColumnNames.indexOf("local-vpn-name"), currentColumnNames.indexOf("connected-remote-vpn-name"),
					currentColumnNames.indexOf("connected-remote-router-name"), currentColumnNames.indexOf("admin-state"), currentColumnNames.indexOf("inbound-operational-state"), currentColumnNames.indexOf("outbound-operational-state"),
					currentColumnNames.indexOf("queue-operational-state"), currentColumnNames.indexOf("connection-uptime-in-seconds"),
					// 6 Computed columns:
					currentColumnNames.indexOf("current-rate-per-second"), currentColumnNames.indexOf("average-rate-per-minute"),
					currentColumnNames.indexOf("current-byte-rate-per-second"), currentColumnNames.indexOf("average-byte-rate-per-minute"),
					currentColumnNames.indexOf("current-compressed-rate-per-second"), currentColumnNames.indexOf("average-compressed-rate-per-minute"),
					
					// 14 from Stats:
					currentColumnNames.indexOf("ingress-compression-ratio"), currentColumnNames.indexOf("egress-compression-ratio"),
					currentColumnNames.indexOf("current-ingress-rate-per-second"), currentColumnNames.indexOf("average-ingress-rate-per-minute"),
					currentColumnNames.indexOf("current-ingress-byte-rate-per-second"), currentColumnNames.indexOf("average-ingress-byte-rate-per-minute"),
					currentColumnNames.indexOf("current-egress-rate-per-second"), currentColumnNames.indexOf("average-egress-rate-per-minute"),
					currentColumnNames.indexOf("current-egress-byte-rate-per-second"), currentColumnNames.indexOf("average-egress-byte-rate-per-minute"),
					
					currentColumnNames.indexOf("client-data-messages-received"), currentColumnNames.indexOf("client-data-messages-sent"),
					currentColumnNames.indexOf("client-data-bytes-received"), currentColumnNames.indexOf("client-data-bytes-sent"),
					// 18 from Conns:
					currentColumnNames.indexOf("is-zip"), currentColumnNames.indexOf("is-ssl"), 
					
					currentColumnNames.indexOf("receive-queue-bytes"), currentColumnNames.indexOf("send-queue-bytes"),
					currentColumnNames.indexOf("receive-queue-segments"), currentColumnNames.indexOf("send-queue-segments"),
					
					currentColumnNames.indexOf("retransmit-time-ms"), currentColumnNames.indexOf("round-trip-time-smooth-us"), currentColumnNames.indexOf("round-trip-time-variance-us"), 
					currentColumnNames.indexOf("advertised-window-size"), currentColumnNames.indexOf("transmit-window-size"), currentColumnNames.indexOf("bandwidth-window-size"), currentColumnNames.indexOf("congestion-window-size"),
					currentColumnNames.indexOf("slow-start-threshold-size"), currentColumnNames.indexOf("segments-received-out-of-order"), currentColumnNames.indexOf("fast-retransmits"), currentColumnNames.indexOf("timed-retransmits"),
					currentColumnNames.indexOf("blocked-cycles-percent")
					);			
		}
		
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
		
		HashMap<String, ArrayList<String>> bridgeDetailsTableMap; 
		HashMap<String, ArrayList<String>> bridgeDetailsStatsTableMap;
		HashMap<String, ArrayList<String>> bridgeDetailsConnsTableMap;
		
		ArrayList<String> bridgeDetailsTempRow1;
		ArrayList<String> bridgeDetailsTempRow2;
		
		// Get the SEMP responses:
		submitSEMPQuery(SHOW_BRIDGES_REQUEST, multiRecordParserBridge);
		submitSEMPQuery(SHOW_BRIDGES_STATS_REQUEST, multiRecordParserBridgeStats);
		submitSEMPQuery(SHOW_BRIDGES_CONNS_REQUEST, multiRecordParserBridgeConns);
		
		List<String> bridgeDetailsColumnNames = multiRecordParserBridge.getColumnNames();
		List<String> bridgeDetailsStatsColumnNames = multiRecordParserBridgeStats.getColumnNames();
		List<String> bridgeDetailsConnsColumnNames = multiRecordParserBridgeConns.getColumnNames();
		
		// Need to set the column ordering on each run, as the data available can change between polls
		// First get the column names from the bridge details response
		List<String> currentColumnNames = new ArrayList<String>();
		currentColumnNames.addAll(bridgeDetailsColumnNames);
		currentColumnNames.remove("client-name");
		
		// Was there any connection stats available even?
		if (bridgeDetailsConnsColumnNames.isEmpty()) {
			setDesiredColumnOrder(currentColumnNames, true);	// reduced columns to work off
		}
		else {
			// Then add the column names from the Conns details response after removing the RowUID column
			List<String> tempColumnNames = new ArrayList<String>();
			tempColumnNames.addAll(bridgeDetailsConnsColumnNames);
			
			tempColumnNames.remove("RowUID");
			tempColumnNames.remove("name");
			tempColumnNames.remove("message-vpn");
			currentColumnNames.addAll(tempColumnNames);
			
			// And again for the Stats columns
			tempColumnNames = new ArrayList<String>();
			tempColumnNames.addAll(bridgeDetailsStatsColumnNames);
			
			tempColumnNames.remove("RowUID");
			tempColumnNames.remove("name");
			tempColumnNames.remove("message-vpn");
			
			tempColumnNames.add("current-rate-per-second");
			tempColumnNames.add("average-rate-per-minute");
			tempColumnNames.add("current-byte-rate-per-second");
			tempColumnNames.add("average-byte-rate-per-minute");
			tempColumnNames.add("current-compressed-rate-per-second");
			tempColumnNames.add("average-compressed-rate-per-minute");
			
			currentColumnNames.addAll(tempColumnNames);
			
			// Then use this merged columns information to set the final display order
			setDesiredColumnOrder(currentColumnNames, false);	// not reduced columns set.
		}
				
				
		// Merge the three results together to create the final table for publish...
		
		vpnBridgesTableContent = new Vector<ArrayList<String>>();
		
		bridgeDetailsTableMap = multiRecordParserBridge.getData();
		bridgeDetailsConnsTableMap = multiRecordParserBridgeConns.getData();
		bridgeDetailsStatsTableMap = multiRecordParserBridgeStats.getData();
		
		for (Map.Entry<String, ArrayList<String>> entry : bridgeDetailsTableMap.entrySet()){
			
			// (1) Add Bridge Connections table to Bridge Details, Connection details are keyed with the RowUID as client-name and vpn-name
			bridgeDetailsTempRow1 = new ArrayList<String>();
			bridgeDetailsTempRow1.addAll(entry.getValue());
 
			String clientName = bridgeDetailsTempRow1.get(bridgeDetailsColumnNames.indexOf("client-name"));
			String vpnName = bridgeDetailsTempRow1.get(bridgeDetailsColumnNames.indexOf("local-vpn-name"));
			String rowUID = clientName + multiRecordParserBridgeStats.getRowUIDDelim() + vpnName;
		
			bridgeDetailsTempRow1.remove(bridgeDetailsColumnNames.indexOf("client-name"));
			
			// Only if there is indeed a connection to report stats on...
			if (bridgeDetailsConnsTableMap.containsKey(rowUID)) {
				bridgeDetailsTempRow2 = new ArrayList<String>();
				bridgeDetailsTempRow2.addAll(bridgeDetailsConnsTableMap.get(rowUID));
				
				// Remove the redundant columns from second dataset 

				bridgeDetailsTempRow2.remove(bridgeDetailsConnsColumnNames.indexOf("message-vpn"));
				bridgeDetailsTempRow2.remove(bridgeDetailsConnsColumnNames.indexOf("name"));
				bridgeDetailsTempRow2.remove(bridgeDetailsConnsColumnNames.indexOf("RowUID"));

				// Merge second dataset with first
				bridgeDetailsTempRow1.addAll(bridgeDetailsTempRow2);		
				
				// (2) Add Bridge Stats table, that is also keyed with RowUID as client-name and vpn-name
				bridgeDetailsTempRow2 = new ArrayList<String>();
				bridgeDetailsTempRow2.addAll(bridgeDetailsStatsTableMap.get(rowUID));
				
				// Add the calculated columns to the end
				Long currentMsgRate = Long.parseLong(bridgeDetailsTempRow2.get(bridgeDetailsStatsColumnNames.indexOf("current-ingress-rate-per-second"))) +
						Long.parseLong(bridgeDetailsTempRow2.get(bridgeDetailsStatsColumnNames.indexOf("current-egress-rate-per-second")));
				Long averageMsgRate = Long.parseLong(bridgeDetailsTempRow2.get(bridgeDetailsStatsColumnNames.indexOf("average-ingress-rate-per-minute"))) +
						Long.parseLong(bridgeDetailsTempRow2.get(bridgeDetailsStatsColumnNames.indexOf("average-egress-rate-per-minute")));
				Long currentByteRate = Long.parseLong(bridgeDetailsTempRow2.get(bridgeDetailsStatsColumnNames.indexOf("current-ingress-byte-rate-per-second"))) +
						Long.parseLong(bridgeDetailsTempRow2.get(bridgeDetailsStatsColumnNames.indexOf("current-egress-byte-rate-per-second")));
				Long averageByteRate = Long.parseLong(bridgeDetailsTempRow2.get(bridgeDetailsStatsColumnNames.indexOf("average-ingress-byte-rate-per-minute"))) +
						Long.parseLong(bridgeDetailsTempRow2.get(bridgeDetailsStatsColumnNames.indexOf("average-egress-byte-rate-per-minute")));
				Long currentCompressedByteRate = Long.parseLong(bridgeDetailsTempRow2.get(bridgeDetailsStatsColumnNames.indexOf("current-ingress-compressed-rate-per-second"))) +
						Long.parseLong(bridgeDetailsTempRow2.get(bridgeDetailsStatsColumnNames.indexOf("current-egress-compressed-rate-per-second")));;
				Long averageCompressedByteRate = Long.parseLong(bridgeDetailsTempRow2.get(bridgeDetailsStatsColumnNames.indexOf("average-ingress-compressed-rate-per-minute"))) +
						Long.parseLong(bridgeDetailsTempRow2.get(bridgeDetailsStatsColumnNames.indexOf("average-egress-compressed-rate-per-minute")));;
				
				bridgeDetailsTempRow2.add(currentMsgRate.toString());
				bridgeDetailsTempRow2.add(averageMsgRate.toString());
				bridgeDetailsTempRow2.add(currentByteRate.toString());
				bridgeDetailsTempRow2.add(averageByteRate.toString());
				bridgeDetailsTempRow2.add(currentCompressedByteRate.toString());
				bridgeDetailsTempRow2.add(averageCompressedByteRate.toString());
					
				// Remove the first column that is the RowUID from second dataset
				bridgeDetailsTempRow2.remove(bridgeDetailsStatsColumnNames.indexOf("message-vpn"));
				bridgeDetailsTempRow2.remove(bridgeDetailsStatsColumnNames.indexOf("name"));
				bridgeDetailsTempRow2.remove(bridgeDetailsStatsColumnNames.indexOf("RowUID"));

				// Merge datasets again
				bridgeDetailsTempRow1.addAll(bridgeDetailsTempRow2);
			}
			else {
				// Need to pad the missing columns...
				int paddingCount = currentColumnNames.size() - bridgeDetailsTempRow1.size();	
				for (int i = 0; i < paddingCount; i++) {
					bridgeDetailsTempRow1.add("");
				}
			}
			
			// Add this row to the publish table, then iterate again for the subsequent bridges
			vpnBridgesTableContent.add(bridgeDetailsTempRow1);
		}	
		
		// Reorder the merged table into the column order we want. 
		// Iterate to each row in the table contents
		
		vpnBridgesTableContentTemp = new Vector<ArrayList<String>>();
		
		for (int index = 0; index < vpnBridgesTableContent.size(); index++) {
			
			// Build a new tableRow by adding to it in the right order 
			ArrayList<String> tableRow1 = vpnBridgesTableContent.get(index);
			ArrayList<String> tableRow2 = new ArrayList<String>();
			
			for (Integer columnNumber : this.desiredColumnOrder){
				tableRow2.add( tableRow1.get(columnNumber));
			}
			// Add the newly created row to Temp Table
			vpnBridgesTableContentTemp.add(tableRow2); 
		}  
	
		vpnBridgesTableContent = vpnBridgesTableContentTemp;
		
		headlines.putAll(globalHeadlines);
		
		String lastSampleTime = SolGeneosAgent.onlyInstance.getCurrentTimeString();
		headlines.put("Last Sample Time", lastSampleTime);
	
		// NOTE: Columns re-ordered from this point onwards, lookup with COLUMN_NAME_OVERRIDE.
		
		// Convert fields in bytes to MBytes
		vpnBridgesTableContentTemp = new Vector<ArrayList<String>>();
		Iterator<ArrayList<String>> itr = vpnBridgesTableContent.iterator();	
		
		while (itr.hasNext()) {		
			ArrayList<String> tempTableRow = itr.next();
			
			// Only if there are values to convert...
			if (!tempTableRow.get(COLUMN_NAME_OVERRIDE.indexOf("Current Msg Rate")).isEmpty()) {
				for (String columnName : COLUMNS_IN_MBYTES) {
					double bytes = Double.parseDouble(tempTableRow.get(COLUMN_NAME_OVERRIDE.indexOf(columnName)));
					tempTableRow.set(COLUMN_NAME_OVERRIDE.indexOf(columnName), String.format(FLOAT_FORMAT_STYLE, (bytes / BYTE_TO_MBYTE)));
				}	
			}
						
			vpnBridgesTableContentTemp.add(tempTableRow);
		}  

		vpnBridgesTableContent = vpnBridgesTableContentTemp;
		
		// Determine some headlines now		
		long nBridgesEnabled = 
				vpnBridgesTableContent
				.stream()
				.map( tableRow -> tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Admin State") ) )	
				.filter(x -> x.equalsIgnoreCase("Enabled") ) 
				.count() ;
		
		long nBridgesBidirectional = 
				vpnBridgesTableContent
				.stream()
				.map( tableRow -> tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Outbound State") ) )	
				.filter(x -> x.equalsIgnoreCase("Ready") ) 
				.count() ;
		
		long nBridgesUnidirectional = 
				vpnBridgesTableContent
				.stream()
				.map( tableRow -> tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Inbound State") ) )	
				.filter(x -> !x.equalsIgnoreCase("Shutdown") ) 
				.count() ;
		
		long nBridgesActive = 
				vpnBridgesTableContent
				.stream()
				.map( tableRow -> tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Uptime (secs)") ) )	
				.mapToLong(Long::parseLong) 
				.filter(x -> x > 0 ) 
				.count() ;
		
		// (Geneos does not support long values.)
		headlines.put("Number of configured bridges", vpnBridgesTableContent.size() );
		headlines.put("Number of enabled bridges", Long.toString(nBridgesEnabled) );
		headlines.put("Number of active bridges", Long.toString(nBridgesActive) );
		headlines.put("Number of unidirectional bridges", Long.toString(nBridgesUnidirectional - nBridgesBidirectional) );
		headlines.put("Number of bidirectional bridges", Long.toString(nBridgesBidirectional) );
		
		// Add the override column names
		vpnBridgesTableContent.add(0, COLUMN_NAME_OVERRIDE);
		
		// Now ready to publish tables to the view map
    	if (viewMap != null && viewMap.size() > 0) {
    		for (Iterator<String> viewIt = viewMap.keySet().iterator(); viewIt.hasNext();) 
    		{
    			View view = viewMap.get(viewIt.next());	
    			if (view.isActive()) {
    				view.setHeadlines(headlines);
    				// The .setTableContent() method forces a Vector of Object!
					Vector<Object> submitTable = new Vector<Object>();
					submitTable.addAll(vpnBridgesTableContent);
    				view.setTableContent(submitTable);
    			}
    		}
    	}
        return State.REPORTING_QUEUE;
	}
}

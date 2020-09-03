package com.solacesystems.solgeneos.custommonitors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.solacesystems.solgeneos.custommonitors.util.VPNRecordSEMPParser;
import com.solacesystems.solgeneos.solgeneosagent.SolGeneosAgent;
import com.solacesystems.solgeneos.solgeneosagent.UserPropertiesConfig;
import com.solacesystems.solgeneos.solgeneosagent.monitor.BaseMonitor;
import com.solacesystems.solgeneos.solgeneosagent.monitor.View;

public class MessageVPNRatesMonitor extends BaseMonitor implements MonitorConstants {
  
	// What version of the monitor?
	static final public String MONITOR_VERSION = "1.0";
	
	// The SEMP query to execute:
    static final public String SHOW_USERS_REQUEST = 
            "<rpc>" + 
            "	<show>" +
            "		<message-vpn>" +
            "			<vpn-name>*</vpn-name>" +
            "			<stats></stats>" +
            "		</message-vpn>" +
            "	</show>" +
            "</rpc>";
    
    // The elements of interest/exclusion within the SEMP response processing:
    static final private String RESPONSE_ELEMENT_NAME_ROWS = "vpn";
    static final private  List<String> RESPONSE_COLUMNS = 
    		Arrays.asList("name", 
    				"client-data-messages-received", "client-data-messages-sent", "client-persistent-messages-received", "client-persistent-messages-sent", 
    				"client-non-persistent-messages-received", "client-non-persistent-messages-sent", "client-direct-messages-received", "client-direct-messages-sent",
    				"client-data-bytes-received", "client-data-bytes-sent", "client-persistent-bytes-received", "client-persistent-bytes-sent", 
    				"client-non-persistent-bytes-received", "client-non-persistent-bytes-sent", "client-direct-bytes-received", "client-direct-bytes-sent",
    				"current-ingress-rate-per-second", "current-egress-rate-per-second", "current-ingress-byte-rate-per-second", "current-egress-byte-rate-per-second",
    				"average-ingress-rate-per-minute", "average-egress-rate-per-minute", "average-ingress-byte-rate-per-minute", "average-egress-byte-rate-per-minute");
    
    static final private  List<String> RESPONSE_ELEMENT_NAMES_IGNORE = Arrays.asList("authentication", "ingress-discards", "egress-discards", "certificate-revocation-check-stats");
     	
    // What should be the formatting style?
    static final private String FLOAT_FORMAT_STYLE = "%.2f";	// 2 decimal places
    		
    // What is the desired order of columns?
    static final private List<Integer> DESIRED_COLUMN_ORDER = Arrays.asList(
    		RESPONSE_COLUMNS.indexOf("name"), 
    		RESPONSE_COLUMNS.indexOf("current-ingress-rate-per-second"), RESPONSE_COLUMNS.indexOf("current-egress-rate-per-second"),
    		RESPONSE_COLUMNS.indexOf("average-ingress-rate-per-minute"), RESPONSE_COLUMNS.indexOf("average-egress-rate-per-minute"),
    		RESPONSE_COLUMNS.indexOf("current-ingress-byte-rate-per-second"), RESPONSE_COLUMNS.indexOf("current-egress-byte-rate-per-second"),
    		RESPONSE_COLUMNS.indexOf("average-ingress-byte-rate-per-minute"), RESPONSE_COLUMNS.indexOf("average-egress-byte-rate-per-minute"),
    		
    		RESPONSE_COLUMNS.indexOf("client-data-messages-received"), RESPONSE_COLUMNS.indexOf("client-data-messages-sent"), RESPONSE_COLUMNS.indexOf("client-persistent-messages-received"), RESPONSE_COLUMNS.indexOf("client-persistent-messages-sent"),
    		RESPONSE_COLUMNS.indexOf("client-non-persistent-messages-received"), RESPONSE_COLUMNS.indexOf("client-non-persistent-messages-sent"), RESPONSE_COLUMNS.indexOf("client-direct-messages-received"), RESPONSE_COLUMNS.indexOf("client-direct-messages-sent"),
    		
    		RESPONSE_COLUMNS.indexOf("client-data-bytes-received"), RESPONSE_COLUMNS.indexOf("client-data-bytes-sent"), RESPONSE_COLUMNS.indexOf("client-persistent-bytes-received"), RESPONSE_COLUMNS.indexOf("client-persistent-bytes-sent"),
    		RESPONSE_COLUMNS.indexOf("client-non-persistent-bytes-received"), RESPONSE_COLUMNS.indexOf("client-non-persistent-bytes-sent"), RESPONSE_COLUMNS.indexOf("client-direct-bytes-received"), RESPONSE_COLUMNS.indexOf("client-direct-bytes-sent")
    		
    		);
    
    // Override the column names to more human friendly
    static final private List<String> COLUMN_NAME_OVERRIDE = 
    		Arrays.asList("Message VPN",
    				"Current Msg Rate", "Average Msg Rate", "Current Byte Rate", "Average Byte Rate", 	// These will be computed (sum ingress and egress) and added as columns
    				
    				"Current Ingress Msg Rate", "Current Egress Msg Rate", "Average Ingress Msg Rate", "Average Egress Msg Rate",
    				"Current Ingress Byte Rate", "Current Egress Byte Rate", "Average Ingress Byte Rate", "Average Egress Byte Rate",
    				
    				"Data Msgs Received", "Data Msgs Sent", "Data Msgs Received (Persistent)", "Data Msgs Sent (Persistent)", "Data Msgs Received (Non-Persistent)", "Data Msgs Sent (Non-Persistent)", "Data Msgs Received (Direct)", "Data Msgs Sent (Direct)",
    				"Data Bytes Received", "Data Bytes Sent", "Data Bytes Received (Persistent)", "Data Bytes Sent (Persistent)", "Data Bytes Received (Non-Persistent)", "Data Bytes Sent (Non-Persistent)", "Data Bytes Received (Direct)", "Data Bytes Sent (Direct)"
    				
    				);
    static final private int COMPUTED_COLUMN_COUNT = 4;		// Knowing the size can be used to more efficiently call the constructor for the ArrayList<String>...
    
    static final private int BYTE_TO_MBYTE = 1048576;
    
    static final private int TOP_TALKERS_LIMIT = 3;	// How many VPNs to list in the "top talkers" headlines?
    
    private DefaultHttpClient httpClient;
    private ResponseHandler<SampleHttpSEMPResponse> responseHandler;
    private VPNRecordSEMPParser multiRecordParser;

    private Vector<Object> receivedTableContent;
    private Vector<Object> tempTableContent;		// Used in the various stages of manipulating the received table
    
    private LinkedHashMap<String, Object> globalHeadlines = new LinkedHashMap<String, Object>();
    // Is the monitor creating a dataview per VPN or everything is in one view?
    // What is the maximum number of rows to limit the dataview to? Default 200 unless overridden.
    private int maxRows = 200;

    
    // When sorting the table rows before limiting to maxrows, how to prioritise the top of the cut?
    // This comparator is used to sort the table so the highest average message rate is at the top of the table
    // Note: Assuming the sort is done after columns re-ordered and computed columns added. So use lookup via COLUMN_NAME_OVERRIDE
    static class RatesComparator implements Comparator<Object>
    {
        @SuppressWarnings("unchecked")
		public int compare(Object tableRow1, Object tableRow2)
        {
        	double currentMsgRate1 = Double.parseDouble( ((ArrayList<String>)tableRow1).get(COLUMN_NAME_OVERRIDE.indexOf("Average Byte Rate") ));
        	double currentMsgRate2 = Double.parseDouble( ((ArrayList<String>)tableRow2).get(COLUMN_NAME_OVERRIDE.indexOf("Average Byte Rate") ));

            return Double.compare(currentMsgRate2, currentMsgRate1);	
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
		multiRecordParser = new VPNRecordSEMPParser(RESPONSE_ELEMENT_NAME_ROWS, RESPONSE_COLUMNS, RESPONSE_ELEMENT_NAMES_IGNORE);
		
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
		
		
		// Will take what is received as the table contents and then do further processing and adjusting... 
		receivedTableContent = multiRecordParser.getTableContent();		
		// Firstly reorder data into the column order we want versus what came from the parser
		
		tempTableContent = new Vector<Object>();
		// Iterate to each row in the table contents
		for (int index = 0; index < receivedTableContent.size(); index++) {
			
			// Build a new tableRow by adding to it in the right order 
			ArrayList<String> tableRow = new ArrayList<String>();
			
			for (Integer columnNumber : this.DESIRED_COLUMN_ORDER){
				tableRow.add( ((ArrayList<String>)receivedTableContent.get(index)).get(columnNumber));
			}
			// Add the newly created row to reorderedTableContent
			tempTableContent.add(tableRow); 
		}  
		
		receivedTableContent = tempTableContent;
		
		// Add some computed columns to the reordered table, will all be inserted together
		tempTableContent = new Vector<Object>();
		
		ArrayList<String> tableRow;
		ArrayList<String> computedTableColumns;
		for (int i = 0; i < receivedTableContent.size(); i++) {
			
			tableRow = (ArrayList<String>) receivedTableContent.get(i);
			computedTableColumns = new ArrayList<String>(COMPUTED_COLUMN_COUNT);
			
			// NOTE: Until this column addition is done, the index as received from COLUMN_NAME_OVERRIDE is off by the number of columns to add (COMPUTED_COLUMN_COUNT).
			
			int ingressCurrentMsgRate = Integer.parseInt(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Current Ingress Msg Rate") - COMPUTED_COLUMN_COUNT ));
			int egressCurrentMsgRate = Integer.parseInt(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Current Egress Msg Rate") - COMPUTED_COLUMN_COUNT));
			computedTableColumns.add(Integer.toString(ingressCurrentMsgRate + egressCurrentMsgRate) );
			
			int ingressAverageMsgRate = Integer.parseInt(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Average Ingress Msg Rate") - COMPUTED_COLUMN_COUNT));
			int egressAverageMsgRate = Integer.parseInt(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Average Ingress Msg Rate") - COMPUTED_COLUMN_COUNT));
			computedTableColumns.add(Integer.toString(ingressAverageMsgRate + egressAverageMsgRate) );
			
			int ingressCurrentByteRate = Integer.parseInt(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Current Ingress Byte Rate") - COMPUTED_COLUMN_COUNT));
			int egressCurrentByteRate = Integer.parseInt(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Current Egress Byte Rate") - COMPUTED_COLUMN_COUNT));
			computedTableColumns.add(Integer.toString(ingressCurrentByteRate + egressCurrentByteRate) );
			
			int ingressAverageByteRate = Integer.parseInt(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Average Ingress Byte Rate") - COMPUTED_COLUMN_COUNT));
			int egressAverageByteRate = Integer.parseInt(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Average Egress Byte Rate") - COMPUTED_COLUMN_COUNT));
			computedTableColumns.add(Integer.toString(ingressAverageByteRate + egressAverageByteRate) );

			tableRow.addAll(1, computedTableColumns);		// 4 new columns after the vpn name column
			tempTableContent.add(tableRow);
        }		
		
		receivedTableContent = tempTableContent;
		
		// Now calculate the headlines
		headlines = new LinkedHashMap<String, Object>();
		headlines.putAll(globalHeadlines);		
		headlines.put("Last Sample Time", SolGeneosAgent.onlyInstance.getCurrentTimeString());
		
		// Build up some summary headlines on the data. 
		
		long currentMsgRate = 
				receivedTableContent
				.stream()
				.map( thisTableRow -> ((ArrayList<String>)thisTableRow).get( COLUMN_NAME_OVERRIDE.indexOf("Current Msg Rate") ) )	
				.mapToLong(Long::parseLong)
				.sum() ;

		double currentByteRate = 
				receivedTableContent
				.stream()
				.map( thisTableRow -> ((ArrayList<String>)thisTableRow).get( COLUMN_NAME_OVERRIDE.indexOf("Current Byte Rate") ) )	
				.mapToDouble(Double::parseDouble)
				.sum() ;
		
		long averageMsgRate = 
				receivedTableContent
				.stream()
				.map( thisTableRow -> ((ArrayList<String>)thisTableRow).get( COLUMN_NAME_OVERRIDE.indexOf("Average Msg Rate") ) )	
				.mapToLong(Long::parseLong)
				.sum() ;

		double averageByteRate = 
				receivedTableContent
				.stream()
				.map( thisTableRow -> ((ArrayList<String>)thisTableRow).get( COLUMN_NAME_OVERRIDE.indexOf("Average Byte Rate") ) )	
				.mapToDouble(Double::parseDouble)
				.sum() ;
		
		long currentMsgRateIngress = 
				receivedTableContent
				.stream()
				.map( thisTableRow -> ((ArrayList<String>)thisTableRow).get( COLUMN_NAME_OVERRIDE.indexOf("Current Ingress Msg Rate") ) )	
				.mapToLong(Long::parseLong)
				.sum() ;

		double currentByteRateIngress = 
				receivedTableContent
				.stream()
				.map( thisTableRow -> ((ArrayList<String>)thisTableRow).get( COLUMN_NAME_OVERRIDE.indexOf("Current Ingress Byte Rate") ) )	
				.mapToDouble(Double::parseDouble)
				.sum() ;
		
		long averageMsgRateIngress = 
				receivedTableContent
				.stream()
				.map( thisTableRow -> ((ArrayList<String>)thisTableRow).get( COLUMN_NAME_OVERRIDE.indexOf("Average Ingress Msg Rate") ) )	
				.mapToLong(Long::parseLong)
				.sum() ;

		double averageByteRateIngress = 
				receivedTableContent
				.stream()
				.map( thisTableRow -> ((ArrayList<String>)thisTableRow).get( COLUMN_NAME_OVERRIDE.indexOf("Average Ingress Byte Rate") ) )	
				.mapToDouble(Double::parseDouble)
				.sum() ;
		
		long currentMsgRateEgress = 
				receivedTableContent
				.stream()
				.map( thisTableRow -> ((ArrayList<String>)thisTableRow).get( COLUMN_NAME_OVERRIDE.indexOf("Current Egress Msg Rate") ) )	
				.mapToLong(Long::parseLong)
				.sum() ;

		double currentByteRateEgress = 
				receivedTableContent
				.stream()
				.map( thisTableRow -> ((ArrayList<String>)thisTableRow).get( COLUMN_NAME_OVERRIDE.indexOf("Current Egress Byte Rate") ) )	
				.mapToDouble(Double::parseDouble)
				.sum() ;
		
		long averageMsgRateEgress = 
				receivedTableContent
				.stream()
				.map( thisTableRow -> ((ArrayList<String>)thisTableRow).get( COLUMN_NAME_OVERRIDE.indexOf("Average Egress Msg Rate") ) )	
				.mapToLong(Long::parseLong)
				.sum() ;

		double averageByteRateEgress = 
				receivedTableContent
				.stream()
				.map( thisTableRow -> ((ArrayList<String>)thisTableRow).get( COLUMN_NAME_OVERRIDE.indexOf("Average Egress Byte Rate") ) )	
				.mapToDouble(Double::parseDouble)
				.sum() ;
		
		
		// (Geneos does not support long values.)
		headlines.put("Current Msg Rate", Long.toString(currentMsgRate) );
		headlines.put("Current Msg Rate (Ingress)", Long.toString(currentMsgRateIngress) );
		headlines.put("Current Msg Rate (Egress)", Long.toString(currentMsgRateEgress) );
		headlines.put("Average Msg Rate", Long.toString(averageMsgRate) );
		headlines.put("Average Msg Rate (Ingress)", Long.toString(averageMsgRateIngress) );
		headlines.put("Average Msg Rate (Egress)", Long.toString(averageMsgRateEgress) );
		
		headlines.put("Current MByte Rate", String.format(FLOAT_FORMAT_STYLE, currentByteRate / BYTE_TO_MBYTE) );
		headlines.put("Current MByte Rate (Ingress)", String.format(FLOAT_FORMAT_STYLE, currentByteRateIngress / BYTE_TO_MBYTE) );
		headlines.put("Current MByte Rate (Egress)", String.format(FLOAT_FORMAT_STYLE, currentByteRateEgress / BYTE_TO_MBYTE) );
		headlines.put("Average MByte Rate", String.format(FLOAT_FORMAT_STYLE, averageByteRate / BYTE_TO_MBYTE) );
		headlines.put("Average MByte Rate (Ingress)", String.format(FLOAT_FORMAT_STYLE, averageByteRateIngress / BYTE_TO_MBYTE) );
		headlines.put("Average MByte Rate (Egress)", String.format(FLOAT_FORMAT_STYLE, averageByteRateEgress / BYTE_TO_MBYTE) );
		
		// Sort the list to show entries needing attention near to the top. Then also limit to the max row count if exceeding it...
		Vector<Object> tempTableContent = new Vector<Object>();
		
		tempTableContent.addAll(
				receivedTableContent
				.stream()
				.sorted(new RatesComparator())
				.limit(maxRows)				// Then cut the rows at max limit
				.collect(Collectors.toCollection(Vector<Object>::new))
				);
		
		receivedTableContent = tempTableContent;	
		
		for (int i = 0; i < this.TOP_TALKERS_LIMIT; i++) {
			headlines.put("Top Talker #" + (i + 1), ((ArrayList<String>)receivedTableContent.get(i)).get(this.COLUMN_NAME_OVERRIDE.indexOf("Message VPN"))   );
		}
		
		
		// Table content all complete now for publishing. Just add the column names too.
		receivedTableContent.add(0, this.COLUMN_NAME_OVERRIDE);	// No longer as received from parser in receivedColumnNames
		
		// Now ready to publish table to the view map
    	if (viewMap != null && viewMap.size() > 0) {
    		for (Iterator<String> viewIt = viewMap.keySet().iterator(); viewIt.hasNext();) 
    		{
    			
    			View view = viewMap.get(viewIt.next());
    			
    			if (view.isActive()) {
    					view.setHeadlines(headlines);
        				view.setTableContent(receivedTableContent);
    			}
    		}
    	}
    	
        return State.REPORTING_QUEUE;
	}

}

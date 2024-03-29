package com.solacesystems.solgeneos.custommonitors;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.LocalDateTime;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

import com.solacesystems.solgeneos.custommonitors.util.RatesHWM;
import com.solacesystems.solgeneos.custommonitors.util.MonitorConstants;
import com.solacesystems.solgeneos.custommonitors.util.SampleHttpSEMPResponse;
import com.solacesystems.solgeneos.custommonitors.util.SampleResponseHandler;
import com.solacesystems.solgeneos.custommonitors.util.SampleSEMPParser;
import com.solacesystems.solgeneos.custommonitors.util.VPNRecordSEMPParser;
import com.solacesystems.solgeneos.solgeneosagent.SolGeneosAgent;
import com.solacesystems.solgeneos.solgeneosagent.UserPropertiesConfig;
import com.solacesystems.solgeneos.solgeneosagent.monitor.BaseMonitor;
import com.solacesystems.solgeneos.solgeneosagent.monitor.View;

public class MessageRatesMonitor extends BaseMonitor implements MonitorConstants {
  
	// What version of the monitor?
	static final public String MONITOR_VERSION = "1.4.0";
	
	// The SEMP query to execute:
    static final public String SHOW_VPN_RATES_REQUEST = 
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
    				"average-ingress-rate-per-minute", "average-egress-rate-per-minute", "average-ingress-byte-rate-per-minute", "average-egress-byte-rate-per-minute",
    				"locally-configured");
    
    static final private  List<String> RESPONSE_ELEMENT_NAMES_IGNORE = Arrays.asList("authentication", "ingress-discards", "egress-discards", "certificate-revocation-check-stats");
     	
    // What should be the formatting style?
    static final private String FLOAT_FORMAT_STYLE = "%.2f";	// 2 decimal places
    		
    // What is the desired order of columns? (Will be set after first getting a response)
    private List<Integer> desiredColumnOrder;
    
    // Override the column names to more human friendly
    static final private List<String> COLUMN_NAME_OVERRIDE = 
    		Arrays.asList("Message VPN", 
    				"Current Msg Rate", "Average Msg Rate", "Current Byte Rate", "Average Byte Rate", 	// These will be computed (sum ingress and egress) and added as columns
    				
    				"Current Ingress Msg Rate", "Current Egress Msg Rate", "Current Ingress Byte Rate", "Current Egress Byte Rate", 
    				"Average Ingress Msg Rate", "Average Egress Msg Rate", "Average Ingress Byte Rate", "Average Egress Byte Rate",
    				
    				"Data Msgs Received", "Data Msgs Sent", "Data Msgs Received (Persistent)", "Data Msgs Sent (Persistent)", "Data Msgs Received (Non-Persistent)", "Data Msgs Sent (Non-Persistent)", "Data Msgs Received (Direct)", "Data Msgs Sent (Direct)",
    				"Data Bytes Received", "Data Bytes Sent", "Data Bytes Received (Persistent)", "Data Bytes Sent (Persistent)", "Data Bytes Received (Non-Persistent)", "Data Bytes Sent (Non-Persistent)", "Data Bytes Received (Direct)", "Data Bytes Sent (Direct)"
    				);
    
    static final private List<String> HWM_DATAVIEW_COLUMN_NAMES = 
    		Arrays.asList("High Water Mark Metric", "Timestamp", 
    				"Current Msg Rate", "Current Ingress Msg Rate", "Current Egress Msg Rate",
    				"Average Msg Rate", "Average Ingress Msg Rate", "Average Egress Msg Rate",
    				"Current MByte Rate", "Current Ingress MByte Rate", "Current Egress MByte Rate",
    				"Average MByte Rate", "Average Ingress MByte Rate", "Average Egress MByte Rate",
    				"Top Talker VPN #1", "Top Talker VPN #2", "Top Talker VPN #3"
    				);
    
    static final private int COMPUTED_COLUMN_COUNT = 4;		// Knowing the size can be used to more efficiently call the constructor for the ArrayList<String>...
    
    static final private int BYTE_TO_MBYTE = 1048576;
    
    static final private int TOP_TALKERS_LIMIT = 3;	// How many VPNs to list in the "top talkers" headlines?
    
    // When serializing state about the high water marks, where to put it?
    static final private String SZ_PATH_HWM_ALLTIME = "logs/messageRatesHWM_allTime.ser";
    static final private String SZ_PATH_HWM_DAILY = "logs/messageRatesHWM_daily.ser";
    static final private String SZ_PATH_HWM_WEEKLY = "logs/messageRatesHWM_weekly.ser";
    static final private String SZ_PATH_HWM_MONTHLY = "logs/messageRatesHWM_monthly.ser";
    static final private String SZ_PATH_HWM_YEARLY = "logs/messageRatesHWM_yearly.ser";
    
    private DefaultHttpClient httpClient;
    private ResponseHandler<SampleHttpSEMPResponse> responseHandler;
    private VPNRecordSEMPParser multiRecordParser;

    private Vector<Object> receivedTableContent;
    private Vector<Object> tempTableContent;		// Used in the various stages of manipulating the received table
    private Vector<Object> hwmTableContent;			// Used for the High Water Mark values display
    
    // These will either be initialised with the constructor later, or deserialized from file.
    private LinkedHashMap<String, RatesHWM> messageRatesHWM_allTime;
    private LinkedHashMap<String, RatesHWM> messageRatesHWM_daily; 
    private LinkedHashMap<String, RatesHWM> messageRatesHWM_weekly; 
    private LinkedHashMap<String, RatesHWM> messageRatesHWM_monthly; 
    private LinkedHashMap<String, RatesHWM> messageRatesHWM_yearly;
    
    private LinkedHashMap<String, Object> globalHeadlines = new LinkedHashMap<String, Object>();
    // Is the monitor creating a dataview per VPN or everything is in one view?
    // What is the maximum number of rows to limit the dataview to? Default 200 unless overridden.
    private int maxRows = 200;

    private LocalDateTime sampleTime;
    private Integer localConfigurationStatusColumnID;	// Save this to prevent lookup on each sample

    
    
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
		
		// (5) Create the High Water Mark objects for each interested metric to do it for...
		initHWMs();
		
	}
	
	/**
	 * This method is responsible for initializing the high water mark tracking objects
	 * If they have been previously created and serialized, load it back. 
	 * Otherwise create them for the first time.
	 */
	
	private void initHWMs () {

		// There is a HWM object for each metric, then different groups of them with different reset periods. (all-time, daily, weekly, yearly) 
		LinkedHashMap<String, RatesHWM.Type> tempHWMNames = new LinkedHashMap<String, RatesHWM.Type>();
		
		tempHWMNames.put("Current Msg Rate", RatesHWM.Type.CURRENT_MSG_RATE);
		tempHWMNames.put("Current Ingress Msg Rate", RatesHWM.Type.CURRENT_INGRESS_MSG_RATE);
		tempHWMNames.put("Current Egress Msg Rate", RatesHWM.Type.CURRENT_EGRESS_MSG_RATE);
		
		tempHWMNames.put("Average Msg Rate", RatesHWM.Type.AVERAGE_MSG_RATE);
		tempHWMNames.put("Average Ingress Msg Rate", RatesHWM.Type.AVERAGE_INGRESS_MSG_RATE);
		tempHWMNames.put("Average Egress Msg Rate", RatesHWM.Type.AVERAGE_EGRESS_MSG_RATE);
		
		tempHWMNames.put("Current MByte Rate", RatesHWM.Type.CURRENT_MBYTE_RATE);
		tempHWMNames.put("Current Ingress MByte Rate", RatesHWM.Type.CURRENT_INGRESS_MBYTE_RATE);
		tempHWMNames.put("Current Egress MByte Rate", RatesHWM.Type.CURRENT_EGRESS_MBYTE_RATE);
		
		tempHWMNames.put("Average MByte Rate", RatesHWM.Type.AVERAGE_MBYTE_RATE);
		tempHWMNames.put("Average Ingress MByte Rate", RatesHWM.Type.AVERAGE_INGRESS_MBYTE_RATE);
		tempHWMNames.put("Average Egress MByte Rate", RatesHWM.Type.AVERAGE_EGRESS_MBYTE_RATE);
		
		// Has there been some previous objects serialized and should be restored?
		
		// Create the all-time ones:
		messageRatesHWM_allTime = deserializeHWM(SZ_PATH_HWM_ALLTIME);
		if (messageRatesHWM_allTime == null) {
			// Not a successful deserialize operation. Either an exception, no previous file, etc.
			// Blunt response is to initialise it all afresh, whatever the cause of the 'false' being returned!
	        this.getLogger().info("Initialising new HWM objects for 'allTime'");
			messageRatesHWM_allTime = new LinkedHashMap<String, RatesHWM>();
			for (Map.Entry<String, RatesHWM.Type> entry : tempHWMNames.entrySet()){
				messageRatesHWM_allTime.put(entry.getKey(), new RatesHWM(entry.getValue()));
			}
		}

		// The remaining types reset based on some time period. This is controlled by supplying a "continuity value"
		// that when changes causes a reset of previously saved HWM values. This value can be a relevant part of the changing date/time
		this.sampleTime = LocalDateTime.now();
		int tempContinuityValue;
		
		// Create the daily ones. The continuity value is simply the current day of year
		messageRatesHWM_daily = deserializeHWM(SZ_PATH_HWM_DAILY);
		if (messageRatesHWM_daily == null) {
	        this.getLogger().info("Initialising new HWM objects for 'daily'");
			messageRatesHWM_daily = new LinkedHashMap<String, RatesHWM>();
			tempContinuityValue = sampleTime.getDayOfYear(); 
			for (Map.Entry<String, RatesHWM.Type> entry : tempHWMNames.entrySet()){
				messageRatesHWM_daily.put(entry.getKey() + " - Day", new RatesHWM(entry.getValue()));
			}
		}
		
		// Create the weekly ones. The continuity value is simply the week of year
		messageRatesHWM_weekly = deserializeHWM(SZ_PATH_HWM_WEEKLY);
		if (messageRatesHWM_weekly == null) {
	        this.getLogger().info("Initialising new HWM objects for 'weekly'");
			messageRatesHWM_weekly = new LinkedHashMap<String, RatesHWM>();
			tempContinuityValue = sampleTime.get(WeekFields.of(Locale.getDefault()).weekOfYear());
			for (Map.Entry<String, RatesHWM.Type> entry : tempHWMNames.entrySet()){
				messageRatesHWM_weekly.put(entry.getKey() + " - Week", new RatesHWM(entry.getValue(), tempContinuityValue));
			}
		}
		
		// Create the monthly ones. The continuity value is simply the month of year
		messageRatesHWM_monthly = deserializeHWM(SZ_PATH_HWM_MONTHLY);
		if (messageRatesHWM_monthly == null) {
	        this.getLogger().info("Initialising new HWM objects for 'monthly'");
			messageRatesHWM_monthly = new LinkedHashMap<String, RatesHWM>();
			tempContinuityValue = sampleTime.getMonthValue();
			for (Map.Entry<String, RatesHWM.Type> entry : tempHWMNames.entrySet()){
				messageRatesHWM_monthly.put(entry.getKey() + " - Month", new RatesHWM(entry.getValue(), tempContinuityValue));
			}
		}
		
		// Create the Yearly ones. The continuity value is simply the year
		messageRatesHWM_yearly = deserializeHWM(SZ_PATH_HWM_YEARLY);
		if (messageRatesHWM_yearly == null) {
	        this.getLogger().info("Initialising new HWM objects for 'yearly'");
			messageRatesHWM_yearly = new LinkedHashMap<String, RatesHWM>();
			tempContinuityValue = sampleTime.getYear();
			for (Map.Entry<String, RatesHWM.Type> entry : tempHWMNames.entrySet()){
				messageRatesHWM_yearly.put(entry.getKey() + " - Year", new RatesHWM(entry.getValue(), tempContinuityValue));
			}
		}
		
	}
        
	private void serializeHWM (LinkedHashMap<String, RatesHWM> messageRateHWM, String szFilePath) {

		try {
			ObjectOutputStream out;
			FileOutputStream fileOut;
			
			fileOut = new FileOutputStream(szFilePath);
			out = new ObjectOutputStream(fileOut);
	        out.writeObject(messageRateHWM);
	        out.close();
	        fileOut.close();	
		} 
		catch (IOException i) {
	         this.getLogger().error("IO Exception during serialization of HWM at: " + szFilePath + ". " + i.getMessage() + i.toString());
	    }       
	}
	
	@SuppressWarnings("unchecked")
	private LinkedHashMap<String, RatesHWM> deserializeHWM (String szFilePath) {

		this.getLogger().info("Started deserialization of HWM object from: " + szFilePath);
		LinkedHashMap<String, RatesHWM> messageRateHWM = null;
		
		try {
			ObjectInputStream in;
			FileInputStream fileIn;
			
			fileIn = new FileInputStream(szFilePath);
			in = new ObjectInputStream(fileIn);
			messageRateHWM = (LinkedHashMap<String, RatesHWM>) in.readObject();
						
	        in.close();
	        fileIn.close();	
	        this.getLogger().info("Finished deserialization of HWM object from: " + szFilePath);
		} 
		catch (FileNotFoundException f) {
	         this.getLogger().info("File not found during deserialization of HWM at: " + szFilePath + ". " + f.toString());
	    }  
		catch (IOException | ClassNotFoundException i) {
			this.getLogger().error("IO Exception during deserialization of HWM at: " + szFilePath + ". " + i.toString());
	    }
		return messageRateHWM;  

	}
	
	private void setDesiredColumnOrder (List<String> currentColumnNames) {
	    
		desiredColumnOrder = Arrays.asList(
				currentColumnNames.indexOf("name"), 
				currentColumnNames.indexOf("current-ingress-rate-per-second"), currentColumnNames.indexOf("current-egress-rate-per-second"),
				currentColumnNames.indexOf("average-ingress-rate-per-minute"), currentColumnNames.indexOf("average-egress-rate-per-minute"),
				currentColumnNames.indexOf("current-ingress-byte-rate-per-second"), currentColumnNames.indexOf("current-egress-byte-rate-per-second"),
				currentColumnNames.indexOf("average-ingress-byte-rate-per-minute"), currentColumnNames.indexOf("average-egress-byte-rate-per-minute"),
				
				currentColumnNames.indexOf("client-data-messages-received"), currentColumnNames.indexOf("client-data-messages-sent"), currentColumnNames.indexOf("client-persistent-messages-received"), currentColumnNames.indexOf("client-persistent-messages-sent"),
				currentColumnNames.indexOf("client-non-persistent-messages-received"), currentColumnNames.indexOf("client-non-persistent-messages-sent"), currentColumnNames.indexOf("client-direct-messages-received"), currentColumnNames.indexOf("client-direct-messages-sent"),
				
				currentColumnNames.indexOf("client-data-bytes-received"), currentColumnNames.indexOf("client-data-bytes-sent"), currentColumnNames.indexOf("client-persistent-bytes-received"), currentColumnNames.indexOf("client-persistent-bytes-sent"),
				currentColumnNames.indexOf("client-non-persistent-bytes-received"), currentColumnNames.indexOf("client-non-persistent-bytes-sent"), currentColumnNames.indexOf("client-direct-bytes-received"), currentColumnNames.indexOf("client-direct-bytes-sent")
		);
		
		// Did any expected field above not get found in the SEMP response? Report error if so...
		if (desiredColumnOrder.contains(-1)) {
			getLogger().error(
					"Not all expected fields were present in the SEMP response when setting the column order. " 
					+ "Available columns: " + currentColumnNames.toString()
					+ "Final ordering as set: " + desiredColumnOrder.toString()
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

	@SuppressWarnings({ "unchecked", "static-access" })
	@Override
	protected State onCollect() throws Exception {

		TreeMap<String, View> viewMap = getViewMap();
		
		LinkedHashMap<String, Object> headlines;
		LinkedHashMap<String, Object> headlinesHWM;
		
		submitSEMPQuery(SHOW_VPN_RATES_REQUEST, multiRecordParser);
		List<String> currentColumnNames = multiRecordParser.getColumnNames();

		// Will take what is received as the table contents and then do further processing and adjusting... 
		receivedTableContent = multiRecordParser.getTableContent();
		
		// Has the desired column order been determined yet? (Done on the first time responses and their columns came back.)
		if (this.desiredColumnOrder == null) {
			this.setDesiredColumnOrder(currentColumnNames);
		}
		
		// First remove VPN entries that are 'locally-configured=false', they are not full VPN entries, but discovered from the Multi-Node Routing Network
		// Will iterate the initial 'dirty' VPN data and filter rows to create the 'clean' VPN data. 
		
		ArrayList<String> tableRowVpn ;
		String localConfigurationStatus;
		tempTableContent = new Vector<Object>();
		
		if (localConfigurationStatusColumnID == null) {
			localConfigurationStatusColumnID = currentColumnNames.indexOf("locally-configured");
		}
		
		for (int index = 0; index < receivedTableContent.size(); index++) {
			
			tableRowVpn = (ArrayList<String>) receivedTableContent.get(index);
			localConfigurationStatus = tableRowVpn.get(localConfigurationStatusColumnID);
			
			if (localConfigurationStatus.equalsIgnoreCase("true")) {
				tempTableContent.add(tableRowVpn);
			}
		}  
		
		receivedTableContent = tempTableContent;
		
		// Now reorder data into the column order we want versus what came from the parser
		
		tempTableContent = new Vector<Object>();
		// Iterate to each row in the table contents
		for (int index = 0; index < receivedTableContent.size(); index++) {
			
			// Build a new tableRow by adding to it in the right order 
			ArrayList<String> tableRow = new ArrayList<String>();
			
			for (Integer columnNumber : this.desiredColumnOrder){
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
			
			long ingressCurrentMsgRate = Long.parseLong(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Current Ingress Msg Rate") - COMPUTED_COLUMN_COUNT ));
			long egressCurrentMsgRate = Long.parseLong(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Current Egress Msg Rate") - COMPUTED_COLUMN_COUNT));
			computedTableColumns.add(Long.toString(ingressCurrentMsgRate + egressCurrentMsgRate) );
			
			long ingressAverageMsgRate = Long.parseLong(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Average Ingress Msg Rate") - COMPUTED_COLUMN_COUNT));
			long egressAverageMsgRate = Long.parseLong(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Average Egress Msg Rate") - COMPUTED_COLUMN_COUNT));
			computedTableColumns.add(Long.toString(ingressAverageMsgRate + egressAverageMsgRate) );
			
			long ingressCurrentByteRate = Long.parseLong(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Current Ingress Byte Rate") - COMPUTED_COLUMN_COUNT));
			long egressCurrentByteRate = Long.parseLong(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Current Egress Byte Rate") - COMPUTED_COLUMN_COUNT));
			computedTableColumns.add(Long.toString(ingressCurrentByteRate + egressCurrentByteRate) );
			
			long ingressAverageByteRate = Long.parseLong(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Average Ingress Byte Rate") - COMPUTED_COLUMN_COUNT));
			long egressAverageByteRate = Long.parseLong(tableRow.get( COLUMN_NAME_OVERRIDE.indexOf("Average Egress Byte Rate") - COMPUTED_COLUMN_COUNT));
			computedTableColumns.add(Long.toString(ingressAverageByteRate + egressAverageByteRate) );

			tableRow.addAll(1, computedTableColumns);		// 4 new columns after the vpn name column
			tempTableContent.add(tableRow);
        }		
		
		receivedTableContent = tempTableContent;
		
		// Now calculate the headlines
		headlines = new LinkedHashMap<String, Object>();
		headlines.putAll(globalHeadlines);	
		
		String lastSampleTime = SolGeneosAgent.onlyInstance.getCurrentTimeString();
		headlines.put("Last Sample Time", lastSampleTime);
		
		headlinesHWM = new LinkedHashMap<String, Object>();
		headlinesHWM.putAll(globalHeadlines);		
		headlinesHWM.put("Last Sample Time", SolGeneosAgent.onlyInstance.getCurrentTimeString());
		
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
				.sorted(new RatesComparator())	// Currently highest average byte rate is at the top...
				.limit(maxRows)				// Then cut the rows at max limit
				.collect(Collectors.toCollection(Vector<Object>::new))
				);
		
		receivedTableContent = tempTableContent;	
		
		for (int i = 0; i < this.TOP_TALKERS_LIMIT; i++) {
			headlines.put("Top Talker #" + (i + 1), ((ArrayList<String>)receivedTableContent.get(i)).get(this.COLUMN_NAME_OVERRIDE.indexOf("Message VPN"))   );
		}
		
		// Top 3 Talkers just for the HWM dataview:
		String topTalkerVPN1 = (receivedTableContent.size() > 0) ? ((ArrayList<String>)receivedTableContent.get(0)).get(this.COLUMN_NAME_OVERRIDE.indexOf("Message VPN")) : ""; 
		String topTalkerVPN2 = (receivedTableContent.size() > 1) ? ((ArrayList<String>)receivedTableContent.get(1)).get(this.COLUMN_NAME_OVERRIDE.indexOf("Message VPN")) : ""; 
		String topTalkerVPN3 = (receivedTableContent.size() > 2) ? ((ArrayList<String>)receivedTableContent.get(2)).get(this.COLUMN_NAME_OVERRIDE.indexOf("Message VPN")) : ""; 

		// Main table content all complete now for publishing. Just add the column names too.
		receivedTableContent.add(0, this.COLUMN_NAME_OVERRIDE);	// No longer as received from parser in receivedColumnNames
		
		// Setup HWM dataview table content too, column names first
		hwmTableContent = new Vector<Object>();
		hwmTableContent.add(0, this.HWM_DATAVIEW_COLUMN_NAMES);
		
		
		// Submit the current rates to the HWM objects to update if higher than what is previously known:		
		// All Time ones
		for (Map.Entry<String, RatesHWM> entry : messageRatesHWM_allTime.entrySet()){
			
			entry.getValue().updateHWMs(lastSampleTime, currentMsgRate, currentMsgRateIngress, currentMsgRateEgress, 
					averageMsgRate, averageMsgRateIngress, averageMsgRateEgress, 
					currentByteRate / BYTE_TO_MBYTE, currentByteRateIngress / BYTE_TO_MBYTE, currentByteRateEgress / BYTE_TO_MBYTE,
					averageByteRate / BYTE_TO_MBYTE, averageByteRateIngress / BYTE_TO_MBYTE, averageByteRateEgress / BYTE_TO_MBYTE,
					topTalkerVPN1, topTalkerVPN2, topTalkerVPN3);			
						
			ArrayList<String> tempRow = entry.getValue().getHWMRow();
			tempRow.add(0, entry.getKey());
			hwmTableContent.add(tempRow);
		}
		// Get the current time
		this.sampleTime = LocalDateTime.now();
		
		// Daily reset ones
		for (Map.Entry<String, RatesHWM> entry : messageRatesHWM_daily.entrySet()){
			
			// Does it need to be reset?
			entry.getValue().resetHWMs(sampleTime.getDayOfYear());
			// Now submit current values to assess:
			entry.getValue().updateHWMs(lastSampleTime, currentMsgRate, currentMsgRateIngress, currentMsgRateEgress, 
					averageMsgRate, averageMsgRateIngress, averageMsgRateEgress, 
					currentByteRate / BYTE_TO_MBYTE, currentByteRateIngress / BYTE_TO_MBYTE, currentByteRateEgress / BYTE_TO_MBYTE,
					averageByteRate / BYTE_TO_MBYTE, averageByteRateIngress / BYTE_TO_MBYTE, averageByteRateEgress / BYTE_TO_MBYTE,
					topTalkerVPN1, topTalkerVPN2, topTalkerVPN3);			
						
			ArrayList<String> tempRow = entry.getValue().getHWMRow();
			tempRow.add(0, entry.getKey());
			hwmTableContent.add(tempRow);
		}
		// Weekly reset ones
		for (Map.Entry<String, RatesHWM> entry : messageRatesHWM_weekly.entrySet()){
			
			// Does it need to be reset?
			entry.getValue().resetHWMs(sampleTime.get(WeekFields.of(Locale.getDefault()).weekOfYear()) );
			// Now submit current values to assess:
			entry.getValue().updateHWMs(lastSampleTime, currentMsgRate, currentMsgRateIngress, currentMsgRateEgress, 
					averageMsgRate, averageMsgRateIngress, averageMsgRateEgress, 
					currentByteRate / BYTE_TO_MBYTE, currentByteRateIngress / BYTE_TO_MBYTE, currentByteRateEgress / BYTE_TO_MBYTE,
					averageByteRate / BYTE_TO_MBYTE, averageByteRateIngress / BYTE_TO_MBYTE, averageByteRateEgress / BYTE_TO_MBYTE,
					topTalkerVPN1, topTalkerVPN2, topTalkerVPN3);			
						
			ArrayList<String> tempRow = entry.getValue().getHWMRow();
			tempRow.add(0, entry.getKey());
			hwmTableContent.add(tempRow);
		}				
		// Monthly reset ones
		for (Map.Entry<String, RatesHWM> entry : messageRatesHWM_monthly.entrySet()){
			
			// Does it need to be reset?
			entry.getValue().resetHWMs(sampleTime.getMonthValue());
			// Now submit current values to assess:
			entry.getValue().updateHWMs(lastSampleTime, currentMsgRate, currentMsgRateIngress, currentMsgRateEgress, 
					averageMsgRate, averageMsgRateIngress, averageMsgRateEgress, 
					currentByteRate / BYTE_TO_MBYTE, currentByteRateIngress / BYTE_TO_MBYTE, currentByteRateEgress / BYTE_TO_MBYTE,
					averageByteRate / BYTE_TO_MBYTE, averageByteRateIngress / BYTE_TO_MBYTE, averageByteRateEgress / BYTE_TO_MBYTE,
					topTalkerVPN1, topTalkerVPN2, topTalkerVPN3);			
						
			ArrayList<String> tempRow = entry.getValue().getHWMRow();
			tempRow.add(0, entry.getKey());
			hwmTableContent.add(tempRow);
		}	
		// Yearly reset ones
		for (Map.Entry<String, RatesHWM> entry : messageRatesHWM_yearly.entrySet()){
			
			// Does it need to be reset?
			entry.getValue().resetHWMs(sampleTime.getYear());
			// Now submit current values to assess:
			entry.getValue().updateHWMs(lastSampleTime, currentMsgRate, currentMsgRateIngress, currentMsgRateEgress, 
					averageMsgRate, averageMsgRateIngress, averageMsgRateEgress, 
					currentByteRate / BYTE_TO_MBYTE, currentByteRateIngress / BYTE_TO_MBYTE, currentByteRateEgress / BYTE_TO_MBYTE,
					averageByteRate / BYTE_TO_MBYTE, averageByteRateIngress / BYTE_TO_MBYTE, averageByteRateEgress / BYTE_TO_MBYTE,
					topTalkerVPN1, topTalkerVPN2, topTalkerVPN3);			
						
			ArrayList<String> tempRow = entry.getValue().getHWMRow();
			tempRow.add(0, entry.getKey());
			hwmTableContent.add(tempRow);
		}	
		
		// Save the new updated HWMs to file
		serializeHWM(messageRatesHWM_allTime, SZ_PATH_HWM_ALLTIME);
		serializeHWM(messageRatesHWM_daily, SZ_PATH_HWM_DAILY);
		serializeHWM(messageRatesHWM_weekly, SZ_PATH_HWM_WEEKLY);
		serializeHWM(messageRatesHWM_monthly, SZ_PATH_HWM_MONTHLY);
		serializeHWM(messageRatesHWM_yearly, SZ_PATH_HWM_YEARLY);
		
		// Now ready to publish tables to the view map
    	if (viewMap != null && viewMap.size() > 0) {
    		for (Iterator<String> viewIt = viewMap.keySet().iterator(); viewIt.hasNext();) 
    		{
    			View view = viewMap.get(viewIt.next());	
    			if (view.isActive()) {
    				switch (view.getName()) {
						case "msgRates":
							view.setHeadlines(headlines);
	        				view.setTableContent(receivedTableContent);
							break;
						case "msgRatesHWM":
							view.setHeadlines(headlinesHWM);
							view.setTableContent(hwmTableContent);
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

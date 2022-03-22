package com.solacesystems.solgeneos.custommonitors;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;
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
import com.solacesystems.solgeneos.custommonitors.util.MultiFieldSEMPParser;
import com.solacesystems.solgeneos.custommonitors.util.SampleHttpSEMPResponse;
import com.solacesystems.solgeneos.custommonitors.util.SampleResponseHandler;
import com.solacesystems.solgeneos.custommonitors.util.SampleSEMPParser;
import com.solacesystems.solgeneos.solgeneosagent.SolGeneosAgent;
import com.solacesystems.solgeneos.solgeneosagent.UserPropertiesConfig;
import com.solacesystems.solgeneos.solgeneosagent.monitor.BaseMonitor;
import com.solacesystems.solgeneos.solgeneosagent.monitor.View;

public class SoftwareSystemHealthMonitor extends BaseMonitor implements MonitorConstants {
  
	// What version of the monitor?
	static final public String MONITOR_VERSION = "1.0";
	
	// The SEMP queries to execute:
    static final public String SHOW_SYSTEM_HEALTH_REQUEST = 
        "<rpc>" + 
        "	<show>" +
        "		<system>" +
        "			<health></health>" +
        "		</system>" +
        "	</show>" +
        "</rpc>";

    // The elements of interest/exclusion within the SEMP response processing:
    static final private  List<String> SYSTEM_HEALTH_RESPONSE_ELEMENTS = 
    		Arrays.asList("last-clear-time",
    				"disk-latency-minimum-value", "disk-latency-maximum-value", "disk-latency-average-value", "disk-latency-current-value", "disk-latency-high-threshold", "disk-latency-suppressed-events",
    				"compute-latency-minimum-value", "compute-latency-maximum-value", "compute-latency-average-value", "compute-latency-current-value", "compute-latency-high-threshold", "compute-latency-suppressed-events",
    				"network-latency-minimum-value", "network-latency-maximum-value", "network-latency-average-value", "network-latency-current-value", "network-latency-high-threshold", "network-latency-suppressed-events",
    				"mate-link-latency-minimum-value", "mate-link-latency-maximum-value", "mate-link-latency-average-value", "mate-link-latency-current-value", "mate-link-latency-high-threshold", "mate-link-latency-suppressed-events"
    				);
          

    static final private List<String> DATAVIEW_COLUMN_NAMES = 
    		Arrays.asList("Item", "Current Value", "Minimum Value", "Average Value", "Maximum Value", "High Threshold", "Suppressed Events");

    static final private HashMap<String, String> DATAVIEW_ROW_NAMES_MAP;
    static { 
    	DATAVIEW_ROW_NAMES_MAP = new HashMap<>();
    	DATAVIEW_ROW_NAMES_MAP.put("Disk Latency (usec)", "disk-latency");
    	DATAVIEW_ROW_NAMES_MAP.put("Compute Latency (usec)", "compute-latency");
    	DATAVIEW_ROW_NAMES_MAP.put("Network Latency (usec)", "network-latency");
    	DATAVIEW_ROW_NAMES_MAP.put("Mate-Link Latency (usec)", "mate-link-latency");
    }
    
    private DefaultHttpClient httpClient;
    private ResponseHandler<SampleHttpSEMPResponse> responseHandler;
    private MultiFieldSEMPParser multiFieldSEMPParser;

    private Vector<Object> tableContent;
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
		
        HashMap<String, String> interestedFields = new HashMap<String, String>();
		
		this.SYSTEM_HEALTH_RESPONSE_ELEMENTS.forEach(
				field -> interestedFields.put(field, ""));
		
		multiFieldSEMPParser = new MultiFieldSEMPParser(interestedFields);
		submitSEMPQuery(this.SHOW_SYSTEM_HEALTH_REQUEST, multiFieldSEMPParser);

		tableContent = new Vector<Object>();
				
		headlines.putAll(globalHeadlines);
		
		String lastSampleTime = SolGeneosAgent.onlyInstance.getCurrentTimeString();
		headlines.put("Last Sample Time", lastSampleTime);
		

	    DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.getDefault());
	    String lastResetTime = dateFormat.format(new Date(Long.parseLong(interestedFields.get("last-clear-time")) * 1000L));
			
		headlines.put("Last Values Cleared Time", lastResetTime);
		
		// Build the table
		ArrayList<String> tableRow;
		for (String rowName : this.DATAVIEW_ROW_NAMES_MAP.keySet()) {
			tableRow = new ArrayList<String>();
			tableRow.add(rowName);
			tableRow.add(interestedFields.get(this.DATAVIEW_ROW_NAMES_MAP.get(rowName) + "-current-value"));
			tableRow.add(interestedFields.get(this.DATAVIEW_ROW_NAMES_MAP.get(rowName) + "-minimum-value"));
			tableRow.add(interestedFields.get(this.DATAVIEW_ROW_NAMES_MAP.get(rowName) + "-average-value"));
			tableRow.add(interestedFields.get(this.DATAVIEW_ROW_NAMES_MAP.get(rowName) + "-maximum-value"));
			tableRow.add(interestedFields.get(this.DATAVIEW_ROW_NAMES_MAP.get(rowName) + "-high-threshold"));
			tableRow.add(interestedFields.get(this.DATAVIEW_ROW_NAMES_MAP.get(rowName) + "-suppressed-events"));
			
			tableContent.add(tableRow);
		}
		
		// Add the column names
		tableContent.add(0, this.DATAVIEW_COLUMN_NAMES);			
		
		// Now ready to publish tables to the view map
    	if (viewMap != null && viewMap.size() > 0) {
    		for (Iterator<String> viewIt = viewMap.keySet().iterator(); viewIt.hasNext();) 
    		{
    			View view = viewMap.get(viewIt.next());	
    			if (view.isActive()) {
    				view.setHeadlines(headlines);
    				view.setTableContent(tableContent);
    			}
    		}
    	}
        return State.REPORTING_QUEUE;
	}
}

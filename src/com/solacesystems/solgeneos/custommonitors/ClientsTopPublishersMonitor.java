package com.solacesystems.solgeneos.custommonitors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.solacesystems.solgeneos.solgeneosagent.SolGeneosAgent;
import com.solacesystems.solgeneos.solgeneosagent.UserPropertiesConfig;
import com.solacesystems.solgeneos.solgeneosagent.monitor.BaseMonitor;
import com.solacesystems.solgeneos.solgeneosagent.monitor.View;

public class ClientsTopPublishersMonitor extends BaseMonitor implements MonitorConstants {
  
	// What version of the monitor?
	static final public String MONITOR_VERSION = "1.0";
	
	// The SEMP queries to execute:
    static final public String SHOW_CLIENTS_REQUEST = 
        "<rpc>" + 
        "	<show>" +
        "		<client>" +
        "			<name>*</name>" +
        "			<sorted-stats/>" +
        "			<stats-to-show>current-ingress-message-rate-per-second,average-ingress-message-rate-per-minute,current-ingress-byte-rate-per-second,average-ingress-byte-rate-per-minute,total-client-bytes-received</stats-to-show>" +
        "			<sort-by/>" +
        "			<stats-to-sort-by>average-ingress-byte-rate-per-minute</stats-to-sort-by>" +
        "			<count/>" +
        "			<num-elements>10</num-elements>" +
        "		</client>" +
        "	</show>" +
        "</rpc>";
        
    // The elements of interest/exclusion within the SEMP response processing:
    static final private String CLIENT_DETAILS_RESPONSE_ELEMENT_NAME = "row";

    // What should be the formatting style?
    static final private String FLOAT_FORMAT_STYLE = "%.3f";	// 3 decimal places. Wanted to add thousandth separator but Geneos fails to recognise it as numbers for rule purposes!
    
    // Override the column names to more human friendly
    static final private List<String> DATAVIEW_COLUMN_NAMES = 
    		Arrays.asList("Client ID", "Username", "Message VPN", 
    				"Current Ingress Msg Rate", "Average Ingress Msg Rate",
    				"Current Ingress MByte Rate", "Average Ingress MByte Rate",
    				"Total MBytes Received");
    
    static final private List<String> COLUMNS_IN_MBYTES = Arrays.asList("Current Ingress MByte Rate", "Total MBytes Received", "Average Ingress MByte Rate");
    
    
    static final private int BYTE_TO_MBYTE = 1048576;
    
    private DefaultHttpClient httpClient;
    private ResponseHandler<SampleHttpSEMPResponse> responseHandler;
    private MultiRecordSEMPParser multiRecordParser;

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
        
        // create SEMP parser with the name of the element that contains the records
		multiRecordParser = new MultiRecordSEMPParser(CLIENT_DETAILS_RESPONSE_ELEMENT_NAME);
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

	@SuppressWarnings({ "static-access", "unchecked" })
	@Override
	protected State onCollect() throws Exception {

		TreeMap<String, View> viewMap = getViewMap();
		LinkedHashMap<String, Object> headlines = new LinkedHashMap<String, Object>();
		
		submitSEMPQuery(this.SHOW_CLIENTS_REQUEST, multiRecordParser);
		
		headlines.putAll(globalHeadlines);
		
		String lastSampleTime = SolGeneosAgent.onlyInstance.getCurrentTimeString();
		headlines.put("Last Sample Time", lastSampleTime);
		
		tableContent = multiRecordParser.getTableContent();
		
		// Remove the internal '#client' client and convert bytes to MBytes
		Iterator<Object> itr = tableContent.iterator();	
		
		while (itr.hasNext()) {		
			ArrayList<String> tempTableRow = (ArrayList<String>) itr.next();
			
			String clientID = tempTableRow.get(DATAVIEW_COLUMN_NAMES.indexOf("Client ID"));
			
			if (clientID.startsWith("#client"))  {
				itr.remove();
			}
			else
			{
				for (String columnName : COLUMNS_IN_MBYTES) {
					double bytes = Double.parseDouble(tempTableRow.get(DATAVIEW_COLUMN_NAMES.indexOf(columnName)));
					tempTableRow.set(DATAVIEW_COLUMN_NAMES.indexOf(columnName), String.format(FLOAT_FORMAT_STYLE, (bytes / BYTE_TO_MBYTE)));
				}
			}
		}  

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

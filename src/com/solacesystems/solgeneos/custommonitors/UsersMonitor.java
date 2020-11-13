package com.solacesystems.solgeneos.custommonitors;

import java.util.ArrayList;
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

import com.solacesystems.solgeneos.custommonitors.util.MultiRecordSEMPParser;
import com.solacesystems.solgeneos.custommonitors.util.MonitorConstants;
import com.solacesystems.solgeneos.custommonitors.util.SampleHttpSEMPResponse;
import com.solacesystems.solgeneos.custommonitors.util.SampleResponseHandler;
import com.solacesystems.solgeneos.solgeneosagent.SolGeneosAgent;
import com.solacesystems.solgeneos.solgeneosagent.UserPropertiesConfig;
import com.solacesystems.solgeneos.solgeneosagent.monitor.BaseMonitor;
import com.solacesystems.solgeneos.solgeneosagent.monitor.View;

public class UsersMonitor extends BaseMonitor implements MonitorConstants {
  
	// What version of the monitor?
	static final public String MONITOR_VERSION = "0.6.7.2";
	
	// The SEMP query to execute:
    static final public String SHOW_USERS_REQUEST = 
            "<rpc>\n" +
            "    <show>\n" +
            "        <username>\n" +
            "            <username-pattern>*</username-pattern>\n" +
            "        </username>\n" +
            "    </show>\n" +
            "</rpc>\n";
    
    // The element of interest within the SEMP response:
    static final public String RESPONSE_ELEMENT_NAME = "user";
    
    private DefaultHttpClient httpClient;
    private ResponseHandler<SampleHttpSEMPResponse> responseHandler;
    private MultiRecordSEMPParser multiRecordParser;

    private List<String> receivedColumnNames;
    private Vector<Object> receivedTableContent;
    private Vector<Object> filteredTableContent;
    
    private LinkedHashMap<String, Object> globalHeadlines = new LinkedHashMap<String, Object>();
    
    private boolean multiview = false;
    
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
		
		// Add this monitor's version as a headline
		globalHeadlines.put("Version", MONITOR_VERSION);

		// (2) Are there properties specific to this monitor in its config file?
		// This monitor can be operated in two modes: (1) single view, or (2) multi view.
		// In single view, all the users are listed in the same dataview. In multi view mode, two data views are used, CLI users and File-Transfer users separately reported.
		
		UserPropertiesConfig monitorPropsConfig = SolGeneosAgent.onlyInstance.
				getUserPropertiesConfig(MONITOR_PROPERTIES_FILE_NAME_PREFIX + this.getName() +MONITOR_PROPERTIES_FILE_NAME_SUFFIX);
		// If the file exists and its not empty, add each property as a headline:
		if (monitorPropsConfig != null && monitorPropsConfig.getProperties() != null) {
			multiview = Boolean.parseBoolean(monitorPropsConfig.getProperties().get("multiview").toString());
		}
		
		
		// (3) Retrieve SEMP over HTTP properties from global properties
		Properties props = SolGeneosAgent.onlyInstance.getGlobalProperties();
        String host = props.getProperty(MGMT_IP_ADDRESS_PROPERTY_NAME);
        int port = 80;
        try {
        	port = Integer.parseInt(props.getProperty(MGMT_PORT_PROPERTY_NAME));
        } catch (NumberFormatException e) {
    		if (getLogger().isInfoEnabled()) {
    			getLogger().info("Invalid port number, use default 80");
    		}
        }
        String username = props.getProperty(MGMT_USERNAME_PROPERTY_NAME);
        String password = SolGeneosAgent.onlyInstance.getEncryptedProperty(MGMT_ENCRYPTED_PASSWORD_PROPERTY_NAME,MGMT_PASSWORD_PROPERTY_NAME);
        
		TreeMap<String, View> viewMap = getViewMap();
		
		// Operating in single view or multiview?
		if (multiview) {
			// Remove the view with name v0 as not going to be used.
			viewMap.remove("v0");
			// Add the user type specific views
	        this.addView("fileTransfer");
	        this.addView("cli");
	        // Set them up and activate
			viewMap.get("cli").setViewName("Users - CLI");
			viewMap.get("cli").setActive(true);
			viewMap.get("fileTransfer").setViewName("Users - File Transfer");
			viewMap.get("fileTransfer").setActive(true);	
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
		multiRecordParser = new MultiRecordSEMPParser(RESPONSE_ELEMENT_NAME);
		

		
		
	}
        
	/**
	 * This method is responsible to collect data required for a view.
	 * @return The next monitor state which should be State.REPORTING_QUEUE.
	 */

	@SuppressWarnings({ "unchecked", "static-access" })
	@Override
	protected State onCollect() throws Exception {

		LinkedHashMap<String, Object> headlines = new LinkedHashMap<String, Object>();
		headlines.putAll(globalHeadlines);
		
		headlines.put("Last Sample Time", SolGeneosAgent.onlyInstance.getCurrentTimeString());
		headlines.put("Monitor Class Name", this.getName());
		headlines.put("Sampling Interval (secs)", this.getSamplingRate());
		
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
		this.receivedColumnNames = multiRecordParser.getColumnNames();
		this.receivedTableContent = multiRecordParser.getTableContent();
		
		TreeMap<String, View> viewMap = getViewMap();		
    	if (viewMap != null && viewMap.size() > 0) {
    		for (Iterator<String> viewIt = viewMap.keySet().iterator(); viewIt.hasNext();) {
    			
    			int cliCount =  
						receivedTableContent
							.stream()
							.filter( tableRow -> "cli".equalsIgnoreCase
									( ((ArrayList<String>)tableRow).get(1).toString()
									)
							)
							.collect(Collectors.toList())
							.size() ;
				
				int ftpCount =  
						receivedTableContent
							.stream()
							.filter( tableRow -> "file-transfer".equalsIgnoreCase
									( ((ArrayList<String>)tableRow).get(1).toString()
									)
							)
							.collect(Collectors.toList())
							.size() ;
				
				headlines.put("CLI Users Count", cliCount);
				headlines.put("File Transfer Users Count", ftpCount);
				headlines.put("Total Users Count", receivedTableContent.size());
				
    			
    			View view = viewMap.get(viewIt.next());
    			if (view.isActive()) {
    				view.setHeadlines(headlines);
    	
    				// Extra work only if in multiview mode...
    				if (multiview) {
    					// Depending on the view name, publish a subset of the users into it
	    				this.filteredTableContent = new Vector<Object>();
	    				
	    				switch (view.getName()) {
	    					case "cli":
	    						this.filteredTableContent.addAll( 
		    							receivedTableContent
		    							.stream()
		    							.filter( tableRow -> "cli".equalsIgnoreCase
		    									( ((ArrayList<String>)tableRow).get(1).toString()
		    									)
		    							)
		    							.collect(Collectors.toCollection(Vector<Object>::new))    							
	    							);
	    						break;
	    					case "fileTransfer":
	    						this.filteredTableContent.addAll( 
	        							receivedTableContent
	        							.stream()
	        							.filter( tableRow -> "file-transfer".equalsIgnoreCase
	        									( ((ArrayList<String>)tableRow).get(1).toString()
	        									)
	        							)
	        							.collect(Collectors.toCollection(Vector<Object>::new))    							
	    							);
	    						break;
							default:
								// Do nothing
	    				}
	    				
	    				// Add the column names as the first item
	    				this.filteredTableContent.add(0, this.receivedColumnNames);
	    				// Publish the data for this view
	    				view.setTableContent(this.filteredTableContent);
    				}
    				else {
    					// Simpler, no filtering work needed... Just add the column names as the first item
    					receivedTableContent.add(0, receivedColumnNames);
    					view.setTableContent(receivedTableContent);
    				}
    			}
    		}
    	}
    	
        return State.REPORTING_QUEUE;
	}

}

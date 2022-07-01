package com.solacesystems.solgeneos.custommonitors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.Vector;

import com.solacesystems.solgeneos.custommonitors.messaging.SolaceMessagingTester;
import com.solacesystems.solgeneos.custommonitors.util.MonitorConstants;
import com.solacesystems.solgeneos.solgeneosagent.SolGeneosAgent;
import com.solacesystems.solgeneos.solgeneosagent.UserPropertiesConfig;
import com.solacesystems.solgeneos.solgeneosagent.monitor.BaseMonitor;
import com.solacesystems.solgeneos.solgeneosagent.monitor.View;

public class MessagingTestMonitor extends BaseMonitor implements MonitorConstants {
  
	// What version of the monitor?
	static final public String MONITOR_VERSION = "1.0.1";
	
	static final private List<String> DATAVIEW_COLUMN_NAMES = 
    		Arrays.asList("Destination", "Publish Status", "Subscribe Status", "RTT Latency (ms)");
    
	private boolean monitorConfigReady = false;
	private String monitorStatusMessage;
	
	private String testVpn;
	private String smfUri;
	private String testUser;
	private String testUserPassword;
	private String brokerName;
	private String testTopic;
	private String testQueue;
	
	private SolaceMessagingTester messagingTester;
	
    private Vector<Object> tableContent;
    private ArrayList<String> tableRow;
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
			getLogger().info("Initializing monitor");
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
	
		// (3) Retrieve connection details from monitor properties file
		readConfiguration();
		
		// (4) Initialise the messaging tester object
		if (monitorConfigReady) {		// After configuration read step at least
			messagingTester = new SolaceMessagingTester(testVpn, smfUri, testUser, testUserPassword, brokerName);
			messagingTester.setTestTopic(testTopic);
			if (testQueue != null) {
				messagingTester.setTestQueue(testQueue);
			}
		}

	}
	
	private void readConfiguration () {
		
		// Reset if partial read from a previous call:
		testVpn = "";
		smfUri = "";
		testUser = "";
		testUserPassword = "";
		brokerName = "";
		testVpn = "";
		testTopic = "";
		
		UserPropertiesConfig monitorPropsConfig = SolGeneosAgent.onlyInstance.
				getUserPropertiesConfig(MONITOR_PROPERTIES_FILE_NAME_PREFIX + this.getName() +MONITOR_PROPERTIES_FILE_NAME_SUFFIX);

		if (monitorPropsConfig != null && monitorPropsConfig.getProperties() != null) {
			
			try {
				testVpn = monitorPropsConfig.getProperties().get("testVpn").toString();
				smfUri = monitorPropsConfig.getProperties().get("smfUri").toString();
				testUser = monitorPropsConfig.getProperties().get("testUser").toString();
				testUserPassword = monitorPropsConfig.getProperties().get("testUserPassword").toString();
				brokerName = monitorPropsConfig.getProperties().get("brokerName").toString();
				testVpn = monitorPropsConfig.getProperties().get("testVpn").toString();
				testTopic = monitorPropsConfig.getProperties().get("testTopic").toString();
				
				// This one is optional:
				if (monitorPropsConfig.getProperties().get("testQueue") != null) {
					testQueue = monitorPropsConfig.getProperties().get("testQueue").toString();
				}
				else {
					getLogger().info("No testQueue property supplied, will not test queue messaging.");
				}
				
				this.monitorStatusMessage = "OK";
				this.monitorConfigReady = true;

			} catch (NullPointerException e) {
				this.monitorStatusMessage = "ERROR - Missing properties in " + MONITOR_PROPERTIES_FILE_NAME_PREFIX + this.getName() +MONITOR_PROPERTIES_FILE_NAME_SUFFIX;
				this.monitorConfigReady = false;
				getLogger().error(this.monitorStatusMessage);
			}
			
		}
		else
		{
			this.monitorStatusMessage = "ERROR - Properties file to initialise connection details not found at: " + MONITOR_PROPERTIES_FILE_NAME_PREFIX + this.getName() +MONITOR_PROPERTIES_FILE_NAME_SUFFIX;
			this.monitorConfigReady = false;
			getLogger().error(this.monitorStatusMessage);
		}
		
		// Show config as headlines too:
		globalHeadlines.put("Test VPN", testVpn);
		globalHeadlines.put("SMF URI", smfUri);
		globalHeadlines.put("Client User", testUser);
		globalHeadlines.put("Broker Name", brokerName);
		globalHeadlines.put("Monitor Status", monitorStatusMessage);
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
				
		headlines.putAll(globalHeadlines);
		
		String lastSampleTime = SolGeneosAgent.onlyInstance.getCurrentTimeString();
		headlines.put("Last Sample Time", lastSampleTime);
		
		tableContent = new Vector<Object>();
		
		if (this.monitorConfigReady) {
			
			messagingTester.connect();
			if (messagingTester.isConnected()) {
				
				globalHeadlines.put("Monitor Status", "OK");
				
				// Do Topic Tests
				tableRow = new ArrayList<String>();
				tableRow.add("topic:" + testTopic);
				
				messagingTester.topicSubscribe();
				messagingTester.topicPublish();
				
				if (messagingTester.isTopicPublished()) {
					tableRow.add("PASS");
				}
				else {
					tableRow.add("FAIL: " + messagingTester.getTopicPublishedStatus());
				}
				
				if (messagingTester.isTopicSubscribed()) {
					tableRow.add("PASS");
					tableRow.add(messagingTester.getTopicLatency().toString());
				}
				else {
					tableRow.add("FAIL: " + messagingTester.getTopicSubscribedStatus());
					tableRow.add("");
				}
				tableContent.add(tableRow);
				
				// Do Queue Tests only if queue was supplied
				if (testQueue != null) {
					tableRow = new ArrayList<String>();
					tableRow.add("queue:" + testQueue);
					
					messagingTester.queueSubscribe();
					messagingTester.queuePublish();
					
					if (messagingTester.isQueuePublished()) {
						tableRow.add("PASS");
					}
					else {
						tableRow.add("FAIL: " + messagingTester.getQueuePublishedStatus());
					}
					
					if (messagingTester.isQueueSubscribed()) {
						tableRow.add("PASS");
						tableRow.add(messagingTester.getQueueLatency().toString());
					}
					else {
						tableRow.add("FAIL: " + messagingTester.getQueueSubscribedStatus());
						tableRow.add("");
					}
					tableContent.add(tableRow);
				}

				
			}
			else
			{
				globalHeadlines.put("Monitor Status", "ERROR: " + messagingTester.getConnectedStatus());
			}
			
		}
		
		tableContent.add(0, this.DATAVIEW_COLUMN_NAMES);
		
		// Done with the tester for this sampling run
		messagingTester.disconnect();
		
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

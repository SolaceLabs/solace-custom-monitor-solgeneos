package com.solacesystems.solgeneos.custommonitors.messaging;

import java.text.DecimalFormat;
import java.util.ArrayList;

import com.solacesystems.jcsmp.AccessDeniedException;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.ConsumerFlowProperties;
import com.solacesystems.jcsmp.DeliveryMode;
import com.solacesystems.jcsmp.FlowReceiver;
import com.solacesystems.jcsmp.InvalidPropertiesException;
import com.solacesystems.jcsmp.JCSMPErrorResponseException;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishEventHandler;
import com.solacesystems.jcsmp.OperationNotSupportedException;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageListener;
import com.solacesystems.jcsmp.XMLMessageProducer;

public class SolaceMessagingTester {
	
	private String testVpn;
	private String smfUri;
	private String testUser;
	private String testUserPassword;
	private String brokerName;
	private String testTopic;
	private String testQueue;
	
	private boolean connected = false;
	private String connectedStatus = "";

	private boolean topicPublished = false;
	private String topicPublishedStatus = "";
	
	private boolean topicSubscribed = false;
	private String topicSubscribedStatus = "";
	
	private boolean queuePublished = false;
	private String queuePublishedStatus = "";
	
	private boolean queueSubscribed = false;
	private String queueSubscribedStatus = "";
	
	private JCSMPSession session;
	private Topic topic;
	private Queue queue;
	private XMLMessageProducer topicProducer;
	private XMLMessageProducer queueProducer;
	private XMLMessageConsumer topicConsumer;
	private FlowReceiver       queueConsumer;
	
	private ArrayList<String> queueSubscribedMessages;
	private ArrayList<String> topicSubscribedMessages;

	private DecimalFormat latencyFormatMs = new DecimalFormat("#.###");
	
	private static final int TEST_MESSAGE_PUBLISH_BATCH_COUNT = 3;
	private static final int TEST_MESSAGE_PUBLISH_DELAY_MS = 1000;
	private static final int CONNECTION_INIT_WAIT_DELAY_MS = 1000;
	
	public void connect() {
		
		// Ignore repeat connect
		if (session == null) {
			
			connected = false;
			connectedStatus = "";
			
			// Create a JCSMP Session
	        final JCSMPProperties properties = new JCSMPProperties();
	        properties.setProperty(JCSMPProperties.HOST,     this.smfUri);
	        properties.setProperty(JCSMPProperties.VPN_NAME, this.testVpn);
	        properties.setProperty(JCSMPProperties.USERNAME, this.testUser);  
	        properties.setProperty(JCSMPProperties.PASSWORD, this.testUserPassword);
	        // If connecting over TLS, *not* validating broker's certificate, thereby avoiding trust store setup and filesystem referencing needs for simplicity 
	        properties.setProperty(JCSMPProperties.SSL_VALIDATE_CERTIFICATE, false);	
	        
			try {
				session = JCSMPFactory.onlyInstance().createSession(properties);
				session.connect();
				
				try {
					Thread.sleep(CONNECTION_INIT_WAIT_DELAY_MS);
					connected = true;
					connectedStatus = "Connected session to " + this.smfUri;
				} catch (InterruptedException e) {
					// Do nothing
				}
			} catch (InvalidPropertiesException e1) {
				connected = false;
				connectedStatus = "Invalid Properties on Session Create. " + e1.getMessage();
			} catch (JCSMPException e1) {
				connected = false;
				connectedStatus = "Exception on Session Create. " + e1.getMessage();
			}
			
			// Ready to receive and save messages, reset on each new connect
			queueSubscribedMessages = new ArrayList<String>();
			topicSubscribedMessages = new ArrayList<String>();
			
		}
	}
	
	public void disconnect() {
		
		if (topicProducer != null) {
			topicProducer.close();
			topicProducer = null;
		}
		if (topicConsumer != null) {
			topicConsumer.close();
			topicConsumer = null;
		}
		if (queueProducer != null) {
			queueProducer.close();
			queueProducer = null;
		}
		if (queueConsumer != null) {
			queueConsumer.close();
			queueConsumer = null;
		}
		if (session != null) {
			session.closeSession();
			session = null;
		}
		connected = false;
		connectedStatus = "Disconnected from " + this.smfUri;
	}
	
	public void topicPublish() {
		
		// Topic can be changed between calls
		topic = JCSMPFactory.onlyInstance().createTopic(this.testTopic);

		// Could be a repeat call
		if (topicProducer == null) {
			try {
		        /** Anonymous inner-class for handling publishing events */
				topicProducer = session.getMessageProducer(new JCSMPStreamingPublishEventHandler() {
				    @Override
				    public void responseReceived(String messageID) {
				        // Nothing expected for direct-mode publishing
				    }
				    @Override
				    public void handleError(String messageID, JCSMPException e, long timestamp) {
				    	topicPublished = false;
						topicPublishedStatus = "Received exception on publish: " + e.getMessage();
				    }
				});
			} catch (JCSMPException e) {
				topicPublished = false;
				topicPublishedStatus = "Received exception on producer create: " + e.getMessage();
			}
		}

		// Actual publishing
        try {
            TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);            
            try {
            	for (int i = 0; i < TEST_MESSAGE_PUBLISH_BATCH_COUNT ; i++) {
            		Thread.sleep(TEST_MESSAGE_PUBLISH_DELAY_MS);
                    msg.setText(this.getTestMessage());
                    topicProducer.send(msg,topic);
            	}
			} catch (InterruptedException e) {
				// Do nothing
			}
	        topicPublished = true;
	        topicPublishedStatus = "Successfully published to " + this.testTopic;
	        	        
		} catch (JCSMPErrorResponseException e) {
			topicPublished = false;
			topicPublishedStatus = "Received exception on producer send: " + e.getMessage();
		} catch (JCSMPException e) {
			topicPublished = false;
			topicPublishedStatus = "Received exception on producer send: " + e.getMessage();
		}
	}
	
	public void topicSubscribe() {
		
		topicSubscribed = false;	// Until a received message flips this
		topicSubscribedStatus = "Pending message receive";
		
		// Topic can be changed between calls
		topic = JCSMPFactory.onlyInstance().createTopic(this.testTopic);

		// Could be a repeat call
		if (topicConsumer == null) {
			try {
				
				/** Anonymous inner-class for MessageListener */
		        topicConsumer = session.getMessageConsumer(new XMLMessageListener() {
		            @Override
		            public void onReceive(BytesXMLMessage msg) {
		                if (msg instanceof TextMessage) {
		                	saveTopicSubscribedMessage( ((TextMessage)msg).getText()  );
		                    topicSubscribed = true;
		                    topicSubscribedStatus = "Successfully received message on " + testTopic;
		                } else {
		                	// Not the expected message but at least it proves message receive is working
		                	topicSubscribed = true;
		                    topicSubscribedStatus = "Successfully received message on " + testTopic;
		                }
		            }

		            @Override
		            public void onException(JCSMPException e) {
		            	topicSubscribed = false;
	                    topicSubscribedStatus = "Received exception on subscribe: " + e.getMessage();
		            }
		        });
		        
			} catch (JCSMPException e) {
				topicSubscribed = false;
				topicSubscribedStatus = "Received exception on consumer create: " + e.getMessage();
			}
		}
		
		// Actual subscribing to a topic
        try {
			session.addSubscription(topic);
			topicConsumer.start();
		} catch (JCSMPErrorResponseException e) {
			topicSubscribed = false;
			topicSubscribedStatus = "Received exception on subscription add: " + e.getMessage();
		} catch (JCSMPException e) {
			topicSubscribed = false;
			topicSubscribedStatus = "Received exception on subscription add: " + e.getMessage();
		}
	}
	
	public void queuePublish() {

		// Could be a repeat call
		if (queueProducer == null) {
			try {		
				
		        // Create the queue object locally. 
				// Will not use session.provision() to create on broker as expecting admin-created durable queue to exist already!
		        queue = JCSMPFactory.onlyInstance().createQueue(this.testQueue);
				
		        /** Anonymous inner-class for handling publishing events */
				queueProducer = session.getMessageProducer(new JCSMPStreamingPublishEventHandler() {
				    @Override
				    public void responseReceived(String messageID) {
				    	queuePublished = true;
						queuePublishedStatus = "Successfully published to " + testQueue;
				    }
				    @Override
				    public void handleError(String messageID, JCSMPException e, long timestamp) {
				    	queuePublished = false;
				    	queuePublishedStatus = "Received exception on publish: " + e.getMessage();
				    }
				});
			} catch (JCSMPErrorResponseException e) {
				queuePublished = false;
				queuePublishedStatus = "Received exception on producer create: " + e.getMessage();
			} catch (OperationNotSupportedException e) {
				queuePublished = false;
				queuePublishedStatus = "Received exception on producer create: " + e.getMessage();
			} catch (JCSMPException e) {
				queuePublished = false;
				queuePublishedStatus = "Received exception on producer create: " + e.getMessage();
			} 
		}

		// Actual publishing
        try {
            TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
            msg.setDeliveryMode(DeliveryMode.PERSISTENT);
            try {
            	for (int i = 0; i < TEST_MESSAGE_PUBLISH_BATCH_COUNT ; i++) {
            		Thread.sleep(TEST_MESSAGE_PUBLISH_DELAY_MS);
                    msg.setText(this.getTestMessage());
                    queueProducer.send(msg,queue);
            	}
			} catch (InterruptedException e) {
				// Do nothing
			}
		} catch (JCSMPErrorResponseException e) {
			queueSubscribed = false;
			queueSubscribedStatus = "Received exception on consumer create: " + e.getMessage(); 
		} catch (JCSMPException e) {
			queuePublished = false;
			queuePublishedStatus = "Received exception on producer send: " + e.getMessage();
		} catch (NullPointerException e) {
			queuePublished = false;
			queuePublishedStatus = "Failed to create producer object and is null.";
		}
	}
	
	public void queueSubscribe() {
		
		queueSubscribed = false;	// Until a received message flips this
		queueSubscribedStatus = "Pending message receive";
		
		// Ignore repeat calls if already subscribed
		if (queueConsumer == null) {
			try {

		        // Create the queue object locally. 
				// Will not use session.provision() to create on broker as expecting admin-created durable queue to exist already!
		        queue = JCSMPFactory.onlyInstance().createQueue(testQueue);
		        // Create a Flow be able to bind to and consume messages from the Queue.
		        ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
		        flowProps.setEndpoint(queue);
		        flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);
		        
		        /** Anonymous inner-class for MessageListener */
		        queueConsumer = session.createFlow(new XMLMessageListener() {
		            @Override
		            public void onReceive(BytesXMLMessage msg) {
		                if (msg instanceof TextMessage) {
		                	// Save the message
		                	saveQueueSubscribedMessage( ((TextMessage)msg).getText()  );
		                	queueSubscribed = true;
		                    queueSubscribedStatus = "Successfully received message on " + testQueue;
		                } else {
		                	// Not the expected message but at least it proves message receive is working
		                	queueSubscribed = true;
		                    queueSubscribedStatus = "Successfully received message on " + testQueue;
		                }
		                // When the ack mode is set to SUPPORTED_MESSAGE_ACK_CLIENT,
		                // guaranteed delivery messages are acknowledged after processing
		                msg.ackMessage();
		            }

		            @Override
		            public void onException(JCSMPException e) {
		            	queueSubscribed = false;
						queueSubscribedStatus = "Received exception from flow consumer: " + e.getMessage();
		            }
	            }, flowProps);		        
		        
		        queueConsumer.start();
		        
			} catch (JCSMPErrorResponseException e) {
				queueSubscribed = false;
				queueSubscribedStatus = "Received exception on queue flow create: " + e.getMessage();
			} catch (AccessDeniedException e) {
				queueSubscribed = false;
				queueSubscribedStatus = "Received exception on queue flow create: " + e.getMessage();
			} catch (JCSMPException e) {
				queueSubscribed = false;
				queueSubscribedStatus = "Received exception on queue flow create: " + e.getMessage();
			}
		}
	}
	
	private String getTestMessage() {
		return this.brokerName + "," + System.nanoTime();
	}

	private void saveTopicSubscribedMessage(String msgText) {
		// Is it a valid reflect from this sender and not something stray picked up?
		if (msgText.startsWith(this.brokerName + ","))
		{
			this.topicSubscribedMessages.add(msgText + "," + System.nanoTime());
		}
	}
	
	private void saveQueueSubscribedMessage(String msgText) {
		// Is it a valid reflect from this sender and not something stray picked up?
		if (msgText.startsWith(this.brokerName + ","))
		{
			this.queueSubscribedMessages.add(msgText + "," + System.nanoTime());
		}
	}
	
	public SolaceMessagingTester(String testVpn, String smfUri, String testUser, String testUserPassword,
			String brokerName, String testTopic, String testQueue) {
		super();
		this.testVpn = testVpn;
		this.smfUri = smfUri;
		this.testUser = testUser;
		this.testUserPassword = testUserPassword;
		this.brokerName = brokerName;
		this.testTopic = testTopic;
		this.testQueue = testQueue;
	}
	
	public SolaceMessagingTester(String testVpn, String smfUri, String testUser, String testUserPassword,
			String brokerName) {
		super();
		this.testVpn = testVpn;
		this.smfUri = smfUri;
		this.testUser = testUser;
		this.testUserPassword = testUserPassword;
		this.brokerName = brokerName;
	}
	
	public String getTestVpn() {
		return testVpn;
	}
	public void setTestVpn(String testVpn) {
		this.testVpn = testVpn;
	}
	public String getSmfUri() {
		return smfUri;
	}
	public void setSmfUri(String smfUri) {
		this.smfUri = smfUri;
	}
	public String getTestUser() {
		return testUser;
	}
	public void setTestUser(String testUser) {
		this.testUser = testUser;
	}
	public String getTestUserPassword() {
		return testUserPassword;
	}
	public void setTestUserPassword(String testUserPassword) {
		this.testUserPassword = testUserPassword;
	}
	public String getBrokerName() {
		return brokerName;
	}
	public void setBrokerName(String brokerName) {
		this.brokerName = brokerName;
	}
	public String getTestTopic() {
		return testTopic;
	}
	public void setTestTopic(String testTopic) {
		this.testTopic = testTopic;
	}
	public String getTestQueue() {
		return testQueue;
	}
	public void setTestQueue(String testQueue) {
		this.testQueue = testQueue;
	}
	public boolean isConnected() {
		return connected;
	}
	public String getConnectedStatus() {
		return connectedStatus;
	}
	public boolean isTopicPublished() {
		return topicPublished;
	}
	public String getTopicPublishedStatus() {
		return topicPublishedStatus;
	}
	public boolean isTopicSubscribed() {
		return topicSubscribed;
	}
	public String getTopicSubscribedStatus() {
		return topicSubscribedStatus;
	}
	public boolean isQueuePublished() {
		return queuePublished;
	}
	public String getQueuePublishedStatus() {
		return queuePublishedStatus;
	}
	public boolean isQueueSubscribed() {
		return queueSubscribed;
	}
	public String getQueueSubscribedStatus() {
		return queueSubscribedStatus;
	}
	
	private double calculateLatencyMs(ArrayList<String> messagesList) {
		
		long minimumLatencyNs = -1;
		for (String message : messagesList) {
			
			// Substract send time in nanos from the arrival time in nanos
			long thisLatencyNs = Long.parseLong(message.split(",")[2]) - Long.parseLong(message.split(",")[1]);
			// If this is the first latency calculation, save it, otherwise it stays the same as before
			minimumLatencyNs = (minimumLatencyNs == -1) ? thisLatencyNs : minimumLatencyNs;
			// If this latency calculation is smaller than previously stored, replace it
			minimumLatencyNs = (minimumLatencyNs > thisLatencyNs) ? thisLatencyNs : minimumLatencyNs;
		}
		
		// Convert from nanos to milliseconds with 3 d.p precision
		if (minimumLatencyNs != -1) {
			double latencyMs = (double)minimumLatencyNs / 1000000L;
			return Double.parseDouble(latencyFormatMs.format(latencyMs));
		}
		else
		{
			return minimumLatencyNs;	// Let the caller figure out what to do with it
		}
	}
	
	public Double getTopicLatency() {		
		return calculateLatencyMs(this.topicSubscribedMessages);
	}

	public Double getQueueLatency() {
		return calculateLatencyMs(this.queueSubscribedMessages);
	}
}

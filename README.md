# SolGeneos Custom Monitors

## Overview

The [Solace Geneos Agent (a.k.a SolGeneos)](https://docs.solace.com/SolGeneos-Agent/SolGeneos-Overview.htm) allows for the monitoring of Solace [Event Brokers](https://solace.com/what-is-an-event-broker/) in the [Geneos](https://www.itrsgroup.com/products/geneos) monitoring tool by [ITRS](https://www.itrsgroup.com/).  
While coming with a large number of [dataviews](https://docs.solace.com/SolGeneos-Agent/Default-SolGeneos-Agent-Data-Views.htm) to monitor various product aspects such as pending messages on queues, health of the underlying hardware, and connectivity status to other event-brokers, SolGeneos is an extendable framework that allows customers to [build additional monitors](https://docs.solace.com/SolGeneos-Agent/Monitor-Dev-and-Deployment.htm) for dataviews containing metrics specific to their own requirements.

This repository contains further examples of custom monitors that can support monitor development efforts by customers.

:warning: Important Notice :warning: | 
------------ | 
Customer developed monitors and example code, such as those in this project, are not supported by Solace as part of the SolGeneos product support. Check out [CONTRIBUTING.md](CONTRIBUTING.md) to raise issues/bugs, submit fixes, request features, submit features, submit ideas, or to ask questions.  Responses will be 'best effort' from [contributors](https://github.com/SolaceLabs/solace-custom-monitor-solgeneos/graphs/contributors). | 

*:point_down: [Click to jump ahead to usage instructions](#how-to-use-this-repository)*   

## Table of contents
* [Custom Monitors Index](#Custom-Monitors-Index)
* [How to use this repository](#How-to-use-this-repository)
* [Contributing](#contributing)
* [Authors](#authors)
* [License](#license)
* [Resources](#resources)


## Custom Monitors Index

No.  | Name | Function |
---- | ---- | -------- |
1 | [Users Monitor](#1-users-monitor) | List currently configured CLI and FTP users. Serves as a development sample. |
2 | [QueuesEx Monitor](#2-queuesex-monitor) | Extended version of Queues monitor with several enhancements |
3 | [TopicEndpointsEx Monitor](#3-topicendpointsex-monitor) | Extended version of TopicEndpoints monitor with several enhancements |
4 | [MessageRates Monitor](#4-messagerates-monitor) | New monitor to display message and byte rate activity as well as identify top-talkers and track high water marks (HWMs) |
5 | [MessageVPNLimits Monitor](#5-messagevpnlimits-monitor) | New monitor to clearly display 'current usage vs. max limit' of various capacity-related resources at a message-vpn level |
6 | [BrokerLimits Monitor](#6-brokerlimits-monitor) | New monitor to clearly display 'current allocated vs. broker hard limit' of various capacity related resources |
7 | [ClientProfileLimits Monitor](#7-clientprofilelimits-monitor) | New monitor to clearly display 'current usage vs. max limit' of various capacity-related resources at a client profile level |
8 | [ClientsTopPublishers Monitor](#8-clientstoppublishers-monitor) | New monitor to show the top 10 connected clients by publisher activity |
9 | [ClientsTopSubscribers Monitor](#9-clientstopsubscribers-monitor) | New monitor to show the top 10 connected clients by publisher activity |
10 | [ClientsSlowSubscribers Monitor](#10-clientsslowsubscribers-monitor) | New monitor to show all clients the broker has determined to be slow subscribers |
11 | [MessagingTester Monitor](#11-messagingtester-monitor) | New monitor for 'synthetic monitoring', periodically performing message send and receive to validate the infrastructure more holistically |
12 | [SoftwareSystemHealth Monitor](#12-softwaresystemhealth-monitor) | New monitor specifically for Software Broker deployments. Providing health metrics for the environment the broker is deployed in |
13 | [MessageVPNBridgeRates Monitor](#13-messagevpnbridgerates-monitor) | New monitor to show the configured message VPN bridges, the message and byte rate activity of them, as well as health of the underlying TCP connections. |
14 | [ConfigSyncStatus Monitor](#14-configsyncstatus-monitor) | New monitor to show the status of entries in the Config-Sync database. |

### (1) Users Monitor

This monitor builds significantly on the [sample](https://docs.solace.com/SolGeneos-Agent/Creating-UsersMon.htm) of the same name provided provided with the SolGeneos product. 
Enhancements over the original version include:
* The addition of more headline fields on the dataview containing information such as the total counts of each user type. 
  (Headlines are useful places to provide summary or aggregated information of the main dataview contents. These can then be referenced by dashboard elements too.) 
* The use of Java8 Stream API to further process the SEMP response to apply filters and create subsets of the response. 
* The use of a property file to control the behaviour of the monitor code and how data is presented
* The ability to dynamically generate multiple dataviews from the same monitor, especially if the overall data needs to be segmented for different sets of Geneos users.
* The introduction of a new "Multi Record SEMP Parser" that can be used in a generic fashion across different SEMP responses to extract the records of interest.

The aim of this particular monitor is to continue acting as a **development sample**, it's operational usefulness in a true monitoring enviromment is limited.

### (2) QueuesEx Monitor

This monitor is an extended version for monitoring queues, compared to the `Queues` monitor available in the product install.  Enhancements over the original version include:

* The addition of headline fields on the dataview containing information such total pending messages, total spool usage, count of queues with bound consumers, etc.
  (Headlines are useful places to provide summary or aggregated information of the main dataview contents. These can then be referenced by dashboard elements too.) 
* Configurable row limit for the dataview, maximum of 200 if unspecified.
  (For large deployments, the original queues monitor would display _all queues_, that can run into several thousand rows. This would add load to the Geneos gateway and had the potential to impact monitoring by the netprobe being force suspended.)
* Advanced sorting on the dataviews to show queues with the highest utilisation against its quota at the top. (A more meaningful sort than just looking at pending messages for example.) This coupled with the row-limit feature ensures the monitor acts to show queues that need attention, versus acting as an inventory view of all queues configured.
* The ability to show all queues in a per-VPN dataview, switched on through configuration only. This supports efforts by middleware teams using the [Geneos Gateway Sharing](https://docs.itrsgroup.com/docs/geneos/5.2.0/Gateway_Reference_Guide/geneos_gatewaysharing_ug.html) feature to 'export' specific dataviews to application support team gateways. i.e. Faciliating a single installation of SolGeneos being able to push application specific monitoring to their respective support teams.
* The addition of more queue details to facilitate advanced monitoring rules. e.g. The Oldest and Newest Message ID columns can have rules to alert cases where a queue is bound, yet consumption is not moving forward due to any poison message scenarios. 
* The introduction of a new "Targeted Multi Record SEMP Parser" that can be used in a generic fashion across different SEMP queries to extract records of interest, with filters for the fields required to be returnd and excludes for sections to skip parsing. 

Sample of the new headlines and columns available:
![QueuesEx Combined Dataview Sample](https://github.com/SolaceLabs/solace-custom-monitor-solgeneos/blob/master/images/QueuesEx%20-%20Dataview%20Sample.png?raw=true)

Sample of being able to create per-VPN views of the queues:

![QueuesEx Per-VPN Dataview Sample](https://github.com/SolaceLabs/solace-custom-monitor-solgeneos/blob/master/images/QueuesEx%20-%20Per-VPN%20Dataviews.jpg?raw=true)

### (3) TopicEndpointsEx Monitor

This monitor is an extended version for monitoring topic endpoints, compared to the `TopicEndpoints` monitor available in the product install.  Enhancements over the original version include all those listed for the `QueuesEx` monitor mentioned above. This monitor is near identical in output to the extended queues monitor and thus provides the same new advantages.

Sample of the new headlines and columns available:
![TopicEndpointsEx Combined Dataview Sample](https://github.com/SolaceLabs/solace-custom-monitor-solgeneos/blob/master/images/TopicEndpointsEx%20-%20Dataview%20Sample.png?raw=true)


### (4) MessageRates Monitor

This monitor provides new functionality to look at messaging activity at a per-VPN level as well as the aggregate across the whole broker.  It can be used in the context of capacity management to monitor the message or byte rate and alert if getting close to the known limits of the broker. Furthermore, it tracks the high water mark (HWM) values for the broker-wide aggregates in a dedicated dataview called `MessageRatesHWM`.

Features implemented in the monitor include:
* Computed metrics for the message and data rate for a VPN to provide a simple indicator of activity and load on the shared-tenancy environment. (Computed by summing the ingress and egress metrics as provided by SEMP.)
* Identification of the "Top Talkers" VPN names that are making up the messaging activity at the time of sample. (e.g. Those headline fields can populate dashboard elements to make it clear where any increase in activity or deviances from normal trend are originating from.) 
* Computed metrics for a broker-wide view of messaging activity. (Computed by summing the individual VPN level stats to reach the full broker-wide metric.) 
* Tracking of the previous peak value (i.e. high water mark) for each of the rate metrics to aid in capacity management by providing historical context to the current rate activity.
* Similar to previous monitors in this repository, the implementation of a max-row count to limit the amount of data sent to the Geneos gateway and any load issues this may cause. A sorting is done to ensure the VPNs with the highest average byte rate is near the top before the dataview is truncated. (The broker-wide computed values continue to act on the full dataset of all VPNs configured on the broker.)

Sample of the new headlines and columns available in the primary `MessageRates` dataview:
![MessageRates Dataview Sample](https://github.com/SolaceLabs/solace-custom-monitor-solgeneos/blob/master/images/MessageRates%20-%20Dataview%20Sample.png?raw=true)

Sample of the high water mark (HWM) values that are tracked in the additional `MessageRatesHWM` dataview. Note that the full context of other rate metrics and top talkers at the time of the HWM rate is also retained for reference and context.
![MessageRatesHWM Dataview Sample](https://github.com/SolaceLabs/solace-custom-monitor-solgeneos/blob/master/images/MessageRatesHWM%20-%20Dataview%20Sample.png?raw=true)

Additionally note how the high water mark (HWM) values can be searched for the specific metric of interest. Then for that metric, each HWM type (e.g. daily high, current month's high, all-time high) can be easily reviewed:  
![MessageRatesHWM Dataview Sample - Search](https://github.com/SolaceLabs/solace-custom-monitor-solgeneos/blob/master/images/MessageRatesHWM%20-%20Dataview%20Sample%20-%20Search%20Bar.png?raw=true)

### (5) MessageVPNLimits Monitor

This monitor provides new functionality to look at resource related limits at a per-VPN level. As part of the multi-tenancy approach to using the brokers, various limits are applied at each message-VPN when it is created, each message-VPN in effect being a virtual slice of the overall broker and its resources.
This monitor displays those resources (e.g. number of connections, number of subscriptions) in a 'current usage vs configured limit' manner. This makes rules easier to apply where percentage utilisation can be worked out to highlight any VPNs that are operating close to their configured soft limits.

Features implemented in the monitor include:
* A computed "utilisation score" is done for each message-VPN to highlight those needing attention versus those that don't. This score is used to help prioritise the message-VPNs that should be caught in the dataview if the number of message-VPNs on the broker is higher than the `max rows` limit configured for the dataview. 

Sample of the new dataview showing various resource usage and their max limit:
![MessageVPNLimits Dataview Sample](https://github.com/SolaceLabs/solace-custom-monitor-solgeneos/blob/master/images/MessageVPNLimits%20-%20Dataview%20Sample.png?raw=true)

### (6) BrokerLimits Monitor

This monitor provides new functionality to look at resource related limits at a broker level. Adding to the functionality in the earlier `MessageVPNLimits` dataview (#5 above), this dataview summarises those same resources in a 'total allocated vs broker limit' manner. As part of effective capacity management of a shared-tenancy broker, it is important to monitor when resources being allocated at a VPN level (i.e. the 'soft limits') are cumulatively nearing or perhaps even exceeding the broker level hard limits.
Additionally, if an organisation is also adopting a policy of 'overcommitting' or 'overbooking' the broker resources to the underlying message-VPNs that are created, this monitor will make it easy to monitor the actual usage of a resource versus the known hard limit.

Sample of the new dataview showing various resources for their current usage, total allocation to the configured message-VPNs, and the actual hard limit:  
![BrokerLimits Dataview Sample](https://github.com/SolaceLabs/solace-custom-monitor-solgeneos/blob/master/images/BrokerLimits%20-%20Dataview%20Sample.png?raw=true)

### (7) ClientProfileLimits Monitor

This monitor provides new functionality to look at resource related limits at a per-Client Profile level. One level deeper resource allocation than what is set at the VPN level, this monitor complements the earlier `MessageVPNLimits` Monitor (#5 above). 
This monitor also displays the resources (e.g. number of connections, number of subscriptions) in a 'current usage vs configured limit' manner. This makes rules easier to apply where percentage utilisation can be worked out to highlight any Client Profiles that are operating close to their configured soft limits.

Features implemented in the monitor include:
* A computed "utilisation score" is done for each Client Profile to highlight those needing attention versus those that don't. This score is used to help prioritise the Profiles that should be caught in the dataview if the number of Profiles on the broker is higher than the `max rows` limit configured for the dataview. 

### (8) ClientsTopPublishers Monitor

This monitor augments the earlier `MessageRates` Monitor (#4 above) to show message and byte rate activity at an individual client level. The dataview quite simply shows the top 10 connected clients when ordered by the average publishing byte rate. For a holistic view, the dataset also includes the current and average message rate of the client, the current byte rate, and the total published bytes for each client.

Sample of the new dataview showing (upto) the top 10 publishers:  
![ClientsTopPublishers Dataview Sample](https://github.com/SolaceLabs/solace-custom-monitor-solgeneos/blob/master/images/ClientsTopPublishers%20-%20Dataview%20Sample.png?raw=true)

### (9) ClientsTopSubscribers Monitor

This monitor complements the earlier `TopPublishers` Monitor (#8 above) to show message and byte rate activity at an individual client level. The dataview quite simply shows the top 10 connected clients when ordered by the average subscribing byte rate. For a holistic view, the dataset also includes the current and average message rate of the client, the current byte rate, and the total published bytes for each client.

### (10) ClientsSlowSubscribers Monitor

This monitor shows the connected clients that are marked by the broker as being slow subscribers. That is, the clients are not servicing their network sockets fast enough to keep up with what the broker is transmitting to it. While these clients cannot cause adverse impact to the broker (and will automatically be disconnected if necessary), this monitor allows for such applications to be proactively detected so operational troubleshooting can take place. Most often, these clients are ones that are either bandwidth or CPU constrained on the host they reside, so detecting this condition early to take some action can prevent a wider outage for the application.

Sample of the new dataview showing (if any) clients that are being slow:  
![ClientsSlowSubscribers Dataview Sample](https://github.com/SolaceLabs/solace-custom-monitor-solgeneos/blob/master/images/ClientsSlowSubscribers%20-%20Dataview%20Sample.png?raw=true)

### (11) MessagingTester Monitor

This monitor can be considered an 'advanced' monitor in that it goes beyond looking at metrics to perform ['synthetic monitoring'](https://en.wikipedia.org/wiki/Synthetic_monitoring) by periodically performing message send and receive tests using the broker's SMF client API. The effect is a more holistic peace of mind that the event broker is healthy because all surrounding infrastructure is also covered in the test.   
Consider what could be involved in the scenario of an application connecting to the broker: 
1. A DNS lookup is performed of the connection URI. - This relies on the DNS system having the correct entries and being accesible.
2. A load-balancer may be fronting your active-standby event broker. - This relies on the load-balancer being available and correctly routing new connections.

Consider an application that is successfully connected _but_ the surrounding infrastructure is suffering from the following issues:
1. Network congestion / packet loss that is leading to increased latency on the message publish or subscribe.
2. Storage layer congestion or failures leading to slower persistence of guaranteed messaging.

These are all in the category of failures that are application impacting but may not be detected in the narrow focus of just ensuring the event broker itself is reporting healthy.  

This is what [synthetic monitoring](https://en.wikipedia.org/wiki/Synthetic_monitoring) via this monitor example is intended to achieve. 

Sample of the new dataview showing an inability to resolve the SMF connection URI:  
![MessagingTester Dataview Sample 1](https://github.com/SolaceLabs/solace-custom-monitor-solgeneos/blob/master/images/MessagingTester%20-%20Dataview%20Sample%20-%20Error%201.png?raw=true)
Sample of the new dataview showing an inability to publish new persistent messages as the storage spool is full/unavailable: 
![MessagingTester Dataview Sample 2](https://github.com/SolaceLabs/solace-custom-monitor-solgeneos/blob/master/images/MessagingTester%20-%20Dataview%20Sample%20-%20Error%202.png?raw=true)

### (12) SoftwareSystemHealth Monitor

This monitor shows the runtime metrics as reported by the `show system health` CLI command, allowing for the health and performance of the underlying infrastructure of a Software Broker deployment to be monitored. More information for these metrics is available at this [documentation link](https://docs.solace.com/System-and-Software-Maintenance/SW-Health-Monitoring.htm#Direct). 

**Note:** The configuration for this monitor is disabled by default, and needs to set `autoStart=true` to enable it when the broker being monitored is the software option.

Sample of the new dataview showing the latency metrics for health assessment:  
![SoftwareSystemHealth Dataview Sample](https://github.com/SolaceLabs/solace-custom-monitor-solgeneos/blob/master/images/SoftwareSystemHealth%20-%20Dataview%20Sample.png?raw=true)

### (13) MessageVPNBridgeRates Monitor

This monitor shows the configured message VPN bridges, their current message and byte rates, as well as metrics on the underlying TCP connection. e.g. Current round-trip latency and state of the Send and Recv Queues. 

### (14) ConfigSyncStatus Monitor

This monitor shows the entries in the Config-Sync database, along with their status such as 'In-Sync', 'Out-Of-Sync' or 'Stale'.  

Sample of the new dataview showing the entries and their sync status:  
![SoftwareSystemHealth Dataview Sample](https://github.com/SolaceLabs/solace-custom-monitor-solgeneos/blob/master/images/ConfigSyncStatus%20-%20Dataview%20Sample.png?raw=true)


## How to use this repository

This project assumes you are familiar with custom monitor development already and have your environment setup with the sample java project provided in `solgeneossample` as described [here](https://docs.solace.com/SolGeneos-Agent/Monitor-Dev-and-Deployment.htm).

The sample project uses the [Ant build tool](https://ant.apache.org/) to compile the sample code ([requires minimum 1.8 JDK](https://www.oracle.com/uk/java/technologies/javase/javase-jdk8-downloads.html)) and generate the jar files for deployment on your event broker.
The contents of this project exist within the directory structure of that sample. 

More specifically: 
- Java source code for the new monitors in this project can be found in `src/com/solacesystems/solgeneos/custommonitors`
- The properties files to support the new monitors can be found in `config/`
- The `build.xml` and `build.properties` files have been enhanced over the original sample to add a new task for monitor deployment to a 'dev' environment appliance

To compile the source to build the necessary jar files for deployment, execute:
`ant dist`
while in the root of the project, assuming `ant` has been installed and ready on your machine with the executable in your `PATH`.

To deploy to an appliance environment:
- the contents of `_antDist/lib` should be copied to `/usr/sw/solgeneos/monitors`
- the contents of `_antDist/config` should be copied to `/usr/sw/solgeneos/config`

A restart of the `solgeneos` service will then activate the new monitors.

### Step by step instructions

1. Download the latest `sol-geneossample-<version>.tar.gz` file from the customer downloads area. [(Products > SolGeneos)](https://products.solace.com/)
1. Extract the tar archive at a suitable location on your build machine  
    `tar -xvf sol-geneossample-*.tar.gz`
1. Clone the git repo to the same directory (or download the zip and extract contents)   
    `git clone https://github.com/SolaceLabs/solace-custom-monitor-solgeneos`  
1. Merge/replace the contents of the repo with the `solgeneossample` directory contents  
    `cp -r solace-custom-monitor-solgeneos/config solgeneossample/`  
    `cp -r solace-custom-monitor-solgeneos/src solgeneossample/`  
    `cp solace-custom-monitor-solgeneos/build* solgeneossample/`  
1. Copy `httpclient` library supplied with `solgeneossample` to `bundledLib` directory  
    `cp solgeneossample/lib/compileLib/httpclient-*.jar solgeneossample/lib/bundledLib/`  
1. Install Ant and 1.8 JDK if required and set JAVA_HOME  
    `export JAVA_HOME="/path/to/java/jdk/1.8/"`
1. Move into `solgeneossample` directory and build with Ant  
    `cd solgeneossample/`  
    `ant dist`  
1. Set the hostname of your dev broker to deploy to in `build.properties`  
    `dev.appliance.hostname=192.168.31.50`
1. Use Ant to scp relevant files to the broker (available if Ant setup with [optional scp/ssh libraries on your system](https://ant.apache.org/manual/Tasks/scp.html))  
    `ant deploy`  
      
    If copying manually:  
    - the contents of `_antDist/lib` should be copied to `/usr/sw/solgeneos/monitors`
    - the contents of `_antDist/config` should be copied to `/usr/sw/solgeneos/config`
1. On the dev broker restart the SolGeneos agent to activate new monitors  
    `/usr/sw/solgeneos/currentload/bin/serviceScript.sh restart`

### Additional steps for building the 'advanced' monitors

1. Download the latest Solace PubSub+ Java API (`sol-jcsmp-10.x.y`) from [here.](https://products.solace.com/download/JAVA_API)
1. Extract the archive and navigate to the Jar file: `lib\sol-jcsmp-10.x.y.jar`
1. Copy that file to `bundledLib` and `compileLib` directories under `solgeneossample/lib/`  
    `cp sol-jcsmp-*.jar solgeneossample/lib/compileLib`  
    `cp sol-jcsmp-*.jar solgeneossample/lib/bundledLib`
1. Move into `solgeneossample` directory and build with Ant advanced tasks  
    `cd solgeneossample/`  
    `ant dist-advanced`  
1. Use Ant to scp relevant files to the broker (available if Ant setup with [optional scp/ssh libraries on your system](https://ant.apache.org/manual/Tasks/scp.html))  
    `ant deploy-advanced`  
      
    If copying manually:  
    - the contents of `_antDist/lib` should be copied to `/usr/sw/solgeneos/monitors`
    - the contents of `_antDist/config/advanced` should be copied to `/usr/sw/solgeneos/config`

1. Edit the file `config/_user_MessagingTestMonitor.properties` to provide configuration for connecting to the broker for messaging tests.
1. On the dev broker restart the SolGeneos agent to activate new monitors  
    `/usr/sw/solgeneos/currentload/bin/serviceScript.sh restart`
    
    
## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details on our code of conduct, and the process for submitting pull requests to us.

## Authors

See the list of [contributors](https://github.com/SolaceLabs/solace-custom-monitor-solgeneos/graphs/contributors) who participated in this project.

## License

This project is licensed under the Apache License, Version 2.0. - See the [LICENSE](LICENSE) file for details.

## Resources

For more information try these resources:
- SolGeneos Monitor Development [Best Practices](https://docs.solace.com/SolGeneos-Agent/Best-Practices.htm)
- Get a better understanding of [Solace Event Brokers](https://solace.com/products/event-broker/)
- The Solace [Developer Portal](https://solace.dev)
- Check out the [Solace blog](https://solace.com/blog/) for other interesting discussions around Solace technology
- Ask the [Solace community](https://solace.community/) for help

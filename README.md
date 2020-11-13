# SolGeneos Custom Monitors

## What's in this repository?

The [Solace Geneos Agent (a.k.a SolGeneos)](https://docs.solace.com/SolGeneos-Agent/SolGeneos-Overview.htm) allows for the monitoring of Solace [Event Brokers](https://solace.com/what-is-an-event-broker/) in the [Geneos](https://www.itrsgroup.com/products/geneos) monitoring tool by [ITRS](https://www.itrsgroup.com/).  
While coming with a large number of [dataviews](https://docs.solace.com/SolGeneos-Agent/Default-SolGeneos-Agent-Data-Views.htm) to monitor various product aspects such as pending messages on queues, health of the underlying hardware, and connectivity status to other event-brokers, SolGeneos is an extendable framework that allows customers to [build additional monitors](https://docs.solace.com/SolGeneos-Agent/Monitor-Dev-and-Deployment.htm) for dataviews containing metrics specific to their own requirements.

This repository contains further examples of custom monitors that can support monitor development efforts by customers.

:warning: Important Notice :warning: | 
------------ | 
Customer developed monitors and example code, such as those in this project, are not supported by Solace as part of the SolGeneos product support. Check out [CONTRIBUTING.md](CONTRIBUTING.md) to raise issues/bugs, submit fixes, request features, submit features, submit ideas, or to ask questions.  Responses will be 'best effort' from [contributors](https://github.com/solacese/solgeneos-custom-monitors/graphs/contributors). | 


## Custom Monitors Index

No.  | Name | Function |
---- | ---- | -------- |
1 | [Users Monitor](#1-users-monitor) | List currently configured CLI and FTP users. Serves as a development sample. |
2 | [QueuesEx Monitor](#2-queuesex-monitor) | Extended version of Queues monitor with several enhancements |
3 | [TopicEndpointsEx Monitor](#3-topicendpointsex-monitor) | Extended version of TopicEndpoints monitor with several enhancements |
4 | [MessageRates Monitor](#4-messagerates-monitor) | New monitor to display message and byte rate activity as well as identify top-talkers and track high water marks (HWMs) |
5 | [MessageVPNLimits Monitor](#5-messagevpnlimits-monitor) | New monitor to clearly display 'current usage vs. max limit' of various capacity-related resources at a message-vpn level |
6 | [BrokerLimits Monitor](#6-brokerlimits-monitor) | New monitor to clearly display 'current allocated vs. broker hard limit' of various capacity related resources |


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
![QueuesEx Combined Dataview Sample](https://github.com/solacese/solgeneos-custom-monitors/blob/master/images/QueuesEx%20-%20Dataview%20Sample.png?raw=true)

Sample of being able to create per-VPN views of the queues:

![QueuesEx Per-VPN Dataview Sample](https://github.com/solacese/solgeneos-custom-monitors/blob/master/images/QueuesEx%20-%20Per-VPN%20Dataviews.jpg?raw=true)

### (3) TopicEndpointsEx Monitor

This monitor is an extended version for monitoring topic endpoints, compared to the `TopicEndpoints` monitor available in the product install.  Enhancements over the original version include all those listed for the `QueuesEx` monitor mentioned above. This monitor is near identical in output to the extended queues monitor and thus provides the same new advantages.

Sample of the new headlines and columns available:
![TopicEndpointsEx Combined Dataview Sample](https://github.com/solacese/solgeneos-custom-monitors/blob/master/images/TopicEndpointsEx%20-%20Dataview%20Sample.png?raw=true)


### (4) MessageRates Monitor

This monitor provides new functionality to look at messaging activity at a per-VPN level as well as the aggregate across the whole broker.  It can be used in the context of capacity management to monitor the message or byte rate and alert if getting close to the known limits of the broker. Furthermore, it tracks the high water mark (HWM) values for the broker-wide aggregates in a dedicated dataview called `MessageRatesHWM`.

Features implemented in the monitor include:
* Computed metrics for the message and data rate for a VPN to provide a simple indicator of activity and load on the shared-tenancy environment. (Computed by summing the ingress and egress metrics as provided by SEMP.)
* Identification of the "Top Talkers" VPN names that are making up the messaging activity at the time of sample. (e.g. Those headline fields can populate dashboard elements to make it clear where any increase in activity or deviances from normal trend are originating from.) 
* Computed metrics for a broker-wide view of messaging activity. (Computed by summing the individual VPN level stats to reach the full broker-wide metric.) 
* Tracking of the previous peak value (i.e. high water mark) for each of the rate metrics to aid in capacity management by providing historical context to the current rate activity.
* Similar to previous monitors in this repository, the implementation of a max-row count to limit the amount of data sent to the Geneos gateway and any load issues this may cause. A sorting is done to ensure the VPNs with the highest average byte rate is near the top before the dataview is truncated. (The broker-wide computed values continue to act on the full dataset of all VPNs configured on the broker.)

Sample of the new headlines and columns available in the primary `MessageRates` dataview:
![MessageRates Dataview Sample](https://github.com/solacese/solgeneos-custom-monitors/blob/master/images/MessageRates%20-%20Dataview%20Sample.png?raw=true)

Sample of the high water mark (HWM) values that are tracked in the additional `MessageRatesHWM` dataview. Note that the full context of other rate metrics and top talkers at the time of the HWM rate is also retained for reference and context.
![MessageRatesHWM Dataview Sample](https://github.com/solacese/solgeneos-custom-monitors/blob/master/images/MessageRatesHWM%20-%20Dataview%20Sample.png?raw=true)

### (5) MessageVPNLimits Monitor

This monitor provides new functionality to look at resource related limits at a per-VPN level. As part of the multi-tenancy approach to using the brokers, various limits are applied at each message-VPN when it is created, each message-VPN in effect being a virtual slice of the overall broker and its resources.
This monitor displays those resources (e.g. number of connections, number of subscriptions) in a 'current usage vs configured limit' manner. This makes rules easier to apply where percentage utilisation can be worked out to highlight any VPNs that are operating close to their configured soft limits.

Features implemented in the monitor include:
* A computed "utilisation score" is done for each message-VPN to highlight those needing attention versus those that don't. This score is used to help prioritise the message-VPNs that should be caught in the dataview if the number of message-VPNs on the broker is higher than the `max rows` limit configured for the dataview. 

Sample of the new dataview showing various resource usage and their max limit:
![MessageVPNLimits Dataview Sample](https://github.com/solacese/solgeneos-custom-monitors/blob/master/images/MessageVPNLimits%20-%20Dataview%20Sample.png?raw=true)

### (6) BrokerLimits Monitor

This monitor provides new functionality to look at resource related limits at a broker level. Adding to the functionality in the earlier `MessageVPNLimits` dataview (#5 above), this dataview summarises those same resources in a 'total allocated vs broker limit' manner. As part of effective capacity management of a shared-tenancy broker, it is important to monitor when resources being allocated at a VPN level (i.e. the 'soft limits') are cumulatively nearing or perhaps even exceeding the broker level hard limits.
Additionally, if an organisation is also adopting a policy of 'overcommitting' or 'overbooking' the broker resources to the underlying message-VPNs that are created, this monitor will make it easy to monitor the actual usage of a resource versus the known hard limit.

Sample of the new dataview showing various resources for their current usage, total allocation to the configured message-VPNs, and the actual hard limit:  
![BrokerLimits Dataview Sample](https://github.com/solacese/solgeneos-custom-monitors/blob/master/images/BrokerLimits%20-%20Dataview%20Sample.png?raw=true)

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

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details on our code of conduct, and the process for submitting pull requests to us.

## Authors

See the list of [contributors](https://github.com/solacese/solgeneos-custom-monitors/graphs/contributors) who participated in this project.

## License

This project is licensed under the Apache License, Version 2.0. - See the [LICENSE](LICENSE) file for details.

## Resources

For more information try these resources:
- SolGeneos Monitor Development [Best Practices](https://docs.solace.com/SolGeneos-Agent/Best-Practices.htm)
- Get a better understanding of [Solace Event Brokers](https://solace.com/products/event-broker/)
- The Solace [Developer Portal](https://solace.dev)
- Check out the [Solace blog](https://solace.com/blog/) for other interesting discussions around Solace technology
- Ask the [Solace community](https://solace.community/) for help

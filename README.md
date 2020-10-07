# SolGeneos Custom Monitors

## What's in this repository?

The [Solace Geneos Agent (a.k.a SolGeneos)](https://docs.solace.com/SolGeneos-Agent/SolGeneos-Overview.htm) allows for the monitoring of Solace [Event Brokers](https://solace.com/what-is-an-event-broker/) in the [Geneos](https://www.itrsgroup.com/products/geneos) monitoring tool by [ITRS](https://www.itrsgroup.com/).  
While coming with a large number of [dataviews](https://docs.solace.com/SolGeneos-Agent/Default-SolGeneos-Agent-Data-Views.htm) to monitor various product aspects such as pending messages on queues, health of the underlying hardware, and connectivity status to other event-brokers, SolGeneos is an extendable framework that allows customers to [build additional monitors](https://docs.solace.com/SolGeneos-Agent/Monitor-Dev-and-Deployment.htm) for dataviews containing metrics specific to their own requirements.

This repository contains further examples of custom monitors that can support monitor development efforts by customers.

:warning: Important Notice :warning: | 
------------ | 
Customer developed monitors and example code, such as those in this project, are not supported by Solace as part of the SolGeneos product support. Check out [CONTRIBUTING.md](CONTRIBUTING.md) to raise issues/bugs, submit fixes, request features, submit features, submit ideas, or to ask questions.  Responses will be 'best effort' from [contributors](https://github.com/solacese/solgeneos-custom-monitors/graphs/contributors). | 


## Custom Monitors Index

### (1) Users Monitor

This monitor builds significantly on the [sample](https://docs.solace.com/SolGeneos-Agent/Creating-UsersMon.htm) of the same name provided provided with the SolGeneos product. 
Enhancements over the original version include:
* The addition of more headline fields on the dataview containing information such as the total counts of each user type. 
  (Headlines are useful places to provide summary or aggregated information of the main dataview contents. These can then be referenced by dashboard elements too.) 
* The use of Java8 Stream API to further process the SEMP response to apply filters and create subsets of the response. 
* The use of a property file to control the behaviour of the monitor code and how data is presented
* The ability to dynamically generate multiple dataviews from the same monitor, especially if the overall data needs to be segmented for different sets of Geneos users.
* The introduction of a new "Multi Record SEMP Parser" that can be used in a generic fashion across different SEMP responses to extract the records of interest.

The aim of this particular monitor is to continue acting as a development sample, it's operational usefulness in a true monitoring enviromment is limited.

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


### (3) MessageVPNRates Monitor

This monitor provides new functionality to look at messaging activity at a per-VPN level as well as the aggregate across the whole broker.  It can be used in the context of capacity management to monitor the message or byte rate and alert if getting close to the known limits of the broker.

Features implemented in the monitor include:
* Computed metrics for the message and data rate for a VPN to provide a simple indicator of activity and load on the shared-tenancy environment. (Computed by summing the ingress and egress metrics as provided by SEMP.)
* Identification of the "Top Talkers" VPN names that are making up the messaging activity at the time of sample. (e.g. Those headline fields can populate dashboard elements to make it clear where any increase in activity or deviances from normal trend are originating from.) 
* Computed metrics for a broker-wide view of messaging activity. (Computed by summing the individual VPN level stats to reach the full broker-wide metric.) 
* Similar to previous monitors in this repository, the implementation of a max-row count to limit the amount of data sent to the Geneos gateway and any load issues this may cause. A sorting is done to ensure the VPNs with the highest average byte rate is near the top before the dataview is truncated. (The broker-wide computed values continue to act on the full dataset of all VPNs configured on the broker.)

Additional features that may be considered:
* The tracking of high water mark rates for the broker-wide metrics such as average message rate and average MByte rate.

Sample of the new headlines and columns available:
![MessageVPNRates Dataview Sample](https://github.com/solacese/solgeneos-custom-monitors/blob/master/images/MessageVPNRates%20-%20Dataview%20Sample.png?raw=true)

## How to use this repository

This project assumes you are familiar with custom monitor development already and have your environment setup with the sample java project provided in `solgeneossample` as described [here](https://docs.solace.com/SolGeneos-Agent/Monitor-Dev-and-Deployment.htm).

The sample project uses the [Ant build tool](https://ant.apache.org/) to compile the sample code and generate the jar files for deployment on your event broker.
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

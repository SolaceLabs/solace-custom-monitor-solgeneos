# SolGeneos Custom Monitors

## What's in this repository?

The [Solace Geneos Agent (a.k.a SolGeneos)](https://docs.solace.com/SolGeneos-Agent/SolGeneos-Overview.htm) allows for the monitoring of Solace [Event Brokers](https://solace.com/what-is-an-event-broker/) in the [Geneos](https://www.itrsgroup.com/products/geneos) monitoring tool by [ITRS](https://www.itrsgroup.com/).  
While coming with a large number of [dataviews](https://docs.solace.com/SolGeneos-Agent/Default-SolGeneos-Agent-Data-Views.htm) to monitor various product aspects such as pending messages on queues, health of the underlying hardware, and connectivity status to other event-brokers, SolGeneos is an extendable framework that allows customers to [build additional monitors](https://docs.solace.com/SolGeneos-Agent/Monitor-Dev-and-Deployment.htm) for dataviews containing metrics specific to their own requirements.

This repository contains further examples of custom monitors that can support monitor development efforts by customers.

:warning: Important Notice :warning: | 
------------ | 
Customer developed monitors and example code such as those in this project are not supported by Solace as part of the SolGeneos product support. | 


## Custom Monitors Index

### (1) UsersMonitor

This monitor builds significantly on the [sample](https://docs.solace.com/SolGeneos-Agent/Creating-UsersMon.htm) of the same name provided provided with the SolGeneos product. 
Enhancements over the original version include:
* The addition of more headline fields on the dataview containing information such as the total counts of each user type. 
  (Headlines are useful places to provide summary or aggregated information of the main dataview contents. These can then be referenced by dashboard elements too.) 
* The use of Java8 Stream API to further process the SEMP response to apply filters and create subsets of the response. 
* The use of a property file to control the behaviour of the monitor code and how data is presented
* The ability to dynamically generate multiple dataviews from the same monitor, especially if the overall data needs to be segmented for different sets of Geneos users.
* The introduction of a new "Multi Record SEMP Parser" that can be used in a generic fashion across different SEMP responses to extract the records of interest.

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
- Get a better understanding of [Solace Event Brokers](https://solace.com/products/event-broker/)
- The Solace [Developer Portal](https://solace.dev)
- Check out the [Solace blog](https://solace.com/blog/) for other interesting discussions around Solace technology
- Ask the [Solace community](https://solace.community/) for help

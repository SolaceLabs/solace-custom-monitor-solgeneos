# SolGeneos Custom Monitors

## What's in this repository?

The [Solace Geneos Agent (a.k.a SolGeneos)](https://docs.solace.com/SolGeneos-Agent/SolGeneos-Overview.htm) allows for the monitoring of Solace [Event Brokers](https://solace.com/what-is-an-event-broker/) in the [Geneos](https://www.itrsgroup.com/products/geneos) monitoring tool by [ITRS](https://www.itrsgroup.com/).  
While coming with a large number of [dataviews](https://docs.solace.com/SolGeneos-Agent/Default-SolGeneos-Agent-Data-Views.htm) to monitor various product aspects such as pending messages on queues, health of the underlying hardware, and connectivity status to other event-brokers, SolGeneos is an extendable framework that allows customers to [build additional monitors](https://docs.solace.com/SolGeneos-Agent/Monitor-Dev-and-Deployment.htm) for dataviews containing metrics specific to their own requirements.

This repository contains further examples of custom monitors that can support monitor development efforts by customers.

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

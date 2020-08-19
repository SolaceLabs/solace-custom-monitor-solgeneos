// Modified from original source: SampleConstants.java in package com.solacesystems.solgeneos.sample.util
package com.solacesystems.solgeneos.custommonitors.util;

import org.apache.http.Header;
import org.apache.http.message.BasicHeader;

public interface MonitorConstants {

	/**
     * Management IP Address Property
     */
    static final public String MGMT_IP_ADDRESS_PROPERTY_NAME                = "ipaddress";

    /**
     * Management Port Property
     */
    static final public String MGMT_PORT_PROPERTY_NAME                      = "port";

    /**
     * Management Username Property
     */
    static final public String MGMT_USERNAME_PROPERTY_NAME                  = "username";

    /**
     * Management Password Property (clear form)
     */
    static final public String MGMT_PASSWORD_PROPERTY_NAME                  = "password";
    
    /**
     * Management Password Property (encrypted)
     */    
    static final public String MGMT_ENCRYPTED_PASSWORD_PROPERTY_NAME                  = "encrypted_password";
    
    /**
     * HTTP Post Request Header
     */
	public final static Header HEADER_CONTENT_TYPE_UTF8 = new BasicHeader("Content-type","text/xml; charset=utf-8");	
	
	/**
	 * HTTP Post Request Uri
	 */
	public static final String HTTP_REQUEST_URI = "/SEMP";

	/**
	 * Appliance SEMP Version
	 * 
	 * The appliance's SEMP show commands are backward compatible to a limited number of versions. For this
	 * example, we choose soltr/5_4 SEMP version so that the sample will work with appliance running SOL-TR 5.4 and later.
	 * However, if your monitor is using a SEMP command that exists in a version later than SOL-TR 5.4, then
	 * you should change the value of this variable to the correct SEMP version.
	 * 
	 * Update: Latest recommendation is not to include this semp version in the request and let the broker evaluate against the current schema. 
	 * 		   So this constant will not be used.
	 */
	public static final String SEMP_VERSION = "soltr/5_4";
	
    /**
     * Global Headlines Properties File Name
     */
	public static final String GLOBAL_HEADLINES_PROPERTIES_FILE_NAME = "_user_globalHeadlines.properties";	
		
    /**
     * Monitor Specific Properties File Name Prefix
     */
	public static final String MONITOR_PROPERTIES_FILE_NAME_PREFIX = "_user_";
	
    /**
     * Monitor Specific Properties File Name Suffix
     */
	public static final String MONITOR_PROPERTIES_FILE_NAME_SUFFIX = ".properties";
}

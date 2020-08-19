// Renamed from original source: SampleResponseHandler.java in package com.solacesystems.solgeneos.sample.util
package com.solacesystems.solgeneos.custommonitors.util;

import java.io.IOException;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;

/**
 * Response handler used http client to process http response and release
 * associated resources
 */
public class SampleResponseHandler implements ResponseHandler<SampleHttpSEMPResponse> {

	@Override
	public SampleHttpSEMPResponse handleResponse(HttpResponse response)
			throws ClientProtocolException, IOException {
		return new SampleHttpSEMPResponse(response);
	}

}

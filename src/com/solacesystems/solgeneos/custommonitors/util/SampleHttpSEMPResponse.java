// Renamed from original source: SampleHttpSEMPResponse.java in package com.solacesystems.solgeneos.sample.util
package com.solacesystems.solgeneos.custommonitors.util;

import java.io.IOException;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.util.EntityUtils;

public class SampleHttpSEMPResponse {
	private int statusCode;
	private String reasonPhrase;
	private String respBody = "";
	
	public SampleHttpSEMPResponse(HttpResponse response) throws IOException {
		StatusLine statusLine = response.getStatusLine();
		statusCode = statusLine.getStatusCode();
		reasonPhrase = statusLine.getReasonPhrase();
		HttpEntity entity = response.getEntity();
        if (entity != null) {
        	respBody = EntityUtils.toString(entity);
        }
	}

	public int getStatusCode() {
		return statusCode;
	}

	public String getReasonPhrase() {
		return reasonPhrase;
	}

	public String getRespBody() {
		return respBody;
	}
	
}

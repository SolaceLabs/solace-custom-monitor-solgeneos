package com.solacesystems.solgeneos.custommonitors.util;

import java.util.ArrayList;

public class RatesHWM implements java.io.Serializable {
	
    /**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	// What should be the formatting style?
    static final private String FLOAT_FORMAT_STYLE = "%.2f";	// 2 decimal places
    
	public static enum Type {
		CURRENT_MSG_RATE,
		CURRENT_INGRESS_MSG_RATE,
		CURRENT_EGRESS_MSG_RATE,
		AVERAGE_MSG_RATE,
		AVERAGE_INGRESS_MSG_RATE,
		AVERAGE_EGRESS_MSG_RATE,
		CURRENT_MBYTE_RATE,
		CURRENT_INGRESS_MBYTE_RATE,
		CURRENT_EGRESS_MBYTE_RATE,
		AVERAGE_MBYTE_RATE,
		AVERAGE_INGRESS_MBYTE_RATE,
		AVERAGE_EGRESS_MBYTE_RATE,
	}
	
	private String timestamp = "";
	
	private long currentMsgRate;
	private long currentIngressMsgRate;
	private long currentEgressMsgRate;
	
	private long averageMsgRate;
	private long averageIngressMsgRate;
	private long averageEgressMsgRate;
	
	private double currentMByteRate;
	private double currentIngressMByteRate;
	private double currentEgressMByteRate;
	
	private double averageMByteRate;
	private double averageIngressMByteRate;
	private double averageEgressMByteRate;	

	private String topTalkerVPN1 = "";
	private String topTalkerVPN2 = "";
	private String topTalkerVPN3 = "";
	
	// A flag to signal whether the currently stored HWMs should be reset. (Will do so if a value supplied is different to what is stored.)
	// This way this class works generically whether the HWM is being reset hourly, daily, weekly, monthly, etc. Logic is determined by the user.
	private int continuityValue = -1;
	
	private transient ArrayList<String> row;

	private Type type;

	
	public RatesHWM(RatesHWM.Type type) {
		super();
		this.type = type;

	}
	
	public RatesHWM(RatesHWM.Type type, int continuityValue) {
		super();
		this.type = type;
		this.continuityValue = continuityValue;

	}
	
	public void updateHWMs (String timestamp, long currentMsgRate, long currentIngressMsgRate,
			long currentEgressMsgRate, long averageMsgRate, long averageIngressMsgRate, long averageEgressMsgRate,
			double currentMByteRate, double currentIngressMByteRate, double currentEgressMByteRate, double averageMByteRate,
			double averageIngressMByteRate, double averageEgressMByteRate, String topTalkerVPN1, String topTalkerVPN2,
			String topTalkerVPN3){
		
		
		boolean newHWM = false;
		
		switch (this.type) {
		case CURRENT_MSG_RATE:
			newHWM = (currentMsgRate > this.currentMsgRate) ? true : false;
			break;
		case CURRENT_INGRESS_MSG_RATE:
			newHWM = (currentIngressMsgRate > this.currentIngressMsgRate) ? true : false;
			break;
		case CURRENT_EGRESS_MSG_RATE:
			newHWM = (currentEgressMsgRate > this.currentEgressMsgRate) ? true : false;
			break;
			
		case AVERAGE_MSG_RATE:
			newHWM = (averageMsgRate > this.averageMsgRate) ? true : false;
			break;
		case AVERAGE_INGRESS_MSG_RATE:
			newHWM = (averageIngressMsgRate > this.averageIngressMsgRate) ? true : false;
			break;
		case AVERAGE_EGRESS_MSG_RATE:
			newHWM = (averageEgressMsgRate > this.averageEgressMsgRate) ? true : false;
			break;

		case CURRENT_MBYTE_RATE:
			newHWM = (currentMByteRate > this.currentMByteRate) ? true : false;
			break;
		case CURRENT_INGRESS_MBYTE_RATE:
			newHWM = (currentIngressMByteRate > this.currentIngressMByteRate) ? true : false;
			break;
		case CURRENT_EGRESS_MBYTE_RATE:
			newHWM = (currentEgressMByteRate > this.currentEgressMByteRate) ? true : false;
			break;

		case AVERAGE_MBYTE_RATE:
			newHWM = (averageMByteRate > this.averageMByteRate) ? true : false;
			break;			
		case AVERAGE_INGRESS_MBYTE_RATE:
			newHWM = (averageIngressMByteRate > this.averageIngressMByteRate) ? true : false;
			break;
		case AVERAGE_EGRESS_MBYTE_RATE:
			newHWM = (averageEgressMsgRate > this.averageEgressMsgRate) ? true : false;
			break;
		default:
			break;
			
		}
		
		if (newHWM) {
			this.timestamp = timestamp;
			this.currentMsgRate = currentMsgRate;
			this.currentIngressMsgRate = currentIngressMsgRate;
			this.currentEgressMsgRate = currentEgressMsgRate;
			this.averageMsgRate = averageMsgRate;
			this.averageIngressMsgRate = averageIngressMsgRate;
			this.averageEgressMsgRate = averageEgressMsgRate;
			this.currentMByteRate = currentMByteRate;
			this.currentIngressMByteRate = currentIngressMByteRate;
			this.currentEgressMByteRate = currentEgressMByteRate;
			this.averageMByteRate = averageMByteRate;
			this.averageIngressMByteRate = averageIngressMByteRate;
			this.averageEgressMByteRate = averageEgressMByteRate;
			this.topTalkerVPN1 = topTalkerVPN1;
			this.topTalkerVPN2 = topTalkerVPN2;
			this.topTalkerVPN3 = topTalkerVPN3;			
		}
		
	}
	
	private void resetHWMs () {
		this.timestamp = "";
		this.currentMsgRate = 0;
		this.currentIngressMsgRate = 0;
		this.currentEgressMsgRate = 0;
		this.averageMsgRate = 0;
		this.averageIngressMsgRate = 0;
		this.averageEgressMsgRate = 0;
		this.currentMByteRate = 0;
		this.currentIngressMByteRate = 0;
		this.currentEgressMByteRate = 0;
		this.averageMByteRate = 0;
		this.averageIngressMByteRate = 0;
		this.averageEgressMByteRate = 0;
		this.topTalkerVPN1 = "";
		this.topTalkerVPN2 = "";
		this.topTalkerVPN3 = "";	
	}
	
	public void resetHWMs (int continuityValue) {
		
		if (this.continuityValue != continuityValue) {
			this.continuityValue = continuityValue;
			resetHWMs();
		}	
	}

	public ArrayList<String> getHWMRow (){
		

		row = new ArrayList<String>();
		row.add(this.timestamp);
		row.add(Long.toString(this.currentMsgRate));
		row.add(Long.toString(this.currentIngressMsgRate));
		row.add(Long.toString(this.currentEgressMsgRate));
		row.add(Long.toString(this.averageMsgRate));
		row.add(Long.toString(this.averageIngressMsgRate));
		row.add(Long.toString(this.averageEgressMsgRate));
		row.add(String.format(FLOAT_FORMAT_STYLE, this.currentMByteRate));
		row.add(String.format(FLOAT_FORMAT_STYLE, this.currentIngressMByteRate));
		row.add(String.format(FLOAT_FORMAT_STYLE, this.currentEgressMByteRate));
		row.add(String.format(FLOAT_FORMAT_STYLE, this.averageMByteRate));
		row.add(String.format(FLOAT_FORMAT_STYLE, this.averageIngressMByteRate));
		row.add(String.format(FLOAT_FORMAT_STYLE, this.averageEgressMByteRate));
		row.add(topTalkerVPN1);
		row.add(topTalkerVPN2);
		row.add(topTalkerVPN3);
		return row;
	}

}
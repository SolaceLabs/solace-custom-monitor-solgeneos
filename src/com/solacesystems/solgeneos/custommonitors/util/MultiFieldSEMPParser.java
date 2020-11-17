package com.solacesystems.solgeneos.custommonitors.util;

import java.util.HashMap;
import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

/**
 * This class parses a multi-field SEMP response to extract and return only the fields of interest
 */
public class MultiFieldSEMPParser extends SampleSEMPParser {
	
	// StringBuilder to build up the contents of each element
	private StringBuilder sbElementContent = new StringBuilder();
	
	private HashMap<String, String> fieldsMap;
	private boolean buildField = false;
    
    public MultiFieldSEMPParser(HashMap<String, String> fieldsRequired) throws ParserConfigurationException, SAXException {
    	super();
    	this.fieldsMap = fieldsRequired;
    }
    
	@Override
	protected void initializeParser(String str) {
		super.initializeParser(str);
    	// Clear the StringBuilder
    	sbElementContent.delete(0, sbElementContent.length());
    	
	}
	
	// Useful SAX Parser Ref: https://www.journaldev.com/1198/java-sax-parser-example
	
	// startElement() is called at the start of each new XML tag by the SAX parser
    @Override
    public void startElement(String namespaceURI, String localName, String qualifiedName, Attributes atts) throws SAXException {
    	super.startElement(namespaceURI, localName, qualifiedName, atts);
    	
    	if (this.fieldsMap.keySet().contains((qualifiedName)))
    	{    		
    		// New element start that is a field of interest.
    		sbElementContent.delete(0, sbElementContent.length());
    		buildField = true;	// Optimisation to only build the string for interesting fields
    	}
    	
    }

    // endElement() is called at the end of each new XML tag by the SAX parser
    @Override
    public void endElement(String uri, String localName, String qualifiedName) throws SAXException {
    	super.endElement(uri, localName, qualifiedName);
    	
    	if (this.fieldsMap.keySet().contains((qualifiedName)))
    	{    		
    		// End of an element that is a field of interest.	
    		fieldsMap.put(qualifiedName, this.sbElementContent.toString());
    		buildField = false; // Optimisation to not build the string for subsequent uninteresting fields
    	}
    	
    }

    // characters() method is called when character data is found by SAXParser inside an element. 
    // Note that SAX parser may divide the data into multiple chunks and call characters() method multiple times 
    // Thats why we are using StringBuilder to keep this data for each element of interest using append() method.
    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
    	super.characters(ch, start, length);
    	
    	// Only if in the field is one of interest, then build the string, otherwise do nothing
    	if (buildField) {
    		sbElementContent.append(ch, start, length);    		
    	}
    }
       
	public HashMap<String, String> getFieldsMap() {
		// Return the table without the column names. (To facilitate further processing of the table contents before publishing it.)
		return this.fieldsMap;
	}
}

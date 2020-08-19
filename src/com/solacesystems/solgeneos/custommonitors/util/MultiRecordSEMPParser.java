package com.solacesystems.solgeneos.custommonitors.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

/**
 * This class parses a multi-record SEMP response to extract the records under a given element name name and return a table vector object.
 */
public class MultiRecordSEMPParser extends SampleSEMPParser {
	
	// Will grab these from properties file...
	
	private List<String> columnNames = new ArrayList<String>();
	
	// What tag is the start of the actual data rows? (Each instance of this tag can start populating a row object)
	private String elementName = "";
	
	// Boolean to track where in the parsing things are
	//private boolean foundElementName = false;
	private boolean createRows = false;
	// Boolean to track if column names determined already
	private boolean columnNamesKnown = false;
	
	// StringBuilder to build up the contents of each element
	private StringBuilder sbElementContent = new StringBuilder();
	
	    
	private Vector<Object> tableContent;
	private ArrayList<String> tableRow;
    
    public MultiRecordSEMPParser(String elementName) throws ParserConfigurationException, SAXException {
    	super();
    	this.elementName = elementName;
    }
    
	@Override
	protected void initializeParser(String str) {
		super.initializeParser(str);
    	
    	// Clear the boolean
    	//foundElementName = false;
    	createRows = false;
    	
    	// Clear the StringBuilder
    	sbElementContent.delete(0, sbElementContent.length());
    	
    	// Reinitialise the tableContent object
    	tableContent = new Vector<Object>();

	}
	
	// Useful SAX Parser Ref: https://www.journaldev.com/1198/java-sax-parser-example
	
	// startElement() is called at the start of each new XML tag by the SAX parser
    @Override
    public void startElement(String namespaceURI, String localName, String qualifiedName, Attributes atts) throws SAXException {
    	super.startElement(namespaceURI, localName, qualifiedName, atts);
    	
    	// For this new element, the options are:
    	// (1) The first time getting to the section of interest
    	// (2) If already in the section of interest, the start of a new row
    	// (3) If a row is already started, this must be a column entry until we get to the next row
    	
    	
    	// Have we already found the element with the actual data rows?
    	if (createRows) {
    		
    		// Then this new element start is a sub-element that should be treated as a new column entry
    		sbElementContent.delete(0, sbElementContent.length());
    		//createRows = true;		
    		
    		// Save the name from this element to determine column names
    		if (!columnNamesKnown) {
    			columnNames.add(qualifiedName);
    		}
    	}
    	else
    	{
    		// Is this now the element for the data rows?
    		if (qualifiedName.equalsIgnoreCase(elementName))
    		{
    			// OK now the rows can start being saved for each time this element appears
    			createRows = true; // This will be switched to false each time we leave the section of interest in the XML
    			tableRow = new ArrayList<String>();
    		}
    	}
    	
    }

    // endElement() is called at the end of each new XML tag by the SAX parser
    @Override
    public void endElement(String uri, String localName, String qualifiedName) throws SAXException {
    	super.endElement(uri, localName, qualifiedName);
    	
    	// For this end element, the options are:
    	// (1) We are in the section of interest and got to the end of a row
    	// (2) We are in the section of interest and got to the end of a column
    	
    	if (qualifiedName.equalsIgnoreCase(elementName))
    	{
    		// Do not treat any further elements found by startElement as being columns
    		createRows = false;		
    		// Add the new row as it currently stands to the table
    		tableContent.add(tableRow);
    		// One row fully processed so column names are known now.
    		columnNamesKnown = true;
    	} 
    	else if (createRows)
    	{
    		tableRow.add(sbElementContent.toString());	// String builder will be cleared the next time an element of interest starts
    	}
    	
    }

    // characters() method is called when character data is found by SAXParser inside an element. 
    // Note that SAX parser may divide the data into multiple chunks and call characters() method multiple times 
    // That’s why we are using StringBuilder to keep this data for each element of interest using append() method.
    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
    	super.characters(ch, start, length);
    	
    	// Only if in the column data collecting mode then build the string, otherwise do nothing
    	
    	if (createRows) {
    		sbElementContent.append(ch, start, length);    		
    	}
    }
       
	public Vector<Object> getTableContent() {
		// Return the table without the column names. (To facilitate further processing of the table contents before publishing it.)
		return tableContent;
	}

	public List<String> getColumnNames() {
		// Return the column names so it can added before publishing the content
		return this.columnNames;
	}

	public Vector<Object> getFullTable() {
		// Return a combined table of column names and content
		Vector<Object> tempTableContent = (Vector<Object>) tableContent.clone();
		tempTableContent.add(0, columnNames);
		return tempTableContent;
	}
}

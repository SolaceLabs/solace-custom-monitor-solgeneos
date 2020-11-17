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
public class TargetedMultiRecordSEMPParser extends SampleSEMPParser {
	
	// Need to build up the row UID as the data returned from SEMP will not have a unique key
	private String rowUniqueIdName = "";
	private String rowUniqueIdVpn = "";
	
	// Some consts to find the relevant fields, name the column and the delimiter in its construction of two fields joined up.
	public final String SEMP_VPN_TAG = "message-vpn";
	public final String SEMP_NAME_TAG = "name";
	public final String ROW_UID_NAME = "RowUID";
	public final String ROW_UID_DELIM = "?";
	
	
	private List<String> columnNames = new ArrayList<String>();
	
	// What tag is the start of the actual data rows? (Each instance of this tag can start populating a row object)
	private String rowsElementName = "";
	// What is the name of the tag containing the column names of interest?
	private List<String> columnElementNames;
	// What is the name of the tag containing the sub-elements to skip?
	private List<String> ignoreElementNames;
	
	// Boolean to track where in the parsing things are
	private boolean createRows = false;
	// Boolean to track if column names determined already
	private boolean columnNamesKnown = false;
	// Boolean to track if we're skipping a section
	private boolean skipSection = false;
	
	// There first set of levels in the SEMP response just echo back the command and can be ignored.
	public final int MINIMUM_SEMP_DEPTH = 5;
	// Tracker on how deep into the response the parsing current is
	private int currentSEMPDepth = 0;
	
	// StringBuilder to build up the contents of each element as it's parsed in chunks
	private StringBuilder sbElementContent = new StringBuilder();
	
	// Table content with the individual rows populated as the parsing progresses
	private Vector<Object> tableContent;
	private ArrayList<String> tableRow;
    
    public TargetedMultiRecordSEMPParser(String rowsElementName, List<String> columnElementNames, List<String> ignoreElementNames) throws ParserConfigurationException, SAXException {
    	super();
    	this.rowsElementName = rowsElementName;
    	this.columnElementNames = columnElementNames;
    	this.ignoreElementNames = ignoreElementNames;
    }
    
	@Override
	protected void initializeParser(String str) {
		super.initializeParser(str);
    	
    	// Clear the booleans
    	createRows = false;
    	skipSection = false;
    	
    	// Clear the StringBuilder
    	sbElementContent.delete(0, sbElementContent.length());
    	
    	rowUniqueIdName = "";
    	rowUniqueIdVpn = "";
    	
    	currentSEMPDepth = 0;
    	
    	// Reinitialise the tableContent object
    	tableContent = new Vector<Object>();

	}
	
	// Useful SAX Parser Ref: https://www.journaldev.com/1198/java-sax-parser-example
	
	// startElement() is called at the start of each new XML tag by the SAX parser
    @Override
    public void startElement(String namespaceURI, String localName, String qualifiedName, Attributes atts) throws SAXException {
    	super.startElement(namespaceURI, localName, qualifiedName, atts);
    	
    	// Increment depth for each open tag
    	currentSEMPDepth = currentSEMPDepth + 1;
    	
    	// If still below the minimum depth before it gets interesting, do nothing.
    	if (currentSEMPDepth >= this.MINIMUM_SEMP_DEPTH) {
    	
	    	// For this new element, the options are:
	    	// (1) The first time getting to the parent section of interest, start building rows
	    	// (2) If already in the parent section of interest, is it the start of the nested section?
	    	// (3) If already in the nested elements section, this must be a column entry until we get to the next row
	    
			// Is this now the element for the data rows?
			if (qualifiedName.equalsIgnoreCase(rowsElementName))
			{
				// OK now the rows can start being saved for each time this element appears
				createRows = true; // This will be switched to false each time we leave the section of interest in the XML
				tableRow = new ArrayList<String>();
				
				// Clear the UID generation components
				rowUniqueIdName = "";
		    	rowUniqueIdVpn = "";
				
			}	    	
			// Is this now the element for the skip section rows?
			else if (ignoreElementNames.contains(qualifiedName))
			{	
				skipSection = true; // Will be flipped to false when the end tag reached
			}
			else
			{
				// Then it's just elements that will make up the columns
				if (createRows && !skipSection) 
				{
					// Now check if it is a column that we want?
					if (columnElementNames.contains(qualifiedName))
					{
						// Then this new element start is a sub-element that should be treated as a new column entry
						sbElementContent.delete(0, sbElementContent.length());
						
						// Save the name from this element to determine column names
						if (!columnNamesKnown) {
							columnNames.add(qualifiedName);
						}
					}
				}
			}
    	}
    }

    // endElement() is called at the end of each new XML tag by the SAX parser
    @Override
    public void endElement(String uri, String localName, String qualifiedName) throws SAXException {
    	super.endElement(uri, localName, qualifiedName);
    	
    	// Decrement depth for each close tag
    	currentSEMPDepth = currentSEMPDepth - 1;
    	
    	// If still below the minimum depth before it gets interesting, do nothing.
    	if (currentSEMPDepth >= this.MINIMUM_SEMP_DEPTH) {		
    	
	    	// For this end element, the options are:
	    	// (1) We are in the section of interest and got to the end of a row
	    	// (2) We are in the section of interest and got to the end of a column
	    	
	    	if (qualifiedName.equalsIgnoreCase(rowsElementName))
	    	{
	    		// Do not treat any further elements found by startElement as being columns
	    		createRows = false;
	    		
	    		// Add the UID to the start of the row...
	    		tableRow.add(0, this.rowUniqueIdName.trim() + ROW_UID_DELIM + this.rowUniqueIdVpn.trim());
	    		
	    		// Add the new row as it currently stands to the table
	    		tableContent.add(tableRow);
	    		
	    		if (!columnNamesKnown){
	    			// One row fully processed so column names are known now.
	        		columnNamesKnown = true;
	        		// Add the new RowUID as the first column name though
	        		this.columnNames.add(0, ROW_UID_NAME);	
	    		}
	    	} 
	    	else if (ignoreElementNames.contains(qualifiedName))
	    	{
	    		// End of a section we didn't care for but it's over now so stop skipping.
	    		skipSection = false;
	    	}
	    	else if (createRows && !skipSection)
	    	{	
	    		
				// Now check if it is a column that we want?
				if (columnElementNames.contains(qualifiedName))
				{
					tableRow.add(sbElementContent.toString());	// String builder will be cleared the next time an element of interest starts
				}
	    		
	    		// Collect the components needed to build up the UID for the row. 
				// Not in the above if-statement as theoretically could make up UID with other fields not used in final columns display. Like other natural unique IDs.
	    		if (qualifiedName.equalsIgnoreCase(SEMP_NAME_TAG)) 
	    		{
	    			this.rowUniqueIdName = sbElementContent.toString();
	    		}
	    		if (qualifiedName.equalsIgnoreCase(SEMP_VPN_TAG)) 
	    		{
	    			this.rowUniqueIdVpn = sbElementContent.toString();
	    		}
	    	}
    	}
    }

    // characters() method is called when character data is found by SAXParser inside an element. 
    // Note that SAX parser may divide the data into multiple chunks and call characters() method multiple times 
    // Thats why we are using StringBuilder to keep this data for each element of interest using append() method.
    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
    	super.characters(ch, start, length);
    	
    	// Only if in the column data collecting mode then build the string, otherwise do nothing
    	if (createRows && !skipSection) {
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
		@SuppressWarnings("unchecked")
		Vector<Object> tempTableContent = (Vector<Object>) tableContent.clone();
		tempTableContent.add(0, columnNames);
		return tempTableContent;
	}
}

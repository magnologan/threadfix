////////////////////////////////////////////////////////////////////////
//
//     Copyright (c) 2009-2012 Denim Group, Ltd.
//
//     The contents of this file are subject to the Mozilla Public License
//     Version 1.1 (the "License"); you may not use this file except in
//     compliance with the License. You may obtain a copy of the License at
//     http://www.mozilla.org/MPL/
//
//     Software distributed under the License is distributed on an "AS IS"
//     basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
//     License for the specific language governing rights and limitations
//     under the License.
//
//     The Original Code is Vulnerability Manager.
//
//     The Initial Developer of the Original Code is Denim Group, Ltd.
//     Portions created by Denim Group, Ltd. are Copyright (C)
//     Denim Group, Ltd. All Rights Reserved.
//
//     Contributor(s): Denim Group, Ltd.
//
////////////////////////////////////////////////////////////////////////
package com.denimgroup.threadfix.service.channel;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.denimgroup.threadfix.data.dao.ChannelSeverityDao;
import com.denimgroup.threadfix.data.dao.ChannelTypeDao;
import com.denimgroup.threadfix.data.dao.ChannelVulnerabilityDao;
import com.denimgroup.threadfix.data.entities.ChannelType;
import com.denimgroup.threadfix.data.entities.Finding;
import com.denimgroup.threadfix.data.entities.Scan;
import com.denimgroup.threadfix.webapp.controller.ScanCheckResultBean;

/**
 * TODO import more scans and make sure parameters and paths 
 * are parsed correctly for all vuln types.
 * 
 * @author mcollins
 */
public class NessusChannelImporter extends AbstractChannelImporter {
	
	private static final String SIMPLE_HTTP_REGEX = "(http[^\n]*)";
	private static final String URL_COLON_REGEX   = "URL  : ([^\n]*)\n";
	private static final String PAGE_COLON_REGEX  = "Page : ([^\n]*)\n";
	
	private static final String CSRF_PATH_START = "The following CGIs are not protected by a random token :";
	private static final String CSRF_VULN_CODE = "56818";
	
	private static final String INPUT_NAME_COLON_PARAM_REGEX = "Input name : ([^\n]*)\n";
	
	private static final List<String> SSL_VULNS = 
			Arrays.asList(new String[]{"26928", "60108", "57620", "53360", "42873", "35291"});
	
	private static final Map<String,String> PATH_PARSE_MAP = new HashMap<String,String>();
	static {
		PATH_PARSE_MAP.put("26194", PAGE_COLON_REGEX);
		PATH_PARSE_MAP.put("11411", URL_COLON_REGEX);
		PATH_PARSE_MAP.put("40984", SIMPLE_HTTP_REGEX);
	}
	
	private static final Map<String,String> PARAM_PARSE_MAP = new HashMap<String,String>();
	static {
		PARAM_PARSE_MAP.put("26194", INPUT_NAME_COLON_PARAM_REGEX);
	}

	@Autowired
	public NessusChannelImporter(ChannelTypeDao channelTypeDao,
			ChannelVulnerabilityDao channelVulnerabilityDao,
			ChannelSeverityDao channelSeverityDao) {
		this.channelTypeDao = channelTypeDao;
		this.channelVulnerabilityDao = channelVulnerabilityDao;
		this.channelSeverityDao = channelSeverityDao;
		
		this.channelType = channelTypeDao.retrieveByName(ChannelType.NESSUS);
	}

	@Override
	public Scan parseInput() {
		return parseSAXInput(new NessusSAXParser());
	}
	
	public class NessusSAXParser extends DefaultHandler {
		private Boolean getDate               = false;
		private Boolean getFindings           = false;
		private Boolean getNameText           = false;
		private Boolean getHost               = false;
	
		private String currentChannelVulnCode = null;
		private String currentSeverityCode    = null;
		private String host                   = null;
		
		private StringBuilder pluginOutputString = null;
		
		private String infoLineParamRegex = "\\+ The '([^&]+)' parameter of the [^ ]+ CGI :";
		private String infoLinePathRegex = "\\+ The '[^&]+' parameter of the ([^ ]+) CGI :";
					    
	    public void add(Finding finding) {
			if (finding != null) {
    			finding.setNativeId(getNativeId(finding));
	    		finding.setIsStatic(false);
	    		saxFindingList.add(finding);
    		}
	    }
	    
	    //Once the entire string has been taken out of characters(), parse it
	    public void parseFindingString() {
	    	if (pluginOutputString == null)
	    		return;
	    	
	    	String stringResult = pluginOutputString.toString();
	    	if (stringResult == null || stringResult.trim().isEmpty())
	    		return;
	    	
	    	if (PATH_PARSE_MAP.containsKey(currentChannelVulnCode)) {
	    		parseRegexMatchesAndAdd(stringResult);
	    	} else if (SSL_VULNS.contains(currentChannelVulnCode)){
	    		Finding finding = constructFinding("Application Server", null, 
	    				currentChannelVulnCode, currentSeverityCode);
	    		add(finding);
	    	} else if (CSRF_VULN_CODE.equals(currentChannelVulnCode)){
	    		parseCSRFAndAdd(stringResult);
	    	} else {
	    		parseGenericPattern(stringResult);
	    	}
	    	
    		currentChannelVulnCode = null;
    		currentSeverityCode = null;
	    }

	    private void parseCSRFAndAdd(String stringResult) {
	    	if (stringResult != null && stringResult.contains(CSRF_PATH_START)) {
	    		String smallerPart = stringResult.substring(stringResult.indexOf(CSRF_PATH_START) + CSRF_PATH_START.length());
	    		if (smallerPart.contains("\n")) {
	    			for (String line : smallerPart.split("\n")) {
	    				if (line != null && !line.trim().equals("")) {
	    					Finding finding = constructFinding(line.trim(), null, 
	    		    				currentChannelVulnCode, currentSeverityCode);
	    		    		add(finding);
	    				}
	    			}
	    		}
	    	}
	    }
	    
	    private void parseRegexMatchesAndAdd(String stringResult) {
	    	String paramRegex = null,    pathRegex  = PATH_PARSE_MAP.get(currentChannelVulnCode);
    		Matcher paramMatcher = null, pathMatcher = Pattern.compile(pathRegex).matcher(stringResult);
    		
    		if (PARAM_PARSE_MAP.containsKey(currentChannelVulnCode)) {
    			paramRegex = PARAM_PARSE_MAP.get(currentChannelVulnCode);
    			paramMatcher = Pattern.compile(paramRegex).matcher(stringResult);
    		}
    		
    		//int count = 1;
    		while (pathMatcher.find()) {
    			String param = null;
    			if (paramMatcher != null && paramMatcher.find(pathMatcher.start())) {
    				param = paramMatcher.group(1);
    			}
    				
    			String path = pathMatcher.group(1);
    			
    			if (path != null && host != null && !path.startsWith("http"))
    				path = host + path;
    			
	    		Finding finding = constructFinding(path, param, 
	    				currentChannelVulnCode, currentSeverityCode);
	    		add(finding);
    		}
	    }
	    
	    private void parseGenericPattern(String stringResult) {
	    	String param, path;
	    	
	    	if (stringResult.contains("\n")) {
	    		String [] lines = stringResult.split("\n");
	    		
	    		for (String line : lines) {
	    			
	    			if (line == null || !line.contains("+ The '")) {
	    				continue;
	    			}
	    			
	    			param = getRegexResult(line,infoLineParamRegex);
	    			path = getRegexResult(line,infoLinePathRegex);
	    			
	    			if (path != null && host != null && !path.startsWith("http"))
	    				path = host + path;
	    			
	    			if (param != null || path != null) {
	    				Finding finding = constructFinding(path, param, 
	    	    				currentChannelVulnCode, currentSeverityCode);
	    	    		add(finding);
	    	    		param = null;
	    	    		path = null;
	    			}
	    		}
	    	}
    		currentChannelVulnCode = null;
    		currentSeverityCode = null;
	    }
	    
	    ////////////////////////////////////////////////////////////////////
	    // Event handlers.
	    ////////////////////////////////////////////////////////////////////
	    
	    public void startElement (String uri, String name,
				      String qName, Attributes atts)
	    {
	    	if ("ReportItem".equals(qName)) {
	    		currentChannelVulnCode = atts.getValue("pluginID");
	    		currentSeverityCode = atts.getValue("severity");
	    	} else if ("plugin_output".equals(qName)) {
	    		pluginOutputString = new StringBuilder();
	    		getFindings = true;
	    	} else if ("tag".equals(qName) && "HOST_END".equals(atts.getValue("name"))) {
	    		getDate = true;
	    	} else if (host == null && "name".equals(qName)) {
	    		getNameText = true;
	    	}
	    }

	    public void endElement (String uri, String name, String qName)
	    {
	    	if ("plugin_output".equals(qName)) {
	    		parseFindingString();
	    		pluginOutputString = null;
	    		getFindings = false;
	    	}
	    }
	    
	    public void characters (char ch[], int start, int length) {
	    	if (getDate) {
	    		String tempDateString = getText(ch,start,length);
	    		date = getCalendarFromString("EEE MMM dd kk:mm:ss yyyy", tempDateString);
	    		getDate = false;
	    		
	    	} else if (getFindings) {
	    		char [] mychars = new char[length];
	    		System.arraycopy(ch, start, mychars, 0, length);
	    		pluginOutputString.append(mychars);
	    	} else if (getNameText) {
	    		String text = getText(ch,start,length);
	    		
	    		if ("TARGET".equals(text)) {
	    			getHost = true;
	    		}
	    		
	    		getNameText = false;
	    	} else if (getHost) {
	    		String text = getText(ch,start,length);
	    		
	    		if (text != null && text.startsWith("http")) {
	    			host = text;
	    			if (host.charAt(host.length()-1) == '/') {
	    				host = host.substring(0,host.length()-1);
	    			}
	    			try {
						URL testUrl = new URL(host);
						host = testUrl.getProtocol() + "://" + testUrl.getHost();
					} catch (MalformedURLException e) {
						log.warn("Nessus parser tried to parse " + host + " as a URL.", e);
					}
	    			getHost = false;
	    		}
	    	}
	    }
	}

	@Override
	public ScanCheckResultBean checkFile() {
		return testSAXInput(new NessusSAXValidator());
	}
	
	public class NessusSAXValidator extends DefaultHandler {
		private boolean hasFindings = false;
		private boolean hasDate = false;
		private boolean correctFormat = false;
		private boolean getDate = false;
		
		private boolean clientDataTag = false;
		private boolean reportTag = false;
		
	    private void setTestStatus() {
	    	correctFormat = clientDataTag && reportTag;
	    	
	    	if (!correctFormat) {
	    		testStatus = WRONG_FORMAT_ERROR;
	    	} else if (hasDate) {
	    		testStatus = checkTestDate();
	    	}
	    	
	    	if ((testStatus == null || SUCCESSFUL_SCAN.equals(testStatus)) && !hasFindings) {
	    		testStatus = EMPTY_SCAN_ERROR;
	    	} else if (testStatus == null) {
	    		testStatus = SUCCESSFUL_SCAN;
	    	}
	    }

	    ////////////////////////////////////////////////////////////////////
	    // Event handlers.
	    ////////////////////////////////////////////////////////////////////
	    
	    public void endDocument() {
	    	setTestStatus();
	    }

	    public void startElement (String uri, String name, String qName, Attributes atts) throws SAXException {	    	
	    	if ("NessusClientData_v2".equals(qName)) {
	    		clientDataTag = true;
	    	} else if ("Report".equals(qName)) {
	    		reportTag = true;
	    	} else if ("ReportItem".equals(qName)) {
	    		hasFindings = true;
	    		setTestStatus();
	    		throw new SAXException(FILE_CHECK_COMPLETED);
	    	} else if ("tag".equals(qName) && "HOST_END".equals(atts.getValue("name"))) {
	    		getDate = true;
	    	}
	    }
	    
	    public void characters (char ch[], int start, int length) {
	    	if (getDate) {
	    		String tempDateString = getText(ch,start,length);
	    		testDate = getCalendarFromString("EEE MMM dd kk:mm:ss yyyy", tempDateString);
	    		
	    		hasDate = testDate != null;
	    		
	    		getDate = false;
	    	}
	    }
	}
}

////////////////////////////////////////////////////////////////////////
//
//     Copyright (c) 2009-2013 Denim Group, Ltd.
//
//     The contents of this file are subject to the Mozilla Public License
//     Version 2.0 (the "License"); you may not use this file except in
//     compliance with the License. You may obtain a copy of the License at
//     http://www.mozilla.org/MPL/
//
//     Software distributed under the License is distributed on an "AS IS"
//     basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
//     License for the specific language governing rights and limitations
//     under the License.
//
//     The Original Code is ThreadFix.
//
//     The Initial Developer of the Original Code is Denim Group, Ltd.
//     Portions created by Denim Group, Ltd. are Copyright (C)
//     Denim Group, Ltd. All Rights Reserved.
//
//     Contributor(s): Denim Group, Ltd.
//
////////////////////////////////////////////////////////////////////////
package com.denimgroup.threadfix.service.framework;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;

import com.denimgroup.threadfix.data.entities.DataFlowElement;
import com.denimgroup.threadfix.data.entities.Finding;
import com.denimgroup.threadfix.data.entities.Scan;
import com.denimgroup.threadfix.service.framework.filefilter.FileExtensionFileFilter;

public class CommonPathFinder {
	
	private static final Pattern 
		BACKSLASH_PATTERN = Pattern.compile("\\\\"), // we have to double escape for Java String + regex pattern
		FORWARD_SLASH_PATTERN = Pattern.compile("/");
	
	private static final char forwardSlash = '/', backwardSlash = '\\';
	
	private CommonPathFinder(){}

	public static final String findOrParseProjectRoot(Scan scan) {
		return findOrParseProjectRoot(scan, null);
	}
	
	public static final String findOrParseProjectRoot(Scan scan, String fileExtension) {
		return parseRoot(getFilePaths(scan, fileExtension));
	}

	public static final String findOrParseUrlPath(Scan scan) {
		return parseRoot(getUrlPaths(scan));
	}
	
	public static final String findOrParseProjectRootFromDirectory(File rootFile, String fileExtension) {
		return parseRoot(getFilePathsFromDirectory(rootFile, fileExtension));
	}
	
	@SuppressWarnings("unchecked")
	private static List<String> getFilePathsFromDirectory(File rootFile, String fileExtension) {
		Collection<File> files = FileUtils.listFiles(rootFile, 
				new FileExtensionFileFilter(fileExtension), 
				TrueFileFilter.INSTANCE);
		
		List<String> strings = new ArrayList<>();
		
		for (File file : files) {
			strings.add(file.getAbsolutePath());
		}
		
		return strings;
	}

	private static List<String> getFilePaths(Scan scan, String fileExtension) {
		if (scan == null || scan.getFindings() == null
				|| scan.getFindings().isEmpty()) {
			return null;
		}

		List<String> returnString = new ArrayList<>();

		for (Finding finding : scan.getFindings()) {
			if (finding.getIsStatic()) {
				List<DataFlowElement> dataFlowElements = finding.getDataFlowElements();
				if (dataFlowElements == null || dataFlowElements.size() == 0)
					continue;

				if (dataFlowElements.get(0) != null
						&& dataFlowElements.get(0).getSourceFileName() != null) {
					
					String sourceFileName = dataFlowElements.get(0).getSourceFileName();
					
					if (fileExtension == null || sourceFileName.endsWith(fileExtension)) {
						returnString.add(dataFlowElements.get(0)
								.getSourceFileName());
					}
				}
			}
		}

		return returnString;
	}

	private static List<String> getUrlPaths(Scan scan) {
		if (scan == null || scan.getFindings() == null
				|| scan.getFindings().isEmpty()) {
			return null;
		}

		List<String> returnStrings = new ArrayList<>();

		for (Finding finding : scan.getFindings()) {
			if (finding != null && finding.getSurfaceLocation() != null
					&& finding.getSurfaceLocation().getPath() != null) {
				returnStrings.add(finding.getSurfaceLocation().getPath());
			}
		}

		return returnStrings;
	}

	private static String parseRoot(List<String> items) {
		if (items == null || items.isEmpty())
			return null;
		
		String response = null;
		
		String[] commonParts = null;
		int maxLength = Integer.MAX_VALUE;
		boolean startsWithCharacter = false;
		
		Pattern splitPattern = null;
		char splitChar = 0;
		
		for (String item : items) {
			if (splitPattern == null) {
				if (item.indexOf('\\') != -1) {
					splitPattern = BACKSLASH_PATTERN;
					splitChar = backwardSlash;
				} else {
					splitPattern = FORWARD_SLASH_PATTERN;
					splitChar = forwardSlash;
				}
				startsWithCharacter = item.indexOf(splitChar) == 0;
			}
			
			String[] parts = splitPattern.split(item);
			
			if (parts.length < maxLength) {
				maxLength = parts.length;
			}
			
			commonParts = getCommonParts(commonParts, parts);
		}
		
		if (commonParts != null) {
			StringBuilder builder = new StringBuilder();
			
			for (String string : commonParts) {
				if (string != null && !string.equals("")) {
					builder.append(splitChar + string);
				}
			}
			
			response = builder.toString();
			
			if (!startsWithCharacter && response.indexOf(splitChar) == 0) {
				response = response.substring(1);
			}
		}
		
		return response;
	}

	private static String[] getCommonParts(String[] soFar, String[] newParts) {
		
		String[] returnParts = newParts;
		
		if (soFar != null && soFar.length == 0) {
			returnParts = soFar;
		} else if (soFar != null) {
			int endIndex = 0;
			
			for (int i = 0; i < soFar.length && i < newParts.length; i++) {
				if (!soFar[i].equals(newParts[i])) {
					break;
				} else {
					endIndex += 1;
				}
			}
			
			if (endIndex == 0) {
				returnParts = new String[]{};
			} else if (endIndex == soFar.length) {
				returnParts = soFar;
			} else {
				returnParts = Arrays.copyOfRange(soFar, 0, endIndex);
			}
		}
		
		return returnParts;
	}
	
}

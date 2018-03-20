package org.ihtsdo.termserver.scripting.util;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ihtsdo.termserver.scripting.GraphLoader;
import org.ihtsdo.termserver.scripting.TermServerScript;
import org.ihtsdo.termserver.scripting.TermServerScriptException;
import org.ihtsdo.termserver.scripting.TermServerScript.ReportActionType;
import org.ihtsdo.termserver.scripting.TermServerScript.Severity;
import org.ihtsdo.termserver.scripting.domain.Concept;
import org.ihtsdo.termserver.scripting.domain.Description;
import org.ihtsdo.termserver.scripting.domain.RF2Constants;
import org.ihtsdo.termserver.scripting.domain.Task;

import com.google.common.base.Charsets;
import com.google.common.io.Files;

/**
 * Class reads in a spreadsheet of terms from a file and uses it to validate
 * active descriptions of concepts
 * @author Peter
 *
 */
public class TermVerifier implements RF2Constants {
	
	Map<Concept, String[]> conceptTermsMap;
	
	public static int idx_sctid = 0;
	public static int idx_fsn = 2;
	public static int idx_us = 3;
	public static int idx_syn = 4;
	public static int idx_gb = 5;
	
	TermServerScript script;
	File inputFile;
	
	public TermVerifier (File inputFile, TermServerScript script) {
		this.inputFile = inputFile;
		this.script = script;
	}
	
	public void init() throws TermServerScriptException {
		conceptTermsMap = new HashMap<>();
		String[] lineItems;
		TermServerScript.info ("Loading term file " + inputFile.getAbsolutePath());
		try {
			List<String> lines = Files.readLines(inputFile, Charsets.UTF_8);
			lines = SnomedUtils.removeBlankLines(lines);
			for (int lineNum = 0; lineNum < lines.size(); lineNum++) {
				if (lineNum == 0) {
					continue; //skip header row  
				}
				lineItems = lines.get(lineNum).replace("\"", "").split(TermServerScript.inputFileDelimiter);
				Concept c = GraphLoader.getGraphLoader().getConcept(lineItems[idx_sctid]);
				conceptTermsMap.put(c, lineItems);
			}
		} catch (Exception e) {
			throw new TermServerScriptException("Failed to load " + inputFile, e);
		}
	}
	
	public void validateTerms(Task t, Concept c) throws TermServerScriptException {
		if (conceptTermsMap.containsKey(c)) {
			String[] terms = conceptTermsMap.get(c);
			validateTerm (t, c, c.getFSNDescription(), terms[idx_fsn], true);
			validateTerm (t, c, c.getPreferredSynonym(US_ENG_LANG_REFSET), terms[idx_us], false);
			validateTerm (t, c, c.getPreferredSynonym(GB_ENG_LANG_REFSET), terms[idx_gb], false);
			
			//And try to find the synonym
			String synonym = fixIssues(terms[idx_gb], false);
			if (c.findTerm(synonym) == null) {
				String msg = "Unable to find suggested synonym: " + synonym;
				script.report(t, c, Severity.HIGH, ReportActionType.VALIDATION_CHECK, msg);
			}
		}
	}

	private void validateTerm(Task t, Concept c, Description d, String suggestedTerm, boolean isFSN) {
		suggestedTerm = fixIssues(suggestedTerm, isFSN);
		if (!d.getTerm().equals(suggestedTerm)) {
			String msg = "Description " + d + " does not match suggested value '" + suggestedTerm + "'";
			script.report(t, c, Severity.HIGH, ReportActionType.VALIDATION_CHECK, msg);
		}
	}

	private String fixIssues(String term, boolean isFSN) {
		//Fix known issues 
		term = term.replace(',', '.');
		
		if (!isFSN) {
			term.replaceAll("milligram", "mg");
		}
		
		term = term.replaceAll(" only ", " precisely ");
		
		//Do we have milligram without a space?
		if (term.contains("milligram") && !term.contains(" milligram ")) {
			term = term.replaceAll("milligram", "milligram ");
			term = term.replace("  ", " ");
		}
		return term;
	}

}

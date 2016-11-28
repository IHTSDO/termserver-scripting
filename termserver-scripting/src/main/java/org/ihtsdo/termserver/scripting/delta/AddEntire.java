package org.ihtsdo.termserver.scripting.delta;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ihtsdo.termserver.scripting.TermServerScriptException;
import org.ihtsdo.termserver.scripting.client.SnowOwlClientException;
import org.ihtsdo.termserver.scripting.domain.Concept;
import org.ihtsdo.termserver.scripting.domain.Description;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;

/**
 * Class to read in a spreadsheet of terms and generate new FSN and PTs 
 * - with associated Acceptability - adding the word "Entire" and
 * inactivate the existing terms.
 */
public class AddEntire extends DeltaGenerator {
	
	enum MODE { FSN_PT, SYN };
	private MODE mode = null;
	
	static Map<String, String> findReplace = new HashMap<String,String>();
	static {
		findReplace.put("Between region joint of vertebral bodies","joint of vertebral bodies between regions");
		findReplace.put("Within region joint of vertebral bodies", "joint of vertebral bodies within region");
	}
	
	static final String ENTIRE = "Entire";

	public static void main(String[] args) throws TermServerScriptException, IOException, SnowOwlClientException, InterruptedException {
		AddEntire delta = new AddEntire();
		try {
			delta.init(args);
			//Recover the current project state from TS (or local cached archive) to allow quick searching of all concepts
			delta.loadProjectSnapshot(false);  //Not just FSN, load all terms with lang refset also
			//We won't incude the project export in our timings
			delta.startTimer();
			delta.processFile();
			SnomedUtils.createArchive(new File(delta.outputDirName));
		} finally {
			delta.finish();
			if (delta.descIdGenerator != null) {
				println(delta.descIdGenerator.finish());
			}
		}
	}
	
	protected void init (String[] args) throws IOException, TermServerScriptException {
		super.init(args);
		
		for (int x=0; x<args.length; x++) {
			if (args[x].equals("-m")) {
				mode = MODE.valueOf(args[++x]);
			}
		}
		if (mode == null) {
			String msg = "Require a mode as a -m argument - FSN_PT or SYN";
			throw new TermServerScriptException(msg);
		}
	}

	protected List<Concept> processFile() throws TermServerScriptException {
		List<Concept> allConcepts = super.processFile();
		//Since our code works through all descriptions for a concept, we can remove duplicate entries of concepts, 
		Set<Concept> uniqueConcepts = new LinkedHashSet<Concept>(allConcepts);
		for (Concept thisConcept : uniqueConcepts) {
			if (!thisConcept.isActive()) {
				report (thisConcept, null, SEVERITY.MEDIUM, REPORT_ACTION_TYPE.VALIDATION_ERROR, "Concept is inactive, skipping");
				
			}
			try {
				switch (mode) {
				case FSN_PT: 	addEntireToFSN(thisConcept);
								addEntireToPrefTerms(thisConcept);
								break;
				case SYN:		addEntireToSynonyms(thisConcept);
				}

			} catch (TermServerScriptException e) {
				report (thisConcept, null, SEVERITY.CRITICAL, REPORT_ACTION_TYPE.API_ERROR, "Exception while processing: " + e.getMessage() + " : " + SnomedUtils.getStackTrace(e));
			}
		}
		return allConcepts;
	}

	private void addEntireToPrefTerms(Concept c) throws TermServerScriptException {
		List<Description> prefTerms = c.getSynonyms(Acceptability.PREFERRED);
		for (Description d : prefTerms) {
			addEntireToTerm(c, d, false, true);
		}
	}

	private void addEntireToFSN(Concept c) throws TermServerScriptException {
		Description fsn = c.getFSNDescription();
		addEntireToTerm(c, fsn, true, true);	
	}
	

	private void addEntireToSynonyms(Concept c) throws TermServerScriptException {
		List<Description> synonyms = c.getSynonyms(Acceptability.ACCEPTABLE);
		for (Description d : synonyms) {
			addEntireToTerm(c, d, false,false);
		}
		
	}

	private void addEntireToTerm(Concept c, Description d, boolean isFSN, boolean isPT) throws TermServerScriptException {

		//We only work with active terms
		if (d == null) {
			report (c,d,SEVERITY.HIGH, REPORT_ACTION_TYPE.VALIDATION_ERROR, "No active " + (isFSN?"FSN":"SYN") + " found for concept");
			return;
		}
		
		String[] newTermParts = new String[2];
		if (isFSN) {
			newTermParts = SnomedUtils.deconstructFSN(d.getTerm());
		} else {
			newTermParts[0] = d.getTerm();
		}
		
		//Do we in fact need to do anything?
		if (newTermParts[0].toLowerCase().contains(ENTIRE.toLowerCase())) {
			report (c,d,SEVERITY.NONE, REPORT_ACTION_TYPE.NO_CHANGE, "Term already contains 'entire'");
			return;
		}
		
		//Does this term match any of our search and replace cases?
		for (Map.Entry<String, String> entry : findReplace.entrySet()) {
			if (newTermParts[0].equals(entry.getKey())) {
				newTermParts[0] = entry.getValue();
			}
		}
		
		//If the entire term is case sensitive, then we don't want to decapitalize the first letter
		boolean caseSensitive = d.getCaseSignificance().equals(ENITRE_TERM_CASE_SENSITIVE);
		String newTerm = ENTIRE + " " + (caseSensitive? newTermParts[0]:SnomedUtils.deCapitalize(newTermParts[0]));
		if (isFSN) {
			newTerm += " " + newTermParts[1];
		}
		
		//Because we're adding a word that is not case sensitive, we'll sent the case significance
		//to 900000000000020002 |Only initial character case insensitive (core metadata concept)|
		replaceDescription (c,d,newTerm, ONLY_INITIAL_CHAR_CASE_INSENSITIVE, isPT);
	}

	private void replaceDescription(Concept c, Description d, String newTerm, String newCaseSignificance, boolean isPT) throws TermServerScriptException {
		
		if (!d.isActive()) {
			String msg = "Attempting to inactivate an already inactive description";
			report (c,d,SEVERITY.HIGH, REPORT_ACTION_TYPE.API_ERROR, msg);
			return;
		}
		
		//Do we already have this description, even inactivated?
		Description duplicate = null;
		for (Description thisDesc : c.getDescriptions(ActiveState.ACTIVE)) {
			if (thisDesc.getTerm().equalsIgnoreCase(newTerm)) {
				//Have we already found a duplicate?
				if (duplicate != null) {
					report (c,duplicate,SEVERITY.CRITICAL, REPORT_ACTION_TYPE.VALIDATION_ERROR, "Multiple matching active duplicates found!");
				}
				duplicate = thisDesc;
			}
		}
		
		if (duplicate == null) {
			for (Description thisDesc : c.getDescriptions(ActiveState.INACTIVE)) {
				if (thisDesc.getTerm().equalsIgnoreCase(newTerm)) {
					//Have we already found a duplicate?
					if (duplicate != null) {
						report (c,duplicate,SEVERITY.CRITICAL, REPORT_ACTION_TYPE.VALIDATION_ERROR, "Multiple matching inactivate duplicates found!");
					}
					duplicate = thisDesc;
				}
			}
		}
		
		if (duplicate == null) {
			String newSCTID = descIdGenerator.getSCTID(PartionIdentifier.DESCRIPTION);
			Description replacement = d.clone(newSCTID);
			replacement.setTerm(newTerm);
			replacement.setCaseSignificance(newCaseSignificance);
			c.addDescription(replacement);
			report (c,replacement,SEVERITY.MEDIUM, REPORT_ACTION_TYPE.DESCRIPTION_CHANGE_MADE, "Added new Description");
			outputRF2(replacement);
		} else {
			SEVERITY severity = SEVERITY.MEDIUM;
			REPORT_ACTION_TYPE action = REPORT_ACTION_TYPE.DESCRIPTION_CHANGE_MADE;
			//If the duplicate is inactive, we need to activate it.
			String duplicateMsg = "";
			if (!duplicate.isActive()) {
				duplicate.setActive(true);
				duplicateMsg = "Reactivated duplicate term and ";
			}

			//We need to merge the Acceptability of the previous term and that of the duplicate
			//so that we get the best of both.  This might promote an acceptable term to a preferred one.
			if (SnomedUtils.mergeLangRefsetEntries(d, duplicate)) {
				duplicateMsg += "Modified duplicate term's lang refset entries - " + duplicate;
				severity = SEVERITY.HIGH;
			}
			
			if (duplicateMsg.isEmpty()) {
				duplicateMsg="No changes needed to duplicate - " + duplicate;
				action = REPORT_ACTION_TYPE.NO_CHANGE;
			}
			report (c,d,severity,action, duplicateMsg);
			outputRF2(duplicate);
		}
		d.setActive(false);
		report (c,d,SEVERITY.MEDIUM, REPORT_ACTION_TYPE.DESCRIPTION_CHANGE_MADE, "Inactivated Description");
		outputRF2(d);
	}

	@Override
	public String getScriptName() {
		return "Add Entire";
	}

	@Override
	protected Concept loadLine(String[] lineItems)
			throws TermServerScriptException {
		Concept c = graph.getConcept(lineItems[0]);
		return c;
	}
}
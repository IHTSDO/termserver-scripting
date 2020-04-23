package org.ihtsdo.termserver.scripting.fixes;

import java.io.IOException;
import java.util.*;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Task;
import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.GraphLoader;
import org.ihtsdo.termserver.scripting.client.TermServerClient;

import org.ihtsdo.termserver.scripting.domain.*;

import us.monoid.json.JSONException;
import us.monoid.json.JSONObject;

/*
Checks that all inactive concepts have no more than one active inactivation indicator, and attempts to 
inactivate the surplus.
* NB This fix fails because the Refset Endpoint only works for Simple Refsets.
* Reworking this code to generate an RF2 Delta archive for import.
 */
public class InactivateDuplicateInactivationIndicators_fail extends BatchFix implements RF2Constants{
	
	protected InactivateDuplicateInactivationIndicators_fail(BatchFix clone) {
		super(clone);
	}

	public static void main(String[] args) throws TermServerScriptException, IOException, InterruptedException {
		InactivateDuplicateInactivationIndicators_fail fix = new InactivateDuplicateInactivationIndicators_fail(null);
		try {
			fix.selfDetermining = true;
			fix.populateEditPanel = false;
			TermServerClient.supportsIncludeUnpublished = false;   //This code not yet available in MS
			fix.tsRoot="MAIN/2017-01-31/SNOMEDCT-US/";
			fix.init(args);
			//Recover the current project state from TS (or local cached archive) to allow quick searching of all concepts
			fix.loadProjectSnapshot(false); //Load all descriptions
			//We won't include the project export in our timings
			fix.processFile();
		} finally {
			fix.finish();
		}
	}

	@Override
	public int doFix(Task t, Concept c, String info) throws TermServerScriptException {
		//Concept loadedConcept = loadConcept(concept, task.getBranchPath());  
		//We don't need the browser version of the concept in this case since we're working with the 
		//Refset endpoint.
		int changesMade = fixInactivationIndicators(t, c);
		if (changesMade > 0) {
			for (InactivationIndicatorEntry i : c.getInactivationIndicatorEntries()) {
				if (i.isDirty() && !i.isActive()) {
					try {
						inactivateRefsetMember(t, c, i, info);
					} catch (Exception e) {
						report(t, c, Severity.CRITICAL, ReportActionType.API_ERROR, "Failed to save changed inactivation indicator " + i + " to TS: " + ExceptionUtils.getStackTrace(e));
					}
				}
			}
		}
		return changesMade;
	}

	private int fixInactivationIndicators(Task task, Concept concept) throws TermServerScriptException {
		//If we have both INT and Extension active inactivation indicators, inactivate all the extension indicators.
		//If we have no INT but multiple Extension inactive indicators, list as a critical validation error for the moment.
		int coreIndicators = 0;
		int extensionIndicators = 0;
		int changesMade = 0;
		
		List<InactivationIndicatorEntry> activeIndicators = concept.getInactivationIndicatorEntries(ActiveState.ACTIVE);
		
		for (InactivationIndicatorEntry i : activeIndicators) {
			if (i.getModuleId().equals(SCTID_CORE_MODULE)) {
				coreIndicators++;
			} else extensionIndicators ++;
		}	
			
		if (coreIndicators > 1) {
			report(task, concept, Severity.CRITICAL, ReportActionType.VALIDATION_CHECK, coreIndicators + " active inactivation indicators in core module.  Skipping.");
		} else if (coreIndicators == 1) {
			for (InactivationIndicatorEntry i : activeIndicators) {
				if (!i.getModuleId().equals(SCTID_CORE_MODULE)) {
					i.setActive(false);
					String msg = "Inactivated " + i;
					report(task, concept, Severity.LOW, ReportActionType.REFSET_MEMBER_REMOVED, msg);
					changesMade++;
				} 
			}	
		} else if (extensionIndicators > 1) {
			for (InactivationIndicatorEntry i : activeIndicators) {
				String reason = GraphLoader.getGraphLoader().getConcept(i.getInactivationReasonId()).toString();
				String msg = "Multiple active extension inativation indicators detected: " + reason + ". Skipping...";
				report(task, concept, Severity.CRITICAL, ReportActionType.VALIDATION_CHECK, msg);
			}
		}
		return changesMade;
	}

	protected List<Component> identifyComponentsToProcess() throws TermServerScriptException {
		Collection<Concept> allPotential = GraphLoader.getGraphLoader().getAllConcepts();
		Set<Concept> allAffected = new TreeSet<Concept>();  //We want to process in the same order each time, in case we restart and skip some.
		for (Concept thisConcept : allPotential) {
			if (!thisConcept.isActive()) {
				List<InactivationIndicatorEntry> activeInactivationIndicators = thisConcept.getInactivationIndicatorEntries(ActiveState.ACTIVE);
				if (activeInactivationIndicators.size() > 1) {
					allAffected.add(thisConcept);
				}
			}
		}
		return new ArrayList<Component>(allAffected);
	}

	@Override
	protected List<Component> loadLine(String[] lineItems) throws TermServerScriptException {
		return null; // We will identify descriptions to edit from the snapshot
	}
}

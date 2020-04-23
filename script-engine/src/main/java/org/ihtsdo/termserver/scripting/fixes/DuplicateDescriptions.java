package org.ihtsdo.termserver.scripting.fixes;

import java.io.IOException;
import java.util.*;

import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Task;
import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ValidationFailure;

import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.fixes.BatchFix;


/* INFRA-4133 Fix issue of multiple descriptions with the same term - unpublished and active
 * Also seeing empty terms and "GBTERM:null"
 */
public class DuplicateDescriptions extends BatchFix implements RF2Constants{
	
	Set<Description> duplicateDescriptions = new HashSet<>();
	
	protected DuplicateDescriptions(BatchFix clone) {
		super(clone);
	}

	public static void main(String[] args) throws TermServerScriptException, IOException, InterruptedException {
		DuplicateDescriptions fix = new DuplicateDescriptions(null);
		try {
			ReportSheetManager.targetFolderId = "1fIHGIgbsdSfh5euzO3YKOSeHw4QHCM-m";  //Ad-Hoc Batch Updates
			//fix.runStandAlone = false;  //Was causing issues with historical associations not being set
			fix.selfDetermining = true;
			fix.getArchiveManager().setPopulateReleasedFlag(true);
			fix.init(args);
			//Recover the current project state from TS (or local cached archive) to allow quick searching of all concepts
			fix.loadProjectSnapshot(true); //Just 
			fix.postInit();
			fix.processFile();
		} finally {
			fix.finish();
		}
	}

	@Override
	public int doFix(Task task, Concept concept, String info) throws TermServerScriptException, ValidationFailure {
		Concept tsConcept = loadConcept(concept, task.getBranchPath());
		int changesMade = removeDuplicateDescriptions(task, tsConcept);
		if (changesMade > 0) {
			updateConcept(task, tsConcept, info);
		}
		return changesMade;
	}

	private int removeDuplicateDescriptions(Task t, Concept c) throws TermServerScriptException {
		int changesMade = 0;
		List<Description> descriptions = new ArrayList<>(c.getDescriptions());
		for (Description d : descriptions) {
			if (duplicateDescriptions.contains(d) || d.getTerm().isEmpty() || d.getTerm().contains("null")) {
				removeDescription(t, c, d, InactivationIndicator.NONCONFORMANCE_TO_EDITORIAL_POLICY);
				changesMade++;
			}
		}
		return changesMade;
	}

	@Override
	protected List<Component> identifyComponentsToProcess() throws TermServerScriptException {
		info ("Identifying concepts to process");
		List<Concept> processMe = new ArrayList<>();
		Set<String> termsKnown = new HashSet<>();
		
		//for (Concept c : gl.getAllConcepts()) {
		//for (Concept c : Collections.singletonList(gl.getConcept("14311001"))) {
		for (Concept c : MEDICINAL_PRODUCT.getDescendents(NOT_SET)) {
			if (!c.isReleased()) {
				termsKnown.clear();
				for (Description d : c.getDescriptions()) {
					if (d.getTerm().isEmpty() || d.getTerm().contains("null")) {
						processMe.add(c);
						break;
					} else if (termsKnown.contains(d.getTerm())) {
						duplicateDescriptions.add(d);
						processMe.add(c);
						break;
					}
					termsKnown.add(d.getTerm());
				}
			}
		}
		
		info ("Identified " + processMe.size() + " concepts to process");
		processMe.sort(Comparator.comparing(Concept::getFsn));
		return new ArrayList<Component>(processMe);
	}

}

package org.ihtsdo.termserver.scripting.fixes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.ihtsdo.termserver.scripting.TermServerScriptException;
import org.ihtsdo.termserver.scripting.client.SnowOwlClientException;
import org.ihtsdo.termserver.scripting.domain.Concept;
import org.ihtsdo.termserver.scripting.domain.RF2Constants;
import org.ihtsdo.termserver.scripting.domain.Relationship;
import org.ihtsdo.termserver.scripting.domain.Task;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;

import us.monoid.json.JSONObject;

/*
For DRUG-422, DRUG-431
Driven by a text file of concepts, move specified concepts to exist under
a parent concept.
*/
public class ReplaceParents extends BatchFix implements RF2Constants{
	
	Relationship newParentRel;
	String newParent = "763158003"; // |Medicinal product (product)| 
	
	protected ReplaceParents(BatchFix clone) {
		super(clone);
	}

	public static void main(String[] args) throws TermServerScriptException, IOException, SnowOwlClientException, InterruptedException {
		ReplaceParents fix = new ReplaceParents(null);
		try {
			fix.reportNoChange = true;
			fix.populateEditPanel = false;
			fix.populateTaskDescription = true;
			fix.additionalReportColumns = "ACTION_DETAIL, DEF_STATUS, PARENT_COUNT, ATTRIBUTE_COUNT";
			fix.init(args);
			//Recover the current project state from TS (or local cached archive) to allow quick searching of all concepts
			fix.loadProjectSnapshot(true); 
			fix.postLoadInit();
			fix.startTimer();
			fix.processFile();
			info ("Processing complete.  See results: " + fix.reportFile.getAbsolutePath());
		} finally {
			fix.finish();
		}
	}

	private void postLoadInit() throws TermServerScriptException {
		Concept parentConcept =  gl.getConcept(newParent);
		newParentRel = new Relationship(null, IS_A, parentConcept, 0);
	}

	@Override
	public int doFix(Task task, Concept concept, String info) throws TermServerScriptException {
		
		Concept loadedConcept = loadConcept(concept, task.getBranchPath());
		int changesMade = replaceParents(task, loadedConcept);
		
		//All concepts should be fully defined, if possible
		if (loadedConcept.getDefinitionStatus().equals(DefinitionStatus.PRIMITIVE)) {
			if (countAttributes(loadedConcept) > 0) {
				loadedConcept.setDefinitionStatus(DefinitionStatus.FULLY_DEFINED);
				changesMade++;
				report (task, loadedConcept, Severity.LOW, ReportActionType.CONCEPT_CHANGE_MADE, "Concept marked as fully defined");
			} else {
				report (task, loadedConcept, Severity.HIGH, ReportActionType.VALIDATION_CHECK, "Unable to mark fully defined - no attributes!");
			}
		}
		
		try {
			String conceptSerialised = gson.toJson(loadedConcept);
			debug ((dryRun ?"Dry run ":"Updating state of ") + loadedConcept + info);
			if (!dryRun) {
				tsClient.updateConcept(new JSONObject(conceptSerialised), task.getBranchPath());
			}
		} catch (Exception e) {
			report(task, concept, Severity.CRITICAL, ReportActionType.API_ERROR, "Failed to save changed concept to TS: " + ExceptionUtils.getStackTrace(e));
		}
		return changesMade;
	}

	private int replaceParents(Task task, Concept loadedConcept) throws TermServerScriptException {
		
		int changesMade = 0;
		List<Relationship> parentRels = new ArrayList<Relationship> (loadedConcept.getRelationships(CharacteristicType.STATED_RELATIONSHIP, 
																		IS_A,
																		ActiveState.ACTIVE));
		String parentCount = Integer.toString(parentRels.size());
		String attributeCount = Integer.toString(countAttributes(loadedConcept));
		
		String semTag = SnomedUtils.deconstructFSN(loadedConcept.getFsn())[1];
		switch (semTag) {
			case "(medicinal product form)" : loadedConcept.setConceptType(ConceptType.MEDICINAL_PRODUCT_FORM);
												break;
			case "(product)" : loadedConcept.setConceptType(ConceptType.PRODUCT);
								break;
			case "(medicinal product)" : loadedConcept.setConceptType(ConceptType.MEDICINAL_PRODUCT);
										 break;
			case "(clinical drug)" : loadedConcept.setConceptType(ConceptType.CLINICAL_DRUG);
										break;
			default : loadedConcept.setConceptType(ConceptType.UNKNOWN);
		}
		
		boolean replacementNeeded = true;
		for (Relationship parentRel : parentRels) {
			if (!parentRel.equals(newParentRel)) {
				removeParentRelationship (task, parentRel, loadedConcept, newParentRel.getTarget().toString(), null);
				changesMade++;
			} else {
				replacementNeeded = false;
			}
		}
		
		if (replacementNeeded) {
			Relationship thisNewParentRel = newParentRel.clone(null);
			thisNewParentRel.setSource(loadedConcept);
			loadedConcept.addRelationship(thisNewParentRel);
			changesMade++;
			String msg = "Single parent set to " + newParent;
			report (task, loadedConcept, Severity.LOW, ReportActionType.RELATIONSHIP_INACTIVATED, msg, loadedConcept.getDefinitionStatus().toString(), parentCount, attributeCount);
		}
		return changesMade;
	}

	private Integer countAttributes(Concept c) {
		int attributeCount = 0;
		for (Relationship r : c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, ActiveState.ACTIVE)) {
			if (!r.getType().equals(IS_A)) {
				attributeCount++;
			}
		}
		return attributeCount;
	}

	@Override
	protected List<Concept> loadLine(String[] lineItems) throws TermServerScriptException {
		Concept c = gl.getConcept(lineItems[0]);
		return Collections.singletonList(c);
	}

}

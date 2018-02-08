package org.ihtsdo.termserver.scripting.fixes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.ihtsdo.termserver.scripting.TermServerScriptException;
import org.ihtsdo.termserver.scripting.client.SnowOwlClientException;
import org.ihtsdo.termserver.scripting.domain.Component;
import org.ihtsdo.termserver.scripting.domain.Concept;
import org.ihtsdo.termserver.scripting.domain.RF2Constants;
import org.ihtsdo.termserver.scripting.domain.Relationship;
import org.ihtsdo.termserver.scripting.domain.RelationshipGroup;
import org.ihtsdo.termserver.scripting.domain.Task;
import org.ihtsdo.termserver.scripting.fixes.BatchFix;

/*

*/
public class SplitRoleGroupsWithRepeatedAttributes extends BatchFix implements RF2Constants{
	
	Concept subHierarchy;
	List<Concept> attributesToSplit;
	
	protected SplitRoleGroupsWithRepeatedAttributes(BatchFix clone) {
		super(clone);
	}

	public static void main(String[] args) throws TermServerScriptException, IOException, SnowOwlClientException, InterruptedException {
		SplitRoleGroupsWithRepeatedAttributes fix = new SplitRoleGroupsWithRepeatedAttributes(null);
		try {
			fix.inputFileHasHeaderRow = true;
			fix.selfDetermining = true;
			fix.reportNoChange = true;
			fix.populateEditPanel = false;
			fix.populateTaskDescription = true;
			fix.additionalReportColumns = "GroupId, RepeatedAttribute";
			fix.runStandAlone = true;
			fix.init(args);
			//Recover the current project state from TS (or local cached archive) to allow quick searching of all concepts
			fix.loadProjectSnapshot(true); 
			fix.postLoadInit();
			fix.startTimer();
			fix.processFile();
			println ("Processing complete.  See results: " + fix.reportFile.getAbsolutePath());
		} finally {
			fix.finish();
		}
	}

	private void postLoadInit() throws TermServerScriptException {
		subHierarchy = gl.getConcept("46866001"); // |Fracture of lower limb (disorder)|
		attributesToSplit = new ArrayList<Concept>();
		attributesToSplit.add(gl.getConcept("116676008")); // |Associated morphology (attribute)|"))
		attributesToSplit.add(gl.getConcept("363698007")); // |Finding site (attribute)|
		attributesToSplit.add(gl.getConcept("246075003")); // |Causative agent (attribute)|
	}
	
	protected List<Component> identifyComponentsToProcess() throws TermServerScriptException {
		Set<Concept> concepts = subHierarchy.getDescendents(NOT_SET);
		List<Component> componentsToProcess = new ArrayList<>();
		for (Concept c : concepts) {
			if (hasRepeatedAttributeType(c).size() > 0) {
				componentsToProcess.add(c);
			}
		}
		//TODO Check for intermediate primitives
		return componentsToProcess;
	}
	
	protected Set<RelationshipGroup> hasRepeatedAttributeType (Concept c) {
		Set<RelationshipGroup> repeatedAttributeDetected = new HashSet<>();
		Set<Concept> attributeDetected = new HashSet<>();
		for (RelationshipGroup g : c.getRelationshipGroups(CharacteristicType.STATED_RELATIONSHIP, ActiveState.ACTIVE)) {
			attributeDetected.clear();
			for (Relationship r : g.getRelationships()) {
				//Is this an attribute of interest?
				if (attributesToSplit.contains(r.getType())) {
					//Have we already seen it in this group?  Report if so, otherwise record sighting.
					if (attributeDetected.contains(r.getType())) {
						repeatedAttributeDetected.add(g);
						g.addIssue(r.getType());
					} else {
						attributeDetected.add(r.getType());
					}
				}
			}
		}
		return repeatedAttributeDetected;
	}

	@Override
	public int doFix(Task task, Concept concept, String info) throws TermServerScriptException {
		
		Concept loadedConcept = loadConcept(concept, task.getBranchPath());
		if (!loadedConcept.isActive()) {
			report (task, loadedConcept, Severity.HIGH, ReportActionType.VALIDATION_CHECK, "Concept is recently inactive - skipping");
			return 0;
		}
		fixRepeatedAttributesInGroup(task, loadedConcept);
		
		/*for (Concept thisModifiedConcept : modifiedConcepts) {
			try {
				String conceptSerialised = gson.toJson(thisModifiedConcept);
				debug ((dryRun ?"Dry run ":"Updating state of ") + thisModifiedConcept + info);
				if (!dryRun) {
					tsClient.updateConcept(new JSONObject(conceptSerialised), task.getBranchPath());
				}
			} catch (Exception e) {
				report(task, concept, Severity.CRITICAL, ReportActionType.API_ERROR, "Failed to save changed concept to TS: " + ExceptionUtils.getStackTrace(e));
			}
		}
		incrementSummaryInformation("Concepts Modified", modifiedConcepts.size());
		incrementSummaryInformation(task.getKey(), modifiedConcepts.size());
		return modifiedConcepts.size();*/
		return 1;
	}

	private void fixRepeatedAttributesInGroup(Task t, Concept loadedConcept) {
		for (RelationshipGroup g : hasRepeatedAttributeType(loadedConcept)) {
			//What types have we noted as an issue here?
			for (Concept repeatedAttributeType : g.getIssue()) {
				report(t, loadedConcept, Severity.LOW, ReportActionType.INFO, Long.toString(g.getGroupId()), repeatedAttributeType.toString());
			}
		}
	}

	@Override
	protected Concept loadLine(String[] lineItems) throws TermServerScriptException {
		// TODO Auto-generated method stub
		return null;
	}

}
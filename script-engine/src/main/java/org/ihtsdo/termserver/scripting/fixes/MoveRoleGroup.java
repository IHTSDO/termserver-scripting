package org.ihtsdo.termserver.scripting.fixes;

import java.io.IOException;
import java.util.*;

import org.ihtsdo.otf.rest.client.terminologyserver.pojo.*;
import org.ihtsdo.otf.exception.TermServerScriptException;

import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;

/*
 * DEVICES-114 Concepts were given attributes as self grouped, which need to be changed to ungrouped
 */
public class MoveRoleGroup extends BatchFix implements RF2Constants {
	Set<Concept> targetAttributeValues;
	CharacteristicType charType = CharacteristicType.STATED_RELATIONSHIP;
	
	protected MoveRoleGroup(BatchFix clone) {
		super(clone);
	}

	public static void main(String[] args) throws TermServerScriptException, IOException, InterruptedException {
		MoveRoleGroup fix = new MoveRoleGroup(null);
		try {
			ReportSheetManager.targetFolderId = "1fIHGIgbsdSfh5euzO3YKOSeHw4QHCM-m";  //Ad-hoc batch updates
			fix.selfDetermining = true;
			fix.populateEditPanel = false;
			fix.groupByIssue = true;
			fix.init(args);
			fix.loadProjectSnapshot(true);
			fix.postInit();
			fix.processFile();
		} finally {
			fix.finish();
		}
	}
	
	public void postInit() throws TermServerScriptException {
		subHierarchy = DEVICE;
		targetAttributeValues = new HashSet<>();
		targetAttributeValues.add(gl.getConcept("261029002 |Sterile (qualifier value)|")); 
		targetAttributeValues.add(gl.getConcept("863956004 |Non-sterile (qualifier value)|"));
		super.postInit();
	}


	@Override
	public int doFix(Task t, Concept c, String info) throws TermServerScriptException {
		Concept loadedConcept = loadConcept(c, t.getBranchPath());
		int changesMade = moveRoleGroup(t, loadedConcept);
		if (changesMade > 0) {
			updateConcept(t, loadedConcept, info);
		}
		return changesMade;
	}
	
	private int moveRoleGroup(Task t, Concept c) throws TermServerScriptException {
		int changes = 0;
		for (Relationship r : c.getRelationships(charType, ActiveState.ACTIVE)) {
			for (Concept targetValue : targetAttributeValues) {
				if (r.getTarget().equals(targetValue) && r.getGroupId() != UNGROUPED) {
					int currentGroup = r.getGroupId();
					r.setGroupId(UNGROUPED);
					report(t, c, Severity.LOW, ReportActionType.RELATIONSHIP_MODIFIED, "Relationship moved from " + currentGroup + " to ungrouped", r);
					changes++;
				}
			}
		}
		return changes;
	}

	@Override
	protected List<Component> identifyComponentsToProcess() throws TermServerScriptException {
		info ("Identifying concepts to process");
		List<Concept> processMe = new ArrayList<>();
		for (Concept c : subHierarchy.getDescendents(NOT_SET)) {
			for (Concept value : targetAttributeValues) {
				if (SnomedUtils.hasValue(charType, c, value)) {
					c.addIssue(value.getPreferredSynonym());
					processMe.add(c);
				}
			}
		}
		info ("Identified " + processMe.size() + " concepts to process");
		processMe.sort(Comparator.comparing(Concept::getFsn));
		return new ArrayList<Component>(processMe);
	}
}

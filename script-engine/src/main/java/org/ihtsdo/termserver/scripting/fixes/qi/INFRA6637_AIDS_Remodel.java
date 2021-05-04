package org.ihtsdo.termserver.scripting.fixes.qi;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.*;
import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ValidationFailure;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.fixes.BatchFix;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;

/**
 *INFRA-6637 Re-terming and remodel of AIDS concepts
 */
public class INFRA6637_AIDS_Remodel extends BatchFix {

	private RelationshipGroupTemplate[] addGroupTemplates;
	
	private List<Concept> removeTypes = new ArrayList<>();
	
	protected INFRA6637_AIDS_Remodel(BatchFix clone) {
		super(clone);
	}

	public static void main(String[] args) throws TermServerScriptException, IOException, InterruptedException {
		INFRA6637_AIDS_Remodel fix = new INFRA6637_AIDS_Remodel(null);
		try {
			ReportSheetManager.targetFolderId = "1fIHGIgbsdSfh5euzO3YKOSeHw4QHCM-m";  //Ad-hoc batch updates
			fix.populateEditPanel = false;
			fix.populateTaskDescription = false;
			fix.selfDetermining = true;
			fix.reportNoChange = true;
			fix.additionalReportColumns = "Action Detail";
			fix.init(args);
			fix.getArchiveManager().setPopulateReleasedFlag(true);
			fix.loadProjectSnapshot(false);
			fix.postLoadInit();
			fix.processFile();
		} finally {
			fix.finish();
		}
	}

	private void postLoadInit() throws TermServerScriptException {
		subsetECL = "<420721002 |Acquired immunodeficiency syndrome-associated disorder (disorder)|";
		
		addGroupTemplates = new RelationshipGroupTemplate[2];
		addGroupTemplates[0] = RelationshipGroupTemplate.constructGroup(
				gl,
				new Object[] {CAUSE_AGENT, "19030005 |Human immunodeficiency virus (organism)|"},
				new Object[] {PATHOLOGICAL_PROCESS, "441862004 |Infectious process (qualifier value)|"},
				new Object[] {FINDING_SITE, "116003000 |Structure of immune system (body structure)|"});
		
		addGroupTemplates[1] = RelationshipGroupTemplate.constructGroup(
				gl,
				new Object[] {PATHOLOGICAL_PROCESS, "769247005 |Abnormal immune process (qualifier value)|"});
		
		removeTypes.add(ASSOC_WITH);
		
		super.postInit();
	}

	@Override
	public int doFix(Task task, Concept concept, String info) throws TermServerScriptException {
		int changesMade = 0;
		try {
			Concept loadedConcept = loadConcept(concept, task.getBranchPath());
			changesMade = modifyDescriptions(task, loadedConcept);
			changesMade = removeRelationships(task, loadedConcept, ASSOC_WITH, NOT_SET);
			changesMade += addAttribute(task, loadedConcept);
			if (changesMade > 0) {
				updateConcept(task, loadedConcept, info);
			}
		} catch (ValidationFailure v) {
			report(task, concept, v);
		} catch (Exception e) {
			report(task, concept, Severity.CRITICAL, ReportActionType.API_ERROR, "Failed to save changed concept to TS: " + ExceptionUtils.getStackTrace(e));
		}
		return changesMade;
	}

	private int modifyDescriptions(Task t, Concept c) throws TermServerScriptException {
		int changesMade = 0;
		List<Description> originalDescriptions = new ArrayList<>(c.getDescriptions(ActiveState.ACTIVE));
		for (Description d : originalDescriptions) {
			String replacement = d.getTerm();
			boolean changeMade = false;
			if (replacement.contains(" associated ")) {
				replacement = replacement.replaceAll(" associated ", " ");
				changeMade = true;
			}
			
			if (replacement.contains("-associated")) {
				replacement = replacement.replaceAll("-associated", "");
				changeMade = true;
			}
			
			if (replacement.contains("AIDS") && !replacement.toLowerCase().contains("acquired immunodeficiency syndrome")) {
				if (replacement.contains("AIDS-")) {
					replacement = replacement.replaceAll("AIDS-", "AIDS ");
				}
				replacement = replacement.replaceAll("AIDS", "AIDS (acquired immunodeficiency syndrome)");
				changeMade = true;
			}
			
			if (changeMade) {
				replaceDescription(t, c, d, replacement, InactivationIndicator.NONCONFORMANCE_TO_EDITORIAL_POLICY, false);
				changesMade++; 
			}
		}
		return changesMade;
	}


	private int addAttribute(Task t, Concept c) throws TermServerScriptException {
		int changesMade = 0;
		for (RelationshipGroupTemplate rgt : addGroupTemplates) {
			int groupId = determineBestGroup(c, rgt);
			changesMade += addToConcept(t, c, groupId, rgt);
		}
		return changesMade;
	}

	private int addToConcept(Task t, Concept c, int groupId, RelationshipGroupTemplate rgt) throws TermServerScriptException {
		int changesMade = 0;
		for (RelationshipTemplate rt : rgt.getRelationships()) {
			changesMade += addRelationship(t, c, rt, groupId);
		}
		return changesMade;
	}

	private int determineBestGroup(Concept c, RelationshipGroupTemplate rgt) {
		//Do we have any existing groups that feature any of the relationships in our template group?
		//However if they feature any relationships of the same type but DIFFERENT target, then we can't use them
		Integer potentialMatch = null;
		nextGroup:
		for (RelationshipGroup g : c.getRelationshipGroups(CharacteristicType.STATED_RELATIONSHIP)) {
			for (RelationshipTemplate rt : rgt.getRelationships()) {
				if (g.containsTypeValue(rt)) {
					potentialMatch = g.getGroupId();
				} else if (g.containsType(rt)) {
					//If it has the type but not the value, we can't use this group
					potentialMatch = null;
					continue nextGroup;
				}
			}
		}
		//Otherwise we'll just return the next available group number
		return potentialMatch == null ? SnomedUtils.getFirstFreeGroup(c) : potentialMatch;
		
	}

	private boolean isExcluded(Concept c) {
		String fsn = " " + c.getFsn().toLowerCase();
		return isExcluded(fsn);
	}
	
	private boolean isExcluded(String term) {
		return false;
	}


	@Override
	protected List<Component> identifyComponentsToProcess() throws TermServerScriptException {
		return findConcepts(subsetECL)
				.stream()
				.filter(c -> !isExcluded(c))
				.collect(Collectors.toList());
	}
}

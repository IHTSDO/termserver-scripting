package org.ihtsdo.termserver.scripting.fixes;

import java.io.IOException;
import java.util.*;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.*;
import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ValidationFailure;

import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.fixes.BatchFix;

/**
 * Devices-99 A set of tickets adding attributes to concepts based on matching terms in the FSN
 */
public class AddAttributeOnLexicalMatch extends BatchFix {
	
	Concept subHierarchy;
	Map<String, RelationshipTemplate> searchTermAttributeMap;
	Set<String> exclusions;

	protected AddAttributeOnLexicalMatch(BatchFix clone) {
		super(clone);
	}

	public static void main(String[] args) throws TermServerScriptException, IOException, InterruptedException {
		AddAttributeOnLexicalMatch fix = new AddAttributeOnLexicalMatch(null);
		try {
			ReportSheetManager.targetFolderId = "1fIHGIgbsdSfh5euzO3YKOSeHw4QHCM-m";  //Ad-hoc batch updates
			fix.populateEditPanel = false;
			fix.selfDetermining = true;
			fix.reportNoChange = true;
			fix.additionalReportColumns = "Action Detail";
			fix.init(args);
			fix.loadProjectSnapshot(true);
			fix.postLoadInit();
			fix.processFile();
		} finally {
			fix.finish();
		}
	}

	private void postLoadInit() throws TermServerScriptException {
		subHierarchy = gl.getConcept("260787004 |Physical object (physical object)|");
		exclusions = new HashSet<>();
		exclusions.add(" kit");
		exclusions.add(" set");
		exclusions.add("(product)");
		//Order of terms is important since we'll search for the longest first to prevent
		//partial matches
		searchTermAttributeMap = new LinkedHashMap<>();
		/*DEVICES-109
		searchTermAttributeMap.put("non-sterile", new RelationshipTemplate(HAS_DEVICE_CHARAC, gl.getConcept("863956004 |Non-sterile (qualifier value)|")));
		searchTermAttributeMap.put("sterile", new RelationshipTemplate(HAS_DEVICE_CHARAC, gl.getConcept("261029002 |Sterile (qualifier value)|")));
		*/
		
		/*DEVICES-111
		searchTermAttributeMap.put("non-bioabsorbable", new RelationshipTemplate(HAS_DEVICE_CHARAC, gl.getConcept("863965006 |Nonbioabsorbable (qualifier value)|")));
		searchTermAttributeMap.put("partially-bioabsorbable", new RelationshipTemplate(HAS_DEVICE_CHARAC, gl.getConcept("863968008 |Partially bioabsorbable (qualifier value)|")));
		searchTermAttributeMap.put("non-absorbable", new RelationshipTemplate(HAS_DEVICE_CHARAC, gl.getConcept("863967003 |Nonabsorbable (qualifier value)|")));
		searchTermAttributeMap.put("bioabsorbable", new RelationshipTemplate(HAS_DEVICE_CHARAC, gl.getConcept("860574003 |Bioabsorbable (qualifier value)|")));
		searchTermAttributeMap.put("absorbable", new RelationshipTemplate(HAS_DEVICE_CHARAC, gl.getConcept("863966007 |Absorbable (qualifier value)")));
		*/
		
		//DEVICES-115
		searchTermAttributeMap.put("custom-made", new RelationshipTemplate(HAS_DEVICE_CHARAC, gl.getConcept("860573009 |Custom-made (qualifier value)| ")));
		
		super.postInit();
	}

	@Override
	public int doFix(Task task, Concept concept, String info) throws TermServerScriptException {
		int changesMade = 0;
		try {
			Concept loadedConcept = loadConcept(concept, task.getBranchPath());
			changesMade = addAttribute(task, loadedConcept);
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

	private int addAttribute(Task t, Concept c) throws TermServerScriptException {
		int changesMade = NO_CHANGES_MADE;
		String fsn = " " + c.getFsn().toLowerCase();
		if (isExcluded(fsn)) {
			report(t, c, Severity.MEDIUM, ReportActionType.VALIDATION_CHECK, "Concept Excluded due to lexical rule");
		} else {
			for (Map.Entry<String, RelationshipTemplate> entry : searchTermAttributeMap.entrySet()) {
				String searchTerm = entry.getKey();
				changesMade += checkAndReplace(t, c, fsn, searchTerm, entry.getValue());
				if (entry.getKey().contains("-")) {
					changesMade += checkAndReplace(t, c, fsn, searchTerm.replaceAll("-", " "), entry.getValue());
					changesMade += checkAndReplace(t, c, fsn, searchTerm.replaceAll("-", ""), entry.getValue());
				}
				//We can't check for changes because the attribute might already be present.
				//Check for the term again and if found, don't move on to the next search term
				if (fsn.contains(searchTerm) ||
						fsn.contains(searchTerm.replaceAll("-", " ")) ||
						fsn.contains(searchTerm.replaceAll("-", ""))){
					break;
				}
			}
		}
		return changesMade;
	}

	private int checkAndReplace(Task t, Concept c, String fsn, String searchTerm, RelationshipTemplate rel) throws TermServerScriptException {
		if (fsn.contains(searchTerm)) {
			Relationship attrib = rel.createRelationship(c, UNGROUPED, null);
			return addRelationship(t, c, attrib);
		}
		return NO_CHANGES_MADE;
	}

	private boolean isExcluded(String fsn) {
		for (String exclusionWord : exclusions) {
			if (fsn.contains(exclusionWord)) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected List<Component> identifyComponentsToProcess() throws TermServerScriptException {
		List<Component> processMe = new ArrayList<>();
		for (Concept c : subHierarchy.getDescendents(NOT_SET)) {
			for (String searchTerm : searchTermAttributeMap.keySet()) {
				if (c.getFsn().toLowerCase().contains(searchTerm) || 
						c.getFsn().toLowerCase().contains(searchTerm.replaceAll("-", " ")) ||
						c.getFsn().toLowerCase().contains(searchTerm.replaceAll("-", ""))) {
					processMe.add(c);
					break;
				}
			}
		}
		return processMe;
	}
}

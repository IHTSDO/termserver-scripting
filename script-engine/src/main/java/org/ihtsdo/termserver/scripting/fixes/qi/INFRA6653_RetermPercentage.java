package org.ihtsdo.termserver.scripting.fixes.qi;

import java.io.IOException;
import java.util.*;

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
public class INFRA6653_RetermPercentage extends BatchFix {

	String search = "%";
	String replace = " percent";
	
	protected INFRA6653_RetermPercentage(BatchFix clone) {
		super(clone);
	}

	public static void main(String[] args) throws TermServerScriptException, IOException, InterruptedException {
		INFRA6653_RetermPercentage fix = new INFRA6653_RetermPercentage(null);
		try {
			ReportSheetManager.targetFolderId = "1fIHGIgbsdSfh5euzO3YKOSeHw4QHCM-m";  //Ad-hoc batch updates
			fix.populateEditPanel = false;
			fix.populateTaskDescription = false;
			fix.selfDetermining = true;
			fix.reportNoChange = true;
			fix.additionalReportColumns = "Action Detail, Additional Detail";
			fix.init(args);
			fix.getArchiveManager().setPopulateReleasedFlag(true);
			fix.loadProjectSnapshot(false);
			fix.postInit();
			fix.processFile();
		} finally {
			fix.finish();
		}
	}

	@Override
	public int doFix(Task task, Concept concept, String info) throws TermServerScriptException {
		int changesMade = 0;
		try {
			Concept loadedConcept = loadConcept(concept, task.getBranchPath());
			changesMade = modifyDescriptions(task, loadedConcept);
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
		String expectedPT = SnomedUtils.deconstructFSN(c.getFsn())[0];
		String origPT = c.getPreferredSynonym(US_ENG_LANG_REFSET).getTerm();
		String replacement;
		for (Description d : originalDescriptions) {
			if (d.getType().equals(DescriptionType.TEXT_DEFINITION)) {
				continue;
			}
			if (d.isPreferred() && d.getTerm().contains(search)) {
				if (d.getType().equals(DescriptionType.FSN)) {
					//Will we change the PT to be the same or do we need to do some extra work?
					if (!origPT.equals(expectedPT)) {
						report(t, c, Severity.HIGH, ReportActionType.VALIDATION_CHECK, "PT does not align with FSN", c.getPreferredSynonym(US_ENG_LANG_REFSET));
					}
				}
				//If the % is the first thing, use a capital
				if (d.getTerm().charAt(0) == '%') {
					replacement = ("Percentage " + d.getTerm().substring(1)).replaceAll("  ", " ");
				} else {
					replacement = d.getTerm().replaceAll(search, replace).replaceAll("  ", " ");
				}
				replaceDescription(t, c, d, replacement, InactivationIndicator.NONCONFORMANCE_TO_EDITORIAL_POLICY, true);
				changesMade++;
			 }
		}
		return changesMade;
	}

	@Override
	protected List<Component> identifyComponentsToProcess() throws TermServerScriptException {
		List<Component> process = new ArrayList<>();
		//MPs can be ignored.
		Set<Concept> products = PHARM_BIO_PRODUCT.getDescendents(NOT_SET);
		nextConcept:
		for (Concept c : gl.getAllConcepts()) {
			if (!c.isActive() || products.contains(c) 
					|| c.getFsn().contains("(product)")
					|| c.getFsn().contains("(physical object)")) {
				continue;
			}
			for (Description d : c.getDescriptions(ActiveState.ACTIVE)) {
				if (d.isPreferred() && d.getTerm().contains(search) && !d.getType().equals(DescriptionType.TEXT_DEFINITION)) {
					process.add(c);
					continue nextConcept;
				}
			}
		}
		return process;
	}
}

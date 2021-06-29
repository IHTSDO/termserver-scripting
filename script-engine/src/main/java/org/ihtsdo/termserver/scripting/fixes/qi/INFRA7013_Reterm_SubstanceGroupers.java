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
import org.springframework.util.StringUtils;

/**
 *INFRA-6637 Re-terming and remodel of AIDS concepts
 */
public class INFRA7013_Reterm_SubstanceGroupers extends BatchFix {
	
	protected INFRA7013_Reterm_SubstanceGroupers(BatchFix clone) {
		super(clone);
	}

	public static void main(String[] args) throws TermServerScriptException, IOException, InterruptedException {
		INFRA7013_Reterm_SubstanceGroupers fix = new INFRA7013_Reterm_SubstanceGroupers(null);
		try {
			ReportSheetManager.targetFolderId = "1fIHGIgbsdSfh5euzO3YKOSeHw4QHCM-m";  //Ad-hoc batch updates
			fix.populateEditPanel = false;
			fix.populateTaskDescription = false;
			fix.selfDetermining = true;
			fix.reportNoChange = true;
			fix.init(args);
			fix.getArchiveManager().setPopulateReleasedFlag(true);
			fix.loadProjectSnapshot(false);
			fix.postInit();
			fix.processFile();
		} finally {
			fix.finish();
		}
	}
	
	public void postInit() throws TermServerScriptException {

		String[] columnHeadings = new String[] {
				"Task, Desc, SCTID,FSN,ConceptType,Severity,Action, Detail,Details,",
				"Id, FSN, SemTag, CheckMe, Remark, Detail"};
		String[] tabNames = new String[] {
				"Processed",
				"Not Processed"};
		super.postInit(tabNames, columnHeadings, false);
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
		for (Description d : originalDescriptions) {
			if (d.getType().equals(DescriptionType.TEXT_DEFINITION)) {
				continue;
			}
			
			if (d.getTerm().contains(" its ")) {
				debug("Check me: " + d.getTerm());
			}
			
			String newTerm = d.getTerm().replace(" and ", " and/or ")
					.replace(" its", "")
					.replace("derivatives", "derivative")
					.replace("compounds", "compound");
			
			if (newTerm.contains(" and/or derivative")) {
				String X = newTerm.substring(0, newTerm.indexOf(" and/or")).toLowerCase();
				newTerm = newTerm.replace(" and/or derivative", " and/or " + X + " derivative");
			}
			
			if (!d.getTerm().equals(newTerm)) {
				if (!d.isPreferred()) {
					removeDescription(t, c, d, null, InactivationIndicator.NONCONFORMANCE_TO_EDITORIAL_POLICY);
				} else {
					//Do not demote the PT, replace absolutely
					replaceDescription(t, c, d, newTerm, InactivationIndicator.NONCONFORMANCE_TO_EDITORIAL_POLICY, false);
				}
				changesMade++;
			}
		}
		return changesMade;
	}

	@Override
	protected List<Component> identifyComponentsToProcess() throws TermServerScriptException {
		List<Component> process = new ArrayList<>();
		List<Concept> substances = new ArrayList<>(SUBSTANCE.getDescendents(NOT_SET));
		String[] suffixes = new String[] { "derivative", "compound" };
		String[] plurals = new String[] { "", "s" };
		substances.sort(Comparator.comparing(Concept::getFsn));
		nextConcept:
		for (Concept c : substances) {
			if (c.isActive()) {
				String lastIssue = "";
				boolean isGrouper = true;
				for (Description d : c.getDescriptions(ActiveState.ACTIVE)) {
					if (d.isPreferred() || !d.getType().equals(DescriptionType.TEXT_DEFINITION)) {
						String term = d.getTerm().toLowerCase();
						if (d.getType().equals(DescriptionType.FSN)) {
							term = SnomedUtils.deconstructFSN(term)[0];
						}
						String[] parts = term.split(" and ");
						if (parts.length > 2) {
							report (SECONDARY_REPORT, c, isCheckMe(d.getTerm()), "Unexpected pattern: " + d.getTerm());
							continue nextConcept;
						} else if (parts.length == 2) {
							isGrouper = true;
							//Does it match the form X and X derivative|compound|(s) ?
							for (String suffix : suffixes) {
								for (String plural : plurals) {
									if (parts[1].endsWith(suffix+plural)) {
										parts[1]=parts[1].replace(suffix+plural, "").trim();
										if (parts[0].trim().contentEquals(parts[1])) {
											process.add(c);
											continue nextConcept;
										} else {
											lastIssue = "X and Y skipped: " + d.getTerm();
										}
									} else if (StringUtils.isEmpty(lastIssue)) {
										lastIssue = "Term did not end with expected suffix: " + d.getTerm();
									}
								}
							}
						} else {
							//Only 1 part here, so no "and" present.  Not a grouper
							isGrouper = false;
						}
					}
				}
				//Lots of single thing concepts that we don't need to mention.
				if (isGrouper) {
					report(SECONDARY_REPORT, c, isCheckMe(lastIssue), lastIssue);
				}
			}
		}
		return process;
	}

	private String isCheckMe(String str) {
		return (str.contains("derivat") || str.contains("compound")) ? "Y" : "N";
	}
}

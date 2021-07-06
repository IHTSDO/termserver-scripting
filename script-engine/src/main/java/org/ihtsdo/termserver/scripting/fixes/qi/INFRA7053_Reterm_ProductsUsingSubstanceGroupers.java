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

public class INFRA7053_Reterm_ProductsUsingSubstanceGroupers extends BatchFix {
	
	List<String> knownSubstancePTs;
	
	protected INFRA7053_Reterm_ProductsUsingSubstanceGroupers(BatchFix clone) {
		super(clone);
	}

	public static void main(String[] args) throws TermServerScriptException, IOException, InterruptedException {
		INFRA7053_Reterm_ProductsUsingSubstanceGroupers fix = new INFRA7053_Reterm_ProductsUsingSubstanceGroupers(null);
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
			} else {
				Concept substanceGrouper = findSubstanceGrouperAttribute(concept);
				if (substanceGrouper != null) {
					report(task, concept, Severity.HIGH, ReportActionType.VALIDATION_CHECK, "Attribute is substance grouper, not reflected in FSN", substanceGrouper);
				}
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
				debug ("Check calculation of X here");
				String X = findXfromKnownSubstances(newTerm.toLowerCase());
				if (X == null) {
					X = newTerm.substring(0, newTerm.indexOf(" and/or")).toLowerCase();
				}
				newTerm = newTerm.replace(" and/or derivative", " and/or " + X + " derivative");
			}
			
			if (newTerm.contains(" and/or compound")) {
				debug ("Check calculation of X here");
				String X = findXfromKnownSubstances(newTerm.toLowerCase());
				if (X == null) {
					X = newTerm.substring(0, newTerm.indexOf(" and/or")).toLowerCase();
				}
				newTerm = newTerm.replace(" and/or compound", " and/or " + X + " compound");
			}
			
			newTerm = newTerm.replaceAll(" and/or acting as ", " and acting as ");
			
			if (!d.getTerm().equals(newTerm)) {
				if (!d.isPreferred() && d.getTerm().contains("and its compounds")) {
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

	private String findXfromKnownSubstances(String newTerm) throws TermServerScriptException {
		if (knownSubstancePTs == null) {
			knownSubstancePTs = SUBSTANCE.getDescendents(NOT_SET).stream()
					.map(c -> {
						try {
							return c.getPreferredSynonym(GB_ENG_LANG_REFSET).getTerm().toLowerCase();
						} catch (TermServerScriptException e) {return "Error";}
					})
					.collect(Collectors.toList());
		}
		for (String pt : knownSubstancePTs) {
			if (newTerm.contains(pt)) {
				return pt;
			}
		}
		return null;
	}

	@Override
	protected List<Component> identifyComponentsToProcess() throws TermServerScriptException {
		List<Component> process = new ArrayList<>();
		List<Concept> conceptsSorted = PHARM_BIO_PRODUCT.getDescendents(NOT_SET).stream()
				//.filter(Concept::isActive)
				//.filter(c -> !exclude.contains(c))  Too slow
				.sorted(Comparator.comparing(Concept::getSemTag)
						.thenComparing(Comparator.comparing(Concept::getFsn)))
				.collect(Collectors.toList());
		
		String[] suffixes = new String[] { "derivative", "compound" };
		int conceptsConsidered = 0;
		nextConcept:
		for (Concept c : conceptsSorted) {
			/*if (c.getId().equals("4551372014")) {
				debug("Here: " + c);
			}*/
			conceptsConsidered++;
			if (conceptsConsidered%500 == 0) {
				double perc = (conceptsConsidered/(double)conceptsSorted.size()) * (double)100.0;
				debug ("Processed " + String.format("%.1f", perc) + "%");
			}
			
			if (c.isActive()) {
				//If any relationship target values contain an and/or substance, we'll have that
				if (containsSubstanceGrouperAttribute(c) && hasDescriptionLacking(c)) {
					process.add(c);
					continue nextConcept;
				}
				
				for (String suffix : suffixes) {
					if (c.getFsn().contains(suffix)) {
						report(SECONDARY_REPORT, c, "Does not indicate grouper substance attribute");
					}
				}
			}

		}
		return process;
	}

	private boolean hasDescriptionLacking(Concept c) {
		for (Description d : c.getDescriptions(ActiveState.ACTIVE)) {
			if (!d.getTerm().contains("and/or")) {
				return true;
			}
		}
		return false;
	}

	private boolean containsSubstanceGrouperAttribute(Concept c) {
		return findSubstanceGrouperAttribute(c) == null ? false : true;
	}
	
	private Concept findSubstanceGrouperAttribute(Concept c) {
		for (Relationship r : c.getRelationships(CharacteristicType.INFERRED_RELATIONSHIP, ActiveState.ACTIVE)) {
			if (!r.isConcrete()) {
				String fsn = r.getTarget().getFsn();
				if (fsn.contains("(substance)") && fsn.contains("and/or")) {
					return r.getTarget();
				}
			}
		}
		return null;
	}
}

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
import org.springframework.util.StringUtils;

public class INFRA7048_Reterm_SubstanceGrouperReferences extends BatchFix {
	
	private String[] preambleEnds = new String[] { " of ", " by ", " to " };
	
	private String[] knownPhrases = new String[] {" and its compounds", " and derivatives" };
	
	List<String> knownSubstancePTs;
	
	private TermPattern[] knownTermPatterns = new TermPattern[] {
		new TermPattern ("", ""),
		new TermPattern ("Accidental ", " overdose"),
		new TermPattern ("Accidental ", " poisoning"),
		new TermPattern ("Intentional ", " overdose"),
		new TermPattern ("Intentional ", " poisoning"),
		new TermPattern ("", " overdose"),
		new TermPattern ("", " poisoning"),
		new TermPattern ("", " intake"),
		new TermPattern ("", " in 24 hours"),
		new TermPattern ("", " modified diet"),
		new TermPattern ("", " diet"),
		new TermPattern ("", " intake"),
		new TermPattern ("Increased ", " diet"),
		new TermPattern ("Decreased ", " diet"),
		new TermPattern ("Consistent ", " diet"),
		new TermPattern ("Estimated quantity of intake of ", " in 24 hours"),
		new TermPattern ("Measured quantity of intake of ", " in 24 hours"),
		new TermPattern ("Estimated required quantity of intake of ", " in 24 hours"),
		new TermPattern ("Total ", " estimated intake in 24 hours"),
		new TermPattern ("Measured intake of ", " in 24 hours")
	};
	
	protected INFRA7048_Reterm_SubstanceGrouperReferences(BatchFix clone) {
		super(clone);
	}

	public static void main(String[] args) throws TermServerScriptException, IOException, InterruptedException {
		INFRA7048_Reterm_SubstanceGrouperReferences fix = new INFRA7048_Reterm_SubstanceGrouperReferences(null);
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
		List<Concept> exclude = new ArrayList<>(SUBSTANCE.getDescendents(NOT_SET));
		exclude.addAll(MEDICINAL_PRODUCT.getDescendents(NOT_SET));
		info ("Gathering sorted list of potential concepts");
		List<Concept> conceptsSorted = gl.getAllConcepts().stream()
				//.filter(Concept::isActive)
				//.filter(c -> !exclude.contains(c))  Too slow
				.sorted(Comparator.comparing(Concept::getSemTag)
						.thenComparing(Comparator.comparing(Concept::getFsn)))
				.collect(Collectors.toList());
		info ("Gathering list of potential concepts");
		
		String[] suffixes = new String[] { "derivative", "compound" };
		String[] plurals = new String[] { "", "s" };
		int conceptsConsidered = 0;
		nextConcept:
		for (Concept c : conceptsSorted) {
			/*if (c.getId().equals("4551372014")) {
				debug("Here: " + c);
			}*/
			conceptsConsidered++;
			if (conceptsConsidered%5000 == 0) {
				double perc = (conceptsConsidered/(double)conceptsSorted.size()) * (double)100.0;
				debug ("Processed " + String.format("%.1f", perc) + "%");
			}
			
			if (c.isActive() && !exclude.contains(c)) {
				//If any relationship target values contain an and/or substance, we'll have that
				if (containsSubstanceGrouperAttribute(c) && hasDescriptionLacking(c)) {
					process.add(c);
					continue nextConcept;
				}
				String lastIssue = "";
				boolean isGrouper = true;
				for (Description d : c.getDescriptions(ActiveState.ACTIVE)) {
					if (!d.getType().equals(DescriptionType.TEXT_DEFINITION)) {
						String term = d.getTerm().toLowerCase();
						
						//If we've already got and/or then we're done here
						if (d.isPreferred() && term.contains("and/or")) {
							continue nextConcept;
						}
						
						//Is this one of our known phrases?
						for (String knownPhrase : knownPhrases) {
							if (term.contains(knownPhrase)) {
								process.add(c);
								continue nextConcept;
							}
						}
						
						if (d.getType().equals(DescriptionType.FSN)) {
							term = SnomedUtils.deconstructFSN(term)[0];
						}
						
						for (TermPattern knownTermPattern : knownTermPatterns) {
							if (!StringUtils.isEmpty(knownTermPattern.prefix) && term.startsWith(knownTermPattern.prefix)) {
								term = term.substring(knownTermPattern.prefix.length());
							}
							
							if (!StringUtils.isEmpty(knownTermPattern.suffix) && term.endsWith(knownTermPattern.suffix)) {
								term = term.substring(0, term.length() - knownTermPattern.suffix.length());
							}
						
							String[] parts = term.split(" and ");
							if (parts.length > 2) {
								if (isCheckMe(d.getTerm()).equals("Y")) {
									report (SECONDARY_REPORT, c, isCheckMe(d.getTerm()), "Unexpected pattern: " + d.getTerm());
								}
								continue nextConcept;
							} else if (parts.length == 2) {
								isGrouper = true;
								//Does it match the form "preamble + X and X derivative|compound|(s)" ?
								for (String suffix : suffixes) {
									for (String plural : plurals) {
										if (parts[1].endsWith(suffix+plural)) {
											parts[1]=parts[1].replace(suffix+plural, "").trim();
											if (parts[0].trim().contentEquals(parts[1])) {
												process.add(c);
												continue nextConcept;
											} else {
												//Can we remove a preamble?
												for (String preamble : preambleEnds) {
													int cutPoint = parts[0].indexOf(preamble);
													if (cutPoint != NOT_SET) {
														parts[0] = parts[0].substring(cutPoint + preamble.length());
														if (parts[0].trim().contentEquals(parts[1])) {
															process.add(c);
															continue nextConcept;
														}
													}
												}
												lastIssue = "X and Y skipped: " + d.toString();
											}
										} else if (StringUtils.isEmpty(lastIssue)) {
											lastIssue = "Term did not end with expected suffix: " + d.toString();
										}
									}
								}
							} else {
								//Only 1 part here, so no "and" present.  Not a grouper
								isGrouper = false;
							}
						}
					}
				}
				//Lots of single thing concepts that we don't need to mention.
				if (isGrouper && isCheckMe(lastIssue).equals("Y")) {
					report(SECONDARY_REPORT, c, lastIssue);
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

	private String isCheckMe(String str) {
		return (str.contains("derivat") || str.contains("compound")) ? "Y" : "N";
	}
	
	class TermPattern {
		String prefix;
		String suffix;
		TermPattern(String prefix, String suffix) {
			this.prefix = prefix;
			this.suffix = suffix;
		}
	}
}

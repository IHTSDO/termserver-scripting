package org.ihtsdo.termserver.scripting.reports.qi;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component;
import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ReportClass;
import org.ihtsdo.termserver.scripting.AncestorsCache;
import org.ihtsdo.termserver.scripting.TransitiveClosure;

import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.reports.TermServerReport;
import org.ihtsdo.termserver.scripting.reports.release.CrossoverUtils;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;
import org.snomed.otf.scheduler.domain.*;
import org.snomed.otf.scheduler.domain.Job.ProductionStatus;

/**
 * RP-227 Pattern KPIs (RP-273 Splits this into KPI and QI Patterns reports)
 * --------------------
 * RP-233 Role group crossovers
 * RP-234 Ungrouped crossovers
 * RP-235 Intermediate primitive concepts that have sufficiently defined supertypes and subtypes.
 */
public class QIPatternsReport extends TermServerReport implements ReportClass {
	
	private Map<String, Integer> issueSummaryMap = new HashMap<>();
	AncestorsCache cache;
	String previousPreviousRelease;
	TransitiveClosure tc;
	
	public static void main(String[] args) throws TermServerScriptException, IOException {
		Map<String, String> params = new HashMap<>();
		TermServerReport.run(QIPatternsReport.class, args, params);
	}
	
	public void init (JobRun run) throws TermServerScriptException {
		ReportSheetManager.targetFolderId = "11i7XQyb46P2xXNBwlCOd3ssMNhLOx1m1"; //QI / Misc Analysis
		super.init(run);
		runStandAlone = false; //We need to load previous previous for real
		getArchiveManager().setPopulateReleasedFlag(true);
	}
	
	public void postInit() throws TermServerScriptException {
		String[] columnHeadings = new String[] { "SCTID, FSN, Semtag, Issue, Details",
				"Issue, Count"};
		String[] tabNames = new String[] {	"Issues",
				"Summary"};
		cache = gl.getAncestorsCache();
		super.postInit(tabNames, columnHeadings, false);
	}

	@Override
	public Job getJob() {
		return new Job()
				.withCategory(new JobCategory(JobType.REPORT, JobCategory.QI))
				.withName("QI Patterns Report")
				.withDescription("This report identifies a number of potentially problematic patters, many of which are tracked as KPIs ")
				.withProductionStatus(ProductionStatus.PROD_READY)
				.withTag(INT)
				.build();
	}

	public void runJob() throws TermServerScriptException {
		
		info ("Checking structural integrity");
		if (checkStructuralIntegrity()) {
			info("Checking for crossovers");
			checkForRoleGroupCrossovers();
			checkForUngroupedCrossovers();
			
			info("Checking for Intermediate Primitives...");
			checkForIPs();
		}
			
		info("Checks complete, creating summary tag");
		populateSummaryTab();
		
		info("Summary tab complete, all done.");
	}

	private void populateSummaryTab() throws TermServerScriptException {
		issueSummaryMap.entrySet().stream()
				.sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
						.forEach(e -> reportSafely (SECONDARY_REPORT, (Component)null, e.getKey(), e.getValue()));
		
		int total = issueSummaryMap.entrySet().stream()
				.map(e -> e.getValue())
				.collect(Collectors.summingInt(Integer::intValue));
		reportSafely (SECONDARY_REPORT, (Component)null, "TOTAL", total);
	}
	
	private boolean checkStructuralIntegrity() throws TermServerScriptException {
		String issueStr = "Concept referenced in axiom, but does not exist";
		String issueStr2 = "Concept exists without an FSN";
		String issueStr3 = "Active concept does not have any axioms";
		//Don't initialise, we won't mention the check if there's no problem.
		boolean isOK = true;
		for (Concept c : gl.getAllConcepts()) {
			if (c.isReleased() == null) {
				String detail = "";
				if (c.getAxiomEntries() != null && c.getAxiomEntries().size() > 0) {
					detail = c.getAxiomEntries().get(0).toString();
				} else {
					//We'll have to work through all axioms to find where this came from
					List<AxiomEntry> axiomsContaining = findAxiomsContaining(c);
					if (axiomsContaining.size() > 0) {
						detail = "In " + axiomsContaining.size() + " axioms eg " + axiomsContaining.get(0);
					}
				}
				report (c, issueStr, detail);
				isOK = false;
			} else if (c.getFsn() == null || c.getFsn().isEmpty()) {
				report (c, issueStr2);
				isOK = false;
			} else if (c.isActive() && c.getAxiomEntries().size() == 0) {
				//The Root concept can get away with this
				if (!c.equals(ROOT_CONCEPT)) {
					report (c, issueStr3);
					isOK = false;
				} 
			}
		}
		return isOK;
	}
	
	private List<AxiomEntry> findAxiomsContaining(Concept findme) {
		List<AxiomEntry> matches = new ArrayList<>();
		for (Concept c : gl.getAllConcepts()) {
			for (AxiomEntry a : c.getAxiomEntries()) {
				//TODO Isn't great - we may match SCTIDs which are a subtring of another
				//Split the axiom into constituent parts if this proves to be a problem
				if (a.getOwlExpression().contains(findme.getConceptId())) {
					matches.add(a);
				}
			}
		}
		return matches;
	}

	private void checkForRoleGroupCrossovers() throws TermServerScriptException {
		String issueStr = "Pattern 4: Role group crossover";
		initialiseSummary(issueStr);
		Set<GroupPair> processedPairs = new HashSet<>();
		for (Concept c : gl.getAllConcepts()) {
			/*if (c.getConceptId().equals("10311005")) {
				debug("here");
			}*/
			Collection<RelationshipGroup> groups = c.getRelationshipGroups(CharacteristicType.INFERRED_RELATIONSHIP);
			//We only need to worry about concepts with >1 role group
			if (c.isActive() && groups.size() > 1) {
				processedPairs.clear();
				//Test every group against every other group
				for (RelationshipGroup left : groups) {
					if (!left.isGrouped()) {
						continue;
					}
					for (RelationshipGroup right : groups) {
						if (left.getGroupId()==right.getGroupId() || !right.isGrouped()) {
							continue;
						}
						//Have we already processed this combination in the opposite order?
						if (processedPairs.contains(new GroupPair(right, left))) {
							continue;
						}
						switch (CrossoverUtils.subsumptionRoleGroupTest(left, right)) {
							case ROLEGROUPS_CROSSOVER :
							case ROLES_CROSSOVER:
									report (c, issueStr, left, right);
									break;
							default:
						}
						processedPairs.add(new GroupPair(left, right));
					}
				}
			}
		}
	}
	
	private void checkForUngroupedCrossovers() throws TermServerScriptException {
		String issueStr = "Pattern 5: Role and role group anomaly - more specific";
		initialiseSummary(issueStr);
		String issue2Str = "Pattern 5: Role and role group anomaly - inconsistent";
		initialiseSummary(issue2Str);
		for (Concept c : gl.getAllConcepts()) {
			if (!c.isActive() || c.getRelationshipGroup(CharacteristicType.INFERRED_RELATIONSHIP, UNGROUPED) == null) {
				continue;
			}
			List<Relationship> ungroupedRels = c.getRelationshipGroup(CharacteristicType.INFERRED_RELATIONSHIP, UNGROUPED).getRelationships();
			for (Relationship ungroupedRel : ungroupedRels) {
				//Is our ungrouped relationship more specific than any grouped relationship?
				for (RelationshipGroup group : c.getRelationshipGroups(CharacteristicType.INFERRED_RELATIONSHIP)) {
					if (!group.isGrouped()) {
						continue;
					}
					for (Relationship groupedRel : group.getRelationships()) {
						if (SnomedUtils.isMoreSpecific(ungroupedRel, groupedRel, cache)) {
							report (c, issueStr, ungroupedRel, groupedRel);
						} else if (SnomedUtils.inconsistentSubsumption(ungroupedRel, groupedRel, cache)) {
							report (c, issue2Str, ungroupedRel, groupedRel);
						}
					}
				}
			}
		}
	}
	
	private void checkForIPs() throws TermServerScriptException {
		String issueStr = "Pattern 7: Intermediate primitive";
		initialiseSummary(issueStr);
		for (Concept c : identifyIntermediatePrimitives(gl.getAllConcepts(), CharacteristicType.INFERRED_RELATIONSHIP)) {
			report(c, issueStr);
		}
	}

	protected void initialiseSummary(String issue) {
		issueSummaryMap.merge(issue, 0, Integer::sum);
	}
	
	protected void report (Concept c, Object...details) throws TermServerScriptException {
		//First detail is the issue
		issueSummaryMap.merge(details[0].toString(), 1, Integer::sum);
		countIssue(c);
		super.report (PRIMARY_REPORT, c, details);
	}
	
	class GroupPair {
		RelationshipGroup one;
		RelationshipGroup two;
		int hash;
		GroupPair (RelationshipGroup one, RelationshipGroup two) {
			this.one = one;
			this.two = two;
			hash = (one.toString() + two.toString()).hashCode();
		}
		
		public boolean equals(Object other) {
			if (other instanceof GroupPair) {
				GroupPair otherPair = (GroupPair)other;
				if (this.one.getGroupId() == otherPair.one.getGroupId()) {
					return this.two.getGroupId() == otherPair.two.getGroupId();
				}
			}
			return false;
		}
		
		public int hashCode() {
			return hash;
		}
	}

}

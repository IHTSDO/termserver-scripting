package org.ihtsdo.termserver.scripting.reports;

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
import org.ihtsdo.termserver.scripting.util.SnomedUtils;
import org.ihtsdo.termserver.scripting.util.StringUtils;
import org.snomed.otf.scheduler.domain.*;
import org.snomed.otf.scheduler.domain.Job.ProductionStatus;

/**
 * RP-227 Pattern KPIs (RP-273 Splits this into KPI and QI Patterns reports)
 * --------------------
 * RP-228 Redundant ungrouped roles
 * RP-229 Redundant stated groups
 * RP-232 Redundant stated IS A
 * RP-231 Newly inactivatated duplicate created in prior release
 * RP-230 Existing sufficiently defined concepts that 
 * gained a stated intermediate primitive parent and lost active inferred descendant(s)
 */
public class KPIPatternsReport extends TermServerReport implements ReportClass {
	
	private Map<String, Integer> issueSummaryMap = new HashMap<>();
	AncestorsCache cache;
	String previousPreviousRelease;
	TransitiveClosure tc;
	
	public static void main(String[] args) throws TermServerScriptException, IOException {
		Map<String, String> params = new HashMap<>();
		TermServerReport.run(KPIPatternsReport.class, args, params);
	}
	
	public void init (JobRun run) throws TermServerScriptException {
		ReportSheetManager.targetFolderId = "15WXT1kov-SLVi4cvm2TbYJp_vBMr4HZJ"; //Release QA
		super.init(run);
		runStandAlone = false; //We need to load previous previous for real
		getArchiveManager().setPopulateReleasedFlag(true);
		if (!StringUtils.isNumeric(project.getKey())) {
			getArchiveManager().setPopulatePreviousTransativeClosure(true);
			try {
				previousPreviousRelease = getArchiveManager().getPreviousPreviousBranch(project);
			} catch (Exception e) {
				error ("Failed to recover previous branch, falling back to hard coded 20190131", e);
				previousPreviousRelease = "MAIN/2019-01-31";
			}
		}
	}
	
	public void postInit() throws TermServerScriptException {
		String[] columnHeadings = new String[] { "SCTID, FSN, Semtag, Issue, Details",
				"Issue, Count",
				"SCTID, FSN, Semtag, Lost Concept Id, Lost Concept FSN"};
		String[] tabNames = new String[] {	"Issues",
				"Summary",
				"Pattern 11 Detail"};
		cache = gl.getAncestorsCache();
		super.postInit(tabNames, columnHeadings, false);
	}

	@Override
	public Job getJob() {
		return new Job()
				.withCategory(new JobCategory(JobType.REPORT, JobCategory.RELEASE_VALIDATION))
				.withName("KPI Patterns Report")
				.withDescription("This report identifies a number of potentially problematic patters, many of which are tracked as KPIs ")
				.withProductionStatus(ProductionStatus.PROD_READY)
				.withTag(INT)
				.build();
	}

	public void runJob() throws TermServerScriptException {
		
		info ("Checking structural integrity");
		if (checkStructuralIntegrity()) {
			info("Checking for problematic patterns...");
			
			info("Checking for redundancies...");
			checkRedundantlyStatedParents();
			checkRedundantlyStatedUngroupedRoles();
			checkRedundantlyStatedGroups();
		
			info ("Building current Transitive Closure");
			tc = gl.generateTransativeClosure();
			
			info("Checking for historical patterns");
			if (previousPreviousRelease != null) {
				checkCreatedButDuplicate();
				checkPattern11();  //...a very specific situation
			} else {
				report (null, "Skipping Patterns 11 & 21", "No previous previous release available");
			}
			
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

	private void checkRedundantlyStatedParents() throws TermServerScriptException {
		//RP-232 Redundantly stated IsA relationships
		String issueStr = "Pattern 1: Redundantly stated IsA relationships";
		initialiseSummary(issueStr);
		for (Concept c : gl.getAllConcepts()) {
			if (c.isActive()) {
				//Test each ungrouped relationship to see if it subsumes any other
				for (Concept a : c.getParents(CharacteristicType.STATED_RELATIONSHIP)) {
					for (Concept b :c.getParents(CharacteristicType.STATED_RELATIONSHIP)) {
						if (!a.equals(b) && cache.getAncestorsOrSelf(a).contains(b)) {
							report (c, issueStr, a, b);
						}
					}
				}
			}
		}
	}

	private void checkRedundantlyStatedUngroupedRoles() throws TermServerScriptException {
		//RP-288 Redundantly stated ungrouped roles
		String issueStr = "Pattern 2: Redundantly stated ungrouped role";
		initialiseSummary(issueStr);
		for (Concept c : gl.getAllConcepts()) {
			if (c.isActive()) {
				//Test each ungrouped relationship to see if it subsumes any other
				for (Relationship a : c.getRelationshipGroupSafely(CharacteristicType.STATED_RELATIONSHIP, UNGROUPED).getRelationships()) {
					for (Relationship b : c.getRelationshipGroupSafely(CharacteristicType.STATED_RELATIONSHIP, UNGROUPED).getRelationships()) {
						if (!a.equals(b) && cache.getAncestorsOrSelf(a.getType()).contains(b.getType())) {
							if (cache.getAncestorsOrSelf(a.getTarget()).contains(b.getTarget())) {
								report (c, issueStr, a, b);
							}
						}
					}
				}
			}
		}
	}
	
	private void checkRedundantlyStatedGroups() throws TermServerScriptException {
		//RP-289 Redundantly stated ungrouped roles
		String issueStr = "Pattern 3: Redundantly stated group";
		initialiseSummary(issueStr);
		for (Concept c : gl.getAllConcepts()) {
			if (c.isActive()) {
				//Test each relationship group to see if it subsumes any other
				for (RelationshipGroup a : c.getRelationshipGroups(CharacteristicType.STATED_RELATIONSHIP)) {
					for (RelationshipGroup b : c.getRelationshipGroups(CharacteristicType.STATED_RELATIONSHIP)) {
						if (a.getGroupId() == UNGROUPED || b.getGroupId() == UNGROUPED || a.getGroupId() == b.getGroupId()) {
							continue;
						} else {
							if (SnomedUtils.covers(a, b, cache)) {
								report (c, issueStr, a, b);
							}
						}
					}
				}
			}
		}
	}
	
	private void checkCreatedButDuplicate() throws TermServerScriptException {
		//RP-231 Pattern 21 Newly inactivatated duplicate was created in previous release
		String issueStr = "Pattern 21: Newly inactivatated duplicate was created in previous release.";
		initialiseSummary(issueStr);
		int errorCount = 0;
		for (Concept c : gl.getAllConcepts()) {
			if (!c.isActive() && 
				(c.getEffectiveTime() == null || c.getEffectiveTime().isEmpty()) &&
				c.getInactivationIndicator().equals(InactivationIndicator.DUPLICATE)) {
				//Did this concept exist in the previous previous release?
				//If not, then it was created in the previous release and immediately retired
				try {
					Concept loadedConcept = loadConcept(c, previousPreviousRelease);
					if (loadedConcept == null) {
						report (c, issueStr);
					}
				} catch (Exception e) {
					report (c, "API ERROR", "Failed to check previous previous release due to " + e.getMessage());
					if (++errorCount == 5) {
						report (c, "API ERROR", "Maximum failures reached, giving up on Pattern 21");
						break;
					}
				}
			}
		}
	}
	
	private void checkPattern11() throws TermServerScriptException {
		//RP-230 Existing sufficiently defined concepts that: 
		//1. gained a stated intermediate primitive parent and 
		//2. lost active inferred descendant(s)
		String issueStr = "Pattern 11: Existing sufficiently defined concepts that gained a stated intermediate primitive parent and lost active inferred descendant(s)";
		initialiseSummary(issueStr);
		for (Concept c : gl.getAllConcepts()) {
			//Filter for active concepts that have already been published and are sufficiently defined.
			if (c.isActive() && c.isReleased() &&
					c.getDefinitionStatus().equals(DefinitionStatus.FULLY_DEFINED)) {
				List<Concept> newPrimitiveParents = getNewPrimitiveParents(c);
				if (newPrimitiveParents.size() > 0) {
					//What descendants have we lost?  Make sure they're still active.
					Set<Long> previousDescendantIds = gl.getPreviousTC().getDescendants(c);
					//Did we in fact have any descendants in the previous release?
					if (previousDescendantIds == null) {
						continue;
					}
					previousDescendantIds = new HashSet<Long>(previousDescendantIds);
					int previousCount = previousDescendantIds.size();
					Set<Long> currentDescendantIds = tc.getDescendants(c);
					//Have we in fact lost _all_ descendants?
					if (currentDescendantIds == null) {
						currentDescendantIds = new HashSet<Long>();
					}
					int newCount = currentDescendantIds.size();
					//Remove the current set, to see what's no longer a descendant
					previousDescendantIds.removeAll(currentDescendantIds);
					//Map to concepts and filter to retain only those that are active
					List<Concept> lostActive = previousDescendantIds.stream()
							.map(l -> gl.getConceptSafely(l.toString()))
							.filter(f -> f.isActive())
							.collect (Collectors.toList());
					if (lostActive.size() > 0) {
						String stats = previousCount + " -> " + newCount + " (-" + lostActive.size() + ")";
						report (c, issueStr, toString(newPrimitiveParents), "inferred descendants", 
								stats, "eg", lostActive.get(0));
						for (Concept lostConcept : lostActive) {
							report(TERTIARY_REPORT, c, lostConcept.getConceptId(), lostConcept.getFsn());
						}
					}
				}
			}
		}
	}

	private String toString(List<Concept> concepts) {
		return concepts.stream()
				.map(c -> c.toString())
				.collect(Collectors.joining(", "));
	}

	private List<Concept> getNewPrimitiveParents(Concept c) {
		//Get new (ie not released) stated IS_A relationship targets
		return c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, ActiveState.ACTIVE).stream()
				.filter(r -> r.getType().equals(IS_A) && !r.isReleased())
				.map(r -> r.getTarget())
				.collect(Collectors.toList());
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

package org.ihtsdo.termserver.scripting.reports;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ReportClass;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.service.TraceabilityService;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;
import org.ihtsdo.termserver.scripting.util.StringUtils;
import org.snomed.otf.scheduler.domain.*;
import org.snomed.otf.scheduler.domain.Job.ProductionStatus;

public class ConceptChanged extends TermServerReport implements ReportClass {
	
	private Set<Concept> newConcepts = new HashSet<>();
	private Set<Concept> inactivatedConcepts = new HashSet<>();
	private Set<Concept> defStatusChanged = new HashSet<>();
	private Set<Concept> hasNewStatedRelationships = new HashSet<>();
	private Set<Concept> hasNewInferredRelationships = new HashSet<>();
	private Set<Concept> hasLostStatedRelationships = new HashSet<>();
	private Set<Concept> hasLostInferredRelationships = new HashSet<>();
	private Set<Concept> hasNewDescriptions = new HashSet<>();
	private Set<Concept> hasChangedDescriptions = new HashSet<>();
	private Set<Concept> hasLostDescriptions = new HashSet<>();
	private Set<Concept> hasChangedAssociations = new HashSet<>();
	private Set<Concept> hasChangedInactivationIndicators = new HashSet<>();
	private Set<Concept> isTargetOfNewStatedRelationship = new HashSet<>();
	private Set<Concept> wasTargetOfLostStatedRelationship = new HashSet<>();
	private Set<Concept> isTargetOfNewInferredRelationship = new HashSet<>();
	private Set<Concept> wasTargetOfLostInferredRelationship = new HashSet<>();
	
	TraceabilityService traceability;
	
	public static void main(String[] args) throws TermServerScriptException, IOException {
		Map<String, String> params = new HashMap<>();
		TermServerReport.run(ConceptChanged.class, args, params);
	}
	
	public void init (JobRun run) throws TermServerScriptException {
		ReportSheetManager.targetFolderId = "1F-KrAwXrXbKj5r-HBLM0qI5hTzv-JgnU"; //Ad-hoc Reports
		ReportSheetManager.setMaxColumns(18);
		getArchiveManager().setPopulateReleasedFlag(true);
		subHierarchyECL = run.getParamValue(ECL);
		super.init(run);
	}
	
	public void postInit() throws TermServerScriptException {
		String[] columnHeadings = new String[] {
				"Id, FSN, SemTag, Active, DefStatusChanged, Author, Task, Creation Date",
				"Id, FSN, SemTag, Active, hasNewStatedRelationships, hasNewInferredRelationships, hasLostStatedRelationships, hasLostInferredRelationships, Author, Task, Creation Date",
				"Id, FSN, SemTag, Active, hasNewDescriptions, hasChangedDescriptions, hasLostDescriptions, Author, Task, Creation Date",
				"Id, FSN, SemTag, Active, hasChangedAssociations, hasChangedInactivationIndicators, Author, Task, Creation Date",
				"Id, FSN, SemTag, Active, isTargetOfNewStatedRelationship, isTargetOfNewInferredRelationship, wasTargetOfLostStatedRelationship, wasTargetOfLostInferredRelationship, Author, Task, Creation Date",
				"Id, FSN, SemTag, Description, isNew, isChanged, wasInactivated"};
		String[] tabNames = new String[] {	
				"Concept Changes",
				"Relationship Changes",
				"Description Changes",
				"Association Changes",
				"Incoming Relationship Changes",
				"Description Change Details"};
		super.postInit(tabNames, columnHeadings, false);
		traceability = new TraceabilityService(jobRun, this, "pdat");  //Matching Updating and updated
	}
	
	@Override
	public Job getJob() {
		JobParameters params = new JobParameters()
				.add(ECL).withType(JobParameter.Type.ECL).withDefaultValue("<< " + ROOT_CONCEPT)
				.build();
		return new Job()
				.withCategory(new JobCategory(JobType.REPORT, JobCategory.RELEASE_VALIDATION))
				.withName("Concepts Changed")
				.withDescription("This report lists all concepts changed in the current release cycle, optionally restricted to a subset defined by an ECL expression.  The issue count here is the total number of concepts featuring one change or another.")
				.withProductionStatus(ProductionStatus.PROD_READY)
				.withParameters(params)
				.withTag(INT)
				.withTag(MS)
				.build();
	}
	
	public void runJob() throws TermServerScriptException {
		examineConcepts();
		reportConceptsChanged();
		determineUniqueCountAndTraceability();
		traceability.flush();
		info ("Job complete");
	}
	
	public void examineConcepts() throws TermServerScriptException { 
		int conceptsExamined = 0;
		Collection<Concept> conceptsOfInterest;
		
		if (subHierarchyECL != null && !subHierarchyECL.isEmpty()) {
			conceptsOfInterest = findConcepts(subHierarchyECL);
		} else {
			conceptsOfInterest = gl.getAllConcepts();
		}
		
		double lastPercentageReported = 0;
		for (Concept c : conceptsOfInterest) {
			if (c.isReleased() == null) {
				throw new IllegalStateException ("Malformed snapshot. Released status not populated at " + c);
			} else if (!c.isReleased()) {
				//We will not service.populateTraceabilityAndReport any changes on brand new concepts
				//Or (in managed service) concepts from other modules
				continue;
			} else if (inScope(c) && 
					(c.getEffectiveTime() == null || c.getEffectiveTime().isEmpty())) {
				//Only want to log def status change if the concept has not been made inactive
				if (c.isActive()) {
					defStatusChanged.add(c);
				} else {
					inactivatedConcepts.add(c);
				}
			}
			
			for (Description d : c.getDescriptions()) {
				boolean isNew = false;
				boolean isChanged = false;
				boolean wasInactivated = false;
				if (inScope(d)) {
					if (!d.isReleased()) {
						hasNewDescriptions.add(c);
						isNew = true;
					} else if (d.getEffectiveTime() == null || d.getEffectiveTime().isEmpty()) {
						if (!d.isActive()) {
							hasLostDescriptions.add(c);
							wasInactivated = true;
						} else {
							hasChangedDescriptions.add(c);
							isChanged = true;
						}
					}
				}
				
				if (isNew || isChanged || wasInactivated) {
					report (SENARY_REPORT, c, d, isNew, isChanged, wasInactivated);
				}
			}
			
			for (Relationship r : c.getRelationships()) {
				if (inScope(r)) {
					if (StringUtils.isEmpty(r.getEffectiveTime())) {
						boolean isStated = r.getCharacteristicType().equals(CharacteristicType.STATED_RELATIONSHIP);
						if (r.isActive()) {
							if (isStated) {
								hasNewStatedRelationships.add(c);
								isTargetOfNewStatedRelationship.add(r.getTarget());
							} else {
								hasNewInferredRelationships.add(c);
								isTargetOfNewInferredRelationship.add(r.getTarget());
							}
						} else {
							if (isStated) {
								hasLostStatedRelationships.add(c);
								wasTargetOfLostStatedRelationship.add(c);
							} else {
								hasLostInferredRelationships.add(c);
								wasTargetOfLostInferredRelationship.add(c);
							}
						}
					}
				}
			}
			
			if (inScope(c)) {
				for (InactivationIndicatorEntry i : c.getInactivationIndicatorEntries()) {
					if (i.getEffectiveTime() == null || i.getEffectiveTime().isEmpty()) {
						hasChangedInactivationIndicators.add(c);
					}
				}
				
				for (AssociationEntry a : c.getAssociations()) {
					if (a.getEffectiveTime() == null || a.getEffectiveTime().isEmpty()) {
						hasChangedAssociations.add(c);
					}
				}
			}
			
			conceptsExamined++;
			double perc = ((double)conceptsExamined / (double)conceptsOfInterest.size()) * 100;
			if (perc >= lastPercentageReported + 10) {
				info ("Examined " + String.format("%.2f", perc) + "%");
				lastPercentageReported = perc;
			}
		}
	}
	
	private void reportConceptsChanged() throws TermServerScriptException {
		HashSet<Concept> superSet = new HashSet<>();
		superSet.addAll(newConcepts);
		superSet.addAll(defStatusChanged);
		superSet.addAll(inactivatedConcepts);
		debug ("Creating concept report for " + superSet.size() + " concepts");
		for (Concept c : sort(superSet)) {
			traceability.populateTraceabilityAndReport (PRIMARY_REPORT, c,
				c.isActive()?"Y":"N",
				defStatusChanged.contains(c)?"Y":"N");
		}
		traceability.flush();
		superSet.clear();
		
		superSet.addAll(hasNewStatedRelationships);
		superSet.addAll(hasNewInferredRelationships);
		superSet.addAll(hasLostStatedRelationships);
		superSet.addAll(hasLostInferredRelationships);
		debug ("Creating relationship report for " + superSet.size() + " concepts");
		for (Concept c : sort(superSet)) {
			traceability.populateTraceabilityAndReport (SECONDARY_REPORT, c,
				c.isActive()?"Y":"N",
				hasNewStatedRelationships.contains(c)?"Y":"N",
				hasNewInferredRelationships.contains(c)?"Y":"N",
				hasLostStatedRelationships.contains(c)?"Y":"N",
				hasLostInferredRelationships.contains(c)?"Y":"N");
		}
		superSet.clear();
		
		superSet.addAll(hasNewDescriptions);
		superSet.addAll(hasChangedDescriptions);
		superSet.addAll(hasLostDescriptions);
		debug ("Creating description report for " + superSet.size() + " concepts");
		for (Concept c : sort(superSet)) {
			traceability.populateTraceabilityAndReport (TERTIARY_REPORT, c,
				c.isActive()?"Y":"N",
				hasNewDescriptions.contains(c)?"Y":"N",
				hasChangedDescriptions.contains(c)?"Y":"N",
				hasLostDescriptions.contains(c)?"Y":"N");
		}
		superSet.clear();
		
		superSet.addAll(hasChangedAssociations);
		superSet.addAll(hasChangedInactivationIndicators);
		debug ("Creating association report for " + superSet.size() + " concepts");
		for (Concept c : sort(superSet)) {
			traceability.populateTraceabilityAndReport (QUATERNARY_REPORT, c,
				c.isActive()?"Y":"N",
				hasChangedAssociations.contains(c)?"Y":"N",
				hasChangedInactivationIndicators.contains(c)?"Y":"N");
		}
		superSet.clear();
		
		superSet.addAll(isTargetOfNewStatedRelationship);
		superSet.addAll(wasTargetOfLostStatedRelationship);
		superSet.addAll(isTargetOfNewInferredRelationship);
		superSet.addAll(wasTargetOfLostInferredRelationship);
		debug ("Creating incoming relatonshiip report for " + superSet.size() + " concepts");
		for (Concept c : sort(superSet)) {
			traceability.populateTraceabilityAndReport (QUINARY_REPORT, c,
				c.isActive()?"Y":"N",
				isTargetOfNewStatedRelationship.contains(c)?"Y":"N",
				wasTargetOfLostStatedRelationship.contains(c)?"Y":"N",
				isTargetOfNewInferredRelationship.contains(c)?"Y":"N",
				wasTargetOfLostInferredRelationship.contains(c)?"Y":"N");
		}
	}
	
	private void determineUniqueCountAndTraceability() {
		debug ("Determining unique count");
		HashSet<Concept> superSet = new HashSet<>();
		superSet.addAll(newConcepts);
		superSet.addAll(inactivatedConcepts);
		superSet.addAll(defStatusChanged);
		superSet.addAll(hasNewStatedRelationships);
		superSet.addAll(hasNewInferredRelationships);
		superSet.addAll(hasLostStatedRelationships);
		superSet.addAll(hasLostInferredRelationships);
		superSet.addAll(hasNewDescriptions);
		superSet.addAll(hasChangedDescriptions);
		superSet.addAll(hasLostDescriptions);
		superSet.addAll(hasChangedAssociations);
		superSet.addAll(hasChangedInactivationIndicators);
		superSet.addAll(isTargetOfNewStatedRelationship);
		superSet.addAll(wasTargetOfLostStatedRelationship);
		superSet.addAll(isTargetOfNewInferredRelationship);
		superSet.addAll(wasTargetOfLostInferredRelationship);
		
		for (Concept c : superSet) {
			countIssue(c);
		}
		
	}
	
	private List<Concept> sort(Set<Concept> superSet) {
		//We're going to sort on top level hierarchy, then alphabetically
		return superSet.stream()
		.sorted((c1, c2) -> compareSemTagFSN(c1,c2))
		.collect(Collectors.toList());
	}

	private int compareSemTagFSN(Concept c1, Concept c2) {
		String[] fsnSemTag1 = SnomedUtils.deconstructFSN(c1.getFsn());
		String[] fsnSemTag2 = SnomedUtils.deconstructFSN(c2.getFsn());
		
		if (fsnSemTag1[1] == null) {
			System.out.println("FSN Encountered without semtag: " + c1);
			return 1;
		} else if (fsnSemTag2[1] == null) {
			System.out.println("FSN Encountered without semtag: " + c2);
			return -1;
		}
		
		if (fsnSemTag1[1].equals(fsnSemTag2[1])) {
			return fsnSemTag1[0].compareTo(fsnSemTag2[0]);
		}
		return fsnSemTag1[1].compareTo(fsnSemTag2[1]);
	}
	
}

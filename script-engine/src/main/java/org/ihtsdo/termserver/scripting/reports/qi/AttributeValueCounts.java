package org.ihtsdo.termserver.scripting.reports.qi;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ReportClass;
import org.ihtsdo.termserver.scripting.AncestorsCache;
import org.ihtsdo.termserver.scripting.DescendantsCache;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.reports.TermServerReport;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;
import org.snomed.otf.scheduler.domain.*;
import org.snomed.otf.scheduler.domain.Job.ProductionStatus;

import com.google.common.util.concurrent.AtomicLongMap;

/**
 * INFRA-
 */
public class AttributeValueCounts extends TermServerReport implements ReportClass {
	
	public static final String ATTRIBUTE_TYPE = "Attribute Type";
	public static final String IGNORE_ECL = "Ignore Concepts ECL";
	Concept targetAttributeType;
	String ignoreConceptsECL;
	AncestorsCache ancestorCache;
	DescendantsCache descendentCache;
	AtomicLongMap<Concept> valueCounts = AtomicLongMap.create();
	AtomicLongMap<Concept> valueCountsFiltered = AtomicLongMap.create();
	Set<Concept> alreadyReported = new HashSet<>();
	int minimumSizeOfInterest = 150;
	Set<Concept>ignoreConcepts;
	
	static String qiProjectAlreadyProcessed = "<<2775001 OR <<3218000 OR <<3723001 OR <<5294002 OR <<7890003 OR <<8098009 OR <<17322007 " +
	" OR <<20376005 OR <<34014006 OR <<40733004 OR <<52515009 OR <<85828009 OR <<87628006 OR <<95896000 OR <<109355002 OR <<118616009 " + 
	"OR <<125605004 OR <<125643001 OR <<125666000 OR <<125667009 OR <<125670008 OR <<126537000 OR <<128139000 OR <<128294001 " + 
	"OR <<128477000 OR <<128482007 OR <<131148009 OR <<193570009 OR <<233776003 OR <<247441003 OR <<276654001 OR <<283682007 " +
	"OR <<298180004 OR <<307824009 OR <<312608009 OR <<362975008 OR <<399963005 OR <<399981008 OR <<400006008 OR <<400178008 " + 
	"OR <<416462003 OR <<416886008 OR <<417893002 OR <<419199007 OR <<428794004 OR <<429040005 OR <<432119003 OR <<441457006 " +
	"OR <<419199007 OR <<282100009 OR <<55342001 OR <<128462008 OR <<363346000 OR <<372087000 ";
	
	public static void main(String[] args) throws TermServerScriptException, IOException {
		Map<String, String> params = new HashMap<>();
		//params.put(ECL, "<< 64572001 |Disease (disorder)|");
		params.put(ECL, "<< 404684003 |Clinical finding (finding)|");
		params.put(ATTRIBUTE_TYPE, ASSOC_MORPH.toString());
		params.put(IGNORE_ECL, qiProjectAlreadyProcessed);
		TermServerReport.run(AttributeValueCounts.class, args, params);
	}
	
	public void init (JobRun run) throws TermServerScriptException {
		ReportSheetManager.targetFolderId = "1F-KrAwXrXbKj5r-HBLM0qI5hTzv-JgnU"; //Ad-hoc Reports
		additionalReportColumns = "FSN, SemTag, Depth, Raw Concept Count, Adjusted Concept Count, Not-Including Descendants, Adjusted Not-Including Descendants, Parents, GrandParents";
		getArchiveManager().setPopulateHierarchyDepth(true);
		super.init(run);
		
		String attribStr = run.getParamValue(ATTRIBUTE_TYPE);
		if (attribStr != null && !attribStr.isEmpty()) {
			targetAttributeType = gl.getConcept(attribStr);
		}
		
		subHierarchyECL = run.getMandatoryParamValue(ECL);
		ignoreConceptsECL = run.getParamValue(IGNORE_ECL);
	}
	
	@Override
	public Job getJob() {
		JobParameters params = new JobParameters()
				.add(ECL).withType(JobParameter.Type.ECL).withMandatory()
				.add(ATTRIBUTE_TYPE).withType(JobParameter.Type.CONCEPT)
				//TODO Add the characteristic type dropdown
				.build();
		
		return new Job()
				.withCategory(new JobCategory(JobType.REPORT, JobCategory.QI))
				.withName("Attribute Value Counts")
				.withDescription("This report counts of attribute values used for concepts matching the specified ECL.")
				.withProductionStatus(ProductionStatus.PROD_READY)
				.withParameters(params)
				.withTag(INT)
				.build();
	}
	
	public void runJob() throws TermServerScriptException {
		Concept[] types = new Concept[] {targetAttributeType};
		ancestorCache = gl.getAncestorsCache();
		descendentCache = gl.getDescendantsCache();
		info ("Analyzing " + subHierarchyECL);
		ignoreConcepts = new HashSet<>(findConcepts(ignoreConceptsECL));
		for (Concept c : findConcepts(subHierarchyECL)) {
			//Find all the target values for the specified attribute type
			Set<Concept> targets = SnomedUtils.getTargets(c, types, CharacteristicType.INFERRED_RELATIONSHIP);
			for (Concept target : targets) {
				valueCounts.getAndIncrement(target);
				//We also separately count those we haven't been told to ignore eg QI project previously processed.
				if (!ignoreConcepts.contains(c)) {
					valueCountsFiltered.getAndIncrement(target);
				}
			}
		}
		
		//TODO We're also interested in the primitive concepts above this attribute value.
		info ("Outputting counts");
		Set<Concept> targets = new HashSet<>(valueCounts.asMap().keySet());
		//Now work through the list, from top to bottom
		Concept top = calculateHighestConceptOrParent(targets);
		targets.add(top);
		//Go go go recursive programming!
		reportCounts(top, targets, true);
	}

	private void reportCounts(Concept c, Set<Concept> targets, boolean isTop) throws TermServerScriptException {
		//Have we reported this concept already?
		if (alreadyReported.contains(c)) {
			return;
		} else {
			alreadyReported.add(c);
		}
		
		//What's the descendant count added up?
		if (isTop || valueCounts.containsKey(c)) {
			Set<Concept> parents = c.getParents(CharacteristicType.INFERRED_RELATIONSHIP);
			String parentsStr = parents.stream().map(p->p.toString())
					.collect(Collectors.joining(",\n"));
			String parentsParentsStr = parents.stream()
					.flatMap(p->p.getParents(CharacteristicType.INFERRED_RELATIONSHIP).stream())
					.map(pp->pp.toString())
					.collect(Collectors.joining(",\n"));
			int count = getDescendantAndSelfCount(c, targets, false);
			int countFiltered = getDescendantAndSelfCount(c, targets, true);
			int countNotIncludingDescendants = (int)valueCounts.get(c);
			int countNotIncludingDescendantsFiltered = (int)valueCountsFiltered.get(c);
			if (countFiltered > minimumSizeOfInterest) {
				report(c, getDepthIndicator(c), count, countFiltered, countNotIncludingDescendants, countNotIncludingDescendantsFiltered, parentsStr, parentsParentsStr);
			}
		}	
			
		//Now report all of my children, and their children, if they exist.
		for (Concept child : c.getChildren(CharacteristicType.INFERRED_RELATIONSHIP)) {
			reportCounts(child, targets, false);
		}
	}

	private Object getDepthIndicator(Concept c) {
		String depthIndicator="";
		for (int x=NOT_SET; x<c.getDepth(); x++) {
			depthIndicator += "- ";
		}
		return depthIndicator;
	}

	private Integer getDescendantAndSelfCount(Concept c, Set<Concept> targets, boolean doFiltering) throws TermServerScriptException {
		Set<Concept> descendantsInUse = new HashSet<>(descendentCache.getDescendentsOrSelf(c));
		descendantsInUse.retainAll(targets);
		AtomicLongMap<Concept> sourceData = doFiltering ? valueCountsFiltered : valueCounts;
		int count = 0;
		for (Concept target : descendantsInUse) {
			count += sourceData.get(target);
		}
		return count;
	}

	private Concept calculateHighestConceptOrParent(Set<Concept> concepts) throws TermServerScriptException {
		//Find the smallest depth indicator
		Integer minimumDepth = null;
		for (Concept c : concepts) {
			if (minimumDepth == null || c.getDepth() < minimumDepth) {
				minimumDepth = c.getDepth();
			}
		}
		//What all concepts are at that depth?
		final int minDepth = minimumDepth;
		Set<Concept> siblings = concepts.stream()
				.filter(c -> c.getDepth() == minDepth)
				.collect(Collectors.toSet());
		
		if (siblings.size() == 1) {
			return siblings.iterator().next();
		} else {
			//Find the deepest common ancestor of these siblings
			Set<Concept> commonAncestors = null;
			for (Concept sibling : siblings) {
				if (commonAncestors == null) {
					commonAncestors = ancestorCache.getAncestors(sibling);
				} else {
					commonAncestors.retainAll(ancestorCache.getAncestors(sibling));
				}
			}
			return deepestConcept(commonAncestors);
		}
	}

	private Concept deepestConcept(Set<Concept> concepts) throws TermServerScriptException {
		//Find the greatest depth indicator
		Integer maximumDepth = null;
		for (Concept c : concepts) {
			if (maximumDepth == null || c.getDepth() < maximumDepth) {
				maximumDepth = c.getDepth();
			}
		}
		
		//What all concepts are at that depth?
		final int maxDepth = maximumDepth;
		Set<Concept> siblings = concepts.stream()
				.filter(c -> c.getDepth() == maxDepth)
				.collect(Collectors.toSet());
		
		if (siblings.size() != 1) {
			throw new TermServerScriptException("Unable to find deepest concept from " + concepts);
		}
		return siblings.iterator().next();
	}

}

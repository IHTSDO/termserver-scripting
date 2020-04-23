package org.ihtsdo.termserver.scripting.reports;

import java.io.IOException;
import java.util.*;

import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ReportClass;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;
import org.snomed.otf.scheduler.domain.*;
import org.snomed.otf.scheduler.domain.Job.ProductionStatus;

import com.google.common.base.Charsets;
import com.google.common.io.Files;

/**
 * Given a number of sub-hierarchies, find the highest concepts (with a count of descendants)
 * that are not contained by those sub hierarchies
 */
public class ConceptsNotAccountedFor extends TermServerReport implements ReportClass {
	
	Set<Concept> accountedForHierarchies = new HashSet<>();
	Set<Concept> accountedForHierarchiesExpanded = new HashSet<>();
	Set<Concept> notAccountedForHierarchies = new HashSet<>();
	Set<Concept> tooHigh = new HashSet<>(); //Concepts too high up the hierarchy to be considered for grouping.
	
	String [] co_occurrantWords = new String[] { " and ", " with ", " in " };
	Concept[] co_occurrantTypeAttrb;
	Concept[] complexTypeAttrbs;
	
	enum TemplateType {SIMPLE, PURE_CO, COMPLEX, COMPLEX_NO_MORPH, NONE};
	
	public static void main(String[] args) throws TermServerScriptException, IOException {
		TermServerReport.run(ConceptsNotAccountedFor.class, args);
	}
	
	public void init(JobRun run) throws TermServerScriptException {
		ReportSheetManager.targetFolderId = "1YoJa68WLAMPKG6h4_gZ5-QT974EU9ui6"; //QI / Stats
		additionalReportColumns="FSN, Descendants NOC, Already accounted, SIMPLE, PURE_CO, COMPLEX, COMPLEX_NO_MORPH, NONE";
		run.setParameter(SUB_HIERARCHY, CLINICAL_FINDING.toString());
		super.init(run);
		getArchiveManager().setAllowStaleData(true);
	}

	public void postInit() throws TermServerScriptException {
		List<String> lines = null;
		try {
			lines = Files.readLines(inputFile, Charsets.UTF_8);
		} catch (Exception e) {
			throw new TermServerScriptException("Failed to read " + inputFile, e);
		}
		
		for (String line : lines) {
			String[] items = line.split(COMMA);
			Concept accountedForSubHierarchy = gl.getConcept(items[0]);
			accountedForHierarchies.add(accountedForSubHierarchy);
			accountedForHierarchiesExpanded.addAll(accountedForSubHierarchy.getDescendents(NOT_SET));
		}
		super.postInit();
		
		tooHigh.add(subHierarchy);
		tooHigh.add(DISEASE);
		tooHigh.add(gl.getConcept("417163006"));
		tooHigh.add(gl.getConcept("118234003"));
		tooHigh.add(gl.getConcept("250171008"));
		
		co_occurrantTypeAttrb =  new Concept[] {
				gl.getConcept("47429007") //|Associated with (attribute)|
		};
		
		complexTypeAttrbs = new Concept[] {
				gl.getConcept("42752001"), //|Due to (attribute)|
				gl.getConcept("726633004"), //|Temporally related to (attribute)|
				gl.getConcept("255234002"), //|After (attribute)|
				gl.getConcept("288556008"), //|Before (attribute)|
				gl.getConcept("371881003"), //|During (attribute)|
				gl.getConcept("363713009"), //|Has interpretation (attribute)|
				gl.getConcept("363714003")  //|Interprets (attribute)|
			};
	}
	@Override
	public Job getJob() {
		String[] parameterNames = new String[] { "SubHierarchy" };
		return new Job()
				.withCategory(new JobCategory(JobType.REPORT, JobCategory.QI))
				.withName("Concepts not accounted for")
				.withDescription("Given a number of sub-hierarchies, find the highest concepts not included")
				.withProductionStatus(ProductionStatus.HIDEME)
				.withParameters(new JobParameters(parameterNames))
				.withTag(INT)
				.build();
	}

	public void runJob() throws TermServerScriptException {
		
		//Work through all concepts in the hierarchy and work out if we've covered it or not
		for (Concept c : subHierarchy.getDescendents(NOT_SET)) {
			//Is this concept too high to look at?
			if (tooHigh.contains(c)) {
				continue;
			}
			//Is this concept accounted for?
			Set<Concept> highestNotAccountFor = findHighestNotAccountedFor(c);
			if (highestNotAccountFor != null) {
				notAccountedForHierarchies.addAll(highestNotAccountFor);
			}
		}
		
		//Now output the results
		Set<Concept> alreadyReported = new HashSet<>();
		for (Concept c : notAccountedForHierarchies) {
			Set<Concept> descendants = new HashSet<>(gl.getDescendantsCache().getDescendentsOrSelf(c));
			int originalSize = descendants.size();
			descendants.removeAll(alreadyReported);
			descendants.removeAll(accountedForHierarchiesExpanded);
			int alreadyCounted = originalSize - descendants.size();
			
			int[] buckets = countTypes(descendants);
			
			if (descendants.size() > 0) {
				report (c, descendants.size(), alreadyCounted, buckets);
			}
			alreadyReported.addAll(descendants);
		}
	}

	private int[] countTypes(Set<Concept> concepts) {
		int[] counts = new int[TemplateType.values().length];
		for (Concept c : concepts) {
			int idx = getTemplateType(c).ordinal();
			counts[idx]++;
		}
		return counts;
	}

/*	private Set<Concept> findTemplateType(Set<Concept> concepts, TemplateType type) {
		return concepts.stream()
		.filter(c -> getTemplateType(c).equals(type))
		.collect(Collectors.toSet());
	}

	private Set<Concept> findNoModel(Set<Concept> concepts) {
		return concepts.stream()
		.filter(c -> countAttributes(c, CharacteristicType.INFERRED_RELATIONSHIP) == 0)
		.collect(Collectors.toSet());
	}*/

	private Set<Concept> findHighestNotAccountedFor(Concept c) throws TermServerScriptException {
		//Is this concept already accounted for?
		for (Concept hierarchy : accountedForHierarchies) {
			if (gl.getDescendantsCache().getDescendentsOrSelf(hierarchy).contains(c)) {
				return null;
			}
		}
		
		for (Concept hierarchy : notAccountedForHierarchies) {
			if (gl.getDescendantsCache().getDescendentsOrSelf(hierarchy).contains(c)) {
				return null;
			}
		}
		
		//Can we go higher?
		Set<Concept> highestAncestors = new HashSet<>();
		for (Concept parent : c.getParents(CharacteristicType.INFERRED_RELATIONSHIP)) {
			if (!tooHigh.contains(parent)) {
				Set<Concept> highestAncestorSet = findHighestNotAccountedFor(parent);
				if (highestAncestorSet != null) {
					highestAncestors.addAll(highestAncestorSet);
				}
			}
		}
		//Remove redundancy
		SnomedUtils.removeRedundancies(highestAncestors);
		
		//Did we find anything higher?
		if (highestAncestors.size() > 0) {
			return highestAncestors;
		} else {
			return new HashSet<>(Collections.singletonList(c));
		}
	}
	
	private TemplateType getTemplateType(Concept c) {
		try {
			//Zero case, do we in fact have no attributes?
			if (countAttributes(c, CharacteristicType.INFERRED_RELATIONSHIP) == 0) {
				return TemplateType.NONE;
			}
			
			//Firstly, if we have any of the complex attribute types 
			if (SnomedUtils.getTargets(c, complexTypeAttrbs, CharacteristicType.INFERRED_RELATIONSHIP).size() > 0) {
				//Do we have an associated morphology
				if (SnomedUtils.getTargets(c, new Concept[] {ASSOC_MORPH}, CharacteristicType.INFERRED_RELATIONSHIP).size() > 0 ) {
					return TemplateType.COMPLEX;
				} else {
					return TemplateType.COMPLEX_NO_MORPH;
				}
			}
			
			//Do we have a pure co-occurrent type
			if (SnomedUtils.getTargets(c, co_occurrantTypeAttrb, CharacteristicType.INFERRED_RELATIONSHIP).size() > 0) {
				return TemplateType.PURE_CO;
			}
			
			//Do we have a simple co-occurrent word?
			for (String word : co_occurrantWords) {
				if (c.getFsn().contains(word)) {
					return TemplateType.PURE_CO;
				}
			}
			
			//Otherwise we'll assume it's simple
			return TemplateType.SIMPLE;
		} catch (Exception e) {
			throw new IllegalArgumentException ("Trouble at mill",e);
		}
	}

}

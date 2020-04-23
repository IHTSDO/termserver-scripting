package org.ihtsdo.termserver.scripting.reports.qi;

import java.io.*;
import java.util.*;

import org.apache.commons.io.Charsets;
import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ArchiveManager;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.reports.TermServerReport;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;

import com.google.common.io.Files;

/**
 * QI
 * Report changes made in this authoring cycle for specific sub-hierarchies.
 */
public class GenerateWorkDoneStatsWithTempateTypes extends TermServerReport {
	
	List<Concept> subHierarchies;
	List<Concept> targetValues;
	Concept targetType = ASSOC_MORPH;
	Map<Concept, List<Concept>> exclusionMap = new HashMap<>();
	InitialAnalysis ipReport;
	int modifiedSince = 20180131;
	boolean workWithTargetValues = true;
	
	String [] co_occurrantWords = new String[] { " and ", " with ", " in ", " or " };
	String [] complexWords = new String[] { "complication", "sequela", "late effect", "secondary" };
	Concept[] co_occurrantTypeAttrb;
	Concept[] complexTypeAttrbs;
	
	enum TemplateType {SIMPLE, PURE_CO, COMPLEX, COMPLEX_NO_MORPH, NONE};
	Set<Concept> ignoreConcepts = new HashSet<>();
	static String ignoreConceptsECL = "<<2775001 OR <<3218000 OR <<3723001 OR <<5294002 OR <<7890003 OR <<8098009 OR <<17322007 " +
	" OR <<20376005 OR <<34014006 OR <<40733004 OR <<52515009 OR <<85828009 OR <<87628006 OR <<95896000 OR <<109355002 OR <<118616009 " + 
	"OR <<125605004 OR <<125643001 OR <<125666000 OR <<125667009 OR <<125670008 OR <<126537000 OR <<128139000 OR <<128294001 " + 
	"OR <<128477000 OR <<128482007 OR <<131148009 OR <<193570009 OR <<233776003 OR <<247441003 OR <<276654001 OR <<283682007 " +
	"OR <<298180004 OR <<307824009 OR <<312608009 OR <<362975008 OR <<399963005 OR <<399981008 OR <<400006008 OR <<400178008 " + 
	"OR <<416462003 OR <<416886008 OR <<417893002 OR <<419199007 OR <<428794004 OR <<429040005 OR <<432119003 OR <<441457006 " +
	"OR <<419199007 OR <<282100009 OR <<55342001 OR <<128462008 OR <<363346000 OR <<372087000 ";
	
	public static void main(String[] args) throws TermServerScriptException, IOException {
		GenerateWorkDoneStatsWithTempateTypes report = new GenerateWorkDoneStatsWithTempateTypes();
		try {
			ReportSheetManager.targetFolderId = "1YoJa68WLAMPKG6h4_gZ5-QT974EU9ui6"; //QI / Stats
			report.additionalReportColumns = "FSN, SemTag, Depth, Counted elsewhere, Phase 1, Phase 2, Out of Scope, Total, Orphanet (not included)";
			ArchiveManager.getArchiveManager(report, null).setPopulateHierarchyDepth(true);
			report.init(args);
			report.loadProjectSnapshot(false);  //Load all descriptions
			report.postLoadInit();
			report.generateWorkDoneStats();
		} catch (Exception e) {
			info("Failed to produce work done report due to " + e.getMessage());
			e.printStackTrace(new PrintStream(System.out));
		} finally {
			report.finish();
		}
	}

	private void postLoadInit() throws TermServerScriptException, IOException {
		subHierarchies = new ArrayList<>();
		targetValues = new ArrayList<>();
		safetyProtocols = false;
		
		info ("Loading " + inputFile);
		if (!inputFile.canRead()) {
			throw new TermServerScriptException ("Cannot read: " + inputFile);
		}
		
		List<String> lines = Files.readLines(inputFile, Charsets.UTF_8);
		for (String line : lines) {
			if (!line.trim().isEmpty()) {
				String[] concepts = line.split(COMMA);
				Concept concept = gl.getConcept(concepts[0]);
				if (workWithTargetValues) {
					targetValues.add(concept);
				} else {
					subHierarchies.add(concept);
				}
				
				if (concepts.length > 1) {
					List<Concept> exclusions = new ArrayList<Concept>();
					for (int idx = 1; idx < concepts.length; idx++) {
						exclusions.add(gl.getConcept(concepts[idx]));
					}
					exclusionMap.put(concept, exclusions);
				}
			}
		} 
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
			gl.getConcept("363714003"), //|Interprets (attribute)|
			gl.getConcept("47429007")   //|Associated with (attribute)|
		};
		postInit();
		ipReport = new InitialAnalysis(this);
		ignoreConcepts = new HashSet<>(findConcepts(ignoreConceptsECL));
	}

	private void generateWorkDoneStats() throws TermServerScriptException {
		ipReport.setQuiet(true);
		Set<Concept> alreadyAccountedFor = new HashSet<>();
		List<Concept> defnList = workWithTargetValues ? targetValues : subHierarchies;
		for (Concept subsetDefn : defnList) {
			int[] templateTypeTotal = new int[TemplateType.values().length];
			//int[] templateTypeModified = new int[TemplateType.values().length];
			debug ("Analysing subset defined via : " + subsetDefn);
			Collection<Concept> subset;
			if (workWithTargetValues) {
				String ecl = "<< 64572001 |Disease (disorder)| : " + targetType + " = << " + subsetDefn;
				subset = findConcepts(ecl);
			} else {
				subset = new HashSet<>(gl.getDescendantsCache().getDescendentsOrSelf(subsetDefn)); //
				removeExclusions(subsetDefn, subset);
			}
			
			int orphanetCount = subset.size();
			subset.removeAll(gl.getOrphanetConcepts());
			int total = subset.size();
			orphanetCount = orphanetCount - total;
			
			subset.removeAll(alreadyAccountedFor);
			subset.removeAll(ignoreConcepts);
			int withRemovals = subset.size();
			int countedElsewhere = total - withRemovals;
			
			for (Concept c : subset) {
				if (gl.isOrphanetConcept(c)) {
					incrementSummaryInformation("Orphanet concepts excluded");
					continue;
				}
				TemplateType type = getTemplateType(c);
				templateTypeTotal[type.ordinal()]++;
				//Has this concept been modified in the stated form since X?
				/*if (isModified(c, CharacteristicType.STATED_RELATIONSHIP)) {
					templateTypeModified[type.ordinal()]++;
				}*/
			}
			
			report (subsetDefn, subsetDefn.getDepth(),
					countedElsewhere, 
					templateTypeTotal[0] + templateTypeTotal[1],
					templateTypeTotal[2] + templateTypeTotal[3],
					templateTypeTotal[4],
					total, orphanetCount);
			alreadyAccountedFor.addAll(subset);
		}
	}

	private void removeExclusions(Concept subHierarchyStart, Collection<Concept> subSet) throws TermServerScriptException {
		//Do we have exclusions for this subHierarchy?
		List<Concept> theseExclusions = exclusionMap.get(subHierarchyStart);
		if (theseExclusions != null) {
			for (Concept thisExclusion : theseExclusions) {
				info ("For " + subHierarchyStart + " removing " + thisExclusion);
				subSet.removeAll(gl.getDescendantsCache().getDescendentsOrSelf(thisExclusion));
			}
		}
	}

	private TemplateType getTemplateType(Concept c) throws TermServerScriptException {
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
		
		//Do we have a complex word?
		for (String word : complexWords) {
			if (c.getFsn().contains(word)) {
				return TemplateType.COMPLEX;
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
	}

	/*private boolean isModified(Concept c, CharacteristicType charType) throws TermServerScriptException {
		for (Relationship r : c.getRelationships(charType, ActiveState.BOTH)) {
			//Exclude IS_A relationships
			if (r.getType().equals(IS_A)) {
				continue;
			}
			
			if (r.getEffectiveTime() == null || Integer.parseInt(r.getEffectiveTime()) > modifiedSince) {
				return true;
			}
		}
		return false;
	}*/
	
}

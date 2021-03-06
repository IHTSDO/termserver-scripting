package org.ihtsdo.termserver.scripting.template;

import java.io.IOException;
import java.util.*;

import org.snomed.authoringtemplate.domain.ConceptTemplate;
import org.snomed.authoringtemplate.domain.logical.*;

import com.google.common.base.Charsets;
import com.google.common.io.Files;

import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Task;
import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.AncestorsCache;
import org.ihtsdo.termserver.scripting.TermServerScript;
import org.ihtsdo.termserver.scripting.client.TemplateServiceClient;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.fixes.BatchFix;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;
import org.ihtsdo.termserver.scripting.util.StringUtils;

abstract public class TemplateFix extends BatchFix {
	
	protected Set<Concept> exclusions;
	protected List<String> exclusionWords;
	protected List<String> inclusionWords;
	protected boolean includeComplexTemplates = true;
	protected boolean includeOrphanet = true;
	protected List<Concept> complexTemplateAttributes;
	protected boolean includeDueTos = false;
	protected boolean excludeSdMultiRG = false;
	protected Set<Concept> explicitExclusions;
	
	String[] templateNames;
	List<Template> templates = new ArrayList<>();
	TemplateServiceClient tsc = new TemplateServiceClient(null, null);
	
	Map<Concept, Template> conceptToTemplateMap = new HashMap<>();

	protected TemplateFix(BatchFix clone) {
		super(clone);
	}
	
	protected void init(String[] args) throws TermServerScriptException {
		super.init(args);
		
		AttributeGroup.useDefaultValues = true;
		//We'll check these now so we know if there's some parsing error
		char id = 'A';
		for (int x = 0; x < templateNames.length; x++, id++) {
			Template t = loadLocalTemplate(id, templateNames[x]);
			validateTemplate(t);
			info ("Validated template: " + templateNames[x]);
		}
	}
	
	public void postInit(String[] tabNames, String[] columnHeadings, boolean csvOutput) throws TermServerScriptException {
		initTemplatesAndExclusions();
		super.postInit(tabNames, columnHeadings, csvOutput);
		info ("Post initialisation complete, with multiple tabs");
	}
	
	private void importExplicitExclusions() throws TermServerScriptException {
		explicitExclusions = new HashSet<>();
		print("Loading Explicit Exclusions " + inputFile + "...");
		if (!inputFile.canRead()) {
			throw new TermServerScriptException("Cannot read: " + inputFile);
		}
		List<String> lines;
		try {
			lines = Files.readLines(inputFile, Charsets.UTF_8);
		} catch (IOException e) {
			throw new TermServerScriptException("Failure while reading: " + inputFile, e);
		}
		debug("Processing Explicit Exclusions File");
		for (String line : lines) {
			String sctId = line.split(TAB)[0];
			Concept excluded = gl.getConcept(sctId, false, true);  //Validate concept exists
			explicitExclusions.add(excluded);
		}
		addSummaryInformation("Explicitly excluded concepts specified", explicitExclusions.size());
	}

	public void postInit() throws TermServerScriptException {
		initTemplatesAndExclusions();
		super.postInit();
		info ("Post initialisation complete");
	}
	
	private void initTemplatesAndExclusions() throws TermServerScriptException {
		if (subHierarchyStr != null) {
			subHierarchy = gl.getConcept(subHierarchyStr);
		}
		
		//Only load templates now if we've not already done so
		if (templates.isEmpty()) {
			char id = 'A';
			for (int x = 0; x < templateNames.length; x++, id++) {
				Template t = loadLocalTemplate(id, templateNames[x]);
				validateTemplate(t);
				templates.add(t);
				info ("Loaded template: " + t);
				
				if (StringUtils.isEmpty(subsetECL)) {
					subsetECL = t.getDomain();
					info("Subset ECL set to " + subsetECL);
				}
			}
			info(templates.size() + " Templates loaded successfully");
		}
		
		if (exclusions == null) {
			exclusions = new HashSet<>();
		}
		
		if (excludeHierarchies == null) {
			excludeHierarchies = new String[] {};
		}

		for (String thisExclude : excludeHierarchies) {
			info("Setting exclusion of " + thisExclude + " subHierarchy.");
			exclusions.addAll(gl.getConcept(thisExclude).getDescendents(NOT_SET));
		}
		
		//Note add words as lower case as we do all lower case matching
		if (exclusionWords == null) {
			exclusionWords = new ArrayList<>();
		}
		
		if (inputFile != null) {
			importExplicitExclusions();
		}
		
		if (!includeComplexTemplates) {
			if (!includeDueTos) {
				exclusionWords.add("due to");
			}
			exclusionWords.add("co-occurrent");
			exclusionWords.add("on examination");
			exclusionWords.add("complex");
			exclusionWords.add("associated");
			exclusionWords.add("after");
			exclusionWords.add("complication");
			exclusionWords.add("with");
			exclusionWords.add("without");
		} else {
			warn ("Including complex templates");
		}
		
		complexTemplateAttributes = new ArrayList<>();
		if (!includeDueTos) {
			complexTemplateAttributes.add(DUE_TO);
		}
		complexTemplateAttributes.add(AFTER);
		complexTemplateAttributes.add(gl.getConcept("726633004")); //|Temporally related to (attribute)|
		complexTemplateAttributes.add(gl.getConcept("288556008")); //|Before (attribute)|
		complexTemplateAttributes.add(gl.getConcept("371881003")); //|During (attribute)|
		complexTemplateAttributes.add(gl.getConcept("363713009")); //|Has interpretation (attribute)|
		complexTemplateAttributes.add(gl.getConcept("363714003")); //|Interprets (attribute)|
		complexTemplateAttributes.add(gl.getConcept("47429007"));  //|Associated with (attribute)
		
	}
	
	private void validateTemplate(Template t) throws TermServerScriptException {
		//Is the Domain specified by the template valid?  No point running if it selects no rows
		boolean useLocalStoreIfSimple = false;
		String ecl = t.getDomain();
		if (!getArchiveManager().isAllowStaleData() && findConcepts(ecl, false, useLocalStoreIfSimple).size() == 0) {
			throw new TermServerScriptException("Template domain: " + ecl + " returned 0 rows");
		}
		
		//Ensure that any repeated instances of identically named slots have the same value
		Map<String, String> namedSlots = new HashMap<>();
		for (AttributeGroup g : t.getAttributeGroups()) {
			for (Attribute a : g.getAttributes()) {
				//Does this attribute have a named slot?
				if (!StringUtils.isEmpty(a.getValueSlotName())) {
					String attributeClause = a.toString().replaceAll("  ", " ");
					String attributeClauseValue = attributeClause.substring(attributeClause.indexOf("=") + 1).trim();
					if (namedSlots.containsKey(a.getValueSlotName())) {
						if (!attributeClauseValue.equals(namedSlots.get(a.getValueSlotName()))) {
							throw new IllegalArgumentException("Named slots sharing the same name must have identical slot definition: " + a.getValueSlotName() + " -> " + attributeClauseValue);
						}
					} else {
						namedSlots.put(a.getValueSlotName(), attributeClauseValue);
					}
				}
			}
		}
	}

	protected Template loadLocalTemplate (char id, String fileName) throws TermServerScriptException {
		try {
			TermServerScript.info("Loading local template " + id + ": " + fileName );
			ConceptTemplate ct = tsc.loadLocalConceptTemplate(fileName);
			LogicalTemplate lt = tsc.parseLogicalTemplate(ct.getLogicalTemplate());
			Template t = new Template(id, lt, fileName);
			t.setDomain(ct.getDomain());
			t.setDocumentation(ct.getDocumentation());
			return t;
		} catch (IOException e) {
			throw new TermServerScriptException("Unable to load template " + fileName, e);
		}
	}
	
	protected Template loadTemplate (char id, String templateName) throws TermServerScriptException {
		try {
			TermServerScript.info("Loading remote template " + id + ": " + templateName );
			ConceptTemplate ct = tsc.loadLogicalTemplate(templateName);
			LogicalTemplate lt = tsc.parseLogicalTemplate(ct.getLogicalTemplate());
			Template t = new Template(id, lt, templateName);
			t.setDomain(ct.getDomain());
			t.setDocumentation(ct.getDocumentation());
			return t;
		} catch (IOException e) {
			throw new TermServerScriptException("Unable to load template " + templateName, e);
		}
	}
	
	protected Set<Concept> findTemplateMatches(Template t, Collection<Concept> concepts, Set<Concept> misalignedConcepts, Integer exclusionReport, CharacteristicType charType) throws TermServerScriptException {
		Set<Concept> matches = new HashSet<Concept>();
		info ("Examining " + concepts.size() + " concepts against template " + t);
		int conceptsExamined = 0;
		for (Concept c : concepts) {
			if (!c.isActive()) {
				warn ("Ignoring inactive concept returned by ECL: " + c);
				continue;
			}
			if (!isExcluded(c, exclusionReport)) {
				if (TemplateUtils.matchesTemplate(c, t, this, charType)) {
					//Do we already have a template for this concept?  
					//Assign the most specific template if so (TODO Don't assume order indicates complexity!)
					if (conceptToTemplateMap.containsKey(c)) {
						Template existing = conceptToTemplateMap.get(c);
						Template moreSpecific = t.getId() > existing.getId() ? t : existing; 
						warn( c + "matches two templates: " + t.getId() + " & " + existing.getId() + " using most specific " + moreSpecific.getId());
						conceptToTemplateMap.put(c, moreSpecific);
					} else {
						conceptToTemplateMap.put(c, t);
					}
					matches.add(c);
				} else {
					if (misalignedConcepts != null) {
						misalignedConcepts.add(c);
					}
				}
			} else {
				//Only count exclusions for the first pass
				if (t.getId() == 'A') {
					incrementSummaryInformation("Concepts excluded");
				}
			}
			if (++conceptsExamined % 1000 == 0) {
				print(".");
			}
		}
		println("");
		addSummaryInformation("Concepts in \"" + t.getDomain() + "\" matching template: " + t.getId(), matches.size());
		return matches;
	}
	
	protected boolean isExcluded(Concept c, Integer exclusionReport) throws TermServerScriptException {
		
		//These hierarchies have been excluded
		if (exclusions.contains(c)) {
			if (exclusionReport != null) {
				incrementSummaryInformation("Concepts excluded due to hierarchial exclusion");
				report (exclusionReport, c, "Hierarchial exclusion");
			}
			return true;
		}
		
		//Are we excluding sufficiently defined concepts with more than one substantial role group?
		if (excludeSdMultiRG && c.getDefinitionStatus().equals(DefinitionStatus.FULLY_DEFINED)) {
			boolean firstSubstantialRGDetected = false;
			for (RelationshipGroup g : c.getRelationshipGroups(CharacteristicType.INFERRED_RELATIONSHIP)) {
				if (g.size() > 1) {
					if (firstSubstantialRGDetected) {
						//So we're now on our 2nd one
						if (exclusionReport != null) {
							incrementSummaryInformation("Concepts excluded due to SD with multiple substantial role groups");
							report (exclusionReport, c, "Multi-RG exclusion");
						}
						return true;
					} else {
						firstSubstantialRGDetected = true;
					}
				}
			}
		}
		
		if (!includeOrphanet && gl.isOrphanetConcept(c)) {
			if (exclusionReport != null) {
				incrementSummaryInformation("Orphanet concepts excluded");
				report (exclusionReport, c, "Orphanet exclusion");
			}
			return true;
		}
		
		if (StringUtils.isEmpty(c.getFsn())) {
			if (exclusionReport != null) {
				warn("Skipping concept with no FSN: " + c.getConceptId());
				report (exclusionReport, c, "No FSN");
			}
			return true;
		}
		
		//We could ignore on the basis of a word, or SCTID
		String fsn = " " + c.getFsn().toLowerCase();
		for (String word : exclusionWords) {
			//word = " " + word + " ";
			if (fsn.contains(word)) {
				if (exclusionReport != null) {
					incrementSummaryInformation("Concepts excluded due to lexical match ");
					incrementSummaryInformation("Concepts excluded due to lexical match (" + word + ")");
					report (exclusionReport, c, "Lexical exclusion", word);
				}
				return true;
			}
		}
		
		if (inclusionWords.size() > 0 && !containsInclusionWord(c)) {
			incrementSummaryInformation("Concepts excluded due to lexical match failure");
			report (exclusionReport, c, "Lexical inclusion failure");
			return true;
		}
		
		//We're excluding complex templates that have a due to, or "after" attribute
		if (!includeComplexTemplates && isComplex(c)) {
			if (exclusionReport != null) {
				incrementSummaryInformation("Concepts excluded due to complexity");
				report (exclusionReport, c, "Complex templates excluded");
			}
			return true;
		}
		return false;
	}
	
	protected boolean isComplex(Concept c) {
		for (Concept excludedType : complexTemplateAttributes) {
			for (Relationship r : c.getRelationships(CharacteristicType.INFERRED_RELATIONSHIP, ActiveState.ACTIVE)) {
				if (r.getType().equals(excludedType)) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void report (Task task, Component component, Severity severity, ReportActionType actionType, Object... details) throws TermServerScriptException {
		Concept c = (Concept)component;
		char relevantTemplate = ' ';
		if (conceptToTemplateMap != null && conceptToTemplateMap.containsKey(c)) {
			relevantTemplate = conceptToTemplateMap.get(c).getId();
		}
		super.report (task, component, severity, actionType, SnomedUtils.translateDefnStatus(c.getDefinitionStatus()), relevantTemplate, details);
	}
	
	/**
	 * Within each group, check if there are any relationships which are entirely 
	 * redundant as more specific versions of the same type/value exist
	 */
	protected int removeRedundandRelationships(Task t, Concept c) throws TermServerScriptException {
		int changesMade = 0;
		AncestorsCache cache = gl.getAncestorsCache();
		Set<Relationship> removedRels = new HashSet<>();
		for (RelationshipGroup group : c.getRelationshipGroups(CharacteristicType.STATED_RELATIONSHIP)) {
			Set<Relationship> originalRels = group.getRelationships();
			for (Relationship originalRel : originalRels) {
				if (removedRels.contains(originalRel)) {
					continue;
				}
				for (Relationship potentialRedundancy : originalRels) {
					if (SnomedUtils.isMoreSpecific(originalRel, potentialRedundancy, cache)) {
						report (t, c, Severity.MEDIUM, ReportActionType.INFO, "Redundant relationship within group", potentialRedundancy, originalRel );
						removeRelationship(t, c, potentialRedundancy);
						removedRels.add(potentialRedundancy);
						changesMade++;
					}
				}
			}
		}
		return changesMade;
	}
	
	protected int removeRedundandGroups(Task t, Concept c) throws TermServerScriptException {
		int changesMade = 0;
		List<RelationshipGroup> originalGroups = new ArrayList<>(c.getRelationshipGroups(CharacteristicType.STATED_RELATIONSHIP));
		Set<RelationshipGroup> removedGroups = new HashSet<>();
		
		for (RelationshipGroup originalGroup : originalGroups) {
			if (removedGroups.contains(originalGroup) || originalGroup.size() == 0) {
				continue;
			}
			for (RelationshipGroup potentialRedundancy : originalGroups) {
				//Don't compare self, removed or empty groups
				if (originalGroup.getGroupId() == potentialRedundancy.getGroupId() ||
					potentialRedundancy.size() == 0 ||
					removedGroups.contains(potentialRedundancy)) {
					continue;
				}
				boolean aCoversB = SnomedUtils.covers(originalGroup, potentialRedundancy, gl.getAncestorsCache());
				boolean bCoversA = SnomedUtils.covers(potentialRedundancy, originalGroup, gl.getAncestorsCache());
				RelationshipGroup groupToRemove = null;
				if (aCoversB || bCoversA) {
					//If they're the same, remove the potential - likely to be a higher group number
					if (aCoversB && bCoversA && potentialRedundancy.size() <= originalGroup.size()) {
						groupToRemove = potentialRedundancy;
					} else if (aCoversB && potentialRedundancy.size() <= originalGroup.size()) {
						groupToRemove = potentialRedundancy;
					} else if (bCoversA && potentialRedundancy.size() >= originalGroup.size()) {
						groupToRemove = originalGroup;
					} else if (bCoversA && potentialRedundancy.size() < originalGroup.size()) {
						report (t, c, Severity.HIGH, ReportActionType.VALIDATION_CHECK, "Group of larger size appears redundant - check!");
						groupToRemove = originalGroup;
					} else {
						warn ("DEBUG HERE, Redundancy in " + c);
					}
					
					if (groupToRemove != null && groupToRemove.size() > 0) {
						removedGroups.add(groupToRemove);
						report (t, c, Severity.MEDIUM, ReportActionType.RELATIONSHIP_GROUP_REMOVED, "Redundant relationship group removed:", groupToRemove);
						for (Relationship r : groupToRemove.getRelationships()) {
							changesMade += removeRelationship(t, c, r);
						}
					}
				}
			}
		}
		if (changesMade > 0) {
			shuffleDown(t,c);
			for (RelationshipGroup g : c.getRelationshipGroups(CharacteristicType.STATED_RELATIONSHIP)) {
				report (t, c, Severity.LOW, ReportActionType.INFO, "Post redundancy removal group", g);
			}
		}
		return changesMade;
	}

	private void shuffleDown(Task t, Concept c) throws TermServerScriptException {
		List<RelationshipGroup> newGroups = new ArrayList<>();
		for (RelationshipGroup group : c.getRelationshipGroups(CharacteristicType.STATED_RELATIONSHIP)) {
			//Have we missed out the ungrouped group? fill in if so
			if (group.isGrouped() && newGroups.size() == 0) {
				newGroups.add(new RelationshipGroup(UNGROUPED));
			}
			//Since we're working with the true concept relationships here, this will have
			//the effect of changing the groupId in all affected relationships
			if (group.getGroupId() != newGroups.size()) {
				report (t, c, Severity.MEDIUM, ReportActionType.INFO, "Shuffling stated group " + group.getGroupId() + " to " + newGroups.size());
				group.setGroupId(newGroups.size());
				//If we have relationships without SCTIDs here, see if we can pinch them from inactive relationships
				int reuseCount = 0;
				for (Relationship moved : new ArrayList<>(group.getRelationships())) {
					if (StringUtils.isEmpty(moved.getId())) {
						Set<Relationship> existingInactives = c.getRelationships(moved, ActiveState.INACTIVE);
						if (existingInactives.size() > 0) {
							group.removeRelationship(moved);
							c.removeRelationship(moved, true);  //It's OK to force removal, the axiom will still exist.
							Relationship reuse = existingInactives.iterator().next();
							reuse.setActive(true);
							group.addRelationship(reuse);
							c.addRelationship(reuse);
							reuseCount++;
						}
					}
				}
				report (t, c, Severity.MEDIUM, ReportActionType.INFO, "Reused " + reuseCount + " inactivated Ids");
			}
			newGroups.add(group);
		}
	}
	
	protected boolean containsInclusionWord(Concept c) {
		String fsn = c.getFsn().toLowerCase();
		String pt = c.getPreferredSynonym().toLowerCase();
		for (String word : inclusionWords) {
			if (fsn.contains(word) || pt.contains(word)) {
				return true;
			}
		}
		return false;
	}
	
	protected void outputMetaData() throws TermServerScriptException {
		info("Outputting metadata tab");
		String user = jobRun == null ? "System" : jobRun.getUser();
		writeToReportFile (SECONDARY_REPORT, "Requested by: " + user);
		writeToReportFile (SECONDARY_REPORT, QUOTE + "Run against: " + subsetECL + QUOTE);
		writeToReportFile (SECONDARY_REPORT, "Project: " + project);
		if (!StringUtils.isEmpty(subsetECL)) {
			writeToReportFile (SECONDARY_REPORT, "Concepts considered: " + findConcepts(subsetECL).size());
		}
		writeToReportFile (SECONDARY_REPORT, "Templates: " );
		
		for (Template t : templates) {
			writeToReportFile (SECONDARY_REPORT,TAB + "Name: " + t.getName());
			writeToReportFile (SECONDARY_REPORT,QUOTE + TAB  + "Domain: " + t.getDomain() + QUOTE);
			writeToReportFile (SECONDARY_REPORT,TAB + "Documentation: " + t.getDocumentation());
			String stl = t.getLogicalTemplate().toString();
			stl = SnomedUtils.populateFSNs(stl);
			writeToReportFile (SECONDARY_REPORT,QUOTE + TAB + "STL: " +  stl + QUOTE);
			if (!StringUtils.isEmpty(t.getDomain())) {
				writeToReportFile (SECONDARY_REPORT, TAB + "Concepts considered: " + findConcepts(t.getDomain()).size());
			}
			writeToReportFile (SECONDARY_REPORT,TAB);
		}
	}
}


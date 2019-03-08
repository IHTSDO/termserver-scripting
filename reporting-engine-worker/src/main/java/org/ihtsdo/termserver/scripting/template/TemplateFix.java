package org.ihtsdo.termserver.scripting.template;

import java.io.IOException;
import java.util.*;

import org.snomed.authoringtemplate.domain.ConceptTemplate;
import org.snomed.authoringtemplate.domain.logical.*;
import org.ihtsdo.termserver.scripting.TermServerScript;
import org.ihtsdo.termserver.scripting.TermServerScriptException;
import org.ihtsdo.termserver.scripting.client.TemplateServiceClient;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.fixes.BatchFix;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;
import org.ihtsdo.termserver.scripting.util.StringUtils;

abstract public class TemplateFix extends BatchFix {
	
	Set<Concept> exclusions;
	List<String> exclusionWords;
	List<String> inclusionWords;
	boolean includeComplexTemplates = false;
	List<Concept> complexTemplateAttributes;
	boolean includeDueTos = false;
	
	String[] templateNames;
	List<Template> templates = new ArrayList<>();
	TemplateServiceClient tsc = new TemplateServiceClient(null, null);
	
	Map<Concept, Template> conceptToTemplateMap = new HashMap<>();

	protected TemplateFix(BatchFix clone) {
		super(clone);
	}
	
	protected void init(String[] args) throws TermServerScriptException {
		AttributeGroup.useDefaultValues = true;
		//We'll check these now so we know if there's some parsing error
		char id = 'A';
		for (int x = 0; x < templateNames.length; x++, id++) {
			Template t = loadLocalTemplate(id, templateNames[x]);
			validateTemplate(t);
			info ("Validated template: " + templateNames[x]);
		}
		super.init(args);
	}
	
	public void postInit(String[] tabNames, String[] columnHeadings, boolean csvOutput) throws TermServerScriptException {
		initTemplatesAndExclusions();
		super.postInit(tabNames, columnHeadings, csvOutput);
		info ("Post initialisation complete, with multiple tabs");
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
		exclusionWords.add("subluxation");
		exclusionWords.add("avulsion");
		exclusionWords.add("co-occurrent");
		exclusionWords.add("on examination");
		exclusionWords.add("complex");
		
		if (!includeComplexTemplates) {
			if (!includeDueTos) {
				exclusionWords.add("due to");
			}
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
	
	private void validateTemplate(Template t) {
		//Ensure that any repeated instances of identically named slots are the same
		Map<String, String> namedSlots = new HashMap<>();
		for (AttributeGroup g : t.getAttributeGroups()) {
			for (Attribute a : g.getAttributes()) {
				//Does this attribute have a named slot?
				if (!StringUtils.isEmpty(a.getSlotName())) {
					String attributeClause = a.getType().trim() + " = " + StringUtils.safelyTrim(a.getAllowableRangeECL()) + StringUtils.safelyTrim(a.getValue()); 
					if (namedSlots.containsKey(a.getSlotName())) {
						if (!attributeClause.equals(namedSlots.get(a.getSlotName()))) {
							throw new IllegalArgumentException("Named slots sharing the same name must be identical: " + attributeClause);
						}
					} else {
						namedSlots.put(a.getSlotName(), attributeClause);
					}
				}
			}
		}
	}

	protected Template loadLocalTemplate (char id, String fileName) throws TermServerScriptException {
		try {
			TermServerScript.info("Loading local tempate " + id + ": " + fileName );
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
			TermServerScript.info("Loading remote tempate " + id + ": " + templateName );
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
	
	protected Set<Concept> findTemplateMatches(Template t, Collection<Concept> concepts) throws TermServerScriptException {
		Set<Concept> matches = new HashSet<Concept>();
		info ("Examining " + concepts.size() + " concepts against template " + t);
		int conceptsExamined = 0;
		for (Concept c : concepts) {
			if (c.getConceptId().equals("234195006")) {
				debug ("Check template match here");
			}
			if (!c.isActive()) {
				warn ("Ignoring inactive concept returned by ECL: " + c);
				continue;
			}
			if (!isExcluded(c) && TemplateUtils.matchesTemplate(c, t, gl.getDescendantsCache(), CharacteristicType.INFERRED_RELATIONSHIP)) {
				//Do we already have a template for this concept?  
				//TODO Assign the most specific template if so
				if (conceptToTemplateMap.containsKey(c)) {
					Template existing = conceptToTemplateMap.get(c);
					Template moreSpecific = t.getId() > existing.getId() ? t : existing; 
					warn( c + "matches two templates: " + t.getId() + " & " + existing.getId() + " using most specific " + moreSpecific.getId());
					conceptToTemplateMap.put(c, moreSpecific);
				} else {
					conceptToTemplateMap.put(c, t);
				}
				matches.add(c);
			}
			if (++conceptsExamined % 1000 == 0) {
				print(".");
			}
		}
		println("");
		info (matches.size() + " concepts in " + subHierarchyECL + " matching template " + t);
		
		return matches;
	}

	@Override
	protected List<Component> loadLine(String[] lineItems) throws TermServerScriptException {
		// TODO Auto-generated method stub
		return null;
	}
	
	protected boolean isExcluded(Concept c) {
		//These hierarchies have been excluded
		if (exclusions.contains(c)) {
			incrementSummaryInformation("Concepts excluded due to hierarchial exclusion");
			return true;
		}
		
		if (gl.isOrphanetConcept(c)) {
			incrementSummaryInformation("Orphanet concepts excluded");
			return true;
		}
		
		if (StringUtils.isEmpty(c.getFsn())) {
			warn("Skipping concept with no FSN: " + c.getConceptId());
			return true;
		}
		
		//We could ignore on the basis of a word, or SCTID
		String fsn = " " + c.getFsn().toLowerCase();
		for (String word : exclusionWords) {
			//word = " " + word + " ";
			if (fsn.contains(word)) {
				//debug (c + "ignored due to fsn containing:" + word);
				incrementSummaryInformation("Concepts excluded due to lexical match");
				return true;
			}
		}
		
		//We're excluding complex templates that have a due to, or "after" attribute
		if (!includeComplexTemplates) {
			for (Concept excludedType : complexTemplateAttributes) {
				for (Relationship r : c.getRelationships(CharacteristicType.INFERRED_RELATIONSHIP, ActiveState.ACTIVE)) {
					if (r.getType().equals(excludedType)) {
						incrementSummaryInformation("Concepts excluded due to complexity");
						return true;
					}
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
	
	protected int removeRedundandGroups(Task t, Concept c) throws TermServerScriptException {
		int changesMade = 0;
		List<RelationshipGroup> originalGroups = new ArrayList<>(c.getRelationshipGroups(CharacteristicType.STATED_RELATIONSHIP));
		for (RelationshipGroup originalGroup : originalGroups) {
			for (RelationshipGroup potentialRedundancy : originalGroups) {
				//Don't compare self, or empty groups
				if (originalGroup.getGroupId() == potentialRedundancy.getGroupId() ||
					originalGroup.size() == 0) {
					continue;
				}
				if (SnomedUtils.isSameOrMoreSpecific(originalGroup, potentialRedundancy, gl.getAncestorsCache())) {
					if (originalGroup.size() != potentialRedundancy.size()) {
						throw new TermServerScriptException ("Code needs enhanced here to check that redundant group " +
										"does not contain more attributes than the original.  Check redundancy in both directions " +
										"and remove the smaller if applicable, or the higher group number if equivalent");
					}
					report (t, c, Severity.MEDIUM, ReportActionType.RELATIONSHIP_GROUP_REMOVED, "Redundant relationship group removed", potentialRedundancy);
					for (Relationship r : potentialRedundancy.getRelationships()) {
						changesMade += removeRelationship(t, c, r);
						if (true);
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

	private void shuffleDown(Task t, Concept c) {
		List<RelationshipGroup> newGroups = new ArrayList<>();
		for (RelationshipGroup group : c.getRelationshipGroups(CharacteristicType.STATED_RELATIONSHIP)) {
			if (!group.isGrouped() || group.size() > 0) {
				//Since we're working with the true concept relationships here, this will have
				//the effect of changing the groupId in all affected relationships
				group.setGroupId(newGroups.size());
				newGroups.add(group);
			} else if (group.isGrouped()){
				//Add an empty group skip group 0 and prevent shuffling group 1 into group 0
				newGroups.add(new RelationshipGroup(UNGROUPED));
			}
		}
	}
}


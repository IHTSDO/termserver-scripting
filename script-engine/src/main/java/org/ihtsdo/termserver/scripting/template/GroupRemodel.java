package org.ihtsdo.termserver.scripting.template;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Task;
import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ValidationFailure;

import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.fixes.BatchFix;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;
import org.snomed.authoringtemplate.domain.logical.*;

import com.google.common.io.Files;

/**
 * Where a concept has limited modeling, pull the most specific attributes available 
 * into group 1.  Skip any cases of multiple attributes types with values that are not in 
 * the same subhierarchy.
 */
public class GroupRemodel extends TemplateFix {
	
	Set<Concept> groupedAttributeTypes = new HashSet<>();
	Set<Concept> ungroupedAttributeTypes = new HashSet<>();
	Set<Concept> formNewGroupAround = new HashSet<>();
	Set<Relationship> removeRelationships = new HashSet<>();
	
	boolean skipMultipleUngroupedFindingSites = true;
	File alreadyProcessedFile;

	protected GroupRemodel(BatchFix clone) {
		super(clone);
	}
	
	public static void main(String[] args) throws TermServerScriptException, IOException {
		GroupRemodel app = new GroupRemodel(null);
		try {
			ReportSheetManager.targetFolderId = "15FSegsDC4Tz7vP5NPayGeG2Q4SB1wvnr"; //QI  / Group One Remodel
			app.init(args);
			app.loadProjectSnapshot(false);  //Load all descriptions
			app.postInit();
			app.processFile();
		} catch (Exception e) {
			info("Failed to Group Remodel due to " + e.getMessage());
			e.printStackTrace(new PrintStream(System.out));
		} finally {
			app.finish();
		}
	}
	
	protected void init(String[] args) throws TermServerScriptException {
		selfDetermining = true;
		runStandAlone = true; 
		classifyTasks = true;
		populateEditPanel = true;
		populateTaskDescription = true;
		additionalReportColumns = "CharacteristicType, Template, AFTER Stated, BEFORE Stated, Inferred";
		//includeComplexTemplates = true;
		
		formNewGroupAround.add(FINDING_SITE);
		formNewGroupAround.add(CAUSE_AGENT);
		formNewGroupAround.add(ASSOC_MORPH);
		
		if (exclusionWords == null) {
			exclusionWords = new ArrayList<>();
		}
		
		if (inclusionWords == null) {
			inclusionWords = new ArrayList<>();
		}
		/*
		subHierarchyECL =  "<< 125605004";  // QI-30 |Fracture of bone (disorder)|
		templateNames = new String[] {	"templates/Fracture of Bone Structure.json" }; /*,
										"templates/Fracture Dislocation of Bone Structure.json",
										"templates/Pathologic fracture of bone due to Disease.json"};
		
		// QI-36 Part 1 |Chronic inflammatory disorder (disorder)
		subHierarchyECL =  "<< 128294001 MINUS  (<< 128294001 : 246075003 |Causative agent (attribute)| = <<410607006 |Organism (organism)|)";
		templateNames = new String[] {"templates/Chronic Inflammatory Disorder.json"};
		exclusionWords.add("arthritis");
		
		// QI-36 Part 2 |Chronic inflammatory disorder (disorder)
		subHierarchyECL =  "<< 128294001 : 246075003 |Causative agent (attribute)| = <<410607006 |Organism (organism)|";
		templateNames = new String[] {"templates/Infectious Chronic Inflammatory Disorder.json"};
		exclusionWords.add("arthritis");
		
		subHierarchyECL =  "<< 126537000";  //QI-14 |Neoplasm of bone (disorder)|
		templateNames = new String[] {"templates/Neoplasm of Bone.json"};

		subHierarchyECL =  "<< 34014006"; //QI-15 + QI-23 |Viral disease (disorder)|
		templateNames = new String[] {	"templates/Infection caused by virus with optional bodysite.json"};
		
		subHierarchyECL = "<<87628006";  //QI-21 |Bacterial infectious disease (disorder)|
		templateNames = new String[] {	"templates/infection/Infection caused by bacteria.json"};
		
		subHierarchyECL =  "<< 95896000";  //QI-27  |Protozoan infection (disorder)|
		templateNames = new String[] {"templates/Infection caused by Protozoa with optional bodysite.json"};
		
		subHierarchyECL =  "<< 74627003";  //QI-48 |Diabetic Complication|
		templateNames = new String[] {	"templates/Complication co-occurrent and due to Diabetes Melitus.json",
				//"templates/Complication co-occurrent and due to Diabetes Melitus - Minimal.json"
				};
		
		subHierarchyECL =  "<< 3218000"; //QI-70 |Mycosis (disorder)|
		//subHierarchyECL =  "<< 276206000 |Superficial mycosis (disorder)|"; //QI-70 |Mycosis (disorder)|
		templateNames = new String[] {	"templates/infection/Infection caused by Fungus.json"};
		//removeRelationships.add(new Relationship(FINDING_SITE, ANAT_OR_ACQ_BODY_STRUCT));
		
		subHierarchyECL =  "<< 17322007"; //QI-116 |Parasite (disorder)|
		templateNames = new String[] {	"templates/Infection caused by Parasite.json"};
		
		
		subHierarchyECL =  "<< 125643001"; //QI-117 |Open wound| 
		templateNames = new String[] {	"templates/wound/wound of bodysite due to event.json" };
		exclusionWords.add("complication");
		exclusionWords.add("fracture");
		setExclusions(new String[] {"399963005 |Abrasion (disorder)|", "312608009 |Laceration - injury|"});
		includeDueTos = true;
		alreadyProcessedFile = new File(".QI-117_already_processed.txt");
		
		subHierarchyECL = "<<40733004|Infectious disease|"; //QI-159
		templateNames = new String[] {	"templates/infection/Infection NOS.json" };
		setExclusions(new String[] {"87628006 |Bacterial infectious disease (disorder)|","34014006 |Viral disease (disorder)|",
				"3218000 |Mycosis (disorder)|","8098009 |Sexually transmitted infectious disease (disorder)|", 
				"17322007 |Disease caused by parasite (disorder)|", "91302008 |Sepsis (disorder)|"});
		exclusionWords.add("shock");
		alreadyProcessedFile = new File(".QI-159_already_processed.txt");
		
		subHierarchyECL = "<<52515009"; //QI-173 |Hernia of abdominal cavity (disorder)|
		templateNames = new String[] {	"templates/hernia/Hernia of Body Structure.json"};
		excludeHierarchies = new String[] { "236037000 |Incisional hernia (disorder)|" };
		exclusionWords = new ArrayList<String>();
		exclusionWords.add("gangrene");
		exclusionWords.add("obstruction");
		
		subHierarchyECL = "<< 416462003 |Wound (disorder)|"; //QI-209
		setExclusions(new String[] {"125643001 |Open Wound|", 
									"416886008 |Closed Wound|",
									"312608009 |Laceration|",
									"283682007 |Bite Wound|",
									"399963005 |Abrasion|",
									"125670008 |Foreign Body|",
									"125667009 |Contusion (disorder)|"});
		templateNames = new String[] {	"templates/wound/wound of bodysite due to event.json"};
		inclusionWords.add("nail");
		includeDueTos = true;
		
		subHierarchyECL = "<<118616009"; //QI-252 |Neoplastic disease of uncertain behavior| 
		templateNames = new String[] {	"templates/Neoplastic Disease.json"};
		
		subHierarchyECL = "<<74627003";  //QI-119 |Diabetic Complication|
		templateNames = new String[] {	"templates/Complication due to Diabetes Melitus2.json"};
		includeComplexTemplates = true;
		
		subHierarchyECL = "< 85828009 |Autoimmune disease (disorder)|"; //QI-297
		templateNames = new String[] {	"templates/Autoimune.json" };
		
		subHierarchyECL = "< 233776003 |Tracheobronchial disorder|"; //QI-266
		templateNames = new String[] {	"templates/Tracheobronchial.json" };
		
		subHierarchyECL = "<< 417893002|Deformity|"; //QI-278
		templateNames = new String[] {	"templates/Deformity - disorder.json"};
		
		subHierarchyECL = "<<  126765001 |Gastrointestinal obstruction (disorder)|"; //QI-303
		templateNames = new String[] {	"templates/Gastrointestinal.json"};
		
		subHierarchyECL = "<< 276654001 |Congenital malformation (disorder)|"; //QI-286
		templateNames = new String[] {	"templates/Congenital Malformation.json"};
		
		subHierarchyECL = "<< 131148009|Bleeding|"; //QI-191
		templateNames = new String[] { "templates/Bleeding - disorder.json"};
		inclusionWords.add("disorder");
		*/
		subHierarchyECL = "<<362975008 |Degenerative disorder (disorder)|: 116676008 |Associated morphology (attribute)| = << 46595003 |Deposition (morphologic abnormality)| ";
		templateNames = new String[] {	"templates/Degenerative disorder.json"};
		includeComplexTemplates = true;
		
		super.init(args);
		
		//Ensure our ECL matches more than 0 concepts.  This will also cache the result
		boolean expectLargeResults = !safetyProtocols;
		boolean useLocalStoreIfSimple = false;
		if (!getArchiveManager().isAllowStaleData() && findConcepts(subHierarchyECL, false, expectLargeResults, useLocalStoreIfSimple).size() == 0) {
			throw new TermServerScriptException(subHierarchyECL + " returned 0 rows");
		}
	}
	
	public void postInit() throws TermServerScriptException {
		super.postInit();
		
		//Populate grouped and ungrouped attributes
		Iterator<AttributeGroup> groupIterator = templates.get(0).getAttributeGroups().iterator();

		ungroupedAttributeTypes = groupIterator.next().getAttributes().stream()
							.map(a -> gl.getConceptSafely(a.getType()))
							.collect(Collectors.toSet());
		
		groupedAttributeTypes = groupIterator.next().getAttributes().stream()
				.map(a -> gl.getConceptSafely(a.getType()))
				.collect(Collectors.toSet());
		
		//Now it might be that some ungrouped attribute types also appear grouped.
		//We won't call them 'ungrouped' in this case, since they'll be moved out
		ungroupedAttributeTypes.removeAll(groupedAttributeTypes);
		
	}

	@Override
	protected int doFix(Task task, Concept concept, String info) throws TermServerScriptException, ValidationFailure {
		Concept loadedConcept = loadConcept(concept, task.getBranchPath());
		
		//Record that we've looked at this file
		if (alreadyProcessedFile != null) {
			try {
				FileUtils.writeStringToFile(alreadyProcessedFile, loadedConcept.toString()+"\n", true);
			} catch (IOException e) {
				throw new TermServerScriptException("Failed to record already processed in " + alreadyProcessedFile,e);
			}
		}

		if (!loadedConcept.isActive()) {
			report(task, concept, Severity.CRITICAL, ReportActionType.VALIDATION_ERROR, "Attempt to remodel an inactive concept");
			return NO_CHANGES_MADE;
		}
		int changesMade = remodelConcept(task, loadedConcept, templates.get(0));
		if (changesMade > 0) {
			List<String> focusConceptIds = templates.get(0).getLogicalTemplate().getFocusConcepts();
			if (focusConceptIds.size() == 1) {
				checkAndSetProximalPrimitiveParent(task, loadedConcept, gl.getConcept(focusConceptIds.get(0)));
			} else {
				report (task, concept, Severity.CRITICAL, ReportActionType.VALIDATION_ERROR, "Cannot remodel PPP - template specifies multiple focus concepts");
			}
			
			try {
				updateConcept(task,loadedConcept,info);
			} catch (Exception e) {
				report(task, concept, Severity.CRITICAL, ReportActionType.API_ERROR, "Failed to save changed concept to TS: " + ExceptionUtils.getStackTrace(e));
			}
		}
		return changesMade;
	}

	private int remodelConcept(Task t, Concept c, Template template) throws TermServerScriptException {
		int changesMade = 0;

		//Create as many groups as required, but minimum 3
		int numConceptGroups = c.getMaxGroupId(CharacteristicType.STATED_RELATIONSHIP) + 1;
		int numTemplateGroups = template.getAttributeGroups().size();
		int maxGroups = Math.max(Math.max(numConceptGroups, numTemplateGroups),3);
		List<RelationshipGroup> groups = new ArrayList<>(maxGroups);
		
		//Map the group id in the concept to the group number in the template (and back!)
		Map<Integer, Integer> additionalGroupNums = new HashMap<>();
		
		//Get a copy of the stated and inferred modelling "before"
		String statedForm = SnomedUtils.getModel(c, CharacteristicType.STATED_RELATIONSHIP);
		String inferredForm = SnomedUtils.getModel(c, CharacteristicType.INFERRED_RELATIONSHIP);
		
		for (int groupId = 0; groupId < maxGroups; groupId++) {
			//Prepare for groups 0 and 1 anyway
			RelationshipGroup conceptGroup = c.getRelationshipGroup(CharacteristicType.STATED_RELATIONSHIP, groupId);
			//Clone so that we're able to modify actual relationship once we've finished moving rels around
			if (conceptGroup != null) {
				groups.add(conceptGroup.clone());
			} else if (groups.size() <= groupId) {
				groups.add(new RelationshipGroup(groupId));
			}
		}
		
		//Remove any ungrouped relationship types that exist in the template as grouped.  They'll get picked up from 
		//inferred if they're still needed.  Note that this just empties out the clone, not the original rel
		removeGroupedTypes(c, groups.get(UNGROUPED), template);
		
		//Similarly, remove any multiple attribute types
		removeMultiples(groups);
		
		//If we have any empty grouped groups, shuffle them down so we start at 1 with no gaps
		groups = shuffleDown(groups);
		
		if (groups.size() < 2) {
			debug ("Debug Here!");
		}
		
		//Now if we have multiple template groups, align those to any existing attributes
		if (template.getAttributeGroups().size() > 2 && groups.get(1).size() > 0) {
			alignTemplateToExistingGroups(groups, template);
		}
		
		//Work through the attribute groups in the template and see if we can satisfy them
		for (int templateGroupId = 0; templateGroupId <  template.getAttributeGroups().size(); templateGroupId++) {
			//Are we adding more groups as we find attributes to group around?  Successive template groups
			//need to be mapped to later (+n) groups in the concept, otherwise we'll see, like Interprets
			//merged in with causative agents
			AttributeGroup templateGroup = template.getAttributeGroups().toArray(new AttributeGroup[0])[templateGroupId];
			boolean templateGroupOptional = templateGroup.getCardinalityMin().equals("0");
			for (Attribute a : templateGroup.getAttributes()) {
				changesMade += findAttributeToState(t, template, c, a, groups, templateGroupId, CharacteristicType.INFERRED_RELATIONSHIP, templateGroupOptional, additionalGroupNums);
			}

			//Have we formed an additional group? Find different attributes if so
			Iterator<Integer> additionalGroupItr = additionalGroupNums.keySet().iterator();
			while (additionalGroupItr.hasNext()) {
				RelationshipGroup additionalGroup = groups.get(additionalGroupItr.next());
				if (additionalGroup != null && !additionalGroup.isEmpty()) {
					//If we have multiple template groups to choose from, which one is a best fit for this additional group?
					AttributeGroup matchedTemplateGroup = calculateBestTemplateGroup(c, additionalGroup, template);
					
					//We might update our map of what template groups our new attribute group relates to
					additionalGroupNums.put(additionalGroup.getGroupId(), matchedTemplateGroup.getGroupId());
					
					for (Attribute a : matchedTemplateGroup.getAttributes()) {
						//Does this group ALREADY satisfy the template attribute?
						if (TemplateUtils.containsMatchingRelationship(additionalGroup, a, null, this)) {
							continue;
						}
						//Don't check stated relationships because we could then run code to work with a single relationship when 
						//we should be choosing between multiple of the same type available in the inferred view
						changesMade += findAttributeToState(t, template, c, a, groups, additionalGroup.getGroupId(), CharacteristicType.INFERRED_RELATIONSHIP, templateGroupOptional, additionalGroupNums);
					}
				}
			}
		}
		
		//Do we have any relationships to remove?
		for (Relationship removeRel : removeRelationships) {
			for (RelationshipGroup group : groups) {
				for (Relationship r : new ArrayList<>(group.getRelationships())) {
					if (r.equalsTypeValue(removeRel)) {
						group.removeRelationship(r);
						report (t, c, Severity.MEDIUM, ReportActionType.RELATIONSHIP_INACTIVATED, "Removed unwanted: " + r);
					}
				}
			}
		}
		
		int ignoreUngroupedMoves = changesMade;
		
		//Now work through all relationship and move any ungrouped attributes out of groups
		for (Relationship r : c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, ActiveState.ACTIVE)) {
			if (r.getGroupId() != UNGROUPED && ungroupedAttributeTypes.contains(r.getType())) {
				int originalGroup = r.getGroupId();
				r.setGroupId(UNGROUPED);
				report (t, c, Severity.MEDIUM, ReportActionType.RELATIONSHIP_MODIFIED, "Ungrouped relationship moved out of group " + originalGroup +": " + r);
				changesMade++;
			}
		}
		
		//Do we end up with any single relationships grouped?  Make ungrouped if so
		for (RelationshipGroup group : groups) {
			if (group.getGroupId() != UNGROUPED) {
				if (group.getRelationships().size() == 1) {
					Relationship lonelyRelationship = group.getRelationships().get(0);
					groups.set(group.getGroupId(), new RelationshipGroup(group.getGroupId()));
					lonelyRelationship.setGroupId(UNGROUPED);
					if (!groups.get(UNGROUPED).containsTypeValue(lonelyRelationship)) {
						groups.get(UNGROUPED).addRelationship(lonelyRelationship);
					}
				}
			}
		}
		
		if (ignoreUngroupedMoves > 0) {
			changesMade += applyRemodelledGroups(t,c,groups);
			changesMade += removeRedundandGroups(t,c);
			selfGroupAttributes(t,c);
			String modifiedForm = SnomedUtils.getModel(c, CharacteristicType.STATED_RELATIONSHIP);
			
			//Now check that we've actually ended up making actual changes
			if (modifiedForm.equals(statedForm)) {
				throw new ValidationFailure(c, "Stated modelling unchanged");
			}
			report (t, c, Severity.LOW, ReportActionType.RELATIONSHIP_GROUP_ADDED, modifiedForm, statedForm, inferredForm);
		}
		return changesMade;
	}

	private List<RelationshipGroup> shuffleDown(List<RelationshipGroup> groups) {
		List<RelationshipGroup> newGroups = new ArrayList<>();
		for (RelationshipGroup group : groups) {
			if (!group.isGrouped() || group.size() > 0) {
				group.setGroupId(newGroups.size());
				newGroups.add(group);
			}
		}
		//We always need 3 groups, even if they're empty
		while (newGroups.size() < 3) {
			newGroups.add(new RelationshipGroup(newGroups.size()));
		}
		return newGroups;
	}

	private AttributeGroup calculateBestTemplateGroup(Concept c, RelationshipGroup additionalGroup, Template template) throws TermServerScriptException {
		//First work out which concept group is the best match for our new relationship group
		RelationshipGroup bestConceptGroupMatch = null;
		int bestMatchCount = 0;
		for (RelationshipGroup g : c.getRelationshipGroups(CharacteristicType.INFERRED_RELATIONSHIP)) {
			int thisMatchCount = 0;
			for (Relationship conceptR : g.getRelationships()) {
				for (Relationship additionalR : additionalGroup.getRelationships()) {
					if (conceptR.equalsTypeValue(additionalR)) {
						thisMatchCount++;
					}
				}
			}
			if (thisMatchCount >= bestMatchCount) {
				bestConceptGroupMatch = g;
				bestMatchCount = thisMatchCount;
			}
		}
		
		//Now work out which template group is best aligned to this concept group
		AttributeGroup bestTemplateGroupMatch = null;
		bestMatchCount = 0;
		for (AttributeGroup attributeGroup : template.getAttributeGroups()) {
			int thisMatchCount = 0;
			for (Relationship r : bestConceptGroupMatch.getRelationships()) {
				for (Attribute a : attributeGroup.getAttributes()) {
					if (TemplateUtils.matchesAttribute(r, a, null, this)) {
						thisMatchCount++;
					}
				}
			}
			if (thisMatchCount >= bestMatchCount) {
				bestTemplateGroupMatch = attributeGroup;
				bestMatchCount = thisMatchCount;
			}
		}
		return bestTemplateGroupMatch;
	}

	private void alignTemplateToExistingGroups(List<RelationshipGroup> groups, Template template) throws TermServerScriptException {
		Map<Integer, int[]> groupScores = calculateConceptGroupAlignment (groups, template);
		List<AttributeGroup> newOrder = new ArrayList<>();
		List<AttributeGroup> oldOrder = template.getAttributeGroups();
		
		//So what is the "best" order for our templates groups to be in?
		for (int conceptGroupId = 0 ; conceptGroupId <= groups.size(); conceptGroupId++) {
			if (groupScores.containsKey(conceptGroupId)) {
				int[] groupScore = groupScores.get(conceptGroupId);
				Integer bestScoringGroup = null;
				int bestScore = 0;
				for (int templateGroupId = 0; templateGroupId < groupScore.length; templateGroupId++) {
					int thisScore = groupScore[templateGroupId];
					if (bestScoringGroup == null || thisScore > bestScore ) {
						if (!newOrder.contains(oldOrder.get(templateGroupId))) {
							bestScore = thisScore;
							bestScoringGroup = templateGroupId;
						}
					}
				}
				if (bestScoringGroup != null) {
					newOrder.add(oldOrder.get(bestScoringGroup));
				}
			}
		}
		//Now add in any template groups we've not allocated
		oldOrder.removeAll(newOrder);
		newOrder.addAll(oldOrder);
		template.setAttributeGroups(newOrder);
	}
	
	private Map<Integer, int[]> calculateConceptGroupAlignment (List<RelationshipGroup> groups, Template template) throws TermServerScriptException {
		//Each concept group Id gets a score when considered against the template group
		Map<Integer, int[]> groupScores = new HashMap<>();
		int attributeGroupId = 0;
		for (AttributeGroup attributeGroup : template.getAttributeGroups()) {
			for (RelationshipGroup conceptGroup : groups) {
				int score = 0;
				for (Relationship r : conceptGroup.getRelationships()) {
					for (Attribute a : attributeGroup.getAttributes()) {
						if (TemplateUtils.matchesAttribute(r, a, null, this)) {
							score++;
						}
					}
				}
				//Have we created a score list for this concept group already?
				int[] scores = groupScores.get(conceptGroup.getGroupId());
				if (scores == null) {
					scores = new int[template.getAttributeGroups().size()];
					groupScores.put(conceptGroup.getGroupId(), scores);
				}
				scores[attributeGroupId] = score;
			}
			attributeGroupId++;
		}
		return groupScores;
	}

	private void removeMultiples(List<RelationshipGroup> groups) {
		for (RelationshipGroup group : groups) {
			Set<Concept> typesSeen = new HashSet<>();
			for (Relationship r : new ArrayList<>(group.getRelationships())) {
				if (typesSeen.contains(r.getType())) {
					group.removeRelationship(r);
				} else {
					typesSeen.add(r.getType());
				}
			}
		}
		
	}

	private void removeGroupedTypes(Concept c, RelationshipGroup group0, Template t) {
		//Work out all attribute types grouped in the template
		List<Concept> allowTypes = new ArrayList<>();
		List<Concept> removeTypes = new ArrayList<>();
		boolean ungrouped = true;
		for (AttributeGroup attribGroup : t.getAttributeGroups()) {
			if (ungrouped == true) {
				//ignore any that are also ungrouped
				allowTypes.addAll(attribGroup.getAttributes().stream()
						.map(a -> gl.getConceptSafely(a.getType()))
						.collect(Collectors.toList()));
				ungrouped = false;
			} else {
				removeTypes.addAll(attribGroup.getAttributes().stream()
						.map(a -> gl.getConceptSafely(a.getType()))
						.collect(Collectors.toList()));
			}
		}
		removeTypes.removeAll(allowTypes);
		for (Relationship r : new ArrayList<Relationship>(group0.getRelationships())) {
			if (removeTypes.contains(r.getType())) {
				group0.removeRelationship(r);
			}
		}
	}

	private int findAttributeToState(Task t, Template template, Concept c, Attribute a, List<RelationshipGroup> groups, int groupOfInterest, CharacteristicType charType, boolean templateGroupOptional, Map<Integer, Integer> additionalGroupNums) throws TermServerScriptException {
		RelationshipGroup group = groups.get(groupOfInterest);
		
		//Do we have this attribute type in the inferred form?
		Concept type = gl.getConcept(a.getType());
		
		//Is this value hard coded in the template?  Only want to do this where the template group is required.
		if (a.getValue() != null && !templateGroupOptional) {
			Relationship constantRel = new Relationship(c, type, gl.getConcept(a.getValue()), group.getGroupId());
			//Don't add if our group already contains this, or something more specific
			if (!group.containsTypeValue(type, gl.getDescendantsCache().getDescendentsOrSelf(a.getValue()))) {
				//Does this constant already exist ungrouped?
				if (groups.get(UNGROUPED).containsTypeValue(constantRel)) {
					Relationship ungroupedConstant = groups.get(UNGROUPED).getTypeValue(constantRel);
					groups.get(UNGROUPED).removeRelationship(ungroupedConstant);
					ungroupedConstant.setGroupId(group.getGroupId());
					group.addRelationship(ungroupedConstant);
					report (t, c, Severity.LOW, ReportActionType.RELATIONSHIP_ADDED, "Template specified constant, moved from group 0: " + ungroupedConstant);
				} else {
					group.addRelationship(constantRel);
					report (t, c, Severity.LOW, ReportActionType.RELATIONSHIP_ADDED, "Template specified constant: " + constantRel);
				}
				removeRedundancies(constantRel, group);
				return CHANGE_MADE;
			}
			return NO_CHANGES_MADE;
		}
		
		//Otherwise attempt to satisfy from existing relationships
		Set<Concept> values = SnomedUtils.getTargets(c, new Concept[] {type}, charType);
		
		//Remove the less specific values from this list
		if (values.size() > 1) {
			SnomedUtils.removeRedundancies(values);
		}
		
		//Also potentially remove any values if we've been told to remove that relationship
		for (Relationship removeRel : removeRelationships) {
			if (removeRel.getType().equals(type)) {
				values.remove(removeRel.getTarget());
				debug("Removed potential relationship " + removeRel);
			}
		}
		
		//Do we have a single value, or should we consider creating an additional grouping?
		boolean additionNeeded = true;
		if (values.size() == 0) {
			return NO_CHANGES_MADE;
		} else if (values.size() == 1) {
			//If we're pulling from stated rels, move any group 0 instances to the new group id
			if (charType.equals(CharacteristicType.STATED_RELATIONSHIP)) {
				//List<Relationship> ungroupedRels = c.getRelationships(charType, type, UNGROUPED); <- Was allowing same rel into mutiple groups
				List<Relationship> ungroupedRels = groups.get(UNGROUPED).getRelationships().stream()
						.filter(r -> r.getType().equals(type))
						.collect(Collectors.toList());
				if (ungroupedRels.size() == 1) {
					Relationship ungroupedRel = ungroupedRels.get(0);
					groups.get(UNGROUPED).removeRelationship(ungroupedRel);
					//Make a clone so we're not immediately affecting the underlying concept
					ungroupedRel = ungroupedRel.clone(ungroupedRel.getId());
					ungroupedRel.setGroupId(group.getGroupId());  //We want to retain the SCTID
					group.addRelationship(ungroupedRel); 
					report (t, c, Severity.LOW, ReportActionType.RELATIONSHIP_MODIFIED, "Ungrouped relationship moved to group " + group.getGroupId() + ": " + ungroupedRel);
					return CHANGE_MADE;
				} else if (ungroupedRels.size() > 1) {
					warn ("Consider this case");
				}
			}
			Concept value = values.iterator().next();
			if (value == null) {
				warn ("Should not be null");
			}
			//If this relationship type/value doesn't already exist in this group, stated, add it
			Relationship r = new Relationship (c, type, value, group.getGroupId());
			if (additionNeeded && !group.containsTypeValue(r)) {
				group.addRelationship(r);
				//Remove any attributes already present that are less specific than this one
				removeRedundancies(r, group);
				return CHANGE_MADE;
			} else {
				return NO_CHANGES_MADE;
			}
		} else if (values.size() > 2) {
			//We can only deal with 2
			throw new ValidationFailure (c , "Multiple non-subsuming " + type + " values : " + values.stream().map(v -> v.toString()).collect(Collectors.joining(" and ")));
		} else {
			//Has our group been populated with attributes that we could use to work out which (inferred) group we should pull from?
			return considerAdditionalGrouping(t, template, c, type, groups, groupOfInterest, charType, values, groupOfInterest == UNGROUPED, additionalGroupNums);
		}
	}

	private int considerAdditionalGrouping(Task t, Template template, Concept c, Concept type, List<RelationshipGroup> groups,
			int groupOfInterest, CharacteristicType charType, Set<Concept> values, boolean ungrouped, Map<Integer, Integer> additionalGroupNums) throws TermServerScriptException {
		//Sort the values alphabetically to ensure the right one goes into the right group
		List<Concept> disjointAttributeValues = new ArrayList<>(values);
		disjointAttributeValues.sort(Comparator.comparing(Concept::getFsn));
		
		//If it's a finding site and either attribute is in group 0, we'll leave it there
		//This is specific to diabetes.  When putting in back in, we need to find a way to 
		//retain these relationships in group 0, because if they're not modelled, they'll be removed.
		//Actually, we could just skip them in the first place.
		/*if (type.equals(FINDING_SITE) && (( c.getRelationships(charType, type, disjointAttributeValues.get(0), UNGROUPED, ActiveState.ACTIVE).size() > 0) ||
				c.getRelationships(charType, type, disjointAttributeValues.get(1), UNGROUPED, ActiveState.ACTIVE).size() > 0 )) {
			debug ("Finding site in group 0, not forming 2nd group: " + c );
			return NO_CHANGES_MADE;
		}*/
		
		//Does the template specify one of the values specifically in group 0?  No need to move
		//to an additional group in that case.
		for ( Attribute a : template.getLogicalTemplate().getUngroupedAttributes()) {
			if (a.getValue() != null && a.getType().equals(type.getConceptId())) {
				for (Concept value : values) {
					if (a.getValue().equals(value.getConceptId())) {
						debug ( type + "= " + value + " specified in template, no additional group required." );
						return NO_CHANGES_MADE;
					}
				}
			}
		}
		
		//Is this an attribute that we might want to form a new group around?
		if (formNewGroupAround.contains(type)) {
			//Sort values so they remain with other attributes they're already grouped with
			//Issue here when the same type/value appears in multiple groups. Multiple up if so
			Concept[] affinitySorted = sortByAffinity(disjointAttributeValues, type, c, groups, ungrouped);
			//Add the attributes to the appropriate group.  We only need to do this for the first
			//pass, since that will assign both values
			int groupId = 0;
			int changesMade = 0;
			for (Concept value : affinitySorted) {
				if (groupId >= groups.size()) {
					warn("Check here!");
				}
				
				if (value != null) {
					if (groups.get(groupId) == null) {
						groups.set(groupId, new RelationshipGroup(groupId));
					}
					
					if (groups.get(groupId).isEmpty()) {
						//Record that we're starting an additional group here
						additionalGroupNums.put(groupId, groupOfInterest);
					}
					
					Relationship r = new Relationship(c,type,value, groupId);
					if (!groups.get(groupId).containsTypeValue(r)) {
						groups.get(groupId).addRelationship(r);
						
						//Can we remove a less specific relationship?
						for (Relationship possibleAncestor : new ArrayList<>(groups.get(groupId).getRelationships())) {
							if (possibleAncestor.getType().equals(r.getType()) && gl.getAncestorsCache().getAncestors(r.getTarget()).contains(possibleAncestor.getTarget())) {
								groups.get(groupId).removeRelationship(possibleAncestor);
							}
						}
						changesMade++;
					}
				}
				if (!ungrouped) {
					//Don't need separate groups when considering ungrouped attributes
					groupId++;
				}
			}
			return changesMade;
		} else { 
			//If we've *already* formed a new group, then we could use it.
			if (!additionalGroupNums.isEmpty()) {
				//TODO We'll need to loop through all the additional groups created for this template
				RelationshipGroup additionalGroup = groups.get(additionalGroupNums.values().iterator().next());
				Concept[] affinitySorted = sortByAffinity(disjointAttributeValues, type, c, groups, ungrouped);
				Relationship r = new Relationship(c,type,affinitySorted[1], 2);
				if (!additionalGroup.containsTypeValue(r)) {
					additionalGroup.addRelationship(r);
					return CHANGE_MADE;
				} else {
					return NO_CHANGES_MADE;
				}
			}
		}
		return NO_CHANGES_MADE;
	}

	/* Sort the two attribute values so that they'll "chum up" with other attributes that they're
	 * already grouped with in the inferred form.
	 */
	private Concept[] sortByAffinity(List<Concept> values, Concept type, Concept c, List<RelationshipGroup> groups, boolean ungrouped) throws TermServerScriptException {
		Concept[] sortedValues = new Concept[groups.size()];
		//Do we already have attributes in groups which should be grouped with one of our values?
		nextValue:
		for (Concept value : values) {
			Relationship proposedRel = new Relationship (type, value);
			
			//In fact, we might already have one of these values stated in a group, in which case the affinity is already set
			//Or a less specific one that we could replace
			int idx=1;
			for (RelationshipGroup group : groups) {
				if (group!= null && (group.containsTypeValue(proposedRel) || group.containsTypeValue(type, gl.getAncestorsCache().getAncestorsOrSelf(value)))) {
					//If we're considering grouped attributes, and we find one ungrouped, move it up
					if (group.getGroupId() == UNGROUPED && !ungrouped) {
						sortedValues[idx] = value;
					} else {
						sortedValues[group.getGroupId()] = value;
					}
					//We need to check for other instances of the same type/value so continue through the groups
					idx++;
				}
			}
			
			//If we've found stated as many items of this type value as are inferred, there's no need to 
			//search the inferred groups
			int inferredCount = SnomedUtils.appearsInGroups(c, proposedRel, CharacteristicType.INFERRED_RELATIONSHIP).size();
			int statedCount = SnomedUtils.appearances(sortedValues, value);
			if (statedCount < inferredCount) {
				for (int groupId = ungrouped ? 0 : 1 ; groupId < groups.size(); groupId++) {
					//Loop through other attributes already set in this stated group, and see if we can find them
					//grouped in the inferred form with our proposed new relationship
					RelationshipGroup group = groups.get(groupId);
					if (group != null && !group.isEmpty()) {
						for (Relationship r : group.getRelationships()) {
							if (!r.equalsTypeValue(proposedRel) && SnomedUtils.isGroupedWith(r, proposedRel, c, CharacteristicType.INFERRED_RELATIONSHIP)) {
								//adjust the target stated group Id to account for gaps in inferred group numbering
								int statedGroupId = shuffDownInferredGroupId(r.getGroupId(), c);
								if (sortedValues[statedGroupId] == null) {
									sortedValues[statedGroupId] = value;
									continue nextValue;
								} 
								//It's OK if we found our value grouped with this relationship but couldn't use it.
								//It might also be grouped with it in another group, so carry on
							}
						}
					}
				}
			}
		}
		
		//Remove any we've assigned from the list
		values.removeAll(Arrays.asList(sortedValues));
		
		//If we've assigned one, we can auto assign the other.
		//Or if neither, keep the original order ie alphabetical
		Iterator<Concept> iter = values.iterator();
		for (int i = ungrouped ? 0 : 1; i < sortedValues.length && iter.hasNext(); i++) {
			if (sortedValues[i] == null && iter.hasNext()) {
				sortedValues[i] = iter.next();
			}
		}
		return sortedValues;
	}

	private int shuffDownInferredGroupId(int groupId, Concept c) {
		//work our way up to groupId, skipping any non-populated groups
		int statedGroupId = 0;
		List<RelationshipGroup> inferredGroups = new ArrayList<>(c.getRelationshipGroups(CharacteristicType.INFERRED_RELATIONSHIP));
		for (int i=0; i < groupId; i++) {
			if (inferredGroups.get(i) != null && !inferredGroups.get(i).isEmpty()) {
				statedGroupId++;
			}
		}
		return statedGroupId;
	}

	private void removeRedundancies(Relationship r, RelationshipGroup group) throws TermServerScriptException {
		//Do we have other attributes of this type that are less specific than r?
		for (Relationship potentialRedundancy : new ArrayList<>(group.getRelationships())) {
			if (SnomedUtils.isMoreSpecific(r, potentialRedundancy, gl.getAncestorsCache())) {
				group.getRelationships().remove(potentialRedundancy);
			}
		}
	}

	protected List<Component> identifyComponentsToProcess() throws TermServerScriptException {
		//Find concepts that only have ungrouped attributes, or none at all.
		List<Concept> processMe = new ArrayList<>();
		
		//Have we already processed concepts in this area?
		Set<Concept> alreadyProcessed = new HashSet<>();
		if (alreadyProcessedFile !=null && alreadyProcessedFile.exists()) {
			try {
				for (String conceptStr : Files.readLines(alreadyProcessedFile, Charset.forName("utf-8"))) {
					alreadyProcessed.add(gl.getConcept(conceptStr));
				}
			} catch (Exception e) {
				throw new TermServerScriptException("Unable to read already processed concepts from " + alreadyProcessedFile, e);
			}
			info ("Skipping " + alreadyProcessed.size() + " concepts declared as already processed in " + alreadyProcessedFile);
		}
		
		for (Concept c : findConcepts(subHierarchyECL)) {
		//for (Concept c : Collections.singleton(gl.getConcept("231937002"))) {
			if (inclusionWords.size() > 0) {
				if (!containsInclusionWord(c)) {
					incrementSummaryInformation("Skipped as doesn't contain inclusion word");
					continue;
				}
			}
			if (alreadyProcessed.contains(c)) {
				incrementSummaryInformation("Skipped as already processed");
				continue;
			}
			//At THIS point in the evolution of the code, attempt to remodel any concept
			//where the stated attribute do not match the inferred OR where the concept
			//is not aligned to the template
			if (!isExcluded(c, false)) {
				if (!SnomedUtils.inferredMatchesStated(c)) {
					processMe.add(c);
				} else {
					//just check we don't match any of the other templates in the stated form
					//Eg having two ungrouped finding sites for complications of diabetes
					boolean matchesTemplate = false;
					for (Template template : templates) {
						if (TemplateUtils.matchesTemplate(c, template, 
								this, 
								CharacteristicType.STATED_RELATIONSHIP, 
								true //Do allow additional unspecified ungrouped attributes
								)) {
							matchesTemplate = true;
							break;
						}
					}
					if (!matchesTemplate) {
						processMe.add(c);
					}
				}
			}
		}
		processMe.sort(Comparator.comparing(Concept::getFsn));
		List<Component> firstPassComplete = firstPassRemodel(processMe);
		return firstPassComplete;
	}
	
	protected boolean isExcluded(Concept c, boolean quiet) throws TermServerScriptException {
		if (skipMultipleUngroupedFindingSites) {
			if (c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, FINDING_SITE, UNGROUPED).size() > 1) {
				warn("Excluding due to multiple ungrouped finding sites: " + c);
				return true;
			}
		}
		return super.isExcluded(c, null);
	}

	private List<Component> firstPassRemodel(List<Concept> processMe) throws TermServerScriptException {
		setQuiet(true);
		List<Component> firstPassComplete = new ArrayList<>();
		List<ValidationFailure> failures = new ArrayList<>();
		Set<Concept> noChangesMade = new HashSet<>();
		for (Concept c : processMe) {
			try {
				Concept cClone = c.cloneWithIds();
				int changesMade = remodelConcept(null, cClone, templates.get(0));
				if (changesMade > 0) {
					firstPassComplete.add(c);
				} else {
					noChangesMade.add(c);
				}
			} catch (ValidationFailure vf) {
				failures.add(vf);
			}
		}
		setQuiet(false);
		for (ValidationFailure failure : failures) {
			report (failure);
		}
		for (Concept unchanged : noChangesMade) {
			report ((Task)null, unchanged, Severity.NONE, ReportActionType.NO_CHANGE, "");
		}
		info("First pass attempt at remodel complete, " + firstPassComplete.size() + " concepts identified to change from an initial " + processMe.size() + ". " + noChangesMade.size() + " had no changes.");
		return firstPassComplete;
	}

}

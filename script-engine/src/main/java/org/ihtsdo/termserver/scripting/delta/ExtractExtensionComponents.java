package org.ihtsdo.termserver.scripting.delta;

import java.io.File;
import java.io.IOException;
import java.util.*;

import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component;
import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.AxiomUtils;
import org.ihtsdo.termserver.scripting.client.TermServerClient;

import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;
import org.snomed.otf.owltoolkit.conversion.AxiomRelationshipConversionService;
import org.snomed.otf.owltoolkit.conversion.ConversionException;
import org.snomed.otf.owltoolkit.domain.AxiomRepresentation;

/**
 * Class form a delta of specified concepts from some edition and 
 * promote those (along with attribute values and necessary ancestors)
 * into the core module.
 * TODO Load in the MRCM and properly populate the Never Group attributes
 */
public class ExtractExtensionComponents extends DeltaGenerator {
	
	private List<Component> allIdentifiedConcepts;
	private List<Component> allModifiedConcepts = new ArrayList<>();
	private Map<String, Concept> loadedConcepts = new HashMap<>();
	TermServerClient secondaryConnection;
	String targetModuleId = SCTID_CORE_MODULE;
	private static String secondaryCheckPath = "MAIN";
	private AxiomRelationshipConversionService axiomService = new AxiomRelationshipConversionService (new HashSet<Long>());
	
	public static void main(String[] args) throws TermServerScriptException, IOException, InterruptedException {
		ExtractExtensionComponents delta = new ExtractExtensionComponents();
		try {
			delta.runStandAlone = true;
			delta.moduleId = "911754081000004104"; //Nebraska Lexicon Pathology Synoptic module
			//delta.moduleId = "731000124108";  //US Module
			//delta.moduleId = "32506021000036107"; //AU Module
			delta.init(args);
			//Recover the current project state from TS (or local cached archive) to allow quick searching of all concepts
			delta.loadProjectSnapshot(false);  //Not just FSN, load all terms with lang refset also
			//We won't incude the project export in our timings
			delta.additionalReportColumns = "ACTION_DETAIL, DEF_STATUS, PARENT, Detail, Additional Detail";
			delta.postInit();
			delta.startTimer();
			delta.processFile();
			delta.outputModifiedComponents(true);
			delta.flushFiles(false, true); //Need to flush files before zipping
			SnomedUtils.createArchive(new File(delta.outputDirName));
		} finally {
			delta.finish();
		}
	}
	
	protected void init (String[] args) throws TermServerScriptException {
		super.init(args);
		info ("Select an environment for live secondary checking ");
		for (int i=0; i < environments.length; i++) {
			println ("  " + i + ": " + environments[i]);
		}
		print ("Choice: ");
		String choice = STDIN.nextLine().trim();
		int envChoice = Integer.parseInt(choice);
		String secondaryURL = environments[envChoice];
		
		if (!secondaryURL.equals(url)) {
			print ("Please enter your authenticated cookie for connection to " + url + " : ");
			String secondaryCookie = STDIN.nextLine().trim();
			secondaryConnection = createTSClient(url, secondaryCookie);
		} else {
			println ("Existing authentication cookie will be used for secondary connection");
			secondaryConnection = createTSClient(url, authenticatedCookie);
		}
	}

	protected List<Component> processFile() throws TermServerScriptException {
		allIdentifiedConcepts = super.processFile();
		addSummaryInformation("Concepts specified", allIdentifiedConcepts.size());
		initialiseSummaryInformation("Unexpected dependencies included");
		info ("Extracting specified concepts");
		for (Component thisComponent : allIdentifiedConcepts) {
			Concept thisConcept = (Concept)thisComponent;
			
			/*if (thisConcept.getConceptId().equals("2301000004107")) {
				debug("Here");
			}*/
			
			//If we don't have a module id for this identified concept, then it doesn't properly exist in this release
			if (thisConcept.getModuleId() == null) {
				report (thisConcept, Severity.CRITICAL, ReportActionType.VALIDATION_ERROR, "Concept specified for extract not found in input Snapshot");
				continue;
			}
			
			if (!thisConcept.isActive()) {
				report (thisConcept, Severity.CRITICAL, ReportActionType.VALIDATION_ERROR, "Concept is inactive, skipping");
				continue;
			}
			try {
				if (!switchModule(thisConcept)) {
					addSummaryInformation("Specified but no movement: " + thisConcept, null);
					incrementSummaryInformation("Concepts no movement required");
				} else if (thisConcept.getDefinitionStatus().equals(DefinitionStatus.FULLY_DEFINED) &&
							SnomedUtils.countAttributes(thisConcept, CharacteristicType.STATED_RELATIONSHIP) == 0) {
					//Check we're not ending up with a Fully Defined concept with only ISAs
					report (thisConcept, Severity.HIGH, ReportActionType.VALIDATION_CHECK, "Concept FD with only ISAs ", thisConcept.toExpression(CharacteristicType.STATED_RELATIONSHIP));
				}
			} catch (TermServerScriptException e) {
				report (thisConcept, Severity.CRITICAL, ReportActionType.API_ERROR, "Exception while processing: " + e.getMessage() + " : " + SnomedUtils.getStackTrace(e));
			}
		}
		return allIdentifiedConcepts;
	}
	
	private boolean switchModule(Concept c) throws TermServerScriptException {
		boolean conceptAlreadyTransferred = false;
		if (c.getModuleId() ==  null) {
			report (c, Severity.HIGH, ReportActionType.VALIDATION_ERROR, "Concept does not specify a module!  Unable to switch.");
			return false;
		}
		//It's possible that this concept has already been transferred by an earlier run if it was identified as a dependency, so 
		//we have to check every concept.
		Concept conceptOnTS = loadConcept(c);
		
		//Switch the module of this concept, then all active descriptions and relationships
		//As long as the current module is not equal to the target module, we'll switch it
		if (!c.getModuleId().equals(targetModuleId) && !c.getModuleId().equals(SCTID_MODEL_MODULE)) {
			if (!c.getModuleId().equals(moduleId)) {
				report (c, Severity.MEDIUM, ReportActionType.VALIDATION_CHECK, "Specified concept in unexpected module, switching anyway", c.getModuleId());
			}
			//Was this concept originally specified, or picked up as a dependency?
			String parents = parentsToString(c);
			
			if (!conceptOnTS.equals(NULL_CONCEPT)) {
				conceptAlreadyTransferred = true;
				report (c, Severity.MEDIUM, ReportActionType.NO_CHANGE, "Concept has already been moved to " + targetModuleId + " (possibly as a dependency).   Checking descriptions and relationships", c.getDefinitionStatus().toString(), parents);
			} else {
				if (allIdentifiedConcepts.contains(c)) {
					report (c, Severity.LOW, ReportActionType.MODULE_CHANGE_MADE, "Specified concept, module set to " + targetModuleId, c.getDefinitionStatus().toString(), parents);
				} else {
					report (c, Severity.MEDIUM, ReportActionType.CONCEPT_CHANGE_MADE, "Dependency concept, module set to " + targetModuleId, c.getDefinitionStatus().toString(), parents);
				}
				c.setModuleId(targetModuleId);
				incrementSummaryInformation("Concepts moved");
			}
			allModifiedConcepts.add(c);
			
			//If we have no stated modelling (either stated relationships, or those extracted from an axiom, 
			//create an Axiom Entry from the inferred rels.
			if (c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, ActiveState.ACTIVE).size() == 0) {
				convertInferredRelsToAxiomEntry(c);
			}
		} else {
			conceptAlreadyTransferred = true;
			if (allIdentifiedConcepts.contains(c)) {
				if (c.getModuleId().equals(targetModuleId)) {
					report (c, Severity.HIGH, ReportActionType.NO_CHANGE, "Specified concept already in target module: " + c.getModuleId() + " checking for additional modeling in source module.");
				} else {
					throw new IllegalStateException("This should have been picked up in the block above");
				}
			}
		}
		
		boolean subComponentsMoved = false;
		for (Description d : c.getDescriptions(ActiveState.ACTIVE)) {
			if (!d.getModuleId().equals(targetModuleId) && !d.getModuleId().equals(SCTID_MODEL_MODULE)) {
				if (moveDescriptionToTargetModule(d, conceptOnTS)) {
					subComponentsMoved = true;
				}
			}
		}
		
		boolean relationshipMoved = false;
		boolean relationshipAlreadyMoved = false;
		for (Relationship r : c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, ActiveState.ACTIVE)) {
			if (r.getModuleId().equals(targetModuleId)) {
				relationshipAlreadyMoved = true;
			} else if (!r.getModuleId().equals(targetModuleId) && !r.getModuleId().equals(SCTID_MODEL_MODULE)) {
				if (r.isActive() && !r.fromAxiom()) {
					info ("Unexpected active stated relationship: "+ r);
				}
				if (moveRelationshipToTargetModule(r, conceptOnTS)) {
					subComponentsMoved = true;
					relationshipMoved = true;
				}
			}
		}
		
		//Did we move some of the modeling but not all of it?  Warn about that if so
		if (relationshipMoved && relationshipAlreadyMoved) {
			report (c, Severity.HIGH, ReportActionType.VALIDATION_CHECK, "Partial move on the modeling.  Exported axiom may be incomplete.");
		}
		
		/* Policy is not to moved inferred modeling
		for (Relationship r : c.getRelationships(CharacteristicType.INFERRED_RELATIONSHIP, ActiveState.ACTIVE)) {
			if ((!r.getModuleId().equals(targetModuleId) && !r.getModuleId().equals(SCTID_MODEL_MODULE)) {
				moveRelationshipToTargetModule(r);
				subComponentsMoved = true;
			}
		}*/
		
		for (AxiomEntry a : c.getAxiomEntries()) {
			if ((!a.getModuleId().equals(targetModuleId) && !a.getModuleId().equals(SCTID_MODEL_MODULE))) {
				moveAxiomToTargetModule(c, a, conceptOnTS);
				subComponentsMoved = true;
			}
		}
		
		if (conceptAlreadyTransferred && !subComponentsMoved) {
			return false;
		}
		
		if (conceptAlreadyTransferred && subComponentsMoved) {
			incrementSummaryInformation("Existing concepts additional modeling moved.");
		}
		return true;
	}

	private void convertInferredRelsToAxiomEntry(Concept c) throws TermServerScriptException {
		try {
			AxiomRepresentation axiom = new AxiomRepresentation();
			axiom.setLeftHandSideNamedConcept(Long.parseLong(c.getConceptId()));
			axiom.setPrimitive(c.getDefinitionStatus().equals(DefinitionStatus.PRIMITIVE));
			Map<Integer, List<org.snomed.otf.owltoolkit.domain.Relationship>> relationshipMap = new HashMap<>();
			boolean includeIsA = true;
			for (RelationshipGroup g : c.getRelationshipGroups(CharacteristicType.INFERRED_RELATIONSHIP, includeIsA)) {
				relationshipMap.put(g.getGroupId(), g.getToolKitRelationships());
			}
			axiom.setRightHandSideRelationships(relationshipMap);
			String axiomStr = axiomService.convertRelationshipsToAxiom(axiom);
			AxiomEntry axiomEntry = AxiomEntry.withDefaults(c, axiomStr);
			axiomEntry.setModuleId(targetModuleId);
			axiomEntry.setDirty();
			c.getAxiomEntries().add(axiomEntry);
		} catch (ConversionException e) {
			throw new TermServerScriptException(e);
		}
	}

	private String parentsToString(Concept c) {
		StringBuffer parentsStr = new StringBuffer();
		boolean firstParent = true;
		for (Concept p : c.getParents(CharacteristicType.STATED_RELATIONSHIP)) {
			if (!firstParent) {
				parentsStr.append(", ");
			} else {
				firstParent = false;
			}
			parentsStr.append(p.toString());
		}
		return parentsStr.toString();
	}
	
	private boolean moveAxiomToTargetModule(Concept c, AxiomEntry a, Concept conceptOnTS) throws TermServerScriptException {
		try {
			//Don't trust the local module, we might need to copy the data over anyway
			//if (a.getModuleId().equals(targetModuleId)) {
			//	return false;
			//}
			
			//If we already have the concept on the Terminology Server, perhaps we already have the description too,
			//Despite what the local file claims
			if (!conceptOnTS.equals(NULL_CONCEPT)) {
				Axiom relOnTS = conceptOnTS.getClassAxiom(a.getId());
				if (relOnTS != null) {
					report (conceptOnTS, Severity.MEDIUM, ReportActionType.NO_CHANGE, "Content already on server", a.getId());
					return false;
				}
			} 
			
			a.setModuleId(targetModuleId);
			AxiomRepresentation axiomRepresentation = axiomService.convertAxiomToRelationships(a.getOwlExpression());
			for (Relationship r : AxiomUtils.getRHSRelationships(c, axiomRepresentation)) {
				//This is only needed to include dependencies. 
				//The relationship itself is not attached to the concept
				moveRelationshipToTargetModule(r, conceptOnTS);
			}
		} catch (ConversionException e) {
			throw new TermServerScriptException("Failed to convert axiom for " + c , e);
		}
		return true;
	}

	private boolean moveRelationshipToTargetModule(Relationship r, Concept conceptOnTS) throws TermServerScriptException {
		if (r.getModuleId().equals(targetModuleId)) {
			return false;
		}
		
		//If we already have the concept on the Terminology Server, perhaps we already have the description too,
		//Despite what the local file claims
		if (!conceptOnTS.equals(NULL_CONCEPT)) {
			Relationship relOnTS = conceptOnTS.getRelationship(r.getId());
			if (relOnTS != null) {
				report (conceptOnTS, Severity.MEDIUM, ReportActionType.NO_CHANGE, "Content already on server", r);
				return false;
			}
		} 
		//Switch the relationship.   Also switch both the type and the destination - will return if not needed
		r.setModuleId(targetModuleId);
		if (switchModule(r.getType())) {
			//Is this an unexpected dependency
			if (!allIdentifiedConcepts.contains(r.getType())) {
				incrementSummaryInformation("Unexpected dependencies included");
				addSummaryInformation("Unexpected type dependency: " + r.getType(), "");
			}
		}
		//Note that switching the target will also recursively work up the hierarchy as parents are also switched
		//up the hierarchy until a concept owned by the core module is encountered.
		
		Concept target = r.getTarget();
		//Don't worry about the target if it's on our to-do list anyway.
		if (!allIdentifiedConcepts.contains(target)) {
			//If our dependency is in the core module, then check - live - if it is active
			//because if not, we can't take this relationship.  Check we haven't just moved it there.
			if (target.getModuleId().equals(targetModuleId) && ! allModifiedConcepts.contains(target)) {
				Concept loadedTarget = loadConcept(target);
				//If this target is inactive, find an alternative target and create a replacement relationship
				//TODO in the stated form, we'll need to re-write the axiom if we see this!
				if (!loadedTarget.isActive()) {
					String reason = loadedTarget.getInactivationIndicator().toString();
					Concept replacement = getReplacement(loadedTarget);
					String msg = "Target of " + r + " is inactive in MAIN due to " + reason;
					msg += ". Replacing with " + replacement;
					report (r.getSource(), Severity.CRITICAL, ReportActionType.VALIDATION_CHECK, msg);
					target = replacement;
					Relationship newRel = new Relationship(r.getSource(),r.getType(), replacement, r.getGroupId());
					newRel.setModuleId(targetModuleId);
					newRel.setDirty();
					newRel.setRelationshipId(relIdGenerator.getSCTID());
					r.getSource().removeRelationship(r);
					r.getSource().addRelationship(newRel);
				}
			}
			//Again will recursively switch all dependencies as we're switching a concept here
			if (switchModule(target)) {
				//Is this an unexpected dependency
				if (!allIdentifiedConcepts.contains(target)) {
					incrementSummaryInformation("Unexpected dependencies included");
					addSummaryInformation("Unexpected target dependency: " + target, "");
				}
			}
		}
		
		//If we didn't need to transfer the concept, then do report the movement of it's sub components.
		if (!conceptOnTS.equals(NULL_CONCEPT)) {
			report (r.getSource(), Severity.MEDIUM, ReportActionType.MODULE_CHANGE_MADE, r, r.getId());
		}
		return true;
	}

	private Concept getReplacement(Concept inactiveConcept) throws TermServerScriptException {
		/*List<HistoricalAssociation> assocs =  inactiveConcept.getHistorialAssociations(ActiveState.ACTIVE);
		if (assocs.size() == 1) {
			HistoricalAssociation assoc = assocs.iterator().next();
			Concept assocType = gl.getConcept(assoc.getRefsetId());
			Concept assocTarget = gl.getConcept(assoc.getTargetComponentId());
			println (inactiveConcept + " is " + assocType.getPreferredSynonym() + " " + assocTarget);
			return assocTarget;
		} else {
			throw new TermServerScriptException("Unable to find replacement for " + inactiveConcept + " due to " + assocs.size() + " associations");
		}*/
		Set<String> assocs = inactiveConcept.getAssociationTargets().getReplacedBy();
		if (assocs.size() != 1) {
			throw new TermServerScriptException("Unable to find replacement for " + inactiveConcept + " due to " + assocs.size() + " associations");
		} else {
			//We will probably not have loaded this concept via RF2 if the target has been replaced, so load from secondary source
			return loadConcept(gl.getConcept(assocs.iterator().next()));
		}
	}

	private Concept loadConcept(Concept c) throws TermServerScriptException {
		//Do we already have this concept?
		Concept loadedConcept = loadedConcepts.get(c.getConceptId());
		if (loadedConcept == null) {
			loadedConcept = loadConcept(secondaryConnection, c, secondaryCheckPath);
			//If the concept was not found, that may be OK
			if (loadedConcept == null || loadedConcept.getConceptId() == null) {
				loadedConcept = NULL_CONCEPT;
			}
			loadedConcepts.put(loadedConcept.getConceptId(), loadedConcept);
		}
		return loadedConcept;
	}

	private boolean moveDescriptionToTargetModule(Description d, Concept conceptOnTS) throws TermServerScriptException {
		if (d.getModuleId().equals(targetModuleId)) {
			return false;
		}
		
		//If we already have the concept on the Terminology Server, perhaps we already have the description too,
		//Despite what the local file claims
		if (!conceptOnTS.equals(NULL_CONCEPT)) {
			Description descriptionOnTS = findMatchingDescription(d, conceptOnTS);
			if (descriptionOnTS != null) {
				report (conceptOnTS, Severity.MEDIUM, ReportActionType.NO_CHANGE, "Content already on server", d);
				return false;
			}
			
			//If we already have this concept on the server, then we can't have another FSN
			if (d.getType().equals(DescriptionType.FSN)) {
				d.setType(DescriptionType.SYNONYM);
				report (conceptOnTS, Severity.MEDIUM, ReportActionType.DESCRIPTION_CHANGE_MADE, "Demoted term to Synonym due to existing FSN", d);
			}
		}
		
		//First swap the module id, then add in the GB refset 
		d.setModuleId(targetModuleId);
		
		//AU for example doesn't give language refset entries for FSNs
		if (d.getLangRefsetEntries(ActiveState.ACTIVE, targetLangRefsetIds).size() == 0) {
			//The international edition however, does PREF for FSNs
			String acceptability = SCTID_PREFERRED_TERM;
			if (d.getType().equals(DescriptionType.SYNONYM) && !d.isPreferred()) {
				acceptability = SCTID_ACCEPTABLE_TERM;
			}
			
			for (String refsetId : targetLangRefsetIds) {
				LangRefsetEntry entry = LangRefsetEntry.withDefaults(d, refsetId, acceptability);
				entry.setModuleId(targetModuleId);
				entry.setDirty();
				d.addAcceptability(entry);
			}
		} else {
			for (LangRefsetEntry usEntry : d.getLangRefsetEntries(ActiveState.ACTIVE, US_ENG_LANG_REFSET)) {
				usEntry.setModuleId(targetModuleId);
				//If we already have this concept on the server, then we'll already have a preferred term
				if (!conceptOnTS.equals(NULL_CONCEPT) && usEntry.getAcceptabilityId().equals(SCTID_PREFERRED_TERM)) {
					usEntry.setAcceptabilityId(SCTID_ACCEPTABLE_TERM);
					report (conceptOnTS, Severity.MEDIUM, ReportActionType.DESCRIPTION_ACCEPTABILIY_CHANGED, "Demoted term to acceptable due to existing PT", usEntry);
				}
				LangRefsetEntry gbEntry = usEntry.clone(d.getDescriptionId());
				gbEntry.setRefsetId(GB_ENG_LANG_REFSET);
				d.addAcceptability(gbEntry);
			}
		}
		
		//If we didn't need to transfer the concept, then do report the movement of it's sub components.
		if (!conceptOnTS.equals(NULL_CONCEPT)) {
			Concept c = gl.getConcept(d.getConceptId());
			report (c, Severity.MEDIUM, ReportActionType.MODULE_CHANGE_MADE, d, d.getId());
		}
		
		return true;
	}

	private Description findMatchingDescription(Description d, Concept conceptOnTS) {
		Description match = conceptOnTS.getDescription(d.getId());
		if (match != null) {
			return match;
		}
		
		for (Description m : conceptOnTS.getDescriptions()) {
			if (d.getTerm().contentEquals(m.getTerm()) ) {
				return m;
			}
		}
		return null;
	}

	@Override
	protected List<Component> loadLine(String[] lineItems)
			throws TermServerScriptException {
		return Collections.singletonList(gl.getConcept(lineItems[0]));
	}
}

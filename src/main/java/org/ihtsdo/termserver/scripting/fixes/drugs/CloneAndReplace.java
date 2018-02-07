package org.ihtsdo.termserver.scripting.fixes.drugs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.ihtsdo.termserver.scripting.TermServerScriptException;
import org.ihtsdo.termserver.scripting.client.SnowOwlClientException;
import org.ihtsdo.termserver.scripting.domain.AssociationTargets;
import org.ihtsdo.termserver.scripting.domain.Concept;
import org.ihtsdo.termserver.scripting.domain.Description;
import org.ihtsdo.termserver.scripting.domain.HistoricalAssociation;
import org.ihtsdo.termserver.scripting.domain.RF2Constants;
import org.ihtsdo.termserver.scripting.domain.Relationship;
import org.ihtsdo.termserver.scripting.domain.Task;
import org.ihtsdo.termserver.scripting.fixes.BatchFix;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;

/*
For DRUGS-450, DRUGS-452, DRUGS-455, DRUGS-456
Driven by a text file of concepts, clone concepts - adjusting FSN and attributes
then inactivate original and add a historical association to the clone
Edit: Added column to specify inactivation reason on a per concept basis
*/
public class CloneAndReplace extends BatchFix implements RF2Constants{
	
	Set<Concept> allStatedTargets = new HashSet<>();
	Set<Concept> allInferredTargets = new HashSet<>();
	
	Map<Concept, Concept> newDoseForms = new HashMap<>();
	Map<Concept, String> newFSNs = new HashMap<>();
	Map<Concept, InactivationIndicator> inactivationReasons = new HashMap<>();
	Map<Concept, String> historicalAssociations = new HashMap<>();
	Concept hasDoseForm;
	
	protected CloneAndReplace(BatchFix clone) {
		super(clone);
	}

	public static void main(String[] args) throws TermServerScriptException, IOException, SnowOwlClientException, InterruptedException {
		CloneAndReplace fix = new CloneAndReplace(null);
		try {
			//fix.runStandAlone = true;
			fix.reportNoChange = true;
			fix.populateEditPanel = false;
			fix.populateTaskDescription = true;
			fix.additionalReportColumns = "ACTION_DETAIL, DEF_STATUS, PARENT_COUNT, ATTRIBUTE_COUNT";
			fix.init(args);
			//Recover the current project state from TS (or local cached archive) to allow quick searching of all concepts
			fix.loadProjectSnapshot(false); 
			fix.postLoadInit();
			fix.startTimer();
			fix.processFile();
			println ("Processing complete.  See results: " + fix.reportFile.getAbsolutePath());
		} finally {
			fix.finish();
		}
	}

	private void postLoadInit() throws TermServerScriptException {
		//Find all concepts that appear as attributes of another concept
		for (Concept c : gl.getAllConcepts()) {
			for (Relationship r : c.getRelationships(CharacteristicType.INFERRED_RELATIONSHIP, ActiveState.ACTIVE)) {
				if (!r.getType().equals(IS_A)) {
					allInferredTargets.add(r.getTarget());
				}
			}
			for (Relationship r : c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, ActiveState.ACTIVE)) {
				if (!r.getType().equals(IS_A)) {
					allStatedTargets.add(r.getTarget());
				}
			}
		}
		
		hasDoseForm = gl.getConcept("411116001"); // |Has manufactured dose form (attribute)|
	}

	@Override
	public int doFix(Task task, Concept concept, String info) throws TermServerScriptException {
		int changesMade = 0;
		Concept loadedConcept = loadConcept(concept, task.getBranchPath());
		identifyType(loadedConcept);
		
		//Can we safely save this clone before we inactivate the original?
		if (!loadedConcept.isActive()) {
			report(task, concept, Severity.MEDIUM, ReportActionType.VALIDATION_CHECK, "Concept is already inactive");
		} else if (isSafeToInactivate(task, concept)) {  //Need to use local version since loadedConcept will not have parents populated
			changesMade = cloneAndReplace(task, loadedConcept);
			if (changesMade > 0) {
				try {
					updateConcept(task, loadedConcept, info);
				} catch (Exception e) {
					report(task, concept, Severity.CRITICAL, ReportActionType.API_ERROR, "Failed to save changed concept to TS: " + ExceptionUtils.getStackTrace(e));
				}
			}
		}
		return changesMade;
	}

	private boolean isSafeToInactivate(Task t, Concept c) throws TermServerScriptException {
		//If this concept has stated or inferred children OR if it's used as the target to some relationship, then 
		//it's not safe to inactivate
		String msg = "Concept is not safe to inactivate. ";

		if (c.getChildren(CharacteristicType.STATED_RELATIONSHIP).size() > 0 ||
			c.getChildren(CharacteristicType.INFERRED_RELATIONSHIP).size() > 0) {
			msg += "It has descendants";
			report (t, c, Severity.HIGH, ReportActionType.VALIDATION_CHECK, msg);
			return false;
		}
		
		if (allStatedTargets.contains(c) || allInferredTargets.contains(c)) {
			msg += "It is used as the target of a relationship";
			report (t, c, Severity.HIGH, ReportActionType.VALIDATION_CHECK, msg);
			return false;
		}
		
		return true;
	}

	private void identifyType(Concept loadedConcept) {
		String semTag = SnomedUtils.deconstructFSN(loadedConcept.getFsn())[1];
		switch (semTag) {
			case "(medicinal product form)" : loadedConcept.setConceptType(ConceptType.MEDICINAL_PRODUCT_FORM);
												break;
			case "(product)" : loadedConcept.setConceptType(ConceptType.PRODUCT);
								break;
			case "(medicinal product)" : loadedConcept.setConceptType(ConceptType.MEDICINAL_PRODUCT);
										 break;
			case "(clinical drug)" : loadedConcept.setConceptType(ConceptType.CLINICAL_DRUG);
										break;
			default : loadedConcept.setConceptType(ConceptType.UNKNOWN);
		}
	}

	private int cloneAndReplace(Task task, Concept loadedConcept) throws TermServerScriptException {
		Concept clone = loadedConcept.clone();
		remodel(clone, loadedConcept);
		
		//Save clone to TS
		clone = createConcept(task, clone, " cloned from " + loadedConcept);
		report (task, loadedConcept, Severity.LOW, ReportActionType.CONCEPT_ADDED, clone.toString());
		
		//Check if the concept we're about to inactivate is used as the target of a historical association
		//and rewire that to point to our new clone
		checkAndReplaceHistoricalAssociations(task, loadedConcept, clone);
		
		//If the save of the clone didn't throw an exception, we can inactivate the original
		inactivateConcept(loadedConcept, clone);
		report (task, loadedConcept, Severity.LOW, ReportActionType.CONCEPT_INACTIVATED, "");
		
		return 1;
	}


	private void checkAndReplaceHistoricalAssociations(Task t, Concept inactivating, Concept replacing) throws TermServerScriptException {
		List<HistoricalAssociation> histAssocs = gl.usedAsHistoricalAssociationTarget(inactivating);
		if (histAssocs != null && histAssocs.size() > 0) {
			for (HistoricalAssociation histAssoc : histAssocs) {
				Concept source = gl.getConcept(histAssoc.getReferencedComponentId());
				String assocType = gl.getConcept(histAssoc.getRefsetId()).getPreferredSynonym(US_ENG_LANG_REFSET).getTerm().replace("association reference set", "");
				String thisDetail = "Concept was as used as the " + assocType + "target of a historical association for " + source;
				thisDetail += " (since " + (histAssoc.getEffectiveTime().isEmpty()?" prospective release":histAssoc.getEffectiveTime()) + ")";
				report (t, inactivating, Severity.HIGH, ReportActionType.INFO, thisDetail);
				replaceHistoricalAssociation(t, source, inactivating, replacing);
			}
		}
	}

	private void replaceHistoricalAssociation(Task t, Concept concept, Concept current, Concept replacement) throws TermServerScriptException {
		//We need a copy from the TS
		Concept loadedConcept = loadConcept(concept, t.getBranchPath());
		loadedConcept.setInactivationIndicator(inactivationReasons.get(current));
		//Make sure we only have one current association target
		int targetCount = loadedConcept.getAssociationTargets().size();
		if (targetCount > 1) {
			report (t, concept, Severity.HIGH, ReportActionType.INFO, "Replacing 1 historical association out of " + targetCount);
		}
		AssociationTargets targets = loadedConcept.getAssociationTargets();
		targets.remove(current.getConceptId());
		targets.getPossEquivTo().add(replacement.getConceptId());
		updateConcept(t, loadedConcept, " with re-jigged inactivation indicator and historical associations");
		report (t, loadedConcept, Severity.MEDIUM, ReportActionType.ASSOCIATION_ADDED, "InactReason set to " + inactivationReasons.get(current) + " and PossiblyEquivalentTo: " + replacement);
	}

	private void inactivateConcept(Concept original, Concept clone) {
		original.setActive(false);
		original.setInactivationIndicator(inactivationReasons.get(original));
		original.setAssociationTargets(AssociationTargets.possEquivTo(clone));
		
		//Need to also remove any unpublished relationships
		List<Relationship> allRelationships = new ArrayList<>(original.getRelationships());
		for (Relationship r : allRelationships) {
			if (r.isActive() && (r.getEffectiveTime() == null || r.getEffectiveTime().isEmpty())) {
				original.removeRelationship(r);
			}
		}
	}

	/*
	 * Set the FSN and correct dose form on the cloned concept
	 */
	private void remodel(Concept clone, Concept original) throws TermServerScriptException {
		//The FSN is a new object, so no need to inactivate, just change directly
		String newFSN = newFSNs.get(original);
		Description fsn = clone.getFSNDescription();
		fsn.setTerm(newFSN);
		
		//Also the preferred term.  Remove all current preferred terms and replace
		//to avoid clashes with US / GB spellings
		List<Description> PTs = clone.getDescriptions(Acceptability.PREFERRED, DescriptionType.SYNONYM, ActiveState.ACTIVE);
		for (Description thisPT : PTs) {
			clone.removeDescription(thisPT);
		}
		
		//Remove the semantic tag
		String pTerm = SnomedUtils.deconstructFSN(newFSN)[0];
		addSynonym(clone, pTerm, Acceptability.PREFERRED, ENGLISH_DIALECTS);
		
		//Now find the dose form and replace that - again directly.
		for (Relationship r : clone.getRelationships(CharacteristicType.STATED_RELATIONSHIP, hasDoseForm, ActiveState.ACTIVE)) {
			r.setTarget(newDoseForms.get(original));
		}
	}

	@Override
	protected Concept loadLine(String[] lineItems) throws TermServerScriptException {
		
		Concept c = gl.getConcept(lineItems[2]);
		inactivationReasons.put(c, InactivationIndicator.valueOf(lineItems[0].trim().toUpperCase()));
		historicalAssociations.put(c, lineItems[1]);
		
		//What's the new FSN?
		String newFSN = lineItems[4].trim();
		newFSNs.put(c, newFSN);
		
		//What's the correct dose Form
		String newDoseForm = lineItems[5];
		String newDoseFormSctid = newDoseForm.trim().replaceFirst("\\|", " ").split(" ")[0];
		Concept newDoseFormConcept = gl.getConcept(newDoseFormSctid);
		newDoseForms.put(c, newDoseFormConcept);
		return c;
	}

}

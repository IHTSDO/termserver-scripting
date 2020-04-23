package org.ihtsdo.termserver.scripting.fixes;

import java.io.IOException;
import java.util.*;

import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Task;
import org.apache.commons.lang.NotImplementedException;
import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ValidationFailure;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;

import com.google.common.base.Charsets;
import com.google.common.io.Files;

/*
 * INFRA-4695
 * Inactivate concepts where a replacement does not exist, based on some critera
 * We will point the "MAY BE A" to the parent
 * 
 * INFRA-4865 Hist assoc will be MOVED TO -> UK
 */
public class InactivateConceptsNoReplacement extends BatchFix implements RF2Constants {
	
	//InactivationIndicator inactivationIndicator = InactivationIndicator.AMBIGUOUS;
	InactivationIndicator inactivationIndicator = InactivationIndicator.MOVED_ELSEWHERE;
	
	Set<String> searchTerms = new HashSet<>();
	Set<Concept> targetHierarchies = new HashSet<>();
	Map<Concept, Concept> incomingHistAssocReplacements;
	Set<Concept> unsafeToProcess = new HashSet<>();
	Map<Concept, Task> processed = new HashMap<>();
	
	//This is tricky to explain.  I will attempt:
	//When we inactivate a concept, it might be the target of an existing incoming historical association.
	//These need to be rewired to point to some alternative
	//However, the source of that association might also point to some OTHER concept that we're
	//inactivating, in which case we need to leave that rewiring in place ie for a single given concept
	//we can't just wipe out all existing historical associations.  We need to maintain a new set of them.
	Map<Concept, Set<Concept>> historicallyRewiredPossEquivTo = new HashMap<>();
	
	protected InactivateConceptsNoReplacement(BatchFix clone) {
		super(clone);
	}

	public static void main(String[] args) throws TermServerScriptException, IOException, InterruptedException {
		InactivateConceptsNoReplacement fix = new InactivateConceptsNoReplacement(null);
		try {
			ReportSheetManager.targetFolderId = "1fIHGIgbsdSfh5euzO3YKOSeHw4QHCM-m";  //Ad-hoc batch updates
			fix.selfDetermining = true;
			fix.maxFailures = Integer.MAX_VALUE;
			fix.getArchiveManager().setPopulateReleasedFlag(true);
			fix.init(args);
			//fix.getArchiveManager().setPopulateReleasedFlag(true);
			fix.loadProjectSnapshot(true);
			fix.postInit();
			fix.processFile();
		} finally {
			fix.finish();
		}
	}
	
	public void postInit() throws TermServerScriptException {
		//INFRA-4865
		searchTerms.add("on examination");
		searchTerms.add("o/e");
		searchTerms.add("complaining of");
		searchTerms.add("c/o");
		targetHierarchies.add(CLINICAL_FINDING);
		targetHierarchies.add(SITN_WITH_EXP_CONTXT);
		
		super.postInit();
		
		//Need report output initialised before this next bit
		if (inputFile != null) {
			loadIncomingHistoricReplacements();
		}
	}

	private void loadIncomingHistoricReplacements() throws TermServerScriptException {
		incomingHistAssocReplacements = new HashMap<>();
		print("Loading Historic Replacements " + inputFile + "...");
		if (!inputFile.canRead()) {
			throw new TermServerScriptException("Cannot read: " + inputFile);
		}
		List<String> lines;
		try {
			lines = Files.readLines(inputFile, Charsets.UTF_8);
		} catch (IOException e) {
			throw new TermServerScriptException("Failure while reading: " + inputFile, e);
		}
		debug("Processing Historic Replacements File");
		for (String line : lines) {
			//Split the line up on tabs
			String[] items = line.split(TAB);
			//We're only populating where we have a replacements
			if (items.length == 3) {
				Concept inactivatedConcept = gl.getConcept(items[0], false, true);
				String replacementSCTID = items[2];
				
				//Does it even have an SCTID in it?   Try a description look up if not
				if (!SnomedUtils.startsWithSCTID(replacementSCTID)) {
					Concept conceptFromDesc = gl.findConcept(replacementSCTID);
					if (conceptFromDesc != null) {
						replacementSCTID = conceptFromDesc.getConceptId();
						warn("Looked up replacement " + conceptFromDesc);
					} else {
						report ((Task)null, inactivatedConcept, Severity.HIGH, ReportActionType.VALIDATION_CHECK, "FSN of replacement doesn't exist in current environment. ", replacementSCTID);
						continue;
					}
				}
				
				Concept replacement = gl.getConcept(replacementSCTID, false, false);
				if (replacement == null) {
					report ((Task)null, inactivatedConcept, Severity.HIGH, ReportActionType.VALIDATION_CHECK, "suggested replacement doesn't exist in current environment. ", replacementSCTID);
				} else {
					incomingHistAssocReplacements.put(inactivatedConcept, replacement);
				}
			}
		}
	}

	@Override
	public int doFix(Task t, Concept c, String info) throws TermServerScriptException {
		Concept loadedConcept = loadConcept(c, t.getBranchPath());
		int changesMade = 0;
		if (loadedConcept == null || !loadedConcept.isActive()) {
			report (t, c, Severity.LOW, ReportActionType.NO_CHANGE, "Concept already inactivated?");
		} else if (loadedConcept.isReleased()) {
			changesMade = inactivateConcept(t, loadedConcept);
			if (changesMade > 0) {
				updateConcept(t, loadedConcept, info);
			}
			processed.put(c, t);
		} else {
			changesMade = deleteConcept(t, loadedConcept);
		}
		return changesMade;
	}
	
	private int inactivateConcept(Task t, Concept c) throws TermServerScriptException {
		//Have we already seen this concept?
		if (processed.containsKey(c)) {
			report(t, c, Severity.LOW, ReportActionType.INFO, "Concept already processed in " + processed.get(c));
			//We'll remove it from this task if it wasn't originally on this task
			if (!processed.get(c).equals(t)) {
				t.remove(c);
			}
			return NO_CHANGES_MADE;
		}
		
		//Set<Concept> replacements = c.getParents(CharacteristicType.STATED_RELATIONSHIP);
		Concept replacement = gl.getConcept("370137002|Extension Namespace {1000000} (namespace concept)|");
		Set<Concept> replacements = Collections.singleton(replacement);
		//Check for this concept being the target of any historical associations and rewire them to the replacement
		//With the same inactivation reasons
		checkAndInactivatateIncomingAssociations(t, c, inactivationIndicator, replacements);
		
		//How many children do we have to also inactivate?  We've already checked they must all be.
		//Use locally held concept when traversing transitive closure
		Set<Concept> children = gl.getConcept(c.getConceptId()).getChildren(CharacteristicType.INFERRED_RELATIONSHIP);
		for (Concept child : children) {
			//This will call recursively and we'll add into this task
			if (doFix(t, child, null) > 0) {
				report(t, child, Severity.HIGH, ReportActionType.INFO, "Child inactivation squeezed into same task as " + c);
				t.remove(child);
				t.addAfter(child, c);
			}
		}
		
		c.setActive(false);  //Function also inactivates all relationships
		c.setEffectiveTime(null);
		c.setInactivationIndicator(inactivationIndicator);
		
		//c.setAssociationTargets(AssociationTargets.possEquivTo(replacements));
		c.setAssociationTargets(AssociationTargets.movedTo(replacement));
		
		//String histAssocType = " possibly equiv to ";
		//for (Concept replacement : replacements) {
		String histAssocType = " moved to ";
		report(t, c, Severity.LOW, ReportActionType.CONCEPT_CHANGE_MADE, "Concept inactivated as " + inactivationIndicator + histAssocType + (replacement.equals(NULL_CONCEPT)?"":replacement));
		//}
		return CHANGE_MADE;
	}


	
	protected int deleteConcept(Task t, Concept c) throws TermServerScriptException {
		//Check for this concept being the target of any historical associations and rewire them to the replacement
		checkAndInactivatateIncomingAssociations(t, c, InactivationIndicator.NONCONFORMANCE_TO_EDITORIAL_POLICY, null);
		report (t, c, Severity.MEDIUM, ReportActionType.CONCEPT_DELETED);
		return super.deleteConcept(t, c);
	}

	private void checkAndInactivatateIncomingAssociations(Task task, Concept c, InactivationIndicator reason, Set<Concept> replacements) throws TermServerScriptException {
		if (gl.usedAsHistoricalAssociationTarget(c).isEmpty()) {
			return;
		}
		
		//throw new TermServerScriptException (c + " is the target of incoming association(s) from: " + gl.usedAsHistoricalAssociationTarget(c) );
		
		for (AssociationEntry assoc : gl.usedAsHistoricalAssociationTarget(c)) {
			Concept incoming = gl.getConcept(assoc.getReferencedComponentId());
			//Does the concept we're inactivating have a concepts designated to rewire to?
			if (incomingHistAssocReplacements.containsKey(c)) {
				replacements = Collections.singleton(incomingHistAssocReplacements.get(c));
				//For INFRA-4865 we can't re-jig the incoming associations, so we'll use AMBIGUOUS
				//so the suggested replacement is PossEquivTo
				reason = InactivationIndicator.AMBIGUOUS;
				inactivateHistoricalAssociation (task, assoc, c, reason, replacements);
			} else {
				String errMsg = "Concept is target of incoming historical association from " + incoming + " with no replacement suggested";
				throw new TermServerScriptException(errMsg);
			}
		}
	}

	private void inactivateHistoricalAssociation(Task t, AssociationEntry assoc, Concept c, InactivationIndicator reason, Set<Concept> replacements) throws TermServerScriptException {
		//The source concept can no longer have this historical association, and its
		//inactivation reason must also change.
		Concept originalTarget = gl.getConcept(assoc.getTargetComponentId());
		Concept incomingConcept = loadConcept(assoc.getReferencedComponentId(), t.getBranchPath());
		String assocType = "Unknown";
		incomingConcept.setInactivationIndicator(reason);
		if ((replacements == null || replacements.size() == 0) && 
				!reason.equals(InactivationIndicator.NONCONFORMANCE_TO_EDITORIAL_POLICY)) {
			throw new ValidationFailure(t, c, "Hist Assoc rewiring attempted wtih no replacement offered.");
		}
		replacements = new HashSet<>(replacements);
		
		if (reason.equals(InactivationIndicator.NONCONFORMANCE_TO_EDITORIAL_POLICY)) {
			incomingConcept.setAssociationTargets(null);
		} else if (reason.equals(InactivationIndicator.OUTDATED)) {
			throw new NotImplementedException();
			//incomingConcept.setAssociationTargets(AssociationTargets.replacedBy(replacements));
		} else if (reason.equals(InactivationIndicator.DUPLICATE)) {
			throw new NotImplementedException();
			//incomingConcept.setAssociationTargets(AssociationTargets.sameAs(replacements));
		} else if  (reason.equals(InactivationIndicator.AMBIGUOUS)) {
			//Have we seen this concept before?
			Set<Concept> newAssocs = historicallyRewiredPossEquivTo.get(incomingConcept);
			if (newAssocs == null) {
				newAssocs = new HashSet<>();
			} else {
				report(t, c, Severity.NONE, ReportActionType.INFO, "Concept has previously rewired associations", newAssocs.toString());
			}
			replacements.addAll(newAssocs);
			assocType = "PossEquivTo";
			incomingConcept.setAssociationTargets(AssociationTargets.possEquivTo(replacements));
			//Store the complete set away so if we see that concept again, we maintain a complete set
			historicallyRewiredPossEquivTo.put(incomingConcept,replacements);
		} else if (reason.equals(InactivationIndicator.MOVED_ELSEWHERE)) {
			//We can only move to one location
			if (replacements.size() != 1) {
				throw new IllegalArgumentException("Moved_Elsewhere expects a single MovedTo association.  Found " + replacements.size());
			}
			assocType = "MovedTo";
			Concept replacement = replacements.iterator().next();
			incomingConcept.setAssociationTargets(AssociationTargets.movedTo(replacement));
		} else {
			throw new IllegalArgumentException("Don't know what historical association to use with " + reason);
		}
		Severity severity = replacements.size() == 0 ? Severity.CRITICAL : Severity.MEDIUM;
		for (Concept replacement : replacements) {
			report(t, originalTarget, severity, ReportActionType.CONCEPT_CHANGE_MADE, "Historical incoming association from " + incomingConcept, " rewired as " + assocType, replacement);
		};
			//Add this concept into our task so we know it's been updated
		t.addAfter(incomingConcept, gl.getConcept(assoc.getTargetComponentId()));
		updateConcept(t, incomingConcept, "");
	}

	@Override
	protected List<Component> identifyComponentsToProcess() throws TermServerScriptException {
		info ("Identifying concepts to process");
		Set<Concept> processMe = new HashSet<>();
		
		for (Concept hierarchy : targetHierarchies) {
			for (Concept c : hierarchy.getDescendents(NOT_SET)) {
				addComponentsToProcess(c, processMe);
			}
		}
		info ("Identified " + processMe.size() + " concepts to process");
		return new ArrayList<Component>(processMe);
	}
	
	private void addComponentsToProcess(Concept c, Set<Concept> processMe) throws TermServerScriptException {
		if (c.isActive() && containsSearchTerm(c)) {
			
			if (!isSafeToInactivate(c)) {
				report ((Task)null, c, Severity.HIGH, ReportActionType.INFO, "Concept has incoming historical association and no replacement.");
				unsafeToProcess.add(c);
				return;
			}
			
			//Use a 2nd collection to hold descendants temporarily, until we know they're all safe
			//If any descendant concept is unsafe, we cannot inactivate the ancestor
			Set<Concept> descendantsToProcess = new HashSet<>();
			//Any children of that concept must also require inactivating, or we have to 
			//do more work to re-point them (see grandchildren rewiring in sibling class)
			for (Concept child : c.getChildren(CharacteristicType.INFERRED_RELATIONSHIP)) {
				if (!containsSearchTerm(child)) {
					report ((Task)null, c, Severity.HIGH, ReportActionType.INFO, "Concept has child not being inactived due to absence of lexical indicator. Process manually.", child);
					unsafeToProcess.add(child);
					return;
				}
				
				if (!isSafeToInactivate(c, child)) {
					report ((Task)null, c, Severity.HIGH, ReportActionType.INFO, "Concept has child not being inactived due to incoming historical assocation without replacement. Process manually.", child);
					unsafeToProcess.add(child);
					return;
				}
				addComponentsToProcess(child, descendantsToProcess);
			}
			processMe.add(c);
			processMe.addAll(descendantsToProcess);
		}
		
	}

	private boolean isSafeToInactivate(Concept parent, Concept child) throws TermServerScriptException {
		//Do we already know this concept is unsafe to process?
		if (unsafeToProcess.contains(child)) {
			report ((Task) null, child, Severity.HIGH, ReportActionType.NO_CHANGE, "Concept previously identified as unsafe to process via another ancestory");
			return false;
		}
		
		//If we have further children, we have to check all of them as well
		for (Concept grandchild : child.getChildren(CharacteristicType.INFERRED_RELATIONSHIP)) {
			if (!isSafeToInactivate(child, grandchild)) {
				report ((Task) null, child, Severity.HIGH, ReportActionType.NO_CHANGE, "Descendant concept is not safe to inactivate", grandchild);
				return false;
			}
		}
		
		if (isSafeToInactivate(child)) {
			return true;
		}
		
		Concept example = gl.getConcept(gl.usedAsHistoricalAssociationTarget(child).get(0).getReferencedComponentId());
		report ((Task) null, child, Severity.NONE, ReportActionType.INFO, "Example of incoming historical association ", example);
		return false;
	}


	private boolean isSafeToInactivate(Concept c) {
		//To be safe this concept has to have a replacement for incoming historical associations,
		//or no incoming historical associations
		if (incomingHistAssocReplacements.containsKey(c)) {
			return true;
		}
		
		if (gl.usedAsHistoricalAssociationTarget(c).size() == 0) {
			return true;
		}
		
		return false;
	}

	private boolean containsSearchTerm(Concept c) {
		String term = c.getFsn().toLowerCase();
		for (String searchTerm : searchTerms) {
			if (term.contains(searchTerm)) {
				return true;
			}
		}
		return false;
	}
}

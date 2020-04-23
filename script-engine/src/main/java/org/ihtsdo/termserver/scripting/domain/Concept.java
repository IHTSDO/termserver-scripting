package org.ihtsdo.termserver.scripting.domain;

import java.util.*;
import java.util.stream.Collectors;

import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component;
import org.apache.commons.lang.StringUtils;
import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.*;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;
import org.snomed.otf.owltoolkit.domain.ObjectPropertyAxiomRepresentation;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class Concept extends Component implements RF2Constants, Comparable<Concept>, Expressable  {

	@SerializedName("effectiveTime")
	@Expose
	private String effectiveTime;
	@SerializedName("moduleId")
	@Expose
	private String moduleId;
	@SerializedName("active")
	@Expose
	private boolean active = true;
	@SerializedName("released")
	@Expose
	private Boolean released = null;
	
	@SerializedName(value="conceptId", alternate="id")
	@Expose
	private String conceptId;
	
	@SerializedName("fsn")
	@Expose
	private Object fsn;
	
	@SerializedName("definitionStatus")
	@Expose
	private DefinitionStatus definitionStatus;
	
	@SerializedName("preferredSynonym")
	@Expose
	private String preferredSynonym;
	
	@SerializedName("descriptions")
	@Expose
	private List<Description> descriptions = new ArrayList<Description>();
	
	@SerializedName("relationships")
	@Expose
	private List<Relationship> relationships = new ArrayList<Relationship>();
	
	@SerializedName("isLeafStated")
	@Expose
	private boolean isLeafStated;
	
	@SerializedName("isLeafInferred")
	@Expose
	private boolean isLeafInferred;
	
	@SerializedName("inactivationIndicator")
	@Expose
	private InactivationIndicator inactivationIndicator;
	
	@SerializedName("associationTargets")
	@Expose
	private AssociationTargets associationTargets;
	
	@SerializedName("classAxioms")
	@Expose
	private List<Axiom> classAxioms;
	
	@SerializedName("additionalAxioms")
	@Expose
	private List<Axiom> additionalAxioms;
	
	@SerializedName("gciAxioms")
	@Expose
	private List<Axiom> gciAxioms;
	private boolean isLoaded = false;
	private int originalFileLineNumber;
	private ConceptType conceptType;
	private List<String> assertionFailures = new ArrayList<String>();
	private String assignedAuthor;
	private String reviewer;
	boolean isModified = false; //indicates if has been modified in current processing run
	private String deletionEffectiveTime;
	private boolean isDeleted = false;
	private int depth = NOT_SET;
	private boolean isDirty = false;
	private Long statedRelSum = null;  //Allows cached quick comparison of relationships
	
	//Note that these values are used when loading from RF2 where multiple entries can exist.
	//When interacting with the TS, only one inactivation indicator is used (see above).
	List<InactivationIndicatorEntry> inactivationIndicatorEntries;
	List<AssociationEntry> associations;
	List<AxiomEntry> axiomEntries;
	ObjectPropertyAxiomRepresentation objectPropertyAxiom;

	Set<Concept> statedParents = new HashSet<>();
	Set<Concept> inferredParents = new HashSet<>();
	Set<Concept> statedChildren = new HashSet<>();
	Set<Concept> inferredChildren = new HashSet<>();
	
	Collection<RelationshipGroup> statedRelationshipGroups;
	Collection<RelationshipGroup> inferredRelationshipGroups;
	
	public void reset() {
		assertionFailures = new ArrayList<String>();
		statedRelationshipGroups = null;
		inferredRelationshipGroups = null;
		descriptions = new ArrayList<Description>();
		relationships = new ArrayList<Relationship>();
		statedParents = new HashSet<>();
		inferredParents = new HashSet<>();
		statedChildren = new HashSet<>();
		inferredChildren = new HashSet<>();
	}
	
	public String getReviewer() {
		return reviewer;
	}

	public void setReviewer(String reviewer) {
		this.reviewer = reviewer;
	}
	
	public Concept(String conceptId) {
		this.conceptId = conceptId;
		
		//default values
		this.definitionStatus = DefinitionStatus.PRIMITIVE;
	}
	
	public Concept(String conceptId, String fsn) {
		this(conceptId);
		this.fsn = fsn;
	}
	
	public Concept(Long conceptId) {
		this(conceptId.toString());
	}
	
	public Concept(Concept c) {
		this(c.getConceptId());
		this.fsn = c.getFsn();
	}

	public Concept(String conceptId, int originalFileLineNumber) {
		this(conceptId);
		this.originalFileLineNumber = originalFileLineNumber;
	}
	
	public static Concept withDefaults (String conceptId) {
		Concept c = new Concept(conceptId);
		c.setModuleId(SCTID_CORE_MODULE);
		c.setActive(true);
		c.setDefinitionStatus(DefinitionStatus.PRIMITIVE);
		return c;
	}

	public String getEffectiveTime() {
		return effectiveTime;
	}

	public void setEffectiveTime(String effectiveTime) {
		this.effectiveTime = effectiveTime;
	}

	public String getModuleId() {
		return moduleId;
	}

	public void setModuleId(String moduleId) {
		if (this.moduleId != null && !this.moduleId.equals(moduleId)) {
			setDirty();
			this.effectiveTime = null;
		}
		this.moduleId = moduleId;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean newActiveState) {
		this.active = newActiveState;
		
		if (newActiveState == false) {
			//If the concept has been made active, then set DefnStatus
			setDefinitionStatus(DefinitionStatus.PRIMITIVE);
			
			//And inactivate all relationships
			for (Relationship r : relationships) {
				r.setActive(false);
			}
		}
	}

	public String getConceptId() {
		return conceptId;
	}

	public void setConceptId(String conceptId) {
		this.conceptId = conceptId;
	}

	public String getFsn() {
		if (fsn != null)
			if (fsn instanceof String) {
				return fsn.toString();
			} else if (fsn instanceof Map){
				return ((Map)fsn).get("term").toString();
			}else {
				return fsn.toString();
			}
		return null;
	}
	

	public String getFsnSafely() {
		if (fsn == null) {
			return "";
		}
		return getFsn();
	}

	public void setFsn(String fsn) {
		this.fsn = fsn;
	}

	public DefinitionStatus getDefinitionStatus() {
		return definitionStatus;
	}

	public void setDefinitionStatus(DefinitionStatus definitionStatus) {
		this.definitionStatus = definitionStatus;
	}

	/**
	 * This doesn't make any sense without saying which dialect to work in.  It must
	 * come from the json representation which is requested with a dialect setting
	 * @return
	 * @throws TermServerScriptException 
	 */
	public String getPreferredSynonym() {
		try {
			if (preferredSynonym == null) {
				Description pt = getPreferredSynonym(US_ENG_LANG_REFSET);
				return pt == null ? null : pt.getTerm();
			}
			return preferredSynonym;
		} catch (Exception e) {
			return "";
		}
	}
	
	public Description getPreferredSynonym(String refsetId) throws TermServerScriptException {
		List<Description> pts = getDescriptions(refsetId, Acceptability.PREFERRED, DescriptionType.SYNONYM, ActiveState.ACTIVE);
		return pts.size() == 0 ? null : pts.get(0);
	}
	
	public Description getPreferredSynonymSafely(String refsetId) {
		String debug = "";
		try {
			List<Description> pts = getDescriptions(refsetId, Acceptability.PREFERRED, DescriptionType.SYNONYM, ActiveState.ACTIVE);
			return pts.size() == 0 ? null : pts.get(0);
		} catch (Exception e) {
			e.printStackTrace();
			debug = e.getMessage();
		}
		return new Description("Exception recovering PT: " + debug);
	}

	public void setPreferredSynonym(String preferredSynonym) {
		this.preferredSynonym = preferredSynonym;
	}

	public List<Description> getDescriptions() {
		return descriptions;
	}

	public void setDescriptions(List<Description> descriptions) {
		this.descriptions = descriptions;
	}

	public List<Relationship> getRelationships() {
		return relationships;
	}
	
	public List<Relationship> getRelationships(CharacteristicType characteristicType, ActiveState state, String effectiveTime) {
		List<Relationship> matches = new ArrayList<Relationship>();
		for (Relationship r : relationships) {
			if (effectiveTime == null || r.getEffectiveTime().equals(effectiveTime)) {
				if (characteristicType.equals(CharacteristicType.ALL) || r.getCharacteristicType().equals(characteristicType)) {
					if (state.equals(ActiveState.BOTH) || (state.equals(ActiveState.ACTIVE) && r.isActive()) ||
							(state.equals(ActiveState.INACTIVE) && !r.isActive())) {
						matches.add(r);
					}
				}
			}
		}
		return matches;
	}
	
	public List<Relationship> getRelationships(CharacteristicType characteristicType, ActiveState activeState) {
		return getRelationships(characteristicType, activeState, null);
	}
	
	//Gets relationships that match the triple + group + charType
	public List<Relationship> getRelationships(Relationship r) {
		return getRelationships(r.getCharacteristicType(), r.getType(), r.getTarget(), r.getGroupId(), ActiveState.ACTIVE);
	}
	
	//Gets relationships that match the triple + group
	public List<Relationship> getRelationships(CharacteristicType charType, Relationship r) {
		return getRelationships(charType, r.getType(), r.getTarget(), r.getGroupId(), ActiveState.ACTIVE);
	}
	
	public List<Relationship> getRelationships(CharacteristicType characteristicType, RelationshipTemplate t) {
		return getRelationships(characteristicType, t.getType(), t.getTarget(), ActiveState.ACTIVE);
	}
	
	public Relationship getRelationship(RelationshipTemplate r, int groupId) {
		List<Relationship> rels = getRelationships(r.getCharacteristicType(), r.getType(), r.getTarget(), groupId, ActiveState.ACTIVE);
		if (rels == null || rels.size() == 0) {
			return null;
		} else if (groupId != NOT_SET && rels.size() > 1) {
			throw new IllegalArgumentException(this + " group " + groupId + " contained > 1 " + r);
		}
		return rels.get(0);
	}
	
	public List<Relationship> getRelationships(Relationship r, ActiveState activeState) {
		return getRelationships(r.getCharacteristicType(), r.getType(), r.getTarget(), r.getGroupId(), activeState);
	}
	
	public List<Relationship> getRelationships(RelationshipTemplate r, ActiveState activeState) {
		return getRelationships(r.getCharacteristicType(), r.getType(), r.getTarget(), activeState);
	}
	
	public List<Relationship> getRelationships(CharacteristicType characteristicType, Concept type, ActiveState activeState) {
		List<Relationship> potentialMatches = getRelationships(characteristicType, activeState);
		List<Relationship> matches = new ArrayList<Relationship>();
		for (Relationship r : potentialMatches) {
			if (type == null || r.getType().equals(type)) {
				matches.add(r);
			}
		}
		return matches;
	}

	public List<Relationship> getRelationships(CharacteristicType charType, Concept[] targets, ActiveState state) {
		List<Relationship> matchingRels = new ArrayList<>();
		for (Concept target : targets) {
			matchingRels.addAll(getRelationships(charType, null, target, state));
		}
		return matchingRels;
	}
	
	public List<Relationship> getRelationships(CharacteristicType characteristicType, Concept type, Concept target, ActiveState activeState) {
		List<Relationship> potentialMatches = getRelationships(characteristicType, type, activeState);
		List<Relationship> matches = new ArrayList<Relationship>();
		for (Relationship r : potentialMatches) {
			if (target == null || r.getTarget().equals(target)) {
				matches.add(r);
			}
		}
		return matches;
	}

	public List<Relationship> getRelationships(CharacteristicType characteristicType, Concept type, Concept target, int groupId, ActiveState activeState) {
		List<Relationship> potentialMatches = getRelationships(characteristicType, type, target, activeState);
		List<Relationship> matches = new ArrayList<Relationship>();
		for (Relationship r : potentialMatches) {
			if (groupId == NOT_SET || r.getGroupId() == groupId) {
				matches.add(r);
			}
		}
		return matches;
	}
	
	public List<Relationship> getRelationships(CharacteristicType characteristicType, Concept type, int groupId) {
		List<Relationship> potentialMatches = getRelationships(characteristicType, type, ActiveState.ACTIVE);
		List<Relationship> matches = new ArrayList<Relationship>();
		for (Relationship r : potentialMatches) {
			if (r.getGroupId() == groupId) {
				matches.add(r);
			}
		}
		return matches;
	}

	public Relationship getRelationship(String id) {
		for (Relationship r : relationships) {
			if (r.getRelationshipId().equals(id)) {
				return r;
			}
		}
		return null;
	}

	public void setRelationships(List<Relationship> relationships) {
		this.relationships = relationships;
	}
	
	public void removeRelationship(Relationship r) {
		removeRelationship(r, false);
	}
	
	public void removeRelationship(Relationship r, boolean force) {
		if (r.getEffectiveTime() != null && !force) {
			throw new IllegalArgumentException("Attempt to deleted published relationship " + r);
		}
		this.relationships.removeAll(Collections.singleton(r));
		recalculateGroups();
	}

	public boolean isIsLeafStated() {
		return isLeafStated;
	}

	public void setIsLeafStated(boolean isLeafStated) {
		this.isLeafStated = isLeafStated;
	}

	public boolean isIsLeafInferred() {
		return isLeafInferred;
	}

	public void setIsLeafInferred(boolean isLeafInferred) {
		this.isLeafInferred = isLeafInferred;
	}
	
	public boolean isLeaf (CharacteristicType c) {
		if (c.equals(CharacteristicType.STATED_RELATIONSHIP)) {
			return statedChildren.size() == 0;
		} else {
			return inferredChildren.size() == 0;
		}
	}

	@Override
	public String toString() {
		return conceptId + " |" + getFsn() + "|";
	}

	public String toStringPref() {
		return conceptId + " |" + getPreferredSynonym() + "|";
	}

	public String toExpression(CharacteristicType charType) {
		String expression = getDefinitionStatus().equals(DefinitionStatus.FULLY_DEFINED) ? "=== " : "<<< ";
		
		expression += getParents(charType).stream().map(p -> p.toString())
							.collect(Collectors.joining (" + \n"));
		
		if (getRelationships(charType, ActiveState.ACTIVE).size() > 0) {
			expression += " : \n";
		}
		//Add any ungrouped attributes
		boolean isFirstGroup = true;
		for (RelationshipGroup group : getRelationshipGroups (charType)) {
			if (isFirstGroup) {
				isFirstGroup = false;
			} else {
				expression += ",\n";
			}
			expression += group.isGrouped() ? "{" : "";
			expression += group.getRelationships().stream().map(p -> "  " + p.toString())
					.collect(Collectors.joining (",\n"));
			expression += group.isGrouped() ? " }" : "";
		}
		return expression;
	}

	@Override
	public int hashCode() {
		if (conceptId != null)
			return conceptId.hashCode();
		
		//Where a conceptId does not exist, hash the FSN
		if (fsn !=null && !getFsn().trim().isEmpty()) {
			return fsn.hashCode();
		}
		
		//Where we don't have either, hash the expression
		return toExpression(CharacteristicType.STATED_RELATIONSHIP).hashCode();
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof Concept)) {
			return false;
		}
		Concept rhs = ((Concept) other);
		//If both concepts have Ids, compare those
		if (this.conceptId != null && rhs.conceptId != null) {
			return (this.conceptId.compareTo(rhs.conceptId) == 0);
		}
		
		//Otherwise, compare FSNs or expressions
		if (this.fsn != null && !this.getFsn().isEmpty() && rhs.fsn != null && !rhs.getFsn().isEmpty()) {
			return (this.fsn.equals(rhs.fsn));
		}
		String thisExpression = this.toExpression(CharacteristicType.STATED_RELATIONSHIP);
		String rhsExpression = rhs.toExpression(CharacteristicType.STATED_RELATIONSHIP);
		return thisExpression.equals(rhsExpression);
	}

	public void addRelationship(Concept type, Concept target) {
		Relationship r = new Relationship();
		r.setActive(true);
		r.setGroupId(0);
		r.setCharacteristicType(CharacteristicType.STATED_RELATIONSHIP);
		r.setSourceId(this.getConceptId());
		r.setType(type);
		r.setTarget(target);
		r.setModifier(Modifier.EXISTENTIAL);
		relationships.add(r);
		recalculateGroups();
	}

	public boolean isLoaded() {
		return isLoaded;
	}

	public void setLoaded(boolean isLoaded) {
		this.isLoaded = isLoaded;
	}

	public int getOriginalFileLineNumber() {
		return originalFileLineNumber;
	}
	
	public boolean addRelationship(Relationship r) {
		return addRelationship(r, false);
	}
	
	public int addOrReactivateRelationship(Relationship r) {
		//Do we already have an inactive version of this relationship to reactivate?
		//Only relevant if this relationship is new
		if (r.getId() == null) {
			//Before considering inactive rels, check we don't already have this relationship
			List<Relationship> activeRels = getRelationships(r);
			if (activeRels.size() > 0) {
				System.out.println ("Ignoring relationship add in " + this + ", triple + group already present and active " + activeRels.get(0));
				return NO_CHANGES_MADE;
			}
			for (Relationship match : getRelationships(r, ActiveState.INACTIVE)) {
				System.out.println ("Reactivating " + match + " in " + this);
				match.setActive(true);
				return CHANGE_MADE;
			}
		}
		addRelationship(r);
		return CHANGE_MADE;
	}
	
	/**
	 * @return true if the relationship was successfully added, or false if it was ignored
	 */
	public boolean addRelationship(Relationship r, boolean replaceTripleMatch) {
		//Do we already had a relationship with this id?  Replace if so.
		//Actually since delta files from the TS could have different SCTIDs
		//Null out the ID temporarily to force a triple + groupId comparison
		String id = r.getRelationshipId();
		if (replaceTripleMatch) {
			r.setRelationshipId(null);
		}
		
/*		if (r.getType().equals(IS_A) && this.getConceptId().equals("277301002")) {
			TermServerScript.debug("here");
		}*/
		Relationship allowableDuplicate = null;
		if (relationships.contains(r)) {
			//Might match more than one if we have historical overlapping triples
			
			//Special case were we receive conflicting rows for the same triple in a delta.
			//keep the active row in that case.
			//It might alternatively be that we have a 2nd axiom maintaining an is-a relationship
			//avoid removing the parent/child link if we have another axiom with this rel
			if (!r.isActive() && replaceTripleMatch && r.getEffectiveTime() == null) {
				for (Relationship match : getRelationships(r)) {
					if (match.isActive() && 
							(match.getEffectiveTime() == null || !match.fromSameAxiom(r))) {
						//System.out.println ("Ignoring inactivation in " + this + " between already received active " + match + " and incoming inactive " + r);
						return false;
					}
				}
			}
			
			//Seeing an issue where a row from the snapshot stated relationships file is
			//wiping out an axiom relationship.  Axioms always trump.
			if (r.getCharacteristicType().equals(CharacteristicType.STATED_RELATIONSHIP) && !r.isActive() && !r.fromAxiom()) {
				for (Relationship match : getRelationships(r)) {
					if (match.isActive() && match.fromAxiom()) {
						return false;
					}
				}
			}
			
			//We may encounter the same active relationship from both stated (eek!) and an axiom
			//Allow this situation to exist, so that we can detect this anomaly in the ReleaseIssuesReport
			if (r.getCharacteristicType().equals(CharacteristicType.STATED_RELATIONSHIP) && r.isActive()) {
				for (Relationship match : getRelationships(r)) {
					if (match.isActive() && match.fromAxiom() != r.fromAxiom()) {
						allowableDuplicate = match;
					}
				}
			}
			relationships.removeAll(Collections.singleton(r));
		}
		r.setRelationshipId(id);
		
		//Now we might need to remove a relationship with the same id if it also moved group
		relationships.removeAll(Collections.singleton(r));
		relationships.add(r);
		if (allowableDuplicate != null) {
			relationships.add(allowableDuplicate);
		}
		recalculateGroups();
		return true;
	}
	
	public void addChild(CharacteristicType charType, Concept c) {
		getChildren(charType).add(c);
	}
	
	public void removeChild(CharacteristicType charType, Concept c) {
		getChildren(charType).remove(c);
	}
	
	public void addParent(CharacteristicType charType, Concept p) {
		getParents(charType).add(p);
	}
	
	public void removeParent(CharacteristicType charType, Concept p) {
		getParents(charType).remove(p);
	}

	public ConceptType getConceptType() {
		return conceptType;
	}

	public void setConceptType(ConceptType conceptType) {
		this.conceptType = conceptType;
	}
	
	public Set<Concept> getDescendents(int depth) throws TermServerScriptException {
		return getDescendents(depth, CharacteristicType.INFERRED_RELATIONSHIP);
	}
	
	public Set<Concept> getDescendents(int depth, CharacteristicType characteristicType) throws TermServerScriptException {
		return getDescendents(depth, characteristicType, false);
	}
	
	public Set<Concept> getDescendents(int depth, CharacteristicType characteristicType, boolean includeSelf) throws TermServerScriptException {
		Set<Concept> allDescendents = new HashSet<Concept>();
		this.populateAllDescendents(allDescendents, depth, characteristicType);
		if (includeSelf) {
			allDescendents.add(this);
		}
		return allDescendents;
	}
	
	private void populateAllDescendents(Set<Concept> descendents, int depth, CharacteristicType characteristicType) throws TermServerScriptException {
		for (Concept thisChild : getChildren(characteristicType)) {
			descendents.add(thisChild);
			if (depth == NOT_SET || depth > 1) {
				int newDepth = depth == NOT_SET ? NOT_SET : depth - 1;
				thisChild.populateAllDescendents(descendents, newDepth, characteristicType);
			}
		}
	}
	
	public Set<Concept> getAncestors(int depth) throws TermServerScriptException {
		return getAncestors(depth, CharacteristicType.INFERRED_RELATIONSHIP, false);
	}
	
	public Set<Concept> getAncestors(int depth, CharacteristicType characteristicType, boolean includeSelf) throws TermServerScriptException {
		Set<Concept> allAncestors = new HashSet<Concept>();
		this.populateAllAncestors(allAncestors, depth, characteristicType);
		if (includeSelf) {
			allAncestors.add(this);
		}
		return allAncestors;
	}
	
	private void populateAllAncestors(Set<Concept> ancestors, int depth, CharacteristicType characteristicType) throws TermServerScriptException {
		for (Concept thisParent : getParents(characteristicType)) {
			ancestors.add(thisParent);
			if (depth == NOT_SET || depth > 1) {
				int newDepth = depth == NOT_SET ? NOT_SET : depth - 1;
				thisParent.populateAllAncestors(ancestors, newDepth, characteristicType);
			}
	}
	}
	
	public Set<Concept> getChildren(CharacteristicType characteristicType) {
		switch (characteristicType) {
			case STATED_RELATIONSHIP : return statedChildren;
			case INFERRED_RELATIONSHIP : return inferredChildren;
			default:
		}
		return null;
	}

	//A preferred description can be preferred in either dialect, but if we're looking for an acceptable one, 
	//then it must not also be preferred in the other dialect
	public List<Description> getDescriptions(Acceptability acceptability, DescriptionType descriptionType, ActiveState activeState) throws TermServerScriptException {
		List<Description> matchingDescriptions = new ArrayList<Description>();
		for (Description thisDescription : getDescriptions(activeState)) {
			//Is this description of the right type?
			if (descriptionType == null || thisDescription.getType().equals(descriptionType)) {
				//Are we working with JSON representation and acceptability map, or an RF2 representation
				//with language refset entries?
				if (thisDescription.getAcceptabilityMap() != null) {
					if (acceptability.equals(Acceptability.BOTH) || thisDescription.getAcceptabilityMap().containsValue(acceptability)) {
						if (acceptability.equals(Acceptability.BOTH)) {
							matchingDescriptions.add(thisDescription);
						} else if (acceptability.equals(Acceptability.PREFERRED) || !thisDescription.getAcceptabilityMap().containsValue(Acceptability.PREFERRED)) {
							matchingDescriptions.add(thisDescription);
						}
					}
				} else if (!thisDescription.getLangRefsetEntries().isEmpty()) {
					boolean match = false;
					boolean preferredFound = false;
					for (LangRefsetEntry l : thisDescription.getLangRefsetEntries(ActiveState.ACTIVE)) {
						if (acceptability.equals(Acceptability.BOTH) || 
							acceptability.equals(SnomedUtils.translateAcceptability(l.getAcceptabilityId()))) {
							match = true;
						} 
						
						if (l.getAcceptabilityId().equals(SCTID_PREFERRED_TERM)) {
							preferredFound = true;
						}
					}
					//Did we find one, and if it's acceptable, did we also not find another preferred
					if (match) {
						if (acceptability.equals(Acceptability.ACCEPTABLE)) {
							if (!preferredFound) {
								matchingDescriptions.add(thisDescription);
							}
						} else {
							matchingDescriptions.add(thisDescription);
						}
					}
				} else {
					TermServerScript.warn (thisDescription + " is active with no Acceptability map or Language Refset entries (since " + thisDescription.getEffectiveTime() + ").");
				}
			}
		}
		return matchingDescriptions;
	}
	
	public List<Description> getDescriptions(String langRefsetId, Acceptability targetAcceptability, DescriptionType descriptionType, ActiveState active) throws TermServerScriptException {
		//Get the matching terms, and then pick the ones that have the appropriate Acceptability for the specified Refset
		List<Description> matchingDescriptions = new ArrayList<Description>();
		for (Description d : getDescriptions(targetAcceptability, descriptionType, active)) {
			//We might have this acceptability either from a Map (JSON) or Langrefset entry (RF2)
			if (SnomedUtils.hasAcceptabilityInDialect(d, langRefsetId, targetAcceptability)) {
				//Need to check the Acceptability because the first function might match on some other language
				matchingDescriptions.add(d);
			}
		}
		return matchingDescriptions;
	}
	
	public List<Description> getDescriptions(ActiveState a) {
		List<Description> results = new ArrayList<Description>();
		for (Description d : descriptions) {
			if (SnomedUtils.descriptionHasActiveState(d, a)) {
					results.add(d);
			}
		}
		return results;
	}
	
	public Description getDescription(String term, ActiveState a) {
		//Return a
		for (Description d : getDescriptions(a)) {
			if (d.getTerm().equals(term)) {
				return d;
			}
		}
		return null;
	}

	public Description getDescription(String descriptionId) {
		for (Description d : descriptions) {
			if (d.getDescriptionId().equals(descriptionId)) {
				return d;
			}
		}
		return null;
	}
	
	public void addDescription(Description d) {
		addDescription(d, false); //Don't allow duplicates by default
	}
	
	public void addDescription(Description d, boolean allowDuplicateTerms) {
		//Do we already have a description with this SCTID?
		if (!allowDuplicateTerms && descriptions.contains(d)) {
			descriptions.remove(d);
		}
		
		descriptions.add(d);
		if (d.isActive() && d.getType().equals(DescriptionType.FSN)) {
			this.setFsn(d.getTerm());
		}
	}
	
	public void removeDescription (Description d) {
		descriptions.remove(d);
	}

	public Set<Concept> getParents(CharacteristicType characteristicType) {
		//Concepts loaded from TS would not get these arrays populated.  Populate.
		Set<Concept> parents = null;
		switch (characteristicType) {
			case STATED_RELATIONSHIP : parents = statedParents;
										break;
			case INFERRED_RELATIONSHIP: parents = inferredParents;
										break;
			default: throw new IllegalArgumentException("Cannot have " + characteristicType + " parents.");
		}
		
		if (parents == null || parents.size() == 0) {
			if (parents == null) {
				parents = new HashSet<>();
				if (characteristicType.equals(CharacteristicType.STATED_RELATIONSHIP)) {
					statedParents = parents;
				} else {
					inferredParents = parents;
				}
			}
			populateParents(parents, characteristicType);
		}
		return parents;
	}
	
	private void populateParents(Set<Concept> parents, CharacteristicType characteristicType) {
		parents.clear();
		for (Relationship parentRel : getRelationships(characteristicType, IS_A, ActiveState.ACTIVE)) {
			parents.add(parentRel.getTarget());
		}
	}
	
	public boolean hasParent(Concept parent) {
		return hasParent(parent, CharacteristicType.STATED_RELATIONSHIP);
	}
	
	public boolean hasParent(Concept parent, CharacteristicType charType) {
		return getParents(charType).contains(parent);
	}
	
	public Concept getFirstParent() {
		Iterator<Concept> i = getParents(CharacteristicType.STATED_RELATIONSHIP).iterator();
		if (i.hasNext()) {
			return getParents(CharacteristicType.STATED_RELATIONSHIP).iterator().next();
		}
		return null;
	}

	public List<String>getAssertionFailures() {
		return assertionFailures;
	}
	
	public void addAssertionFailure(String failure) {
		assertionFailures.add(failure);
	}

	public String getAssignedAuthor() {
		return assignedAuthor;
	}

	public void setAssignedAuthor(String assignedAuthor) {
		this.assignedAuthor = assignedAuthor;
	}

	public Description getFSNDescription() {
		if (descriptions == null) {
			String err = "Concept " + conceptId + " |" + getFsn() + "| has no descriptions";
			throw new IllegalArgumentException(err);
		}
		for (Description d : descriptions) {
			if (d.isActive() && d.getType().equals(DescriptionType.FSN)) {
				return d;
			}
		}
		return null;
	}
	
	public List<Description> getSynonyms(Acceptability Acceptability) {
		List<Description> synonyms = new ArrayList<Description>();
		for (Description d : descriptions) {
			if (d.isActive() && d.getAcceptabilityMap().values().contains(Acceptability) && d.getType().equals(DescriptionType.SYNONYM)) {
				synonyms.add(d);
			}
		}
		return synonyms;
	}

	public boolean hasTerm(String term, String langCode) {
		boolean hasTerm = false;
		for (Description d : descriptions) {
			if (d.getTerm().equals(term) && d.getLang().equals(langCode)) {
				hasTerm = true;
				break;
			}
		}
		return hasTerm;
	}
	
	public Description findTerm(String term) {
		return findTerm (term, null, false, true);
	}
	
	public Description findTerm(String term , String lang) {
		return findTerm (term, lang, false, true);
	}

	public Description findTerm(String term , String lang, boolean caseInsensitive, boolean includeInactive) {
		//First look for a match in the active terms, then try inactive
		for (Description d : getDescriptions(ActiveState.ACTIVE)) {
			if ((lang == null || lang.equals(d.getLang()))) {
				if (caseInsensitive) {
					term = term.toLowerCase();
					String desc = d.getTerm().toLowerCase();
					if (term.equals(desc)) {
						return d;
					}
				} else if (d.getTerm().equals(term)) {
						return d;
				}
			}
		}
		
		if (includeInactive) {
			for (Description d : getDescriptions(ActiveState.INACTIVE)) {
				if (d.getTerm().equals(term) && 
						(lang == null || lang.equals(d.getLang()))) {
					return d;
				}
			}
		}
		return null;
	}

	public void setModified() {
		isModified = true;
	}
	
	public boolean isModified() {
		return isModified;
	}
	
	public int getDepth() {
		return depth;
	}
	
	public void setDepth(int depth) {
		// We'll maintain the shortest possible path, so don't allow depth to increase
		if (this.depth == NOT_SET || depth < this.depth) {
			this.depth = depth;
		}
	}

	@Override
	public int compareTo(Concept c) {
		return getConceptId().compareTo(c.getConceptId());
	}

	public List<InactivationIndicatorEntry> getInactivationIndicatorEntries() {
		if (inactivationIndicatorEntries == null) {
			inactivationIndicatorEntries = new ArrayList<InactivationIndicatorEntry>();
		}
		return inactivationIndicatorEntries;
	}
	
	public List<InactivationIndicatorEntry> getInactivationIndicatorEntries(ActiveState activeState) {
		if (activeState.equals(ActiveState.BOTH)) {
			return getInactivationIndicatorEntries();
		} else {
			boolean isActive = activeState.equals(ActiveState.ACTIVE);
			List<InactivationIndicatorEntry> selectedInactivationIndicatortEntries = new ArrayList<InactivationIndicatorEntry>();
			for (InactivationIndicatorEntry i : getInactivationIndicatorEntries()) {
				if (i.isActive() == isActive) {
					selectedInactivationIndicatortEntries.add(i);
				}
			}
			return selectedInactivationIndicatortEntries;
		}
	}
	
	public List<AssociationEntry> getAssociations() {
		if (associations == null) {
			associations = new ArrayList<AssociationEntry>();
		}
		return associations;
	}
	
	public List<AssociationEntry> getAssociations(ActiveState activeState) {
		return getAssociations(activeState, false); //All associations by default
	}
	

	public List<AssociationEntry> getAssociations(ActiveState activeState, boolean historicalAssociationsOnly) {
		if (activeState.equals(ActiveState.BOTH)) {
			return getAssociations();
		} else {
			boolean isActive = activeState.equals(ActiveState.ACTIVE);
			List<AssociationEntry> selectedAssociations = new ArrayList<AssociationEntry>();
			for (AssociationEntry h : getAssociations()) {
				//TODO Find a better way of working out if an association is a historical association
				if (h.isActive() == isActive && (!historicalAssociationsOnly ||
					(h.getRefsetId().startsWith("9000000")))) {
					selectedAssociations.add(h);
				}
			}
			return selectedAssociations;
		}
	}
	
	public List<AxiomEntry> getAxiomEntries() {
		if (axiomEntries == null) {
			axiomEntries = new ArrayList<AxiomEntry>();
		}
		return axiomEntries;
	}
	
	public List<AxiomEntry> getAxiomEntries(ActiveState activeState, boolean includeGCIs) {
		switch (activeState) {
			case BOTH: return getAxiomEntries();
			case ACTIVE: return getAxiomEntries().stream()
					.filter(a -> a.isActive())
					.filter(a -> includeGCIs || !a.isGCI())
					.collect(Collectors.toList());
			case INACTIVE: return getAxiomEntries().stream()
					.filter(a -> !a.isActive())
					.filter(a -> includeGCIs || !a.isGCI())
					.collect(Collectors.toList());
			default: throw new IllegalStateException("Unknown state " + activeState);
		}
	}
	
	//id	effectiveTime	active	moduleId	definitionStatusId
	public String[] toRF2() throws TermServerScriptException {
		return new String[] {conceptId, 
				effectiveTime, 
				(active?"1":"0"), 
				moduleId, 
				SnomedUtils.translateDefnStatusToSctid(definitionStatus)};
	}
	
	public String[] toRF2Deletion() throws TermServerScriptException {
		return new String[] {conceptId, 
				effectiveTime, 
				deletionEffectiveTime,
				(active?"1":"0"), 
				"1",  //Deletion is active
				moduleId, 
				SnomedUtils.translateDefnStatusToSctid(definitionStatus)};
	}

	public void setInactivationIndicatorEntries(
			List<InactivationIndicatorEntry> inactivationIndicatorEntries) {
		this.inactivationIndicatorEntries = inactivationIndicatorEntries;
	}

	public boolean isDeleted() {
		return isDeleted;
	}

	public void delete(String deletionEffectiveTime) {
		this.isDeleted = true;
		this.deletionEffectiveTime = deletionEffectiveTime;
	}

	public InactivationIndicator getInactivationIndicator() {
		return inactivationIndicator;
	}

	public void setInactivationIndicator(InactivationIndicator inactivationIndicator) {
		this.inactivationIndicator = inactivationIndicator;
	}

	public static ConceptChange fromRf2Delta(String[] lineItems) {
		return (ConceptChange) fillFromRf2(new ConceptChange(lineItems[CON_IDX_ID]), lineItems);
	}
	
	public static Concept fillFromRf2(Concept c, String[] lineItems) {
		c.setActive(lineItems[CON_IDX_ACTIVE].equals("1"));
		c.setEffectiveTime(lineItems[CON_IDX_EFFECTIVETIME]);
		c.setModuleId(lineItems[CON_IDX_MODULID]);
		c.setDefinitionStatus(SnomedUtils.translateDefnStatus(lineItems[CON_IDX_DEFINITIONSTATUSID]));
		return c;
	}

	public List<Concept> getSiblings(CharacteristicType cType) {
		List<Concept> siblings = new ArrayList<Concept>();
		//Get all the immediate children of the immediate parents
		for (Concept thisParent : getParents(cType)) {
			siblings.addAll(thisParent.getChildren(cType));
		}
		return siblings;
	}

	@Override
	public String getId() {
		return conceptId;
	}
	
	public void setId(String id) {
		conceptId = id;
	}

	@Override
	public String getReportedName() {
		return getFsn();
	}

	@Override
	public String getReportedType() {
		return conceptType==null?"": conceptType.toString();
	}

	public boolean isDirty() {
		return isDirty;
	}

	public void setDirty() {
		this.isDirty = true;
	}

	public void addInactivationIndicator(InactivationIndicatorEntry i) {
		//Remove inactivation indicator first incase we're replacing it
		getInactivationIndicatorEntries().remove(i);
		getInactivationIndicatorEntries().add(i);
		if (i.isActive()) {
			setInactivationIndicator(SnomedUtils.translateInactivationIndicator(i.getInactivationReasonId()));
		}
	}

	public AssociationTargets getAssociationTargets() {
		if (associationTargets == null) {
			associationTargets = new AssociationTargets();
		}
		return associationTargets;
	}

	public void setAssociationTargets(AssociationTargets associationTargets) {
		this.associationTargets = associationTargets;
	}
	
	public Concept clone() {
		return clone(null, false);
	}
	
	public Concept cloneWithIds() {
		return clone(null, true);
	}
	
	public Concept cloneWithUUIDs() {
		return clone(null, true, true, true);
	}
	
	public Concept clone(String sctid) {
		return clone(sctid, false);
	}
	
	private Concept clone(String sctid, boolean keepIds) {
		//Don't include inactive components by default
		return clone(sctid, keepIds, false, false);
	}
	
	private Concept clone(String sctid, boolean keepIds, boolean includeInactiveComponents, boolean populateUUIDs) {
		Concept clone = new Concept(keepIds?conceptId:sctid, getFsn());
		if (populateUUIDs && clone.getId() == null) {
			clone.setId(UUID.randomUUID().toString());
		}
		clone.setEffectiveTime(keepIds?effectiveTime:null);
		clone.setActive(active);
		clone.setDefinitionStatus(getDefinitionStatus());
		clone.setModuleId(getModuleId());
		clone.setConceptType(conceptType);
		clone.setInactivationIndicator(inactivationIndicator);
		clone.setReleased(released);
		
		//Copy all descriptions
		ActiveState activeState = includeInactiveComponents ? ActiveState.BOTH : ActiveState.ACTIVE;
		for (Description d : getDescriptions(activeState)) {
			//We need to null out the conceptId since the clone is a new concept
			Description dClone = d.clone(keepIds?d.getDescriptionId():null);
			dClone.setConceptId(keepIds?conceptId:null);
			dClone.setEffectiveTime(keepIds?d.getEffectiveTime():null);
			clone.addDescription(dClone);
			//If we're keeping IDs, copy any inactivation indicators also.
			if (keepIds) {
				dClone.setInactivationIndicatorEntries(new ArrayList<>(d.getInactivationIndicatorEntries()));
			}
			if (populateUUIDs && d.getId() == null) {
				dClone.setDescriptionId(UUID.randomUUID().toString());
			}
		}
		
		//Copy all stated relationships, or in the case of an exact clone (keepIds = true) also inferred
		List<Relationship> selectedRelationships = keepIds ? relationships : getRelationships(CharacteristicType.STATED_RELATIONSHIP, activeState);
		for (Relationship r : selectedRelationships) {
			//We need to null out the sourceId since the clone is a new concept
			Relationship rClone = r.clone(keepIds?r.getRelationshipId():null);
			rClone.setEffectiveTime(keepIds?r.getEffectiveTime():null);
			rClone.setSourceId(null);
			//We don't want any hierarchy modifications done, so just add the clone directly
			clone.relationships.add(rClone);
			if (populateUUIDs && r.getId() == null) {
				rClone.setRelationshipId(UUID.randomUUID().toString());
			}
		}
		
		//Copy class axioms
		List<Axiom> axioms = getClassAxioms();
		for (Axiom axiom : axioms) {
			//We need to null out the sourceId since the clone is a new concept
			Axiom aClone = axiom.clone(keepIds?axiom.getId():null, clone);
			aClone.setEffectiveTime(keepIds?axiom.getEffectiveTime():null);
			clone.getClassAxioms().add(aClone);
			if (populateUUIDs && axiom.getId() == null) {
				aClone.setAxiomId(UUID.randomUUID().toString());
			}
		}
		
		//Copy Parent/Child arrays
		clone.inferredChildren = inferredChildren == null? new HashSet<>() : new HashSet<>(inferredChildren);
		clone.statedChildren = statedChildren == null? new HashSet<>() : new HashSet<>(statedChildren);
		clone.inferredParents = inferredParents == null? new HashSet<>() : new HashSet<>(inferredParents);
		clone.statedParents = statedParents == null? new HashSet<>() : new HashSet<>(statedParents);
		
		//If we're keeping IDs, copy any inactivation indicators and historical associations also.
		if (keepIds) {
			clone.inactivationIndicatorEntries = new ArrayList<>(getInactivationIndicatorEntries());
			clone.associations = new ArrayList<>(getAssociations());
		}
		
		return clone;
	}

	@Override
	public ComponentType getComponentType() {
		return ComponentType.CONCEPT;
	}
	
	public RelationshipGroup getRelationshipGroup(CharacteristicType charType, int groupId ) {
		for (RelationshipGroup g : getRelationshipGroups(charType)) {
			if (g.getGroupId() == groupId) {
				return g;
			}
		}
		return null;
	}
	
	public RelationshipGroup getRelationshipGroupSafely(CharacteristicType charType, int groupId ) {
		RelationshipGroup group = getRelationshipGroup(charType, groupId);
		if (group == null) {
			group = new RelationshipGroup(groupId);
		}
		return group;
	}
	
	/**
	 * Relationship groups will not include IS A relationships by default
	 */
	public Collection<RelationshipGroup> getRelationshipGroups(CharacteristicType characteristicType) {
		return getRelationshipGroups(characteristicType, false);
	}
	
	public Collection<RelationshipGroup> getRelationshipGroups(CharacteristicType characteristicType, boolean includeIsA) {
		Collection<RelationshipGroup> relationshipGroups = characteristicType.equals(CharacteristicType.STATED_RELATIONSHIP) ? statedRelationshipGroups : inferredRelationshipGroups;
		if (relationshipGroups == null) {
			Map<Integer, RelationshipGroup> groups = new HashMap<>();
			//If we're including group 0, always add that in any event
			for (Relationship r : getRelationships(characteristicType, ActiveState.ACTIVE)) {
				if (!includeIsA && r.getType().equals(IS_A)) {
					continue;
				}
				//Do we know about this Relationship Group yet?
				RelationshipGroup group = groups.get(r.getGroupId());
				if (group == null) {
					group = new RelationshipGroup(r.getGroupId() , r);
					groups.put(r.getGroupId(), group);
				} else {
					group.getRelationships().add(r);
				}
			}
			relationshipGroups = groups.values();
			if (characteristicType.equals(CharacteristicType.STATED_RELATIONSHIP)) {
				statedRelationshipGroups = relationshipGroups;
			} else {
				inferredRelationshipGroups = relationshipGroups;
			}
		}
		return relationshipGroups;
	}

	public int addRelationshipGroup(RelationshipGroup group, List<Relationship> availableForReuse) {
		int changesMade = 0;
		for (Relationship r : group.getRelationships()) {
			//Do we have one of these relationships available to be reused?
			for (Relationship reuseMe : new ArrayList<>(availableForReuse)) {
				if (reuseMe.getType().equals(r.getType()) && reuseMe.getTarget().equals(r.getTarget())) {
					//Check we actually need this relationship before reusing
					if (getRelationships(r, ActiveState.ACTIVE).size() == 0) { 
						System.out.println("** Reusing: " + reuseMe + " in group " + r.getGroupId());
						availableForReuse.remove(reuseMe);
						reuseMe.setGroupId(r.getGroupId());
						reuseMe.setActive(true);
						r = reuseMe;
					}
					break;
				}
			}
			changesMade += addOrReactivateRelationship(r);
		}
		recalculateGroups();
		return changesMade;
	}
	
	public void recalculateGroups() {
		//Force recalculation of groups next time they're requested
		statedRelationshipGroups = null;
		inferredRelationshipGroups = null;
	}

	
	public Boolean isReleased() {
		return released;
	}

	public void setReleased(Boolean released) {
		this.released = released;
	}

	public int getMaxGroupId(CharacteristicType charType) {
		int maxGroupId = 0;
		for (RelationshipGroup g : getRelationshipGroups(charType)) {
			if (g.getGroupId() > maxGroupId) {
				maxGroupId = g.getGroupId();
			}
		}
		return maxGroupId;
	}
	
	public List<Axiom> getClassAxioms() {
		if (classAxioms == null) {
			classAxioms = new ArrayList<>();
		}
		return classAxioms;
	}

	public void setClassAxioms(List<Axiom> classAxioms) {
		this.classAxioms = classAxioms;
	}

	public List<Axiom> getAdditionalAxioms() {
		if (additionalAxioms == null) {
			additionalAxioms = new ArrayList<>();
		}
		return additionalAxioms;
	}

	public void setAdditionalAxioms(List<Axiom> additionalAxioms) {
		this.additionalAxioms = additionalAxioms;
	}

	public List<Axiom> getGciAxioms() {
		if (gciAxioms == null) {
			gciAxioms = new ArrayList<>();
		}
		return gciAxioms;
	}

	public void setGciAxioms(List<Axiom> gciAxioms) {
		this.gciAxioms = gciAxioms;
	}

	public AxiomEntry getAxiom(String id) {
		for (AxiomEntry e : axiomEntries) {
			if (e.getId().equals(id)) {
				return e;
			}
		}
		return null;
	}
	
	/**
	 * Cache a sum of relationship target / values for quick comparison
	 */
	public long getStatedRelSum() {
		if (statedRelSum == null) {
			statedRelSum = 0L;
			for (Relationship r : getRelationships(CharacteristicType.STATED_RELATIONSHIP, ActiveState.ACTIVE)) {
				//Drugs modelling doesn't consider 766939001 |Plays role (attribute)|
				if (!r.getType().equals(PLAYS_ROLE)) {
					statedRelSum += Long.parseLong(r.getType().getId()) + Long.parseLong(r.getTarget().getId());
				}
			}
		}
		return statedRelSum;
	}

	public Axiom getFirstActiveClassAxiom() {
		if (classAxioms == null) {
			classAxioms = new ArrayList<Axiom>();
		}
		for (Axiom axiom : classAxioms) {
			if (axiom.isActive()) {
				return axiom;
			}
		}
		//All existing concepts should have an active axiom
		//So no need to reactivate inactive ones.  
		//We will only add a new one if this is a new concept to create
		Axiom axiom = new Axiom(this);
		classAxioms.add(axiom);
		return axiom;
	}

	@Override
	public List<String> fieldComparison(Component other) {
		if (!(other instanceof Concept)) {
			throw new IllegalStateException("Comarison of " + other + " failed.  It's not a concept");
		}
		Concept otherConcept = (Concept)other;
		List<String> differences = new ArrayList<>();
		String name = this.getClass().getSimpleName(); 
		commonFieldComparison(other, differences);
		
		if (!this.getDefinitionStatus().equals(otherConcept.getDefinitionStatus())) {
			differences.add("Definition Status is different in " + name + ": " + this.getDefinitionStatus() + " vs " + otherConcept.getDefinitionStatus());
		}
		return differences;
	}
	
	
	
	public List<Description> findDescriptionsContaining(String targetWord) {
		return findDescriptionsContaining(Collections.singletonList(targetWord));
	}

	public List<Description> findDescriptionsContaining(List<String> words) {
		return findDescriptionsContaining(words, false);
	}

	public List<Description> findDescriptionsContaining(List<String> words, boolean onlyPref) {
		if (words == null || words.size() == 0) {
			return new ArrayList<>();
		}
		
		return descriptions.stream()
				.filter(d -> d.isActive())
				.filter(d -> onlyPref == false || d.isPreferred())
				.filter(d -> words.stream()
						.anyMatch(w -> StringUtils.containsIgnoreCase(d.getTerm(), w)))
				.collect(Collectors.toList());
	}

	public ObjectPropertyAxiomRepresentation getObjectPropertyAxiomRepresentation() {
		return objectPropertyAxiom;
	}
	
	public void setObjectPropertyAxiomRepresentation(ObjectPropertyAxiomRepresentation rep) {
		this.objectPropertyAxiom = rep;
	}

	public void mergeObjectPropertyAxiomRepresentation(ObjectPropertyAxiomRepresentation rep) {
		if (this.objectPropertyAxiom == null) {
			this.objectPropertyAxiom = rep;
		} else {
			this.objectPropertyAxiom.mergeProperties(rep);
		}
		
	}

}

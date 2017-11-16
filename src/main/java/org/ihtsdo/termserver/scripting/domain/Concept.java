package org.ihtsdo.termserver.scripting.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.ihtsdo.termserver.scripting.TermServerScript;
import org.ihtsdo.termserver.scripting.TermServerScriptException;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;

import sun.security.action.GetLongAction;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class Concept implements RF2Constants, Comparable<Concept>, Component {

	@SerializedName("effectiveTime")
	@Expose
	private String effectiveTime;
	@SerializedName("moduleId")
	@Expose
	private String moduleId;
	@SerializedName("active")
	@Expose
	private boolean active = true;
	@SerializedName("conceptId")
	@Expose
	private String conceptId;
	@SerializedName("fsn")
	@Expose
	private String fsn;
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
	
	private boolean isLoaded = false;
	private int originalFileLineNumber;
	private ConceptType conceptType = ConceptType.UNKNOWN;
	private List<String> assertionFailures = new ArrayList<String>();
	private String assignedAuthor;
	private String reviewer;
	boolean isModified = false; //indicates if has been modified in current processing run
	private String deletionEffectiveTime;
	private boolean isDeleted = false;
	private int depth;
	private boolean isDirty = false;
	
	//Note that these values are used when loading from RF2 where multiple entries can exist.
	//When interacting with the TS, only one inactivation indicator is used (see above).
	List<InactivationIndicatorEntry> inactivationIndicatorEntries;
	List<HistoricalAssociation> historicalAssociations;
	
	public String getReviewer() {
		return reviewer;
	}

	public void setReviewer(String reviewer) {
		this.reviewer = reviewer;
	}

	List<Concept> statedParents = new ArrayList<Concept>();
	List<Concept> inferredParents = new ArrayList<Concept>();
	List<Concept> statedChildren = new ArrayList<Concept>();
	List<Concept> inferredChildren = new ArrayList<Concept>();
	
	public Concept(String conceptId) {
		this.conceptId = conceptId;
	}
	
	public Concept(String conceptId, String fsn) {
		this.conceptId = conceptId;
		this.fsn = fsn;
	}

	public Concept(String conceptId, int originalFileLineNumber) {
		this.conceptId = conceptId;
		this.originalFileLineNumber = originalFileLineNumber;
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
		this.moduleId = moduleId;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean newActiveState) {
		this.active = newActiveState;
	}

	public String getConceptId() {
		return conceptId;
	}

	public void setConceptId(String conceptId) {
		this.conceptId = conceptId;
	}

	public String getFsn() {
		return fsn;
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
	 */
	public String getPreferredSynonym() {
		return preferredSynonym;
	}
	
	public Description getPreferredSynonym(String refsetId) throws TermServerScriptException {
		List<Description> pts = getDescriptions(refsetId, Acceptability.PREFERRED, DescriptionType.SYNONYM, ActiveState.ACTIVE);
		return pts.size() == 0 ? null : pts.get(0);
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
	
	public List<Relationship> getRelationships(CharacteristicType characteristicType, ActiveState state) {
		return getRelationships(characteristicType, state, null);
	}
	
	public List<Relationship> getRelationships(CharacteristicType characteristicType, Concept type, ActiveState state) {
		List<Relationship> potentialMatches = getRelationships(characteristicType, state);
		List<Relationship> matches = new ArrayList<Relationship>();
		for (Relationship r : potentialMatches) {
			if (r.getType().equals(type)) {
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
		if (r.getEffectiveTime() != null) {
			throw new IllegalArgumentException("Attempt to deleted published relationship " + r);
		}
		this.relationships.remove(r);
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
		return conceptId + " |" + this.fsn + "|";
	}

	@Override
	public int hashCode() {
		return conceptId.hashCode();
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof Concept)) {
			return false;
		}
		Concept rhs = ((Concept) other);
		return (this.conceptId.compareTo(rhs.conceptId) == 0);
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
	
	public void addRelationship(Relationship r) {
		//Do we already had a relationship with this id?  Replace if so
		if (relationships.contains(r)) {
			relationships.remove(r);
		}
		relationships.add(r);
	}
	
	public void addChild(CharacteristicType characteristicType, Concept c) {
		getChildren(characteristicType).add(c);
	}
	
	public void removeChild(CharacteristicType characteristicType, Concept c) {
		getChildren(characteristicType).remove(c);
	}
	
	public void addParent(CharacteristicType characteristicType, Concept p) {
		getParents(characteristicType).add(p);
	}
	
	public void removeParent(CharacteristicType characteristicType, Concept p) {
		getParents(characteristicType).remove(p);
	}

	public ConceptType getConceptType() {
		return conceptType;
	}

	public void setConceptType(ConceptType conceptType) {
		this.conceptType = conceptType;
	}
	
	public void setConceptType(String conceptTypeStr) {
		if (conceptTypeStr.contains("Strength")) {
			this.setConceptType(ConceptType.PRODUCT_STRENGTH);
		} else if (conceptTypeStr.contains("Entity")) {
			this.setConceptType(ConceptType.MEDICINAL_ENTITY);
		} else if (conceptTypeStr.contains("Form")) {
			this.setConceptType(ConceptType.MEDICINAL_FORM);
		} else if (conceptTypeStr.contains("Grouper")) {
			this.setConceptType(ConceptType.GROUPER);
		} else {
			this.setConceptType(ConceptType.UNKNOWN);
		}
	}
	
	public Set<Concept> getDescendents(int depth) throws TermServerScriptException {
		return getDescendents(depth, CharacteristicType.INFERRED_RELATIONSHIP, ActiveState.ACTIVE);
	}
	
	public Set<Concept> getDescendents(int depth, CharacteristicType characteristicType, ActiveState activeState) throws TermServerScriptException {
		Set<Concept> allDescendents = new HashSet<Concept>();
		this.populateAllDescendents(allDescendents, depth, characteristicType, activeState);
		return allDescendents;
	}
	
	private void populateAllDescendents(Set<Concept> descendents, int depth, CharacteristicType characteristicType, ActiveState activeState) throws TermServerScriptException {
		for (Concept thisChild : getChildren(characteristicType)) {
			if (activeState.equals(ActiveState.BOTH) || thisChild.active == SnomedUtils.translateActive(activeState)) {
				descendents.add(thisChild);
				if (depth == NOT_SET || depth > 1) {
					int newDepth = depth == NOT_SET ? NOT_SET : depth - 1;
					thisChild.populateAllDescendents(descendents, newDepth, characteristicType, activeState);
				}
			}
		}
	}
	
	public Set<Concept> getAncestors(int depth) throws TermServerScriptException {
		return getAncestors(depth, CharacteristicType.INFERRED_RELATIONSHIP, ActiveState.ACTIVE, false);
	}
	
	public Set<Concept> getAncestors(int depth, CharacteristicType characteristicType, ActiveState activeState, boolean includeSelf) throws TermServerScriptException {
		Set<Concept> allAncestors = new HashSet<Concept>();
		this.populateAllAncestors(allAncestors, depth, characteristicType, activeState);
		if (includeSelf) {
			allAncestors.add(this);
		}
		return allAncestors;
	}
	
	private void populateAllAncestors(Set<Concept> ancestors, int depth, CharacteristicType characteristicType, ActiveState activeState) throws TermServerScriptException {
		for (Concept thisParent : getParents(characteristicType)) {
			if (activeState.equals(ActiveState.BOTH) || thisParent.active == SnomedUtils.translateActive(activeState)) {
				ancestors.add(thisParent);
				if (depth == NOT_SET || depth > 1) {
					int newDepth = depth == NOT_SET ? NOT_SET : depth - 1;
					thisParent.populateAllAncestors(ancestors, newDepth, characteristicType, activeState);
				}
			}
		}
	}
	
	public List<Concept> getChildren(CharacteristicType characteristicType) {
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
			if ( descriptionType == null || thisDescription.getType().equals(descriptionType)) {
				//Are we working with JSON representation and acceptability map, or an RF2 representation
				//with language refset entries?
				if (thisDescription.getAcceptabilityMap() != null) {
					if ( acceptability.equals(Acceptability.BOTH) || thisDescription.getAcceptabilityMap().containsValue(acceptability)) {
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
			Acceptability acceptability = d.getAcceptability(langRefsetId);
			if (targetAcceptability == Acceptability.BOTH || (acceptability!= null && acceptability.equals(targetAcceptability))) {
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
	

	public Description getDescription(String descriptionId) {
		for (Description d : descriptions) {
			if (d.getDescriptionId().equals(descriptionId)) {
				return d;
			}
		}
		return null;
	}
	
	public void addDescription(Description d) {
		//Do we already have a description with this SCTID?
		if (descriptions.contains(d)) {
			descriptions.remove(d);
		}
		
		descriptions.add(d);
		if (d.isActive() && d.getType().equals(DescriptionType.FSN)) {
			this.setFsn(d.getTerm());
		}
	}

	public List<Concept> getParents(CharacteristicType characteristicType) {
		switch (characteristicType) {
			case STATED_RELATIONSHIP : return statedParents;
			case INFERRED_RELATIONSHIP: return inferredParents;
			default: return null;
		}
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
		return findTerm (term, null);
	}

	public Description findTerm(String term , String lang) {
		//First look for a match in the active terms, then try inactive
		for (Description d : getDescriptions(ActiveState.ACTIVE)) {
			if (d.getTerm().equals(term) && 
					(lang == null || lang.equals(d.getLang()))) {
				return d;
			}
		}
		
		for (Description d : getDescriptions(ActiveState.INACTIVE)) {
			if (d.getTerm().equals(term) && 
					(lang == null || lang.equals(d.getLang()))) {
				return d;
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
	
	public List<HistoricalAssociation> getHistorialAssociations() {
		if (historicalAssociations == null) {
			historicalAssociations = new ArrayList<HistoricalAssociation>();
		}
		return historicalAssociations;
	}
	
	public List<HistoricalAssociation> getHistorialAssociations(ActiveState activeState) {
		if (activeState.equals(ActiveState.BOTH)) {
			return getHistorialAssociations();
		} else {
			boolean isActive = activeState.equals(ActiveState.ACTIVE);
			List<HistoricalAssociation> selectedHistorialAssociations = new ArrayList<HistoricalAssociation>();
			for (HistoricalAssociation h : getHistorialAssociations()) {
				if (h.isActive() == isActive) {
					selectedHistorialAssociations.add(h);
				}
			}
			return selectedHistorialAssociations;
		}
	}
	
	//id	effectiveTime	active	moduleId	definitionStatusId
	public String[] toRF2() throws TermServerScriptException {
		return new String[] {conceptId, 
				effectiveTime, 
				(active?"1":"0"), 
				moduleId, 
				SnomedUtils.translateDefnStatus(definitionStatus)};
	}
	
	public String[] toRF2Deletion() throws TermServerScriptException {
		return new String[] {conceptId, 
				effectiveTime, 
				deletionEffectiveTime,
				(active?"1":"0"), 
				"1",  //Deletion is active
				moduleId, 
				SnomedUtils.translateDefnStatus(definitionStatus)};
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

	@Override
	public String getName() {
		return fsn;
	}

	@Override
	public String getType() {
		return conceptType==null?"": conceptType.toString();
	}

	public boolean isDirty() {
		return isDirty;
	}

	public void setDirty() {
		this.isDirty = true;
	}

	public void addInactivationIndicator(InactivationIndicatorEntry i) {
		getInactivationIndicatorEntries().add(i);
		if (i.isActive()) {
			setInactivationIndicator(SnomedUtils.translateInactivationIndicator(i.getInactivationReasonId()));
		}
	}

}

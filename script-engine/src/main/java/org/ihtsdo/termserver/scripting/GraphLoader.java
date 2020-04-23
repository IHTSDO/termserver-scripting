package org.ihtsdo.termserver.scripting;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.IOUtils;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component.ComponentType;
import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;
import org.snomed.otf.owltoolkit.conversion.AxiomRelationshipConversionService;
import org.snomed.otf.owltoolkit.conversion.ConversionException;
import org.snomed.otf.owltoolkit.domain.AxiomRepresentation;
import org.springframework.util.StringUtils;

public class GraphLoader implements RF2Constants {

	private static GraphLoader singleton = null;
	private Map<String, Concept> concepts = new HashMap<String, Concept>();
	private Map<String, Description> descriptions = new HashMap<String, Description>();
	private Map<String, Component> allComponents = null;
	private Map<Component, Concept> componentOwnerMap = null;
	private Map<String, Concept> fsnMap = null;
	private Set<String> excludedModules;
	public static int MAX_DEPTH = 1000;
	private Set<Concept> orphanetConcepts;
	private AxiomRelationshipConversionService axiomService;
	
	private DescendantsCache descendantsCache = DescendantsCache.getDescendentsCache();
	private AncestorsCache ancestorsCache = AncestorsCache.getAncestorsCache();
	private AncestorsCache statedAncestorsCache = AncestorsCache.getStatedAncestorsCache();
	
	//Watch that this map is of the TARGET of the association, ie all concepts used in a historical association
	private Map<Concept, List<AssociationEntry>> historicalAssociations =  new HashMap<Concept, List<AssociationEntry>>();
	private TransitiveClosure previousTransativeClosure;
	private Map<Concept, Set<DuplicatePair>> duplicateLangRefsetEntriesMap;

	public StringBuffer log = new StringBuffer();
	
	public static GraphLoader getGraphLoader() {
		if (singleton == null) {
			singleton = new GraphLoader();
			singleton.axiomService = new AxiomRelationshipConversionService (null);
			singleton.excludedModules = new HashSet<>();
			singleton.excludedModules.add(SCTID_LOINC_MODULE);
			populateKnownConcepts();
		}
		return singleton;
	}
	
	private static void populateKnownConcepts() {
		//Pre populate known concepts to ensure we only ever refer to one object
		//Reset concept each time, to avoid contamination from previous runs
		ROOT_CONCEPT.reset();
		singleton.concepts.put(SCTID_ROOT_CONCEPT.toString(), ROOT_CONCEPT);
		
		IS_A.reset();
		singleton.concepts.put(SCTID_IS_A_CONCEPT.toString(), IS_A);

		PHARM_BIO_PRODUCT.reset();
		singleton.concepts.put(PHARM_BIO_PRODUCT.getConceptId(), PHARM_BIO_PRODUCT);
		
		MEDICINAL_PRODUCT.reset();
		singleton.concepts.put(MEDICINAL_PRODUCT.getConceptId(), MEDICINAL_PRODUCT);

		PHARM_DOSE_FORM.reset();
		singleton.concepts.put(PHARM_DOSE_FORM.getConceptId(), PHARM_DOSE_FORM);
		
		SUBSTANCE.reset();
		singleton.concepts.put(SUBSTANCE.getConceptId(), SUBSTANCE);

		CLINICAL_FINDING.reset();
		singleton.concepts.put(CLINICAL_FINDING.getConceptId(), CLINICAL_FINDING);
		
		BODY_STRUCTURE.reset();
		singleton.concepts.put(BODY_STRUCTURE.getConceptId(), BODY_STRUCTURE);

		PROCEDURE.reset();
		singleton.concepts.put(PROCEDURE.getConceptId(), PROCEDURE);
		
		SITN_WITH_EXP_CONTXT.reset();
		singleton.concepts.put(SITN_WITH_EXP_CONTXT.getConceptId(), SITN_WITH_EXP_CONTXT);

		SPECIMEN.reset();
		singleton.concepts.put(SPECIMEN.getConceptId(), SPECIMEN);
		
		OBSERVABLE_ENTITY.reset();
		singleton.concepts.put(OBSERVABLE_ENTITY.getConceptId(),OBSERVABLE_ENTITY);

		EVENT.reset();
		singleton.concepts.put(EVENT.getConceptId(),EVENT);
		
		DISEASE.reset();
		singleton.concepts.put(DISEASE.getConceptId(), DISEASE);
		
		DEVICE.reset();
		singleton.concepts.put(DEVICE.getConceptId(), DEVICE);
	}

	public Collection <Concept> getAllConcepts() {
		return concepts.values();
	}
	
	public void reset() {
		TermServerScript.info("Resetting Graph Loader");
		concepts = new HashMap<String, Concept>();
		descriptions = new HashMap<String, Description>();
		allComponents = null;
		componentOwnerMap = null;
		fsnMap = null;
		orphanetConcepts = null;
		descendantsCache.reset();
		ancestorsCache.reset();
		statedAncestorsCache.reset();
		historicalAssociations =  new HashMap<Concept, List<AssociationEntry>>();
		//We'll reset the ECL cache during TS Init
		populateKnownConcepts();
		previousTransativeClosure = null;
	}
	
	public Set<Concept> loadRelationships(CharacteristicType characteristicType, InputStream relStream, boolean addRelationshipsToConcepts, boolean isDelta, Boolean isReleased) 
			throws IOException, TermServerScriptException {
		Set<Concept> concepts = new HashSet<Concept>();
		BufferedReader br = new BufferedReader(new InputStreamReader(relStream, StandardCharsets.UTF_8));
		String line;
		boolean isHeaderLine = true;
		long relationshipsLoaded = 0;
		int ignoredRelationships = 0;
		while ((line = br.readLine()) != null) {
			if (!isHeaderLine) {
				String[] lineItems = line.split(FIELD_DELIMITER);
				
				//Exclude LOINC
				if (isExcluded(lineItems[IDX_MODULEID])) {
					continue;
				}
				
/*				if (lineItems[REL_IDX_ID].equals("16101000172123")) {
					TermServerScript.debug ("Debug Here");
				}
				
				if (characteristicType.equals(CharacteristicType.STATED_RELATIONSHIP) && 
						lineItems[REL_IDX_SOURCEID].equals("108554009") && 
						lineItems[REL_IDX_TYPEID].equals("726542003")) {
					TermServerScript.debug ("Debug Here");
				}*/
				
				if (!isConcept(lineItems[REL_IDX_SOURCEID])) {
					TermServerScript.debug (characteristicType + " relationship " + lineItems[REL_IDX_ID] + " referenced a non concept identifier: " + lineItems[REL_IDX_SOURCEID]);
				}
				Concept thisConcept = getConcept(lineItems[REL_IDX_SOURCEID]);
				if (addRelationshipsToConcepts) {
					ignoredRelationships += addRelationshipToConcept(characteristicType, lineItems, isDelta, isReleased);
				}
				concepts.add(thisConcept);
				relationshipsLoaded++;
			} else {
				isHeaderLine = false;
			}
		}
		log.append("\tLoaded " + relationshipsLoaded + " relationships of type " + characteristicType + " which were " + (addRelationshipsToConcepts?"":"not ") + "added to concepts\n");
		if (isDelta) {
			TermServerScript.info (ignoredRelationships + " inactivating relationships were ignored as activating ones received in same delta");
		}
		return concepts;
	}
	
	private boolean isExcluded(String moduleId) {
		return excludedModules.contains(moduleId);
	}

	public void loadAxioms(InputStream axiomStream, boolean isDelta, Boolean isReleased) 
			throws IOException, TermServerScriptException {
		BufferedReader br = new BufferedReader(new InputStreamReader(axiomStream, StandardCharsets.UTF_8));
		String line;
		boolean isHeaderLine = true;
		int axiomsLoaded = 0;
		while ((line = br.readLine()) != null) {
			if (!isHeaderLine) {
				String[] lineItems = line.split(FIELD_DELIMITER);
				
				/*if (lineItems[REF_IDX_ID].equals("8016bcd2-83e7-47c1-a998-8f9c6d3a97b4")) {
					TermServerScript.debug("Debug Here");
				}*/
				
				//Only load OWL Expressions
				if (!lineItems[REF_IDX_REFSETID].equals(SCTID_OWL_AXIOM_REFSET)) {
					continue;
				}
				
				if (!isConcept(lineItems[REF_IDX_REFCOMPID])) {
					TermServerScript.debug("Axiom " + lineItems[REL_IDX_ID] + " referenced a non concept identifier: " + lineItems[REF_IDX_REFCOMPID]);
				}
				
				Long conceptId = Long.parseLong(lineItems[REF_IDX_REFCOMPID]);
				Concept c = getConcept(conceptId);

/*				if (c.getConceptId().equals("714771003")) {
					TermServerScript.debug("Debug Here");
				}*/
				
				try {
					boolean isAdditionalAxiom = false;
					//Also save data in RF2 form so we can build Snapshot
					AxiomEntry axiomEntry = AxiomEntry.fromRf2(lineItems);
					//Are we overwriting an existing axiom?
					if (c.getAxiomEntries().contains(axiomEntry)) {
						AxiomEntry replacedAxiomEntry = c.getAxiom(axiomEntry.getId());
						c.getAxiomEntries().remove(axiomEntry);
						//We'll inactivate all these relationships and allow them to be replaced
						AxiomRepresentation replacedAxiom = axiomService.convertAxiomToRelationships(replacedAxiomEntry.getOwlExpression());
						//Filter out any additional statements such as TransitiveObjectProperty(:123005000)]
						if (replacedAxiom != null) {
							List<Relationship> replacedRelationships = AxiomUtils.getRHSRelationships(c, replacedAxiom);
							alignAxiomRelationships(c, replacedRelationships, replacedAxiomEntry, false);
							for (Relationship r : replacedRelationships) {
								addRelationshipToConcept(CharacteristicType.STATED_RELATIONSHIP, r, isDelta);
							}
						}
					} else if (c.getAxiomEntries(ActiveState.ACTIVE, false).size() > 0) {
						isAdditionalAxiom = true;
					}
					c.getAxiomEntries().add(axiomEntry);
				
					AxiomRepresentation axiom = axiomService.convertAxiomToRelationships(lineItems[REF_IDX_AXIOM_STR]);
					//Filter out any additional statements such as TransitiveObjectProperty(:123005000)]
					if (axiom != null) {
						Long LHS = axiom.getLeftHandSideNamedConcept();
						if (LHS == null) {
							//Is this a CGI?
							Long RHS = axiom.getRightHandSideNamedConcept();
							if (!conceptId.equals(RHS)) {
								throw new IllegalArgumentException("GCI Axiom RHS != RefCompId: " + line);
							}
							c.getGciAxioms().add(AxiomUtils.toAxiom(c, axiomEntry, axiom));
							isAdditionalAxiom = false;
							axiomEntry.setGCI(true);
						} else if (!conceptId.equals(LHS)) {
							throw new IllegalArgumentException("Axiom LHS != RefCompId: " + line);
						}
						
						List<Relationship> relationships = AxiomUtils.getRHSRelationships(c, axiom);
						if (relationships.size() == 0) {
							log.append("Checkhere");
						}
						//Now we might need to adjust the active flag if the axiom is being inactivated
						//Or juggle the groupId, since individual axioms don't know about each other's existence
						alignAxiomRelationships(c, relationships, axiomEntry, axiomEntry.isActive());
						for (Relationship r : relationships) {
							addRelationshipToConcept(CharacteristicType.STATED_RELATIONSHIP, r, isDelta);
						}
						
						if (isAdditionalAxiom) {
							c.getAdditionalAxioms().add(AxiomUtils.toAxiom(c, axiomEntry, axiom));
						}
					} else {
						//Are we looking at a special axiom: Transitive, Reflexive or RoleChain?
						if (lineItems[IDX_ACTIVE].equals("1")) {
							c.mergeObjectPropertyAxiomRepresentation(axiomService.asObjectPropertyAxiom(lineItems[REF_IDX_AXIOM_STR]));
						}
					}
				} catch (ConversionException e) {
					throw new TermServerScriptException("Failed to load axiom: " + line, e);
				}
			} else {
				isHeaderLine = false;
			}
		}
		log.append("\tLoaded " + axiomsLoaded + " axioms");
	}
	
	private void alignAxiomRelationships(Concept c, List<Relationship> relationships, AxiomEntry axiomEntry, boolean active) {
		//Do the groups already exist in the concept?  Give them that groupId if so
		int nextFreeGroup = 1;
		for (RelationshipGroup g : SnomedUtils.toGroups(relationships)) {
			if (g.isGrouped()) {
				RelationshipGroup match = SnomedUtils.findMatchingGroup(c, g, CharacteristicType.STATED_RELATIONSHIP);
				if (match == null) {
					//Do we need a new relationship group id for this group?
					while (!SnomedUtils.isFreeGroup(CharacteristicType.STATED_RELATIONSHIP, c, nextFreeGroup)) {
						nextFreeGroup++;
					}
					g.setGroupId(nextFreeGroup);
					nextFreeGroup++;
				} else {
					g.setGroupId(match.getGroupId());
					if (match.getGroupId() >= nextFreeGroup) {
						nextFreeGroup++;
					}
				}
			}
			g.setActive(active);
			g.setAxiom(axiomEntry);
			g.setModule(axiomEntry.getModuleId());
			//Set the effectiveTime last as changing the other attributes will blank it
			g.setEffectiveTime(axiomEntry.getEffectiveTime());
		}
	}

	private boolean isConcept(String sctId) {
		return sctId.charAt(sctId.length()-2) == '0';
	}
	
	public Concept findConcept (String fsn) {
		//Populate the fsn map if required
		if (fsnMap == null) {
			fsnMap = new HashMap<>();
			for (Concept c : concepts.values()) {
				fsnMap.put(c.getFsn(), c);
			}
		}
		fsn = fsn.trim().replaceAll(ESCAPED_PIPE, "");
		return fsnMap.get(fsn);
	}
	
	private Relationship createRelationshipFromRF2(CharacteristicType charType, String[] lineItems) throws TermServerScriptException {
		String sourceId = lineItems[REL_IDX_SOURCEID];
		Concept source = getConcept(sourceId);
		String destId = lineItems[REL_IDX_DESTINATIONID];
		String typeId = lineItems[REL_IDX_TYPEID];
		
		if (sourceId.length() < 4 || destId.length() < 4 || typeId.length() < 4 ) {
			TermServerScript.debug("*** Invalid SCTID encountered in relationship " + lineItems[REL_IDX_ID] + ": s" + sourceId + " d" + destId + " t" + typeId);
		}
		Concept type = getConcept(lineItems[REL_IDX_TYPEID]);
		Concept destination = getConcept(lineItems[REL_IDX_DESTINATIONID]);
		int groupNum = Integer.parseInt(lineItems[REL_IDX_RELATIONSHIPGROUP]);
		
		Relationship r = new Relationship(source, type, destination, groupNum);
		r.setRelationshipId(lineItems[REL_IDX_ID].isEmpty()?null:lineItems[REL_IDX_ID]);
		r.setCharacteristicType(charType);
		r.setActive(lineItems[REL_IDX_ACTIVE].equals("1"));
		r.setModifier(SnomedUtils.translateModifier(lineItems[REL_IDX_MODIFIERID]));
		r.setModuleId(lineItems[REL_IDX_MODULEID]);
		
		//Set the effectiveTime last because changing the other fields from defaults causes it to null out
		r.setEffectiveTime(lineItems[REL_IDX_EFFECTIVETIME].isEmpty()?null:lineItems[REL_IDX_EFFECTIVETIME]);
		
		//Changing those values after the defaults were set in the constructor will incorrectly mark dirty
		r.setClean();
		return r;
	}

	/**
	 * @param isReleased 
	 * @return ignored count (ie 1 if relationship addition was ignored)
	 * @throws TermServerScriptException
	 */
	public int addRelationshipToConcept(CharacteristicType charType, String[] lineItems, boolean isDelta, Boolean isReleased) throws TermServerScriptException {
		Relationship r = createRelationshipFromRF2(charType, lineItems);
		if (r.isReleased() == null) {
			r.setReleased(isReleased);
		}
		
/*		if (r.getType().equals(IS_A) && r.getSourceId().equals("555621000005106")) {
			TermServerScript.debug("here");
		}*/
		
		boolean relationshipAdded = addRelationshipToConcept(charType, r, isDelta);
		
		//Consider adding or removing parents if the relationship is ISA
		//But only remove items if we're processing a delta
		//Don't modify our loaded hierarchy if we're loading a single concept from the TS
		if (relationshipAdded && r.getType().equals(IS_A) && r.getTarget() != null) {
			if (r.isActive()) {
				r.getSource().addParent(r.getCharacteristicType(),r.getTarget());
				r.getTarget().addChild(r.getCharacteristicType(),r.getSource());
			} else if (isDelta) {
				r.getSource().removeParent(r.getCharacteristicType(),r.getTarget());
				r.getTarget().removeChild(r.getCharacteristicType(),r.getSource());
			}
		} 
		
		return relationshipAdded ? 0 : 1; //If ignored, increase count
	}
	
	public boolean  addRelationshipToConcept(CharacteristicType charType, Relationship r, boolean isDelta) throws TermServerScriptException {
		//In the case of importing an Inferred Delta, we could end up adding a relationship instead of replacing
		//if it has a different SCTID.  We need to check for equality using triple, not SCTID in that case.
		return r.getSource().addRelationship(r, isDelta);
	}
	public Concept getConcept(String identifier) throws TermServerScriptException {
		return getConcept(identifier.trim(), true, true);
	}
	
	public Concept getConceptSafely (String identifier) {
		try {
			return getConcept(identifier, true, true);
		} catch (TermServerScriptException e) {
			return null;
		}
	}
	
	public Concept getConcept(Long sctId) throws TermServerScriptException {
		return getConcept(sctId.toString(), true, true);
	}
	
	public boolean conceptKnown(String sctId) {
		return concepts.containsKey(sctId);
	}
	
	public Concept getConcept(String identifier, boolean createIfRequired, boolean validateExists) throws TermServerScriptException {
		//Have we been passed a full identifier for the concept eg SCTID |FSN| ?
		if (StringUtils.isEmpty(identifier)) {
			if (validateExists) {
				throw new IllegalArgumentException("Empty SCTID encountered");
			}
			return null;
		}
		if (identifier.contains(PIPE)) {
			identifier = identifier.split(ESCAPED_PIPE)[0].trim();
		}
		String sctId = identifier.trim();
		
		//Make sure we're actually being asked for a concept
		if (sctId.length() < 6 || !isConcept(sctId)) {
			if (!createIfRequired && !validateExists && sctId.length() == 0) {
				return null;
			} else {
				throw new IllegalArgumentException("Request made for non concept sctid: '" + sctId + "'");
			}
		}
		Concept c = concepts.get(sctId);
		if (c == null) {
			if (createIfRequired) {
				c = new Concept(sctId);
				concepts.put(sctId, c);
			} else if (validateExists) {
				throw new TermServerScriptException("Expected Concept '" + sctId + "' has not been loaded from archive");
			}
		}
		return c;
	}
	
	public Description getDescription(String sctId) throws TermServerScriptException {
		return getDescription(sctId, true, true);
	}
	
	public Description getDescription(Long sctId) throws TermServerScriptException {
		return getDescription(sctId.toString(), true, true);
	}
	
	public Description getDescription(String sctId, boolean createIfRequired, boolean validateExists) throws TermServerScriptException {
		Description d = descriptions.get(sctId);
		if (d == null) {
			if (createIfRequired) {
				d = new Description(sctId);
				descriptions.put(sctId, d);
			} else if (validateExists) {
				throw new TermServerScriptException("Expected Description " + sctId + " has not been loaded from archive");
			}
		}
		return d;
	}
	
	public void loadConceptFile(InputStream is, Boolean isReleased) throws IOException, TermServerScriptException {
		//Not putting this in a try resource block otherwise it will close the stream on completion and we've got more to read!
		BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
		String line;
		boolean isHeaderLine = true;
		while ((line = br.readLine()) != null) {
			if (!isHeaderLine) {
				String[] lineItems = line.split(FIELD_DELIMITER);
				//Exclude LOINC
				if (isExcluded(lineItems[IDX_MODULEID])) {
					continue;
				}
				
			/*	if (lineItems[IDX_ID].equals("277636009")) {
					TermServerScript.debug("here");
				}*/
				//We might already have received some details about this concept
				Concept c = getConcept(lineItems[IDX_ID]);
				Concept.fillFromRf2(c, lineItems);
				
				//Only set the released flag if it's not set already
				if (c.isReleased() == null) {
					c.setReleased(isReleased);
				}
				if (c.getDefinitionStatus() == null) {
					throw new TermServerScriptException("Concept " + c + " did not define definition status");
				}
			} else {
				isHeaderLine = false;
			}
		}
	}
	
	public void loadDescriptionFile(InputStream descStream, boolean fsnOnly, Boolean isReleased) throws IOException, TermServerScriptException {
		//Not putting this in a try resource block otherwise it will close the stream on completion and we've got more to read!
		BufferedReader br = new BufferedReader(new InputStreamReader(descStream, StandardCharsets.UTF_8));
		String line;
		boolean isHeader = true;
		while ((line = br.readLine()) != null) {
			if (!isHeader) {
				String[] lineItems = line.split(FIELD_DELIMITER);
				//Exclude LOINC
				if (isExcluded(lineItems[IDX_MODULEID])) {
					continue;
				}
				
				/*if (lineItems[DES_IDX_ID].equals("3727472012")) {
					TermServerScript.debug("Debug Here");
				}*/
				
				Concept c = getConcept(lineItems[DES_IDX_CONCEPTID]);
				if (lineItems[DES_IDX_ACTIVE].equals(ACTIVE_FLAG) && lineItems[DES_IDX_TYPEID].equals(FULLY_SPECIFIED_NAME)) {
					c.setFsn(lineItems[DES_IDX_TERM]);
				}
				
				if (!fsnOnly) {
					//We might already have information about this description, eg langrefset entries
					Description d = getDescription (lineItems[DES_IDX_ID]);
					Description.fillFromRf2(d,lineItems);
					//Only set the released flag if it's not set already
					if (d.isReleased() == null) {
						d.setReleased(isReleased);
					}
					c.addDescription(d);
				}
			} else {
				isHeader = false;
			}
		}
	}

	public Set<Concept> loadRelationshipDelta(CharacteristicType characteristicType, InputStream relStream) throws IOException, TermServerScriptException {
		return loadRelationships(characteristicType, relStream, true, true, false);
	}

	public Set<Concept> getModifiedConcepts(
			CharacteristicType characteristicType, ZipInputStream relStream) throws IOException, TermServerScriptException {
		return loadRelationships(characteristicType, relStream, false, false, false);
	}

	public void loadLanguageFile(InputStream is, Boolean isReleased) throws IOException, TermServerScriptException {
		BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
		boolean isHeaderLine = true;
		String line;
		while ((line = br.readLine()) != null) {
			if (!isHeaderLine) {
				String[] lineItems = line.split(FIELD_DELIMITER);
				//Exclude LOINC
				if (isExcluded(lineItems[IDX_MODULEID])) {
					continue;
				}
				Description d = getDescription(lineItems[LANG_IDX_REFCOMPID]);
				LangRefsetEntry langRefsetEntry = LangRefsetEntry.fromRf2(lineItems);
				
				//Are we adding or replacing this entry?
				if (d.getLangRefsetEntries().contains(langRefsetEntry)) {
					d.getLangRefsetEntries().remove(langRefsetEntry);
				}
				
				//Complexity here that we've historically had language refset entries
				//for the same description which attempt to cancel each other out using
				//different UUIDs.  Therefore if we get a later entry inactivating a given
				//dialect, then allow that to overwrite an earlier value with a different UUUID
				
				//Do we have an existing entry for this description & dialect that is later and inactive?
				boolean clearToAdd = true;
				String issue = "";
				List<LangRefsetEntry> allExisting = d.getLangRefsetEntries(ActiveState.BOTH, langRefsetEntry.getRefsetId());
				for (LangRefsetEntry existing : allExisting) {
					//If we have two active for the same description, and neither has an effectiveTime delete the one that hasn't been published
					//Only if we're loading a delta, otherwise it's published
					if (!isReleased) {
						checkForActiveDuplication(d, existing, langRefsetEntry);
					}
					
					if (existing.getEffectiveTime().compareTo(langRefsetEntry.getEffectiveTime()) > 1) {
						clearToAdd = false;
						issue = "Existing " + (existing.isActive()? "active":"inactive") +  " langrefset entry taking priority over incoming " + (langRefsetEntry.isActive()? "active":"inactive") + " as later : " + existing;
					} else if (existing.getEffectiveTime().equals(langRefsetEntry.getEffectiveTime())) {
						//As long as they have different UUIDs, it's OK to have the same effective time
						//But we'll ignore the inactivation
						if (!langRefsetEntry.isActive()) {
							clearToAdd = false;
							issue = "Ignoring inactive langrefset entry with same effective time as active : " + existing;
						}
					} else {
						//New entry is later or same effective time as one we already know about
						d.getLangRefsetEntries().remove(existing);
						issue = "Existing " + (existing.isActive()? "active":"inactive") + " langrefset entry being overwritten by subsequent " + (langRefsetEntry.isActive()? "active":"inactive") + " value " + existing;
					}
				}
				
				if (!issue.isEmpty()) {
					TermServerScript.debug("**Warning: " + issue);
				}
				
				if (clearToAdd) {
					d.getLangRefsetEntries().add(langRefsetEntry);
					if (lineItems[LANG_IDX_ACTIVE].equals("1")) {
						Acceptability a = SnomedUtils.translateAcceptability(lineItems[LANG_IDX_ACCEPTABILITY_ID]);
						d.setAcceptablity(lineItems[LANG_IDX_REFSETID], a);
					} else {
						d.removeAcceptability(lineItems[LANG_IDX_REFSETID]);
					}
				}
			} else {
				isHeaderLine = false;
			}
		}
	}

	
	private void checkForActiveDuplication(Description d, LangRefsetEntry l1, LangRefsetEntry l2) throws TermServerScriptException {
		if (l1.isActive() && l2.isActive()) {
			Set<DuplicatePair> duplicates = getLangRefsetDuplicates(d);
			duplicates.add(new DuplicatePair(l1, l2));  //Keep the first, inactivate (or delete) the second
			System.out.println("Noting langrefset as duplicate with " + l1.getId() + " : " + l2);
		}
	}
	
	private Set<DuplicatePair> getLangRefsetDuplicates(Description d) throws TermServerScriptException {
		Concept c = getConcept(d.getConceptId());
		if (duplicateLangRefsetEntriesMap == null) {
			duplicateLangRefsetEntriesMap = new HashMap<>();
		}
		Set<DuplicatePair> duplicates = duplicateLangRefsetEntriesMap.get(c);
		if (duplicates == null) {
			duplicates = new HashSet<>();
			duplicateLangRefsetEntriesMap.put(c,  duplicates);
		}
		return duplicates;
	}

	/**
	 * Recurse hierarchy and set shortest path depth for all concepts
	 * @throws TermServerScriptException 
	 */
	public void populateHierarchyDepth(Concept startingPoint, int currentDepth) throws TermServerScriptException {
		startingPoint.setDepth(currentDepth);
		/*if (startingPoint.getConceptId().equals("210431006")) {
			//TermServerScript.debug ("Checkpoint");
		}*/
		
		for (Concept child : startingPoint.getChildren(CharacteristicType.INFERRED_RELATIONSHIP)) {
			if (currentDepth >= MAX_DEPTH) {
				throw new TermServerScriptException("Maximum depth exceeded from " + startingPoint + " and inferred child " + child);
			}
			try {
				populateHierarchyDepth(child, currentDepth + 1);
			} catch (TermServerScriptException e) {
				TermServerScript.debug ("Exception path: " + startingPoint + " -> " + child);
				throw (e);
			}
		}
	}

	public void loadInactivationIndicatorFile(InputStream is) throws IOException, TermServerScriptException {
		BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
		boolean isHeaderLine = true;
		String line;
		while ((line = br.readLine()) != null) {
			if (!isHeaderLine) {
				String[] lineItems = line.split(FIELD_DELIMITER);
				//Exclude LOINC
				if (isExcluded(lineItems[IDX_MODULEID])) {
					continue;
				}
				InactivationIndicatorEntry inactivation = InactivationIndicatorEntry.fromRf2(lineItems);
				if (inactivation.getRefsetId().equals(SCTID_CON_INACT_IND_REFSET)) {
					Concept c = getConcept(lineItems[INACT_IDX_REFCOMPID]);
					/*if (c.getConceptId().equals("198308002")) {
						TermServerScript.debug("Check Here");
					}*/
					c.addInactivationIndicator(inactivation);
				} else if (inactivation.getRefsetId().equals(SCTID_DESC_INACT_IND_REFSET)) {
					Description d = getDescription(lineItems[INACT_IDX_REFCOMPID]);
					/*if (d.getDescriptionId().equals("1221136011")) {
						TermServerScript.debug("Check here");
					}*/
					d.addInactivationIndicator(inactivation);
				}
			} else {
				isHeaderLine = false;
			}
		}
	}
	
	public void loadHistoricalAssociationFile(InputStream is) throws IOException, TermServerScriptException {
		BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
		boolean isHeaderLine = true;
		String line;
		while ((line = br.readLine()) != null) {
			if (!isHeaderLine) {
				String[] lineItems = line.split(FIELD_DELIMITER);
				//Exclude LOINC
				if (isExcluded(lineItems[IDX_MODULEID])) {
					continue;
				}
				String referencedComponent = lineItems[INACT_IDX_REFCOMPID];
				if (isConcept(referencedComponent)) {
					Concept c = getConcept(referencedComponent);
					AssociationEntry historicalAssociation = AssociationEntry.fromRf2(lineItems);
					//Remove first in case we're replacing
					c.getAssociations().remove(historicalAssociation);
					c.getAssociations().add(historicalAssociation);
					if (historicalAssociation.isActive()) {
						addHistoricalAssociationInTsForm(c, historicalAssociation);
						recordHistoricalAssociation(historicalAssociation);
					}
				}
			} else {
				isHeaderLine = false;
			}
		}
	}
	
	/*
	 * Adds a historical association using the string based map format as per the Terminology Server's API
	 */
	private void addHistoricalAssociationInTsForm(Concept c, AssociationEntry historicalAssociation) {
		AssociationTargets targets = c.getAssociationTargets();
		if (targets == null) {
			targets = new AssociationTargets();
			c.setAssociationTargets(targets);
		}
		String target = historicalAssociation.getTargetComponentId();
		switch (historicalAssociation.getRefsetId()) {
			case (SCTID_ASSOC_REPLACED_BY_REFSETID) : targets.getReplacedBy().add(target);
													break;
			case (SCTID_ASSOC_SAME_AS_REFSETID) : targets.getSameAs().add(target);
													break;	
			case (SCTID_ASSOC_POSS_EQUIV_REFSETID) : targets.getPossEquivTo().add(target);
													break;	
		}
		
	}

	private void recordHistoricalAssociation(AssociationEntry h) throws TermServerScriptException {
		//Remember we're using the target of the association as the map key here
		Concept target = getConcept(h.getTargetComponentId());
		//Have we seen this concept before?
		List<AssociationEntry> associations;
		if (historicalAssociations.containsKey(target)) {
			associations = historicalAssociations.get(target);
		} else {
			associations = new ArrayList<AssociationEntry>();
			historicalAssociations.put(target, associations);
		}
		associations.add(h);
	}
	
	public List<AssociationEntry> usedAsHistoricalAssociationTarget (Concept c) {
		if (historicalAssociations.containsKey(c)) {
			return historicalAssociations.get(c);
		}
		return new ArrayList<AssociationEntry>();
	}

	public Component getComponent(String id) {
		if (allComponents == null) {
			populateAllComponents();
		}
		return allComponents.get(id);
	}
	
	public Map<String,Component> getComponentMap() {
		if (allComponents == null) {
			populateAllComponents();
		}
		return new HashMap<>(allComponents);
	}
	
	public Concept getComponentOwner(String id) {
		Component component = getComponent(id);
		return componentOwnerMap.get(component);
	}

	private void populateAllComponents() {
		System.out.println("Populating map of all components...");
		allComponents = new HashMap<String, Component>();
		componentOwnerMap = new HashMap<Component, Concept>();
		
		for (Concept c : getAllConcepts()) {
			
			/*if (c.getId().equals("773986009") || c.getId().equals("762566005")) {
				TermServerScript.debug("here");
			}*/
			allComponents.put(c.getId(), c);
			componentOwnerMap.put(c,  c);
			for (Description d : c.getDescriptions()) {
				populateDescriptionComponents(c, d);
			}
			
			for (Relationship r : c.getRelationships()) {
				//A relationship with a null ID will have come from an axiom.
				//We'll let the axiomEntry cover that.
				if (r.fromAxiom()) {
					continue;
				}
				
				if (r.getRelationshipId() == null) {
					throw new IllegalArgumentException ("Rel ID not expected to be null");
				}
				//Have we historically swapped ID from stated to inferred
				if (allComponents.containsKey(r.getRelationshipId())) {
					if (r.isActive()) {
						System.out.println("CMap replacing '" + r.getRelationshipId() + "' " + allComponents.get(r.getRelationshipId()) + " with active " + r);
						allComponents.put(r.getRelationshipId(), r);
					} else if (allComponents.get(r.getRelationshipId()).isActive()) {
						System.out.println("Ignoring inactive '" + r.getRelationshipId() + "' " + r + " due to already having " + allComponents.get(r.getRelationshipId()));
					} else {
						System.out.println("Two inactive components share the same id " + r);
					}
				} else {
					allComponents.put(r.getRelationshipId(), r);
				}
			}

			for (InactivationIndicatorEntry i : c.getInactivationIndicatorEntries()) {
				allComponents.put(i.getId(), i);
				componentOwnerMap.put(i,  c);
			}
			
			for (AssociationEntry h : c.getAssociations()) {
				allComponents.put(h.getId(), h);
				componentOwnerMap.put(h,  c);
			}
			
			for (AxiomEntry a : c.getAxiomEntries()) {
				allComponents.put(a.getId(), a);
				componentOwnerMap.put(a, c);
			}
		}
		System.out.print("complete.");
	}

	private void populateDescriptionComponents(Concept c, Description d) {
		allComponents.put(d.getDescriptionId(), d);
		componentOwnerMap.put(d, c);
		
		for (InactivationIndicatorEntry i : d.getInactivationIndicatorEntries()) {
			allComponents.put(i.getId(), i);
			componentOwnerMap.put(i, c);
		}
		
		for (LangRefsetEntry l : d.getLangRefsetEntries()) {
			allComponents.put(l.getId(), l);
			componentOwnerMap.put(l, c);
		}
	}

	public String listAssociationParticipation(Concept c) {
		return historicalAssociations.get(c).stream()
				.map(assoc -> assoc.toVerboseString())
				.collect (Collectors.joining(", "));
	}

	public void registerConcept(Concept concept) {
		concepts.put(concept.getConceptId(), concept);
	}

	public DescendantsCache getDescendantsCache() {
		return descendantsCache;
	}

	public AncestorsCache getAncestorsCache() {
		return ancestorsCache;
	}
	
	public AncestorsCache getStatedAncestorsCache() {
		return statedAncestorsCache;
	}

	public Concept getComponentOwner(ComponentType componentType, Component c) throws TermServerScriptException {
		if (c==null) {
			return null;
		}
		//Work out the owning component given the known type of this component
		switch (componentType) {
			case CONCEPT : //Concepts own themselves
							return (Concept)c;
			case DESCRIPTION :
			case TEXT_DEFINITION : return getConcept(((Description)c).getConceptId());
			case INFERRED_RELATIONSHIP:
			case STATED_RELATIONSHIP : return getConcept(((Relationship)c).getSourceId());
			case HISTORICAL_ASSOCIATION : String referencedComponentId = ((AssociationEntry)c).getReferencedComponentId();
											ComponentType referencedType = SnomedUtils.getComponentType(referencedComponentId);
											return getComponentOwner(referencedType, getComponent(referencedComponentId));
			case ATTRIBUTE_VALUE : referencedComponentId = ((InactivationIndicatorEntry)c).getReferencedComponentId();
											referencedType = SnomedUtils.getComponentType(referencedComponentId);
											return getComponentOwner(referencedType, getComponent(referencedComponentId));
			case LANGREFSET : referencedComponentId = ((LangRefsetEntry)c).getReferencedComponentId();
											referencedType = SnomedUtils.getComponentType(referencedComponentId);
											return getComponentOwner(referencedType, getComponent(referencedComponentId));
			default: throw new TermServerScriptException("Unknown component Type: " + componentType);
		}
	}
	
	public Component createComponent(ComponentType componentType, String[] lineItems) throws TermServerScriptException {
		//Work out the owning component given the known type of this component
		switch (componentType) {
			case CONCEPT :	Concept c = getConcept(lineItems[IDX_ID]);
							Concept.fillFromRf2(c, lineItems);
							return c;
			case DESCRIPTION :
			case TEXT_DEFINITION :	Description d = getDescription (lineItems[DES_IDX_ID]);
									Description.fillFromRf2(d,lineItems);
									return d;
			case INFERRED_RELATIONSHIP: return createRelationshipFromRF2(CharacteristicType.INFERRED_RELATIONSHIP, lineItems);
			case STATED_RELATIONSHIP : return createRelationshipFromRF2(CharacteristicType.STATED_RELATIONSHIP, lineItems);
			case HISTORICAL_ASSOCIATION : return AssociationEntry.fromRf2(lineItems);
			case ATTRIBUTE_VALUE : return  InactivationIndicatorEntry.fromRf2(lineItems);
			case LANGREFSET : return LangRefsetEntry.fromRf2(lineItems);
		default: throw new TermServerScriptException("Unknown component Type: " + componentType);
		}
	}
	
	public Collection<Concept> getOrphanetConcepts() {
		if (orphanetConcepts == null) {
			TermServerScript.print("Loading list of Orphanet Concepts...");
			try {
				InputStream is = GraphLoader.class.getResourceAsStream("/data/orphanet_concepts.txt");
				if (is == null) {
					throw new RuntimeException ("Failed to load Orphanet data file - not found.");
				}
				orphanetConcepts = IOUtils.readLines(is, "UTF-8").stream()
						.map( s -> getConceptSafely(s))
						.collect(Collectors.toSet());
			} catch (Exception e) {
				throw new RuntimeException ("Failed to load list of Orphanet Concepts",e);
			}
			TermServerScript.println("complete.");
		}
		return Collections.unmodifiableCollection(orphanetConcepts);
	}

	public boolean isOrphanetConcept (Concept c) {
		return getOrphanetConcepts().contains(c);
	}

	public void makeReady() {
		for (Concept c : concepts.values()) {
			c.setIssue(null);
			for (RelationshipGroup g : c.getRelationshipGroups(CharacteristicType.INFERRED_RELATIONSHIP)) {
				g.setIssues(null);
				g.resetIndicators();
			}
		}
	}
	
	public AxiomRelationshipConversionService getAxiomService() {
		return axiomService;
	}

	public void populatePreviousTransativeClosure() throws TermServerScriptException {
		previousTransativeClosure = generateTransativeClosure();
	}
	
	public TransitiveClosure generateTransativeClosure() throws TermServerScriptException {
		TermServerScript.debug ("Calculating transative closure...");
		TransitiveClosure tc = new TransitiveClosure();
		//For all active concepts, populate their ancestors into the TC
		getAllConcepts().parallelStream().forEach(c->{
			try {
				tc.addConcept(c);
			} catch (TermServerScriptException e) {
				e.printStackTrace();
			} 
		});
		TermServerScript.debug ("Completed transative closure: " + tc.size() + " relationships mapped");
		return tc;
	}

	public TransitiveClosure getPreviousTC() {
		return previousTransativeClosure;
	}

	public void setExcludedModules(HashSet<String> excludedModules) {
		this.excludedModules = excludedModules;
	}
	
	public Map<Concept, Set<DuplicatePair>> getDuplicateLangRefsetEntriesMap() {
		return duplicateLangRefsetEntriesMap;
	}
	
	public class DuplicatePair {
		private Component keep;
		private Component inactivate;
		
		DuplicatePair (Component keep, Component inactivate) {
			this.keep = keep;
			this.inactivate = inactivate;
		}
		
		public Component getKeep() {
			return keep;
		}
		public Component getInactivate() {
			return inactivate;
		}

	}
}

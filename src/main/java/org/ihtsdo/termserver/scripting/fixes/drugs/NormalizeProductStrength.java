package org.ihtsdo.termserver.scripting.fixes.drugs;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.lang.ArrayUtils;
import org.ihtsdo.termserver.scripting.TermServerScript;
import org.ihtsdo.termserver.scripting.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ValidationFailure;
import org.ihtsdo.termserver.scripting.client.SnowOwlClientException;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.fixes.BatchFix;
import org.ihtsdo.termserver.scripting.util.DrugTermGenerator;
import org.ihtsdo.termserver.scripting.util.DrugUtils;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;

import us.monoid.json.JSONObject;

/*
DRUGS-489
Existing remodeled concepts need to be reviewed and updated as needed to comply with normalized strength expressions of metric units:

Use picogram if value is <1000; if if > then convert to nanogram
Use nanogram if value is <1000; if > then convert to microgram
Use microgram if value is <1000; if > then convert to milligram
Use milligram if value is <1000; if > then convert to gram

Or if value is < 1 switch to the next smaller unit and multiple the value by 1000.
*/
public class NormalizeProductStrength extends DrugBatchFix implements RF2Constants {
	
	Concept [] units = new Concept [] { PICOGRAM, NANOGRAM, MICROGRAM, MILLIGRAM, GRAM };
	DrugTermGenerator termGenerator = new DrugTermGenerator(this);
	
	protected NormalizeProductStrength(BatchFix clone) {
		super(clone);
	}

	public static void main(String[] args) throws TermServerScriptException, IOException, SnowOwlClientException, InterruptedException {
		NormalizeProductStrength fix = new NormalizeProductStrength(null);
		try {
			fix.additionalReportColumns = "Num/Den, Current Unit, New Strength, New Unit, Role Group";
			fix.selfDetermining = true;
			fix.runStandAlone = true;
			fix.init(args);
			fix.loadProjectSnapshot(false); //Load all descriptions
			//We won't include the project export in our timings
			fix.startTimer();
			fix.processFile();
		} finally {
			fix.finish();
		}
	}

	@Override
	public int doFix(Task task, Concept concept, String info) throws TermServerScriptException, ValidationFailure {
		Concept loadedConcept = loadConcept(concept, task.getBranchPath());
		try {
			int changesMade = normalizeProductStrength(task, loadedConcept);
			if (changesMade > 0) {
				changesMade += termGenerator.ensureDrugTermsConform(task, loadedConcept, CharacteristicType.STATED_RELATIONSHIP);
				String conceptSerialised = gson.toJson(loadedConcept);
				debug ((dryRun?"Dry run updating":"Updating") + " state of " + loadedConcept + info);
				if (!dryRun) {
					tsClient.updateConcept(new JSONObject(conceptSerialised), task.getBranchPath());
				}
			}
			return changesMade;
		} catch (Exception e) {
			report(task, concept, Severity.CRITICAL, ReportActionType.API_ERROR, "Failed to remodel " + concept + " due to " + e.getClass().getSimpleName()  + " - " + e.getMessage());
			e.printStackTrace();
		}
		return 0;
	}
	
	private int normalizeProductStrength(Task t, Concept c) throws TermServerScriptException, ValidationFailure {
		//Running the two types separately because pattern 2a uses both
		int changesMade = normalizeProductStrength(t, c, new Concept[] { HAS_PRES_STRENGTH_VALUE},
				new Concept[] { HAS_PRES_STRENGTH_UNIT} );
		changesMade += normalizeProductStrength(t, c, new Concept[] {  HAS_CONC_STRENGTH_VALUE},
				new Concept[] { HAS_CONC_STRENGTH_UNIT} );
		changesMade += normalizeProductStrength(t, c, new Concept[] { HAS_PRES_STRENGTH_DENOM_VALUE},
				new Concept[] { HAS_PRES_STRENGTH_DENOM_UNIT} );
		changesMade += normalizeProductStrength(t, c, new Concept[] { HAS_CONC_STRENGTH_DENOM_VALUE},
				new Concept[] { HAS_CONC_STRENGTH_DENOM_UNIT} );
		return changesMade;
	}

	private int normalizeProductStrength(Task t, Concept c, Concept[] strengthTypes, Concept[] unitTypes) throws TermServerScriptException, ValidationFailure {
		int changesMade = 0;
		//For any numerator, check if the unit is > 1000 and consider switching to the next unit
		for (RelationshipGroup g : c.getRelationshipGroups(CharacteristicType.STATED_RELATIONSHIP)) {
			Relationship strengthRel = getTargetRel(c, strengthTypes, g.getGroupId(), CharacteristicType.STATED_RELATIONSHIP);
			if (strengthRel != null) {
				double strengthNumber = DrugUtils.getConceptAsNumber(strengthRel.getTarget());
				double newStrengthNumber = NOT_SET;
				Concept newUnit = null;
				Relationship unitRel = getTargetRel(c, unitTypes, g.getGroupId(), CharacteristicType.STATED_RELATIONSHIP);
				int currentIdx =  ArrayUtils.indexOf(units, unitRel.getTarget());
				if (currentIdx != NOT_SET) {
					if (strengthNumber >= 1000) {
						newUnit = units[currentIdx + 1];
						newStrengthNumber = strengthNumber / 1000D;
					} else if (strengthRel != null && strengthNumber <1) {
						newUnit = units[currentIdx - 1];
						newStrengthNumber = strengthNumber * 1000D;
					}
					if (newUnit != null) {
						String newStrengthStr = toString(newStrengthNumber);
						if (!quiet) {
							remodelConcept (t, c, strengthRel, newStrengthStr, unitRel, newUnit);
							report (t, c, Severity.LOW, ReportActionType.VALIDATION_CHECK, strengthNumber + " " + unitRel.getTarget() + " --> " + newStrengthStr + " " + newUnit, g);
						}
						changesMade++;
					}
				}
			}
		}
		return changesMade;
	}
	
	private void remodelConcept(Task t, Concept c, Relationship strengthRel, String newStrengthStr,
			Relationship unitRel, Concept newUnit) throws TermServerScriptException {
		Concept newStrength = DrugUtils.getNumberAsConcept(newStrengthStr);
		if (newStrength == null) {
			newStrength = createNumber(t, newStrengthStr);
			DrugUtils.registerNewNumber(newStrengthStr, newStrength);
			t.addAfter(newStrength, c);
		}
		replaceRelationship(t, c, strengthRel.getType(), newStrength, strengthRel.getGroupId(), false);
		replaceRelationship(t, c, unitRel.getType(), newUnit, unitRel.getGroupId(), false);
	}

	private Concept createNumber(Task t, String numberStr) throws TermServerScriptException {
		Concept number = SnomedUtils.createConcept(numberStr, "(qualifier value)", NUMBER);
		if (!dryRun) {
			number = createConcept(t, number, "");
		} else {
			number.setConceptId("9999" + numberStr.replace(".", "") + "00");
			number.setFsn(numberStr + " (qualifier value)");
		}
		gl.registerConcept(number);
		report (t, number, Severity.HIGH, ReportActionType.CONCEPT_ADDED, number);
		return number;
	}

	//Where we have multiple potential responses eg concentration or presentation strength, return the first one found given the 
	//order specified by the array
	private Relationship getTargetRel(Concept c, Concept[] types, int groupId, CharacteristicType charType) throws TermServerScriptException {
		for (Concept type : types) {
			List<Relationship> rels = c.getRelationships(charType, type, groupId);
			if (rels.size() > 1) {
				TermServerScript.warn(c + " has multiple " + type + " in group " + groupId);
			} else if (rels.size() == 1) {
				//This might not be the full concept, so recover it fully from our loaded cache
				return rels.get(0);
			}
		}
		return null;
	}
	
	protected List<Component> identifyComponentsToProcess() throws TermServerScriptException {
		List<Concept> processMe = new ArrayList<>();  //We want to process in the same order each time, in case we restart and skip some.
		setQuiet(true);
		for (Concept c : PHARM_BIO_PRODUCT.getDescendents(NOT_SET)) {
			SnomedUtils.populateConceptType(c);
			if (c.getConceptType().equals(ConceptType.CLINICAL_DRUG)) {
				if (normalizeProductStrength(null, c) > 0) {
					processMe.add(c);
				}
			}
		}
		processMe.sort(Comparator.comparing(Concept::getFsn));
		setQuiet(false);
		info (processMe.size() + " concepts to process");
		return asComponents(processMe);
	}
	
	public static String toString(double d)
	{
		d = new BigDecimal(d).setScale(6, RoundingMode.HALF_UP).doubleValue();
		if(d == (long) d)
			return String.format("%d",(long)d);
		else
			return String.format("%s",d);
	}

	@Override
	protected List<Concept> loadLine(String[] lineItems) throws TermServerScriptException {
		// TODO Auto-generated method stub
		return null;
	}
}

package org.ihtsdo.termserver.scripting.fixes;

import java.io.IOException;
import java.util.*;

import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Task;
import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ValidationFailure;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.util.AcceptabilityMode;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;


/*
 * DEVICES-102
 */
public class RemodelConceptsPerSpreadsheet extends BatchFix implements RF2Constants {
	
	private static final int OFFSET = 3;
	private static final int IDX_SCTID = 0;
	private static final int IDX_FSN = 1;
	private static final int IDX_NEW_FSN = 2 + OFFSET;
	private static final int IDX_NEW_PT = 3 + OFFSET;
	private static final int IDX_INACT_DESC = 4 + OFFSET;
	private static final int IDX_NEW_DESC = 5 + OFFSET;
	//private static final int IDX_CURR_DEF_STAT = 6 + OFFSET;
	private static final int IDX_NEW_DEF_STAT = 7 + OFFSET;
	private static final int IDX_PARENT = 8 + OFFSET;
	private static final int IDX_HDIS = 9 + OFFSET;
	private static final int IDX_HCM = 10 + OFFSET;
	//private static final int IDX_NULL_1 = 11 + OFFSET;
	private static final int IDX_HDC_1 = 12 + OFFSET;;
	//private static final int IDX_HDC_2 = 13 + OFFSET;
	//private static final int IDX_HDC_3 = 14 + OFFSET;
	//private static final int IDX_HDC_4 = 15 + OFFSET;
	private static final int IDX_NULL_2 = 16 + OFFSET;
	private static final int IDX_STATUS = 17 + OFFSET;
	
	Map<Concept, String[]> remodelMap = new HashMap<>();
	Concept HDIS;
	Concept HCM;
	
	protected RemodelConceptsPerSpreadsheet(BatchFix clone) {
		super(clone);
	}

	public static void main(String[] args) throws TermServerScriptException, IOException, InterruptedException {
		RemodelConceptsPerSpreadsheet fix = new RemodelConceptsPerSpreadsheet(null);
		try {
			ReportSheetManager.targetFolderId = "1fIHGIgbsdSfh5euzO3YKOSeHw4QHCM-m";  //Ad-hoc batch updates
			fix.maxFailures = Integer.MAX_VALUE;
			fix.expectNullConcepts = true;
			fix.getArchiveManager().populateReleasedFlag = true;
			fix.init(args);
			fix.loadProjectSnapshot(false); //Load all descriptions
			fix.postInit();
			fix.processFile();
		} finally {
			fix.finish();
		}
	}
	
	public void postInit() throws TermServerScriptException {
		HDIS = gl.getConcept("836358009 |Has device intended site (attribute)|", false, true);
		HCM = gl.getConcept("840560000 |Has compositional material (attribute)|", false, true);
		super.postInit();
	}


	@Override
	public int doFix(Task t, Concept c, String info) throws TermServerScriptException {
		Concept loadedConcept = loadConcept(c, t.getBranchPath());
		int changesMade = 0;
		if (loadedConcept == null || !loadedConcept.isActive()) {
			report (t, c, Severity.HIGH, ReportActionType.VALIDATION_CHECK, "Concept inactive or not available");
		} else {
			changesMade = remodelConcept(t, loadedConcept);
			if (changesMade > 0) {
				updateConcept(t, loadedConcept, info);
			}
		} 
		return changesMade;
	}
	
	private int remodelConcept(Task t, Concept c) throws TermServerScriptException {
		//Do we have remodelling data for this concept?
		String[] data = remodelMap.get(c);
		if (data == null) {
			throw new ValidationFailure(t, c, "No remodel data available");
		}
		
		//Do we have the right concept?
		if (!c.getFsn().equals(data[IDX_FSN])) {
			throw new ValidationFailure(t, c, "Unexpected FSN in remodel data: " + data[IDX_FSN]);
		}
		
		int changesMade = remodelDescriptions(t, c, data);
		changesMade += remodelAttributes(t, c, data);
		changesMade += remodelParents(t, c, data);
		changesMade += remodelDefStatus(t, c, data);
		return changesMade;
	}

	private int remodelDescriptions(Task t, Concept c, String[] data) throws TermServerScriptException {
		int changesMade = 0;
		if (isPopulated(data, IDX_NEW_FSN)) {
			replaceDescription(t, c, c.getFSNDescription(), data[IDX_NEW_FSN], InactivationIndicator.NONCONFORMANCE_TO_EDITORIAL_POLICY);
			changesMade++;
		}
		
		if (isPopulated(data, IDX_NEW_PT)) {
			replaceDescription(t, c, c.getPreferredSynonym(US_ENG_LANG_REFSET), data[IDX_NEW_PT], InactivationIndicator.NONCONFORMANCE_TO_EDITORIAL_POLICY);
			changesMade++;
		}
		
		if (isPopulated(data, IDX_INACT_DESC)) {
			String[] termsToInactivate = data[IDX_INACT_DESC].split(";");
			for (String termToInactivate : termsToInactivate) {
				Description d = c.getDescription(termToInactivate.trim(), ActiveState.ACTIVE);
				if (d != null) {
					removeDescription(t, c, d, InactivationIndicator.NONCONFORMANCE_TO_EDITORIAL_POLICY);
					changesMade++;
				} else {
					report (t, c, Severity.HIGH, ReportActionType.VALIDATION_CHECK, "Failed to find description to inactivate", data[IDX_INACT_DESC]);
				}
			}
		}
		
		if (isPopulated(data, IDX_NEW_DESC)) {
			Description d = Description.withDefaults(data[IDX_NEW_DESC], DescriptionType.SYNONYM, Acceptability.ACCEPTABLE);
			addDescription(t, c, d);
			changesMade++;
		}
		
		//If we've added a new US PT and accidentally removed the GB PT, then modify the US acceptability 
		//and warn the author
		if (c.getPreferredSynonym(GB_ENG_LANG_REFSET) == null) {
			Description usPT = c.getPreferredSynonym(US_ENG_LANG_REFSET);
			usPT.setAcceptabilityMap(SnomedUtils.createAcceptabilityMap(AcceptabilityMode.PREFERRED_BOTH));
			report (t, c, Severity.HIGH, ReportActionType.VALIDATION_CHECK, "GB PT has been removed.  US PT being reused for now, please check", usPT);
			changesMade++;
		}
		
		return changesMade;
	}
	

	private int remodelAttributes(Task t, Concept c, String[] data) throws TermServerScriptException {
		int changesMade = 0;
		Concept value = getValue(t, c, data, IDX_HDIS);
		if (value != null) {
			Relationship r = new Relationship(c, HDIS, value, UNGROUPED);
			changesMade += addRelationship(t, c, r);
		}
		
		value = getValue(t, c, data, IDX_HCM);
		if (value != null) {
			Relationship r = new Relationship(c, HCM, value, UNGROUPED);
			changesMade += addRelationship(t, c, r);
		}
		
		for (int idx=IDX_HDC_1; idx < IDX_NULL_2; idx++) {
			value = getValue(t, c, data, idx);
			if (value != null) {
				Relationship r = new Relationship(c, HAS_DEVICE_CHARAC, value, UNGROUPED);
				changesMade += addRelationship(t, c, r);
			}
		}
		return changesMade;
	}

	private int remodelParents(Task t, Concept c, String[] data) throws TermServerScriptException {
		int changesMade = 0;
		if (isPopulated(data, IDX_PARENT)) {
			Concept newParent = gl.getConcept(data[IDX_PARENT], false, true);
			changesMade += replaceParents(t, c, newParent);
		}
		return changesMade;
	}

	private int remodelDefStatus(Task t, Concept c, String[] data) throws TermServerScriptException {
		int changesMade = 0;
		if (isPopulated(data, IDX_NEW_DEF_STAT)) {
			DefinitionStatus defStat = SnomedUtils.translateDefnStatusStr(data[IDX_NEW_DEF_STAT].trim());
			if (!c.getDefinitionStatus().equals(defStat)) {
				report (t, c, Severity.LOW, ReportActionType.CONCEPT_CHANGE_MADE, "DefStatus now: " + defStat);
				c.setDefinitionStatus(defStat);
				changesMade++;
			}
		}
		return changesMade;
	}

	private Concept getValue(Task t, Concept c, String[] data, int idx) throws TermServerScriptException {
		if (isPopulated(data, idx)) {
			String term = data[idx].trim().replace("-", "").replace("yes", "");
			Concept value = gl.getConcept(term, false, false);  //Don't create, don't validates
			if (value == null) {
				report (t, c, Severity.HIGH, ReportActionType.VALIDATION_CHECK, "Unable to assign attribute value", data[idx]);
			}
			return value;
		}
		return null;
	}

	private boolean isPopulated(String[] data, int idx) {
		return (data[idx] != null && data[idx].trim().length() > 0);
	}

	@Override
	protected List<Component> loadLine(String[] lineItems) throws TermServerScriptException {
		Concept c = gl.getConcept(lineItems[IDX_SCTID], false, true);
		if (!lineItems[IDX_STATUS].contentEquals("Upload")) {
			report ((Task)null, c, Severity.LOW, ReportActionType.NO_CHANGE, "Concept not marked for upload", lineItems[IDX_STATUS]);
			return null;
		}
		remodelMap.put(c,  lineItems);
		return Collections.singletonList(c);
	}
}

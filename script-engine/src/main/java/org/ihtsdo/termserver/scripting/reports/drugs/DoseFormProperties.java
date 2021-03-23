package org.ihtsdo.termserver.scripting.reports.drugs;

import java.io.IOException;
import java.io.PrintStream;
import java.util.*;

import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.reports.TermServerReport;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;

/**
 * ISP-20 List properties of Pharmaceutical Dose forms
 * SCTid, FSN, PT, Parents (id, PT), 
 * Defined/Primitive?, 
 * Has basic dose form (id, PT), 
 * Has dose form administration method (id, PT), 
 * Has dose form intended site (id, PT), 
 * Has dose form release characteristic (id, PT), 
 * Has dose form transformation (id, PT)
 */
public class DoseFormProperties extends TermServerReport {
	
	public static void main(String[] args) throws TermServerScriptException, IOException {
		DoseFormProperties report = new DoseFormProperties();
		try {
			report.additionalReportColumns = "Dose Form FSN,SemTag,Dose Form PT,ParentIds,Parents,Used in Int,DefnStat,BDFID, Basic Dose Form, AMID, Administration Method, ISID, Intended Site, RCID, Release Characteristic, TransId, Transformation";
			report.init(args);
			report.loadProjectSnapshot(false);  
			report.postInit();
			report.reportDoseForms();
		} catch (Exception e) {
			info("Failed to produce MissingAttributeReport due to " + e.getClass().getSimpleName() + ": " + e.getMessage());
			e.printStackTrace(new PrintStream(System.out));
		} finally {
			report.finish();
		}
	}

	private void reportDoseForms() throws TermServerScriptException {
		Concept pharmDoseForm = gl.getConcept("736542009 |Pharmaceutical dose form (dose form)|");
		List<Concept> pharmDoseForms = new ArrayList<>(pharmDoseForm.getDescendents(NOT_SET));
		pharmDoseForms.sort(Comparator.comparing(Concept::getFsn));
		Set<Concept> usedInInternationalEdition = findDoseFormsUsed();
		for (Concept c : pharmDoseForms) {
			report (c, 
					c.getPreferredSynonym(),
					reportValue(c, IS_A),
					usedInInternationalEdition.contains(c) ? "Y" : "N",
					SnomedUtils.translateDefnStatus(c.getDefinitionStatus()),
					reportValue(c, gl.getConcept("736476002 |Has basic dose form (attribute)|")),
					reportValue(c, gl.getConcept("736472000 |Has dose form administration method (attribute)|")),
					reportValue(c, gl.getConcept("736474004 |Has dose form intended site (attribute)|")),
					reportValue(c, gl.getConcept("736475003 |Has dose form release characteristic (attribute)|")),
					reportValue(c, gl.getConcept("736473005 |Has dose form transformation (attribute)|")));
		}

	}

	private Set<Concept> findDoseFormsUsed() throws TermServerScriptException {
		Set<Concept> doseFormsUsed = new HashSet<>();
		Concept[] types = new Concept[] { gl.getConcept("411116001 |Has manufactured dose form (attribute)|")};
		for (Concept drug : PHARM_BIO_PRODUCT.getDescendents(NOT_SET)) {
			doseFormsUsed.addAll(SnomedUtils.getTargets(drug, types, CharacteristicType.INFERRED_RELATIONSHIP));
		}
		return doseFormsUsed;
	}

	private String[] reportValue(Concept c, Concept attributeType) {
		String[] idsPTs = new String[] {"",""};
		boolean isFirst = true;
		for (Relationship r : c.getRelationships(CharacteristicType.INFERRED_RELATIONSHIP, attributeType, ActiveState.ACTIVE)) {
			idsPTs[0] += (isFirst?"":",\n") + r.getTarget().getId();
			idsPTs[1] += (isFirst?"":",\n") + r.getTarget().getPreferredSynonym();
			isFirst = false;
		}
		return idsPTs;
	}

}

package org.ihtsdo.termserver.scripting.reports;

import java.io.IOException;
import java.util.*;

import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ReportClass;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.otf.owltoolkit.domain.ObjectPropertyAxiomRepresentation;
import org.snomed.otf.scheduler.domain.*;
import org.snomed.otf.scheduler.domain.Job.ProductionStatus;

/**
 * RP-289 List "Special" OWL Axioms
 */
public class SpecialOWLAxioms extends TermServerReport implements ReportClass {
	
	Logger logger = LoggerFactory.getLogger(this.getClass());
	
	public static void main(String[] args) throws TermServerScriptException, IOException {
		Map<String, String> params = new HashMap<>();
		TermServerReport.run(SpecialOWLAxioms.class, args, params);
	}
	
	public void init (JobRun run) throws TermServerScriptException {
		getArchiveManager().setPopulateReleasedFlag(true);
		headers = "SCTID, FSN, Semtag, Axiom Type, Axiom";
		additionalReportColumns="";
		ReportSheetManager.targetFolderId = "1F-KrAwXrXbKj5r-HBLM0qI5hTzv-JgnU"; //Ad-hoc Reports
		super.init(run);
	}
	
	@Override
	public Job getJob() {
		return new Job()
				.withCategory(new JobCategory(JobType.REPORT, JobCategory.ADHOC_QUERIES))
				.withName("Object Property Axioms")
				.withDescription("This report lists all concepts which have special OWL axioms like GCIs")
				.withProductionStatus(ProductionStatus.PROD_READY)
				.withParameters(new JobParameters())
				.withTag(INT)
				.build();
	}
	
	public void postInit() throws TermServerScriptException {

		String[] columnHeadings = new String[] {"Concept, FSN, SemTag, ConceptActive, isTransitive, isReflexive, isRoleChain, OWL",
				"Concept, Type, OWL"};

		String[] tabNames = new String[] {"Special Axioms" ,
				"Additional + GCIs"};
		super.postInit(tabNames, columnHeadings, false);
	}
	
	public void runJob() throws TermServerScriptException {
		for (Concept c : subHierarchy.getDescendents(NOT_SET)) {
			for (Axiom a : c.getAdditionalAxioms()) {
				report (SECONDARY_REPORT, c, "Additional Axiom", a);
				countIssue(c);
			}
			
			for (Axiom a : c.getGciAxioms()) {
				report (SECONDARY_REPORT, c, "GCI Axiom", a);
				countIssue(c);
			}
			
			if (c.getObjectPropertyAxiomRepresentation() != null) {
				ObjectPropertyAxiomRepresentation axiom = c.getObjectPropertyAxiomRepresentation();
				report (PRIMARY_REPORT, c,
						c.isActive(),
						axiom.isTransitive(),
						axiom.isReflexive(),
						axiom.isPropertyChain(),
						axiom.getOwl());
				countIssue(c);
			}
		}
	}

}

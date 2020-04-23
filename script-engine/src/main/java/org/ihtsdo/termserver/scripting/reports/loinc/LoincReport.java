package org.ihtsdo.termserver.scripting.reports.loinc;

import java.io.IOException;
import java.util.*;

import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ReportClass;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.reports.TermServerReport;
import org.ihtsdo.termserver.scripting.service.TraceabilityService;
import org.snomed.otf.scheduler.domain.*;
import org.snomed.otf.scheduler.domain.Job.ProductionStatus;

/**
 * RP-171 List all new concepts in << 363787002 | Observable entity (observable entity)
 * and << 386053000 | Evaluation procedure (procedure)
 */
public class LoincReport extends TermServerReport implements ReportClass {
	
	public static void main(String[] args) throws TermServerScriptException, IOException {
		TermServerReport.run(LoincReport.class, args, new HashMap<>());
	}
	
	public void init (JobRun run) throws TermServerScriptException {
		getArchiveManager().setPopulateReleasedFlag(true);
		ReportSheetManager.targetFolderId = "1F-KrAwXrXbKj5r-HBLM0qI5hTzv-JgnU"; //Ad-hoc Reports
		super.init(run);
	}
	
	public void postInit() throws TermServerScriptException {
		String[] columnHeadings = new String[] { "SCTID, FSN, SemTag, Author, Task, Creation Date",
				"SCTID, FSN, SemTag, Author, Task, Creation Date"};
		String[] tabNames = new String[] {	"New Observable Entities",
				"New Evaluation Procedures"};
		
		super.postInit(tabNames, columnHeadings, false);
	}
	
	@Override
	public Job getJob() {
		return new Job()
				.withCategory(new JobCategory(JobType.REPORT, JobCategory.ADHOC_QUERIES))
				.withName("LOINC Report")
				.withDescription("This report lists new concepts in the core module relevant to legal agreements relating to LOINC, specifically all new concepts in << 363787002 | Observable entity (observable entity) and << 386053000 | Evaluation procedure (procedure)")
				.withProductionStatus(ProductionStatus.PROD_READY)
				.withTag(INT)
				.build();
	}
	
	public void runJob() throws TermServerScriptException {
		TraceabilityService traceability = new TraceabilityService(jobRun, this, "reating concept");
		
		for (Concept c : gl.getConcept("363787002 | Observable entity (observable entity)").getDescendents(NOT_SET)) {
			if (!c.isReleased()) {
				traceability.populateTraceabilityAndReport(PRIMARY_REPORT, c);
				countIssue(c);
			}
		}
		
		for (Concept c : gl.getConcept("386053000 | Evaluation procedure (procedure)").getDescendents(NOT_SET)) {
			if (!c.isReleased()) {
				traceability.populateTraceabilityAndReport (SECONDARY_REPORT, c);
				countIssue(c);
			}
		}
		traceability.flush();
	}
}
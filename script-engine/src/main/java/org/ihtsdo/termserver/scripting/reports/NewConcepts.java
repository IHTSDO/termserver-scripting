package org.ihtsdo.termserver.scripting.reports;

import java.io.IOException;
import java.util.*;

import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ReportClass;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.service.TraceabilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.otf.scheduler.domain.*;
import org.snomed.otf.scheduler.domain.Job.ProductionStatus;

/**
 * RP-166 List all new concepts
 */
public class NewConcepts extends TermServerReport implements ReportClass {
	
	Logger logger = LoggerFactory.getLogger(this.getClass());
	
	public static void main(String[] args) throws TermServerScriptException, IOException {
		Map<String, String> params = new HashMap<>();
		params.put(SUB_HIERARCHY, ROOT_CONCEPT.toString());
		TermServerReport.run(NewConcepts.class, args, params);
	}
	
	public void init (JobRun run) throws TermServerScriptException {
		getArchiveManager().setPopulateReleasedFlag(true);
		headers = "SCTID, FSN, Semtag, Author, Task, Creation Date";
		additionalReportColumns="";
		ReportSheetManager.targetFolderId = "1F-KrAwXrXbKj5r-HBLM0qI5hTzv-JgnU"; //Ad-hoc Reports
		super.init(run);
	}
	
	@Override
	public Job getJob() {
		JobParameters params = new JobParameters()
				.add(SUB_HIERARCHY).withType(JobParameter.Type.CONCEPT).withDefaultValue(ROOT_CONCEPT)
				.build();
		return new Job()
				.withCategory(new JobCategory(JobType.REPORT, JobCategory.RELEASE_VALIDATION))
				.withName("List new concepts")
				.withDescription("This report lists all concepts (optionally in the given subhierarchy) created in the current authoring cycle")
				.withProductionStatus(ProductionStatus.PROD_READY)
				.withParameters(params)
				.withTag(INT)
				.withTag(MS)
				.build();
	}
	
	public void runJob() throws TermServerScriptException {
		//Are we running locally from command line?
		if (jobRun.getTerminologyServerUrl() == null) {
			logger.warn("No TS specified.  Using localhost");
			jobRun.setTerminologyServerUrl("http://localhost:8085/");
		}
		//SnowOwl and Snowstorm used different case significance for "Creating concept", historically
		TraceabilityService service = new TraceabilityService(jobRun, this, "reating concept");
		
		/*List<Concept> testData = new ArrayList<>();
		testData.add(new Concept("715455007", "foo"));
		testData.add(new Concept("715456008"));
		testData.add(new Concept("715457004"));
		testData.add(new Concept("715458009"));
		testData.add(new Concept("715459001"));
		testData.add(new Concept("715460006"));*/
		
		for (Concept c : subHierarchy.getDescendents(NOT_SET)) {
		//for (Concept c : testData) {
			if (!c.isReleased() && inScope(c)) {
				service.populateTraceabilityAndReport(PRIMARY_REPORT, c, (Object[])null);
				countIssue(c);
			}
		}
		//Finish off adding traceability and reporting out any remaining concepts that 
		//haven't filed a batch
		service.flush();
	}

}
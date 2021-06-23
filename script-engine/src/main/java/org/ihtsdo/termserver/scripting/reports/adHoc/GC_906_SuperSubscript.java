package org.ihtsdo.termserver.scripting.reports.adHoc;

import java.io.IOException;
import java.util.*;

import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ReportClass;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.reports.TermServerReport;
import org.snomed.otf.scheduler.domain.*;
import org.snomed.otf.scheduler.domain.Job.ProductionStatus;

/**
 * List all descriptions using super/subscript workaround of ^^ > < 
 */
public class GC_906_SuperSubscript extends TermServerReport implements ReportClass {
	
	String[] brackets = new String[] {"<", ">"};
	
	public static void main(String[] args) throws TermServerScriptException, IOException {
		Map<String, String> params = new HashMap<>();
		TermServerReport.run(GC_906_SuperSubscript.class, args, params);
	}
	
	public void init (JobRun run) throws TermServerScriptException {
		ReportSheetManager.targetFolderId = "1F-KrAwXrXbKj5r-HBLM0qI5hTzv-JgnU"; //Ad-hoc Reports
		additionalReportColumns = "FSN, SemTag, DescMatched";
		super.init(run);
	}
	
	@Override
	public Job getJob() {
		return new Job()
				.withCategory(new JobCategory(JobType.REPORT, JobCategory.ADHOC_QUERIES))
				.withName("Super Subscript")
				.withDescription("This report lists all concepts containing super/subscript workaround ^^ ><")
				.withProductionStatus(ProductionStatus.PROD_READY)
				.withParameters(new JobParameters())
				.withTag(INT)
				.build();
	}
	
	public void runJob() throws TermServerScriptException {
		List<Concept> conceptsSorted = new ArrayList<>(gl.getAllConcepts());
		conceptsSorted.sort(Comparator.comparing(Concept::getSemTag).thenComparing(Concept::getFsn));
		for (Concept c : conceptsSorted) {
			if (c.isActive()) {
				String css = containingSuperSubscript(c);
				if (css.length() > 0) {
					report (c, css);
				}
			}
		}
	}

	private String containingSuperSubscript(Concept c) {
		String css = "";
		for (Description d : c.getDescriptions()) {
			if (containsSuperSubscript(d.getTerm())) {
				css += css.length() == 0 ? "" : ",\n";
				css += d.toString();
			}
		}
		return css;
	}

	private boolean containsSuperSubscript(String term) {
		if (term.contains("^")) {
			return true;
		}
		
		//Otherwise find any open/close angle brackets within 5 characters of each other
		nextOuter:
		for (String outer : brackets) {
			nextInner:
			for (String inner : brackets) {
				int outerPoint = 0;
				while (outerPoint != -1 && outerPoint < term.length()) {
					outerPoint = term.indexOf(outer, outerPoint);
					if (outerPoint == -1) {
						continue nextOuter;
					}
					int innerPoint = term.indexOf(inner, outerPoint+1);
					if (innerPoint == -1) {
						continue nextInner;
					}
					//Are they within 5 characters of each other?
					if (innerPoint != outerPoint && Math.abs(innerPoint - outerPoint) < 5) {
						return true;
					}
					outerPoint++;
				}
			}
		}
		return false;
	}
	
}

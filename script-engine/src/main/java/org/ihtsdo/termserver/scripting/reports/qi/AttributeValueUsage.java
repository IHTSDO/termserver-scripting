package org.ihtsdo.termserver.scripting.reports.qi;

import java.util.*;

import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ReportClass;
import org.ihtsdo.termserver.scripting.ArchiveManager;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.reports.TermServerReport;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;
import org.snomed.otf.scheduler.domain.*;
import org.snomed.otf.scheduler.domain.Job.ProductionStatus;

import com.google.common.util.concurrent.AtomicLongMap;

/**
 * Reports concepts that are intermediate primitives from point of view of some subhierarchy
 * Update: Adding a 2nd report to determine how many sufficiently defined concepts are affected by an IP
 * Update: Didn't seem to work last time I ran this.  use AttributeValueCount instead - more flexible
 * */
@Deprecated 
public class AttributeValueUsage extends TermServerReport implements ReportClass {
	
	AtomicLongMap<Concept> attributeValueMap = AtomicLongMap.create();
	String attributeTypeStr;
	Set<Concept> reported = new HashSet<>();
	
	public static void main(String[] args) throws TermServerScriptException {
		Map<String, String> params = new HashMap<>();
		params.put(SUB_HIERARCHY, CLINICAL_FINDING.toString());
		params.put(ATTRIBUTE_TYPE, ASSOC_MORPH.toString());
		TermServerReport.run(AttributeValueUsage.class, args, params);
	}
	
	public void init(JobRun jobRun) throws TermServerScriptException {
		ReportSheetManager.targetFolderId = "1YoJa68WLAMPKG6h4_gZ5-QT974EU9ui6";  // QI/Stats
		headers = ",Attribute Value, count, cumulativeCount";
		additionalReportColumns="";
		attributeTypeStr = jobRun.getMandatoryParamValue(ATTRIBUTE_TYPE);
		ArchiveManager.getArchiveManager(this, null).setPopulateHierarchyDepth(true);
		super.init(jobRun);
	}

	@Override
	public Job getJob() {
		JobParameters params = new JobParameters()
				.add(SUB_HIERARCHY)
					.withType(JobParameter.Type.CONCEPT)
					.withMandatory()
				.add(ATTRIBUTE_TYPE)
					.withType(JobParameter.Type.CONCEPT)
					.withMandatory()
				.build();
		
		return new Job()
				.withCategory(new JobCategory(JobType.REPORT, JobCategory.QI))
				.withName("Attribute Value Usage")
				.withDescription("This report lists - for a given attribute type - how often each value is used, with cumulative hierarchial totals." +
									"Note that the 'Issues' count here refers to the number of rows reported.")
				.withProductionStatus(ProductionStatus.HIDEME)
				.withParameters(params)
				.withTag(INT)
				.build();
	}

	
	public void runJob() throws TermServerScriptException {
		info("Generating Attribute Value Usage Report for " + subHierarchy);
		Concept[] attributeTypes = new Concept[] {gl.getConcept(attributeTypeStr)};
		
		//First pass to find the specific counts.  Keep track of the highestLevel
		Concept highestLevel = null;
		for (Concept c : subHierarchy.getDescendents(NOT_SET)) {
			for (Concept value : SnomedUtils.getTargets(c, attributeTypes, CharacteristicType.INFERRED_RELATIONSHIP)) {
				attributeValueMap.getAndIncrement(value);
				if (highestLevel == null || value.getDepth() < highestLevel.getDepth()) {
					highestLevel = value;
				}
			}
		}
		
		//Now recursively look for ancestors and sum up usages.
		//We calculate bottom up, but we want to display top down, so populate a stack
		Stack<ReportItem> reportItems = new Stack<>();
		outputUsage(reportItems, highestLevel, 0);
		
		while (reportItems.isEmpty()) {
			reportItems.pop().writeOut();
		}
	}

	private long outputUsage(Stack<ReportItem> reportItems, Concept current, int indent) throws TermServerScriptException {
		
		//Report the counts of immediate children - if they appear in the map
		int cumulativeTotal = 0;
		for (Concept child : current.getChildren(CharacteristicType.INFERRED_RELATIONSHIP)) {
			cumulativeTotal += outputUsage(reportItems, child, indent + 1);
		}
		
		String indentStr = "";
		for (int x=0; x < indent; x++) {
			indentStr += "-- ";
		}
		
		//Output this level, with appropriate indent and my total and cumulative total
		long myCount = 0;
		if (attributeValueMap.containsKey(current)) {
			myCount = attributeValueMap.get(current);
			if (indent < 5 && !reported.contains(current)) {
				reportItems.push(new ReportItem(indentStr + current, myCount, cumulativeTotal));
			}
		}
		reported.add(current);
		return myCount + cumulativeTotal;
	}
	
	class ReportItem {
		String name;
		long count;
		long cumulative;
		
		ReportItem(String name, long count, long cumulative) {
			this.name = name;
			this.count = count;
			this.cumulative = cumulative;
		}
		
		void writeOut() throws TermServerScriptException {
			report (0, null, name, count, count + cumulative);
		}
	}
}

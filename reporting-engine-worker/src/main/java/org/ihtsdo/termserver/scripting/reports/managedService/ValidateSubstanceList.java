package org.ihtsdo.termserver.scripting.reports.managedService;

import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ReportClass;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.reports.TermServerReport;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;
import org.snomed.otf.scheduler.domain.*;
import org.snomed.otf.scheduler.domain.Job.ProductionStatus;

import com.google.common.base.Charsets;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * ISP-36 Simple list of concepts based on ECL
 */
public class ValidateSubstanceList extends TermServerReport implements ReportClass {
	
	Map<String, MOH> mohMap = new HashMap<>();
	
	public static void main(String[] args) throws TermServerScriptException, IOException {
		TermServerReport.run(ValidateSubstanceList.class, args, new HashMap<>());
	}
	
	public void init (JobRun run) throws TermServerScriptException {
		ReportSheetManager.targetFolderId = "1F-KrAwXrXbKj5r-HBLM0qI5hTzv-JgnU"; //Ad-hoc Reports
		loadSubstanceList();
		super.init(run);
	}
	
	public void postInit() throws TermServerScriptException {
		headers = "Substance Name, Substance Code, Edition, All OK, Is Missing, Update Name, Update Edition, Active State, Comment";
		additionalReportColumns="";
		super.postInit();
	}
	
	@Override
	public Job getJob() {
		return new Job()
				.withCategory(new JobCategory(JobType.REPORT, JobCategory.ADHOC_QUERIES))
				.withName("List selected concepts with preferred terms")
				.withDescription("This report lists all concepts matching the specified ECL and preferred terms from the specified refsets." + 
				" As a special case, GB PTs will only be listed where they differ from the US PT.")
				.withProductionStatus(ProductionStatus.HIDEME)
				.withParameters(new JobParameters())
				.build();
	}
	
	public void runJob() throws TermServerScriptException {
		List<Concept> substances = new ArrayList<>(SUBSTANCE.getDescendents(NOT_SET));
		substances.sort(Comparator.comparing(Concept::getFsnSafely));
		for (Concept substance : substances) {
			//Do MOH already know about this substance?
			boolean isMissing = !mohMap.containsKey(substance.getId());
			//String pt = substance.getPreferredSynonym(US_ENG_LANG_REFSET).getTerm();
			String pt = SnomedUtils.deconstructFSN(substance.getFsn())[0];
			if (!isMissing) {
				MOH moh = mohMap.get(substance.getId());
				boolean termOK = moh.name.trim().equals(pt);
				boolean editionOK = checkEdition(moh, substance);
				boolean allOK = termOK && editionOK;
				report (PRIMARY_REPORT,
						moh.name,
						moh.code,
						moh.edition,
						allOK ? "Y" : "N",
						"N",  //Not Missing
						termOK ? "" : pt,
						editionOK ? "" : getEdition(substance),
						"Active",
						"");
			} else {
				report (PRIMARY_REPORT,
						pt,
						substance.getId(),
						getEdition(substance),
						"N",
						"Y",  //Missing
						"",
						"",
						"Active",
						"Missing from source data, active since " + substance.getEffectiveTime());
			}
		}
		
		//Now what all in the MOH data have we not mentioned?
		for (String code : mohMap.keySet()) {
			MOH moh = mohMap.get(code);
			try {
				Concept substance = gl.getConcept(code, false, false);
				if (substance == null) {
					report (PRIMARY_REPORT,  moh.name, moh.code, moh.edition, "N", "N", "", "", "N/A", "SCTID Unknown");
				} else if (!substance.isActive()) {
					report (PRIMARY_REPORT, moh.name, moh.code, moh.edition, "N", "N",  "", "", "Inactive", "Concept Inactive since " + substance.getEffectiveTime());
				}
			} catch (Exception e) {
				report (PRIMARY_REPORT, moh.name, moh.code, moh.edition, "N", "N", "", "", "N/A", e.getMessage());
			}
		}
	}

	private boolean checkEdition(MOH moh, Concept substance) throws TermServerScriptException {
		return moh.edition.equals(getEdition(substance));
	}
	
	private String getEdition(Concept c) throws TermServerScriptException {
		switch (c.getModuleId()) {
			case SCTID_CORE_MODULE : return "International";
			case "731000124108" : return "US";
			default : throw new TermServerScriptException("Unexpected module for " + c + ": " + c.getModuleId());
		}
	}

	private void loadSubstanceList() throws TermServerScriptException {
		inputFile = new File("G:\\My Drive\\005_Ad_hoc_queries\\045_SaudiArabia\\MOH Allergy Substance List Unicode.txt");
		info("Loading " + inputFile);
		if (!inputFile.canRead()) {
			throw new TermServerScriptException ("Cannot read: " + inputFile);
		}
		try {
			List<String> lines = Files.readLines(inputFile, Charsets.UTF_16LE);
			boolean isHeader = true;
			for (String line : lines) {
				if (!isHeader) {
					MOH moh = new MOH(line);
					mohMap.put(moh.code, moh);
				} else {
					isHeader = false;
				}
			}
		} catch (IOException e) {
			throw new TermServerScriptException("Failed to load " + inputFile, e);
		}
	}
	
	public class MOH
	{
		String name;
		String code;
		String edition;
		
		public MOH (String line) {
			String[] parts = line.split(TAB);
			name = parts[0];
			if (name.startsWith("\"")) {
				name = name.substring(1,name.length()-1);
			}
			code = parts[1];
			edition = parts[2];
		}
	}
}

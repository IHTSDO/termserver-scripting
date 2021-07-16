package org.ihtsdo.termserver.scripting.reports.oneOffs;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.*;

import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.Concept;
import org.ihtsdo.termserver.scripting.reports.TermServerReport;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;
import org.springframework.util.StringUtils;

/*
 * Report to list concepts from some file which have been, or are being inactivated
 * First used for INFRA-7142 and ICNP analysis
 */
public class InactivatedInFiles extends TermServerReport {

	private List<File> inputFiles;

	public static void main(String[] args) throws TermServerScriptException, IOException {
		InactivatedInFiles report = new InactivatedInFiles();
		try {
			report.init(args);
			report.loadProjectSnapshot(false);  //Load all descriptions
			report.postInit();
			report.analyzeFiles();
		} catch (Exception e) {
			info("Failed to produce report due to " + e.getMessage());
			e.printStackTrace(new PrintStream(System.out));
		} finally {
			report.finish();
		}
	}

	public void init(String[] args) throws TermServerScriptException {
		inputFiles = new ArrayList<>();
		for (int i=0; i < args.length; i++) {
			if (args[i].startsWith("-f")) {
				File f = new File(args[i+1]);
				if (!f.canRead()) {
					throw new IllegalArgumentException("Can't read " + f);
				}
				inputFiles.add(f);
			}
		}
		super.init(args);
	}
	
	public void postInit() throws TermServerScriptException {
		ReportSheetManager.targetFolderId = "1F-KrAwXrXbKj5r-HBLM0qI5hTzv-JgnU";  //Ad-hoc Reports
		List<String> tabNames = new ArrayList<>();
		List<String> columnHeadings = new ArrayList<>();
		//How many files are we reporting on?
		for (File f : inputFiles) {
			tabNames.add(f.getName());
			columnHeadings.add("ICNP Code, ICNP PT, Status, SCTID, EffectiveDate, Replacement");
		}
		super.postInit(tabNames.toArray(new String[0]), columnHeadings.toArray(new String[0]), false);
	}

	private void analyzeFiles() throws TermServerScriptException, FileNotFoundException {
		int currentTab = 0;
		for (File f : inputFiles) {
			Scanner scanner = new Scanner(f);
			boolean headerRowIgnored = false;
			while (scanner.hasNextLine()) {
				if (!headerRowIgnored) {
					scanner.nextLine();
					headerRowIgnored = true;
				} else {
					String[] lineItems = scanner.nextLine().split(TAB);
					analyseLine(currentTab, lineItems);
				}
			}
			scanner.close();
			currentTab++;
		}
	}

	private void analyseLine(int currentTab, String[] lineItems) throws TermServerScriptException {
		//Only interested in inactive concepts
		String externalPT = lineItems[1];
		Concept concept = null;
		
		if (lineItems.length > 2) {
			concept = gl.getConcept(lineItems[2], false, false);
			if (concept == null) {
				report (currentTab, lineItems[0], lineItems[1], "Invalid SCTID", lineItems[2]);
			}
		}
		
		//If we don't have the concept can we find it from the PT
		if (concept == null) {
			concept = gl.findConceptByUSPT(externalPT);
			if (concept == null) {
				concept = gl.findConceptByGBPT(externalPT);
			}
			if (concept == null) {
				report (currentTab, lineItems[0], lineItems[1], "Not supplied, not found");
			} else {
				report (currentTab, lineItems[0], lineItems[1], "Not supplied, but mapping found", concept);
			}
			return;
		}
		
		if (!concept.getFsn().equals(lineItems[3].trim().replaceAll("\"",""))) {
			report (currentTab, lineItems[0], lineItems[1], "FSN Updated", concept, "", "Supplied: " + lineItems[3]);
		}
		
		if (!concept.isActive()) {
			String et = concept.getEffectiveTime();
			if (StringUtils.isEmpty(et)) {
				et = "20220131";
			}
			report (currentTab, lineItems[0], lineItems[1], "Inactive", concept, et, SnomedUtils.prettyPrintHistoricalAssociations(concept,gl));
		}
	}

}

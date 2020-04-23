package org.ihtsdo.termserver.scripting.reports.release;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Project;
import org.ihtsdo.termserver.scripting.ReportClass;
import org.ihtsdo.termserver.scripting.TransitiveClosure;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.reports.TermServerReport;
import org.snomed.otf.scheduler.domain.*;
import org.snomed.otf.scheduler.domain.Job.ProductionStatus;
import org.springframework.util.StringUtils;

/**
 * RP-288 
 * 
 * TODO As it stands, the report will double count concepts that exist in two top level hierarchies.  
 * I'm looking at you, drug eluting stents.
 * To fix this, the analyze function has to be told what component idx it's looking at, and a Set maintained
 * of components that have contributed to the total, and this checked before the total is added to - during
 * analysis, not during report output as we currently have.
 * */
public class SummaryComponentStats extends TermServerReport implements ReportClass {
	
	public static final String PREV_RELEASE = "Previous Release";
	public static final String THIS_RELEASE = "This Release";
	
	String prevRelease;
	Map<String, Datum> prevData;
	//2D data structure Concepts, Descriptions, Relationships, Axioms, LangRefset, Inactivation Indicators, Historical Associations
	Map<Concept, int[][]> summaryDataMap;
	String thisEffectiveTime;
	int topLevelHierarchyCount = 0;
	static final int IDX_CONCEPTS = 0, IDX_DESCS = 1, IDX_RELS = 2, IDX_AXIOMS = 3,
			IDX_LANG = 4, IDX_INACT_IND = 5, IDX_HIST = 6;
	static final int COMPONENT_COUNT = 7;
	static final int DATA_WIDTH = 6;  //New, Changed, Inactivated, New with New Concept, extra1, extra2
	static final int IDX_NEW = 0, IDX_CHANGED = 1, IDX_INACT = 2, IDX_NEW_NEW = 3, IDX_NEW_P = 4, IDX_NEW_SD = 5;
	List<Concept> topLevelHierarchies; 
	
	public static void main(String[] args) throws TermServerScriptException, IOException {
		Map<String, String> params = new HashMap<>();
		params.put(PREV_RELEASE, "SnomedCT_InternationalRF2_PRODUCTION_20190731T120000Z.zip");
		params.put(THIS_RELEASE, "prod_main_20200131_20191122101800.zip");
		TermServerReport.run(SummaryComponentStats.class, args, params);
	}

	@Override
	public Job getJob() {
		JobParameters params = new JobParameters()
				.add(PREV_RELEASE).withType(JobParameter.Type.STRING)
				.add(THIS_RELEASE).withType(JobParameter.Type.STRING)
				.build();
		
		return new Job()
				.withCategory(new JobCategory(JobType.REPORT, JobCategory.RELEASE_STATS))
				.withName("Summary Component Stats")
				.withDescription("This report lists component changes per major hierarchy.   You can either specify two releases to compare as archives stored in S3 " + 
				"(eg SnomedCT_InternationalRF2_PRODUCTION_20190731T120000Z.zip) or leave them blank to compare the current delta to the previous release as specified " +
				"by that branch.")
				.withParameters(params)
				.withTag(INT)
				.withProductionStatus(ProductionStatus.PROD_READY)
				.build();
	}
	
	public void init (JobRun run) throws TermServerScriptException {
		ReportSheetManager.targetFolderId = "15WXT1kov-SLVi4cvm2TbYJp_vBMr4HZJ"; //Release QA
		prevData = new HashMap<>();
		summaryDataMap = new HashMap<>();
		manyTabOutput = true;
		super.init(run);
	}

	@Override
	protected void loadProjectSnapshot(boolean fsnOnly) throws TermServerScriptException, InterruptedException, IOException {
		boolean compareTwoSnapshots = false; 
		String projectKey = getProject().getKey();
		prevRelease = getJobRun().getParamValue(PREV_RELEASE);
		if (prevRelease == null) {
			prevRelease = getProject().getMetadata().getPreviousPackage();
		}
		
		if (getJobRun().getParamValue(THIS_RELEASE) != null) {
			compareTwoSnapshots = true;
			projectKey = getJobRun().getParamValue(THIS_RELEASE);
		}
		getProject().setKey(prevRelease);
		getArchiveManager().setLoadEditionArchive(true);
		getArchiveManager().loadProjectSnapshot(fsnOnly);
		HistoricStatsGenerator statsGenerator = new HistoricStatsGenerator(this);
		statsGenerator.runJob();
		
		if (compareTwoSnapshots) {
			getArchiveManager().setLoadEditionArchive(true);
			setProject(new Project(projectKey));
			getArchiveManager().loadProjectSnapshot(false);
			//Descriptions for the root concept are a quick way to find the effeciveTime
			thisEffectiveTime = ROOT_CONCEPT.getFSNDescription().getEffectiveTime();
			info ("Detected this effective time as " + thisEffectiveTime);
		} else {
			//Now we can carry on an add the delta on top
			getArchiveManager().setLoadEditionArchive(false);
			getProject().setKey(projectKey);
			File delta = getArchiveManager().generateDelta(project);
			loadArchive(delta, false, "Delta", false);
		}
	};
	
	public void postInit() throws TermServerScriptException {
		String[] columnHeadings = new String[] {"Sctid, Hierarchy, SemTag, New, Changed DefnStatus, Inactivated, New with New Concept, New SD, New P", 
												"Sctid, Hierarchy, SemTag, New / Reactivated, Changed, Inactivated, New with New Concept",
												"Sctid, Hierarchy, SemTag, New Axioms, Changed Axioms, Inactivated Axioms, New with New Concept",
												"Sctid, Hierarchy, SemTag, New Inferred Rels, Changed Inferred Rels, Inactivated Inferred Rels, New with New Concept",
												"Sctid, Hierarchy, SemTag, New / Reactivated, Changed, Inactivated, New with New Concept",
												"Sctid, Hierarchy, SemTag, Inactivations New / Reactivated, Changed, Inactivations Inactivated, New with New Concept",
												"Sctid, Hierarchy, SemTag, Assoc New / Reactivated, Changed, Assoc Inactivated, New with New Concept"
};
		String[] tabNames = new String[] {	"Concepts", 
											"Descriptions",
											"Axioms",
											"Relationships",
											"LangRefSet",
											"Inactivations",
											"Hist Assoc" };
		topLevelHierarchies = new ArrayList<Concept>(ROOT_CONCEPT.getChildren(CharacteristicType.INFERRED_RELATIONSHIP));
		topLevelHierarchies.sort(Comparator.comparing(Concept::getFsn));
		super.postInit(tabNames, columnHeadings, false);
	}
	public void runJob() throws TermServerScriptException {
		info ("Loading Previous Data");
		loadData(prevRelease);
		info ("Analysing Data");
		analyzeConcepts();
		info ("Outputting Results");
		outputResults();
	}

	private void analyzeConcepts() throws TermServerScriptException {
		TransitiveClosure tc = gl.generateTransativeClosure();
		for (Concept c : gl.getAllConcepts()) {
			Concept topLevel;
			if (c.isActive()) {	
				topLevel = getHierarchy(tc, c);
			} else {
				//Was it active in the previous release?
				if (prevData.containsKey(c.getConceptId())) {
					topLevel = gl.getConcept(prevData.get(c.getConceptId()).hierarchy);
				} else {
					//If not, it's been inactivate for a while, nothing more to say
					break;
				}
			}
			//Have we seen this hierarchy before?
			int[][] summaryData = summaryDataMap.get(topLevel);
			if (summaryData == null) {
				summaryData = new int[COMPONENT_COUNT][DATA_WIDTH];
				summaryDataMap.put(topLevel, summaryData);
			}
			
			Datum datum = prevData.get(c.getConceptId());
			boolean isNewConcept = datum==null;
			analyzeConcept(c, datum==null?null:datum.isSD, summaryData[IDX_CONCEPTS]);
			//Component changes
			analyzeComponents(isNewConcept, (datum==null?null:datum.descIds), summaryData[IDX_DESCS], c.getDescriptions());
			analyzeComponents(isNewConcept, (datum==null?null:datum.relIds), summaryData[IDX_RELS], c.getRelationships(CharacteristicType.INFERRED_RELATIONSHIP, ActiveState.BOTH));
			analyzeComponents(isNewConcept, (datum==null?null:datum.axiomIds), summaryData[IDX_AXIOMS], c.getAxiomEntries());
			analyzeComponents(isNewConcept, (datum==null?null:datum.inactivationIds), summaryData[IDX_INACT_IND], c.getInactivationIndicatorEntries());
			analyzeComponents(isNewConcept, (datum==null?null:datum.histAssocIds), summaryData[IDX_HIST], c.getAssociations());
			List<LangRefsetEntry> langRefsetEntries = c.getDescriptions().stream()
					.flatMap(d -> d.getLangRefsetEntries().stream())
					.collect(Collectors.toList());
			analyzeComponents(isNewConcept, (datum==null?null:datum.langRefsetIds), summaryData[IDX_LANG], langRefsetEntries);
		}
	}
	
	
	private void analyzeConcept(Concept c, Boolean wasSD, int[] counts) {
		//If we have no previous data, then the concept is new
		boolean conceptIsNew = (wasSD == null);
		if (c.isActive()) {	
			boolean isSD = c.getDefinitionStatus().equals(DefinitionStatus.FULLY_DEFINED);
			if (conceptIsNew) {
				if (isSD) {
					counts[IDX_NEW_SD]++;
				} else {
					counts[IDX_NEW_P]++;
				}
				counts[IDX_NEW]++;
				counts[IDX_NEW_NEW]++;
			} else {
				//If the concept is active but not new, has it changed?
				if (isSD != wasSD) {
					counts[IDX_CHANGED]++;
				}
			}
		} else {
			//If we had it last time, then it's been inactivated
			if (prevData.containsKey(c.getConceptId())) {
				counts[IDX_INACT]++;
			}
		}
		
	}

	private void analyzeComponents(boolean isNewConcept, List<String> ids, int[] counts, List<? extends Component> components) {
		//If we have no previous data, then the concept is new
		boolean conceptIsNew = (ids == null);
		for (Component c : components) {
			//Was the description present in the previous data?
			boolean existedPreviously = false;
			if (!conceptIsNew) {
				existedPreviously = ids.contains(c.getId());
			}
			if (c.isActive()) {
				if (!existedPreviously) {
					counts[IDX_NEW]++;
					if (isNewConcept) {
						//This component is new because it was created as part of a new concept
						//so it's not been 'added' as such.  Well, we might want to count additions
						//to existing concepts separately.
						counts[IDX_NEW_NEW]++;
					}
				} else if (StringUtils.isEmpty(c.getEffectiveTime()) || c.getEffectiveTime().equals(thisEffectiveTime)) {
					//Did it change in this release?
					counts[IDX_CHANGED]++;
				}
			} else {
				if (existedPreviously) {
					counts[IDX_INACT]++;
				}
			}
		}
	}

	private void outputResults() throws TermServerScriptException {
		Concept totalConcept = new Concept("","Total");
		
		int[][] totals = new int[COMPONENT_COUNT][DATA_WIDTH];
		for (Concept hierarchy : topLevelHierarchies) {
			int[][] summaryData = summaryDataMap.get(hierarchy);
			for (int idxTab = 0; idxTab < COMPONENT_COUNT; idxTab++) {
				report(idxTab, hierarchy, summaryData[idxTab]);
				for (int idxMovement = 0; idxMovement < DATA_WIDTH; idxMovement++) {
					totals[idxTab][idxMovement] += summaryData[idxTab][idxMovement];
				}
			}
		}
		
		for (int idxTab = 0; idxTab < COMPONENT_COUNT; idxTab++) {
			report (idxTab, totalConcept, totals[idxTab]);
		}
	}
	
	protected void report (int idxTab, Concept c, int[] data) throws TermServerScriptException {
		int dataWidth = DATA_WIDTH - 2;  //Just concepts have those two extra fields.
		if (idxTab == IDX_CONCEPTS) {
			dataWidth = DATA_WIDTH;
		}
		int[] dataSubset = Arrays.copyOfRange(data, 0, dataWidth);
		super.report(idxTab, c, dataSubset);
		countIssue(c);
	}

	private void loadData(String release) throws TermServerScriptException {
		File dataFile = null;
		try {
			dataFile = new File("historic-data/" + release + ".tsv");
			if (!dataFile.exists() || !dataFile.canRead()) {
				throw new TermServerScriptException("Unable to load historic data: " + dataFile);
			}
			info ("Loading " + dataFile);
			BufferedReader br = new BufferedReader(new FileReader(dataFile));
			int lineNumber = 0;
			String line = "";
			try {
				while ((line = br.readLine()) != null) {
					lineNumber++;
					Datum datum = fromLine(line);
					
					if (datum.hierarchy.isEmpty()){
						datum.hierarchy = "54690008 |Unknown (origin) (qualifier value)|";
					}
					prevData.put(Long.toString(datum.conceptId), datum);
				}
			} catch (Exception e) {
				String err = e.getClass().getSimpleName();
				throw new TermServerScriptException(err + " at line " + lineNumber + " columnCount: " + line.split(TAB).length + " content: " + line);
			} finally {
				br.close();
			}
		} catch (Exception e) {
			throw new TermServerScriptException("Unable to load " + dataFile, e);
		}
	}
	
	private Concept getHierarchy(TransitiveClosure tc, Concept c) throws TermServerScriptException {
		if (!c.isActive() || c.equals(ROOT_CONCEPT) || c.getDepth() == NOT_SET) {
			return null;  //Hopefully the previous release will know
		} 
		
		if (c.getDepth() == 1) {
			return c;
		} 
		
		for (Long sctId : tc.getAncestors(c)) {
			Concept a = gl.getConcept(sctId);
			if (a.getDepth() == 1) {
				return a;
			}
		}
		throw new TermServerScriptException("Unable to determine hierarchy for " + c);
	}
	
	protected class Datum {
		long conceptId;
		boolean isActive;
		boolean isSD;
		String hierarchy;
		boolean isIP;
		boolean hasSdDescendant;
		boolean hasSdAncestor;
		int hashCode;
		List<String> relIds;
		List<String> descIds;
		List<String> axiomIds;
		List<String> langRefsetIds;
		List<String> inactivationIds;
		List<String> histAssocIds;
		
		@Override
		public int hashCode () {
			return hashCode;
		}
		
		@Override
		public boolean equals (Object o) {
			if (o instanceof Datum) {
				return this.conceptId == ((Datum)o).conceptId;
			}
			return false;
		}
	}
	
	Datum fromLine (String line) {
		Datum datum = new Datum();
		String[] lineItems = line.split(TAB, -1);
		datum.conceptId = Long.parseLong(lineItems[0]);
		datum.isActive = lineItems[1].equals("Y");
		datum.isSD = lineItems[2].equals("SD");
		datum.hierarchy = lineItems[3];
		datum.isIP = lineItems[4].equals("Y");
		datum.hasSdAncestor = lineItems[5].equals("Y");
		datum.hasSdDescendant = lineItems[6].equals("Y");
		datum.hashCode = Long.hashCode(datum.conceptId);
		datum.relIds = Arrays.asList(lineItems[7].split(","));
		datum.descIds = Arrays.asList(lineItems[8].split(","));
		datum.axiomIds = Arrays.asList(lineItems[9].split(","));
		datum.langRefsetIds = Arrays.asList(lineItems[10].split(","));
		datum.inactivationIds= Arrays.asList(lineItems[11].split(","));
		datum.histAssocIds = Arrays.asList(lineItems[12].split(","));
		return datum;
	}
	
}

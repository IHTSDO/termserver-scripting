package org.ihtsdo.termserver.scripting;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.*;

import org.apache.commons.lang.time.DurationFormatUtils;
import org.ihtsdo.otf.rest.client.RestClientException;
import org.ihtsdo.otf.rest.client.authoringservices.AuthoringServicesClient;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.*;
import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.client.*;
import org.ihtsdo.termserver.scripting.dao.RF2Manager;
import org.ihtsdo.termserver.scripting.dao.ReportManager;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.util.ExceptionUtils;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;
import org.ihtsdo.termserver.scripting.util.StringUtils;
import org.snomed.otf.scheduler.domain.*;

import org.springframework.context.ApplicationContext;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import us.monoid.json.JSONException;
import us.monoid.json.JSONObject;
import us.monoid.web.Resty;

public abstract class TermServerScript implements RF2Constants {
	
	static Logger logger = LoggerFactory.getLogger(TermServerScript.class);
	
	protected static boolean debug = true;
	protected static boolean dryRun = true;
	protected static Integer headlessEnvironment = null;
	protected boolean validateConceptOnUpdate = true;
	protected boolean offlineMode = false;
	protected boolean quiet = false; 
	protected boolean suppressOutput = false;
	protected static int dryRunCounter = 0;
	protected String env;
	protected String url = environments[0];
	protected boolean stateComponentType = true;
	protected JobRun jobRun;
	protected TermServerClient tsClient;
	protected AuthoringServicesClient scaClient;
	protected String authenticatedCookie;
	protected Resty resty = new Resty();
	protected Project project;
	protected int maxFailures = 5;
	protected int restartPosition = NOT_SET;
	protected int processingLimit = NOT_SET;
	private Date startTime;
	private Map<String, Object> summaryDetails = new TreeMap<String, Object>();
	protected boolean inputFileHasHeaderRow = false;
	protected boolean runStandAlone = true; //Set to true to avoid loading concepts from Termserver.  Should be used with Dry Run only.
	protected File inputFile;
	protected File inputFile2;
	protected String dependencyArchive;
	protected String projectName;
	private String reportName;
	protected boolean safetyProtocols = true;  //Switch off to bypass all limits
	protected boolean manyTabOutput = false;
	protected boolean includeSummaryTab = false;
	protected boolean reportNullConcept = true;
	
	protected String subHierarchyStr;
	protected String subHierarchyECL;
	protected Concept subHierarchy;
	protected String[] excludeHierarchies;
	
	protected Set<Concept> whiteListedConcepts = new HashSet<>();

	protected GraphLoader gl = GraphLoader.getGraphLoader();
	private ReportManager reportManager;
	private RF2Manager rf2Manager;
	protected ApplicationContext appContext;
	protected String headers = "Concept SCTID,";
	protected String additionalReportColumns = "ActionDetail";
	protected String secondaryReportColumns = "ActionDetail";
	protected String tertiaryReportColumns = "ActionDetail";
	protected boolean expectNullConcepts = false; //Set to true to avoid warning about rows in input file that result in no concept to modify
	public Scanner STDIN = new Scanner(System.in);
	
	public static String CONCEPTS_IN_FILE = "Concepts in file";
	public static String CONCEPTS_TO_PROCESS = "Concepts to process";
	public static String REPORTED_NOT_PROCESSED = "Reported not processed";
	public static String ISSUE_COUNT = "Issue count";
	public static String CRITICAL_ISSUE = "CRITICAL ISSUE";
	public static String WHITE_LISTED_COUNT = "White Listed Count";
	public static String inputFileDelimiter = TSV_FIELD_DELIMITER;
	protected String tsRoot = "MAIN/"; //"MAIN/2016-01-31/SNOMEDCT-DK/";
	public static final String EXPECTED_PROTOCOL = "https://";
	
	
	protected static final String AUTHOR = "Author";
	public static final String Reviewer = "Reviewer";
	public static final String CONCEPTS_PER_TASK = "Concepts per task";
	public static final String FILE = "File";
	//public static final String PROJECT = "Project";
	protected static final String DRY_RUN = "Dry Run";
	protected static final String INPUT_FILE = "InputFile";
	protected static final String SUB_HIERARCHY = "Subhierarchy";
	protected static final String ATTRIBUTE_TYPE = "Attribute Type";
	protected static final String HIERARCHIES = "Hierarchies";
	protected static final String INCLUDE_ALL_LEGACY_ISSUES = "Include All Legacy Issues";
	protected static final String ECL = "ECL";
	protected static final String TEMPLATE = "Template";
	protected static final String TEMPLATE2 = "Template 2";
	protected static final String TEMPLATE_NAME = "TemplateName";
	protected static final String SERVER_URL = "ServerUrl";

	public static Gson gson;
	static {
		GsonBuilder gsonBuilder = new GsonBuilder();
		gsonBuilder.registerTypeAdapter(Relationship.class, new RelationshipSerializer());
		gsonBuilder.setPrettyPrinting();
		gsonBuilder.excludeFieldsWithoutExposeAnnotation();
		gson = gsonBuilder.create();
	}
	
	public enum ReportActionType {	API_ERROR, DEBUG_INFO, INFO, UNEXPECTED_CONDITION,
									CONCEPT_CHANGE_MADE, CONCEPT_ADDED, CONCEPT_INACTIVATED, CONCEPT_DELETED,
									AXIOM_CHANGE_MADE,
									DESCRIPTION_CHANGE_MADE, DESCRIPTION_ACCEPTABILIY_CHANGED, DESCRIPTION_REACTIVATED,
									DESCRIPTION_ADDED, DESCRIPTION_INACTIVATED, DESCRIPTION_DELETED,
									CASE_SIGNIFICANCE_CHANGE_MADE, MODULE_CHANGE_MADE, 
									RELATIONSHIP_ADDED, RELATIONSHIP_REPLACED, RELATIONSHIP_INACTIVATED, RELATIONSHIP_DELETED, RELATIONSHIP_MODIFIED, 
									RELATIONSHIP_GROUP_ADDED,RELATIONSHIP_GROUP_REMOVED,
									NO_CHANGE, VALIDATION_ERROR, VALIDATION_CHECK, SKIPPING,
									REFSET_MEMBER_REMOVED, UNKNOWN, RELATIONSHIP_REACTIVATED, ASSOCIATION_ADDED,
									INACT_IND_ADDED, INACT_IND_MODIFIED, ASSOCIATION_REMOVED};
									
	public enum Severity { NONE, LOW, MEDIUM, HIGH, CRITICAL }; 
	
	public Concept[] selfGroupedAttributes = new Concept[] { FINDING_SITE, CAUSE_AGENT, ASSOC_MORPH };

	protected boolean wideOutput = false;

	public String getScriptName() {
		return this.getClass().getSimpleName();
	}
	
	public String getAuthenticatedCookie() {
		return authenticatedCookie;
	}
	
	public static int getNextDryRunNum() {
		return ++dryRunCounter;
	}

	public void setAuthenticatedCookie(String authenticatedCookie) {
		this.authenticatedCookie = authenticatedCookie;
	}
	
	protected static String[] envKeys = new String[] {"local","dev","uat","prod","dev","dev","uat", "uat", "prod", "prod"};

	protected static String[] environments = new String[] {	"http://localhost:8080/",
															"https://dev-authoring.ihtsdotools.org/",
															"https://uat-authoring.ihtsdotools.org/",
															"https://prod-authoring.ihtsdotools.org/",
															"https://dev-bb18-ms-authoring.ihtsdotools.org/",
															"https://dev-snowstorm.ihtsdotools.org/",
															"https://uat-ms-authoring.ihtsdotools.org/",
															"https://uat-snowstorm.ihtsdotools.org/",
															"https://prod-ms-authoring.ihtsdotools.org/",
															"https://prod-snowstorm.ihtsdotools.org/"
	};
	
	public static void info (String msg) {
		logger.info(msg);
	}
	
	public static void debug (Object obj) {
		logger.debug(obj==null?"NULL":obj.toString());
	}
	
	public static void warn (Object obj) {
		logger.warn("*** " + (obj==null?"NULL":obj.toString()));
	}
	
	public static void error (Object obj, Exception e) {
		System.err.println ("*** " + (obj==null?"NULL":obj.toString()));
		if (e != null) 
			logger.error(org.apache.commons.lang.exception.ExceptionUtils.getStackTrace(e));
	}
	
	public static void print (Object msg) {
		System.out.print (msg.toString());
	}
	
	public static void println (Object msg) {
		System.out.println (msg.toString());
	}
	
	public static String getMessage (Exception e) {
		String msg = e.getMessage();
		Throwable cause = e.getCause();
		if (cause != null) {
			msg += " caused by " + cause.getMessage();
		}
		return msg;
	}
	
	protected void init(String[] args) throws TermServerScriptException {
		
		if (args.length < 3) {
			println("Usage: java <TSScriptClass> [-a author] [-n <taskSize>] [-r <restart position>] [-c <authenticatedCookie>] [-d <Y/N>] [-p <projectName>] -f <batch file Location>");
			println(" d - dry run");
			System.exit(-1);
		}
		boolean isProjectName = false;
		boolean isCookie = false;
		boolean isDryRun = false;
		boolean isRestart = false;
		boolean isInputFile = false;
		boolean isInputFile2 = false;
	
		for (String thisArg : args) {
			if (thisArg.equals("-p")) {
				isProjectName = true;
			} else if (thisArg.equals("-c")) {
				isCookie = true;
			} else if (thisArg.equals("-d")) {
				isDryRun = true;
			} else if (thisArg.equals("-f")) {
				isInputFile = true;
			} else if (thisArg.equals("-f2")) {
				isInputFile2 = true;
			} else if (thisArg.equals("-r")) {
				isRestart = true;
			} else if (isProjectName) {
				projectName = thisArg;
				isProjectName = false;
			} else if (isDryRun) {
				dryRun = thisArg.toUpperCase().equals("Y");
				isDryRun = false;
			} else if (isRestart) {
				restartPosition = Integer.parseInt(thisArg);
				isRestart = false;
			} else if (isInputFile) {
				inputFile = new File(thisArg);
				if (!inputFile.canRead()) {
					throw new TermServerScriptException ("Unable to read input file " + thisArg);
				}
				info ("Reading data from " + inputFile.getAbsolutePath());
				isInputFile = false;
			} else if (isInputFile2) {
				inputFile2 = new File(thisArg);
				if (!inputFile2.canRead()) {
					throw new TermServerScriptException ("Unable to read input file 2 " + thisArg);
				}
				isInputFile2 = false;
			}else if (isCookie) {
				authenticatedCookie = thisArg;
				isCookie = false;
			} 
		}
		checkSettingsWithUser(null);
		init();
	}
	
	private void init() throws TermServerScriptException {
		if (restartPosition == 0) {
			info ("Restart position given as 0 but line numbering starts from 1.  Starting at line 1.");
			restartPosition = 1;
		}
		
		//TODO Make calls through client objects rather than resty direct and remove this member 
		resty.withHeader("Cookie", authenticatedCookie);  
		scaClient = new AuthoringServicesClient(url, authenticatedCookie);
		tsClient = createTSClient(this.url, authenticatedCookie);
		boolean loadingRelease = false;
		//Recover the full project path from authoring services, if not already fully specified
		project = new Project();
		if (projectName.startsWith("MAIN")) {
			project.setBranchPath(projectName);
			if (projectName.equals("MAIN")) {
				project.setKey(projectName);
			} else {
				project.setKey(projectName.substring(projectName.lastIndexOf("/")));
			}
		} else if (StringUtils.isNumeric(projectName)) {
			info ("Loading release: " + projectName); 
			loadingRelease = true;
			project.setKey(projectName);
		} else {
			if (runStandAlone) {
				info ("Running stand alone. Guessing project path to be MAIN/" + projectName);
				project.setBranchPath("MAIN/" + projectName);
			} else {
				try {
					project = scaClient.getProject(projectName);
				} catch (RestClientException e) {
					throw new TermServerScriptException("Unable to recover project: " + projectName,e);
				}
			}
			project.setKey(projectName);
		}
		if (!loadingRelease) {
			info("Full path for projected determined to be: " + project.getBranchPath());
		}
	}

	protected void checkSettingsWithUser(JobRun jobRun) throws TermServerScriptException {
		int envChoice = NOT_SET;
		if (headlessEnvironment != null) {
			envChoice = headlessEnvironment;
		} else {
			info ("Select an environment ");
			for (int i=0; i < environments.length; i++) {
				println ("  " + i + ": " + environments[i]);
			}
			
			print ("Choice: ");
			String choice = STDIN.nextLine().trim();
			envChoice = Integer.parseInt(choice);
		}
		url = environments[envChoice];
		env = envKeys[envChoice];
		
		if (jobRun != null) {
			jobRun.setTerminologyServerUrl(url);
		}
	
		if (jobRun != null && !jobRun.getAuthToken().isEmpty()) {
			authenticatedCookie = jobRun.getAuthToken();
		} else if (authenticatedCookie == null || authenticatedCookie.trim().isEmpty()) {
			print ("Please enter your authenticated cookie for connection to " + url + " : ");
			authenticatedCookie = STDIN.nextLine().trim();
		}
		
		if (jobRun != null && !StringUtils.isEmpty(jobRun.getProject())) {
			projectName = jobRun.getProject();
		}
		
		if (headlessEnvironment == null) {
			print ("Specify Project " + (projectName==null?": ":"[" + projectName + "]: "));
			String response = STDIN.nextLine().trim();
			if (!response.isEmpty()) {
				projectName = response;
			}
		
			if (restartPosition != NOT_SET) {
				print ("Restarting from position [" +restartPosition + "]: ");
				response = STDIN.nextLine().trim();
				if (!response.isEmpty()) {
					restartPosition = Integer.parseInt(response);
				}
			}
		}
		
	}

	protected void init (JobRun jobRun) throws TermServerScriptException {
		this.url = jobRun.getTerminologyServerUrl();
		this.env = getEnv(url);
		this.jobRun = jobRun;
		EclCache.reset();
		authenticatedCookie = jobRun.getAuthToken();
		
		if (jobRun != null && !StringUtils.isEmpty(jobRun.getProject())) {
			projectName = jobRun.getProject();
		} else if ((jobRun == null && projectName == null) || 
				(jobRun != null && StringUtils.isEmpty(jobRun.getProject()))) {
			warn("No project specified, running against MAIN");
			projectName = "MAIN";
		}
		
		if (StringUtils.isEmpty(jobRun.getParamValue(SUB_HIERARCHY))) {
			jobRun.setParameter(SUB_HIERARCHY, ROOT_CONCEPT.toString());
		}
		subHierarchy = gl.getConcept(jobRun.getParamValue(SUB_HIERARCHY));
		
		String inputFileName = jobRun.getParamValue(INPUT_FILE);
		if (!StringUtils.isEmpty(inputFileName)) {
			inputFile = new File(inputFileName);
			if (!inputFile.canRead() || !inputFile.isFile()) {
				throw new TermServerScriptException("Unable to read specified file: " + inputFile);
			}
		}
		
		if (jobRun.getWhiteList() != null) {
			whiteListedConcepts = jobRun.getWhiteList().stream()
					.map( w -> gl.getConceptSafely(w.getSctId()))
					.collect(Collectors.toSet());
		}
		
		if (authenticatedCookie == null || authenticatedCookie.trim().isEmpty()) {
			throw new TermServerScriptException("Unable to proceed without an authenticated token/cookie");
		}
		
		init();
		
		if (projectName.equals("MAIN")) {
			//MAIN is not a project.  Recover Main metadata from branch
			project.setMetadata(tsClient.getBranch("MAIN").getMetadata());
		} else if (!StringUtils.isNumeric(projectName)) {
			//Not if we're loading a release or extension
			try {
				project = scaClient.getProject(projectName);
			} catch (RestClientException e) {
				throw new TermServerScriptException("Failed to recover project " + projectName, e);
			}
		}
	}
	
	private String getEnv(String terminologyServerUrl) throws TermServerScriptException {
		if (!terminologyServerUrl.startsWith(EXPECTED_PROTOCOL)) {
			throw new TermServerScriptException("Termserver URL should start with " + EXPECTED_PROTOCOL);
		}
		String url = terminologyServerUrl.substring(EXPECTED_PROTOCOL.length());
		//What's the first part of the address?
		String machineName = url.split("\\.")[0];
		//Find the last dash, to pick out the environment
		int lastDash = machineName.lastIndexOf("-");
		if (lastDash == NOT_SET) {
			return "prod";
		}
		return machineName.substring(0, lastDash);
	}

	public void postInit() throws TermServerScriptException {
		postInit(null, new String[] {headers + additionalReportColumns}, false);
	}
	
	public void postInit(boolean csvOutput) throws TermServerScriptException {
		postInit(null, new String[] {headers + additionalReportColumns}, csvOutput);
	}
	
	public void postInit(String[] tabNames, String[] columnHeadings, boolean csvOutput) throws TermServerScriptException {
		if (jobRun != null && jobRun.getParamValue(SUB_HIERARCHY) != null) {
			subHierarchy = gl.getConcept(jobRun.getMandatoryParamValue(SUB_HIERARCHY));
			//RP-4 And post that back in, so the FSN is always populated
			jobRun.setParameter(SUB_HIERARCHY, subHierarchy.toString());
		}
		if (!suppressOutput) {
			debug ("Initialising Report Manager");
			reportManager = ReportManager.create(this);
			if (tabNames != null) {
				reportManager.setTabNames(tabNames);
			}
			if (csvOutput) {
				reportManager.setWriteToFile(true);
				reportManager.setWriteToSheet(false);
			}
			if (this.wideOutput ) {
				ReportSheetManager.setMaxColumns(25);
			}
			getReportManager().initialiseReportFiles(columnHeadings);
			debug ("Report Manager initialisation complete");
		}
	}
	
	public void instantiate(JobRun jobRun, ApplicationContext appContext) {
		try {
			debug ("Instantiating " + this.getClass().getName() + " to process request for " + jobRun.getJobName());
			debug ("Application context has " + (appContext == null?"not " : "") + "been supplied");
			this.appContext = appContext;
			init(jobRun);
			loadProjectSnapshot(false);  //Load all descriptions
			postInit();
			if (!suppressOutput) {
				jobRun.setResultUrl(getReportManager().getUrl());
			}
			runJob();
			if (!suppressOutput) {
				flushFilesWithWait(false);  //Make sure we successfully write the last of the data before considering ourselves "Complete"
				jobRun.setStatus(JobStatus.Complete);
				Object issueCountObj = summaryDetails.get(ISSUE_COUNT);
				int issueCount = 0;
				if (issueCountObj != null && StringUtils.isNumeric(issueCountObj.toString())) {
					issueCount = Integer.parseInt(issueCountObj.toString());
				}
				jobRun.setIssuesReported(issueCount);
			}
		} catch (Exception e) {
			String msg = "Failed to complete " + jobRun.getJobName() + ExceptionUtils.getExceptionCause("", e);
			jobRun.setStatus(JobStatus.Failed);
			jobRun.setDebugInfo(msg);
			error(msg, e);
		} finally {
			finish();
		}
	}
	
	protected void runJob () throws TermServerScriptException {
		throw new TermServerScriptException("Override this method in concrete class");
	}
	
	protected static JobRun createJobRunFromArgs(String jobName, String[] args) {
		JobRun jobRun = JobRun.create(jobName, null);
		if (args.length < 3) {
			info("Usage: java <TSScriptClass> [-a author] [-n <taskSize>] [-r <restart position>] [-c <authenticatedCookie>] [-d <Y/N>] [-p <projectName>] -f <batch file Location>");
			info(" d - dry run");
			System.exit(-1);
		}
		
		for (int i=0; i<args.length; i++) {
			if (args[i].equals("-p")) {
				jobRun.setProject(args[i+1]);
			} else if (args[i].equals("-c")) {
				jobRun.setAuthToken(args[i+1]);
			} else if (args[i].equals("-d")) {
				jobRun.setParameter(DRY_RUN, args[i+1]);
			} else if (args[i].equals("-f")) {
				jobRun.setParameter(INPUT_FILE, args[i+1]);
			} else if (args[i].equals("-a")) {
				jobRun.setParameter(AUTHOR, args[i+1]);
				jobRun.setUser(args[i+1]);
			} else if (args[i].equals("-n")) {
				jobRun.setParameter(CONCEPTS_PER_TASK, args[i+1]);
			} else if (args[i].equals("-c")) {
				jobRun.setAuthToken(args[i+1]);
			}
		}
		
		return jobRun;
	}
	
	protected TermServerClient createTSClient(String url, String authenticatedCookie) {
		if (!authenticatedCookie.contains("ihtsdo=")) {
			throw new IllegalArgumentException("Malformed cookie detected.  Expected <env>-ihtsdo=<token> instead received: " + authenticatedCookie);
		}
		String contextPath = "snowstorm/snomed-ct";
		if (url.contains("-ms")) {
			contextPath = "snowowl/snomed-ct/v2";
		}
		return new TermServerClient(url + contextPath, authenticatedCookie);
	}
	
	protected void loadProjectSnapshot(boolean fsnOnly) throws TermServerScriptException, InterruptedException, IOException {
		getArchiveManager().loadProjectSnapshot(fsnOnly);
		//Reset the report name to null here as it will have been set by the Snapshot Generator
		setReportName(null);
	}
	
	protected void loadArchive(File archive, boolean fsnOnly, String fileType, Boolean isReleased) throws TermServerScriptException  {
		getArchiveManager().loadArchive(archive, fsnOnly, fileType, isReleased);
	}
	
	protected Concept loadConcept(String sctid, String branchPath) throws TermServerScriptException {
		if (dryRun) {
			//In a dry run situation, the task branch is not created so use the Project instead
			//But we'll clone it, so the object isn't confused with any local changes
			
			//If we're already working at project level, don't modify branchPath
			if (branchPath.indexOf("/") != branchPath.lastIndexOf("/")) {
				branchPath = branchPath.substring(0, branchPath.lastIndexOf("/"));
			}
			if (runStandAlone) {
				debug ("Loading: " + gl.getConcept(sctid) + " from local store");
				return gl.getConcept(sctid).cloneWithIds();
			}
		}
		Concept loadedConcept = loadConcept (tsClient, sctid, branchPath);
		return loadedConcept;
	}
	
	protected Concept loadConcept(Concept concept, String branchPath) throws TermServerScriptException {
		Concept loadedConcept = loadConcept(concept.getConceptId(), branchPath);
		//Detect attempt to load a deleted concept
		if (loadedConcept == null || StringUtils.isEmpty(loadedConcept.getConceptId())) {
			return null;
		}
		loadedConcept.setConceptType(concept.getConceptType());
		if (!dryRun) {
			//The loaded concept has some idea of the preferred term.  We'll have that now
			concept.setPreferredSynonym(loadedConcept.getPreferredSynonym());
		}
		//In any event, copy any issues over from the cached concept to the loaded one
		loadedConcept.setIssue(concept.getIssues());
		return loadedConcept;
	}
	
	protected Concept loadConcept(TermServerClient client, Concept concept, String branchPath) throws TermServerScriptException {
			return loadConcept(client, concept.getConceptId(), branchPath);
	}
	
	protected Concept loadConcept(TermServerClient client, String sctId, String branchPath) throws TermServerScriptException {
		Concept concept =  gl.getConcept(sctId);
		try {
			debug ("Loading: " + concept + " from TS branch " + branchPath);
			Concept loadedConcept = client.getConcept(sctId, branchPath);
			loadedConcept.setLoaded(true);
			convertAxiomsToRelationships(loadedConcept, loadedConcept.getClassAxioms());
			convertAxiomsToRelationships(loadedConcept, loadedConcept.getAdditionalAxioms());
			return loadedConcept;
		} catch (Exception e) {
			if (e.getMessage() != null && e.getMessage().contains("[404] Not Found") 
					|| e.getMessage().contains("404 Not Found")
					|| e.getMessage().contains("NOT_FOUND")) {
				debug ("Unable to find " + concept + " on branch " + branchPath);
				return null;
			}
			e.printStackTrace();
			String msg =  e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			throw new TermServerScriptException("Failed to recover " + concept + " from TS branch " + branchPath + ", due to: " + msg,e);
		}
	}
	
	protected RefsetMember loadRefsetMember(String uuid) throws TermServerScriptException {
		debug ("Loading refset member " + uuid + " from " + project.getBranchPath());
		return tsClient.getRefsetMember(uuid, project.getBranchPath());
	}
	
	protected RefsetMember loadPreviousRefsetMember(String uuid) throws TermServerScriptException {
		if (project.getPreviousBranchPath() == null) {
			String previousBranchPath = getArchiveManager().getPreviousBranch(project);
			project.setPreviousBranchPath(previousBranchPath);
		}
		debug ("Loading refset member " + uuid + " from " + project.getPreviousBranchPath());
		return tsClient.getRefsetMember(uuid, project.getPreviousBranchPath());
	}
	
	protected RefsetMember updateRefsetMember(RefsetMember rm) throws TermServerScriptException {
		debug((dryRun?"Dry run update of":"Updating") + " refset member " + rm.getMemberId());
		if (dryRun) {
			return rm;
		} else {
			return tsClient.updateRefsetMember(rm, project.getBranchPath());
		}
	}
	
	private void convertAxiomsToRelationships(Concept c, List<Axiom> axioms) throws TermServerScriptException {
		try {
			if (axioms != null) {
				for (Axiom axiom : axioms) {
					if (axiom.isActive()) {
						for (Relationship r : axiom.getRelationships()) {
							r.setAxiom(axiom);
							r.setSource(gl.getConcept(c.getConceptId()));
							r.setTarget(gl.getConcept(r.getTarget().getConceptId()));
							c.addRelationship(r);
						}
					}
				}
			}
		} catch (Exception e) {
			throw new TermServerScriptException ("Failed to convert axioms to relationships in " + c, e);
		}
	}

	protected Concept updateConcept(Task t, Concept c, String info) throws TermServerScriptException {
		try {
			if (!dryRun) {
				convertStatedRelationshipsToAxioms(c);
				if (validateConceptOnUpdate) {
					validateConcept(t, c);
				}
				
				debug ("Updating state of " + c + (info == null?"":info));
				return tsClient.updateConcept(c, t.getBranchPath());
			} else {
				return c;
			}
		} catch (ValidationFailure e) {
			throw e;
		} catch (Exception e) {
			String excpStr =  e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			String msg = "Failed to update " + c + " in TS due to " + excpStr;
			error (msg + " JSON = " + gson.toJson(c), e);
			throw new TermServerScriptException(msg,e); 
		}
	}
	
	private void validateConcept(Task t, Concept c) throws TermServerScriptException {
		//We need to populate new components with UUIDs for validation
		Concept uuidClone = c.cloneWithUUIDs();
		debug("Validating " + c);
		DroolsResponse[] validations = tsClient.validateConcept(uuidClone, t.getBranchPath());
		if (validations.length == 0) {
			debug("Validation clear: " + c);
		} else {
			Set<String> warningsReported = new HashSet<>();
			debug("Validation issues: " + validations.length);
			for (DroolsResponse response : validations) {
				if (response.getSeverity().equals(DroolsResponse.Severity.ERROR)) {
					throw new ValidationFailure(t,  c, "Drools error: " + response.getMessage());
				} else if (response.getSeverity().equals(DroolsResponse.Severity.WARNING)) {
					//Only report a particular warning text once
					if (!warningsReported.contains(response.getMessage())) {
						report (t, c, Severity.HIGH, ReportActionType.VALIDATION_CHECK, "Drools warning: " + response.getMessage());
						warningsReported.add(response.getMessage());
					}
				} else {
					throw new IllegalStateException("Unexpected drools response: " + response);
				}
			}
		}
	}

	/*private void ensureSaveEffective(Concept before, Concept after) throws TermServerScriptException {
		//Check we've got the same number of active / inactive descriptions / relationships
		int activeDescBefore = before.getDescriptions(ActiveState.ACTIVE).size();
		int activeDescAfter = after.getDescriptions(ActiveState.ACTIVE).size();
		
		int inactiveDescBefore = before.getDescriptions(ActiveState.INACTIVE).size();
		int inactiveDescAfter = after.getDescriptions(ActiveState.INACTIVE).size();

		int activeStdRelBefore = before.getRelationships(CharacteristicType.STATED_RELATIONSHIP, ActiveState.ACTIVE).size();
		int activeStdRelAfter = after.getRelationships(CharacteristicType.STATED_RELATIONSHIP, ActiveState.ACTIVE).size();
	
		int inactiveStdRelBefore = before.getRelationships(CharacteristicType.STATED_RELATIONSHIP, ActiveState.INACTIVE).size();
		int inactiveStdRelAfter = after.getRelationships(CharacteristicType.STATED_RELATIONSHIP, ActiveState.INACTIVE).size();
	
		int activeInfRelBefore = before.getRelationships(CharacteristicType.INFERRED_RELATIONSHIP, ActiveState.ACTIVE).size();
		int activeInfRelAfter = after.getRelationships(CharacteristicType.INFERRED_RELATIONSHIP, ActiveState.ACTIVE).size();
	
		int inactiveInfRelBefore = before.getRelationships(CharacteristicType.INFERRED_RELATIONSHIP, ActiveState.INACTIVE).size();
		int inactiveInfRelAfter = after.getRelationships(CharacteristicType.INFERRED_RELATIONSHIP, ActiveState.INACTIVE).size();
	
		if (	activeDescBefore != activeDescAfter ||
				inactiveDescBefore != inactiveDescAfter ||
				activeStdRelBefore != activeStdRelAfter ||
				inactiveStdRelBefore != inactiveStdRelAfter ||
				activeInfRelBefore != activeInfRelAfter ||
				inactiveInfRelBefore != inactiveInfRelAfter) {
			throw new TermServerScriptException("Concept has not fully saved to TS, although no error was reported");
		}
	}*/

	protected Concept createConcept(Task t, Concept c, String info) throws TermServerScriptException {
		if (c.getFsn() == null || c.getFsn().isEmpty()) {
			throw new ValidationFailure(c, "Cannot create concept with no FSN");
		}
		int attempt = 0;
		while (true) {
			try {
				//Copy across the concept type to the returned object - it isn't known to the TS
				ConceptType conceptType = c.getConceptType();
				Concept createdConcept = attemptConceptCreation(t,c,info);
				createdConcept.setConceptType(conceptType);
				return createdConcept;
			} catch (Exception e) {
				attempt++;
				String msg = "Failed to create " + c + " in TS due to " + e.getMessage();
				if (attempt <= 2) {
					incrementSummaryInformation("Concepts creation exceptions");
					warn (msg + " retrying...");
					/*try {
						//Thread.sleep(30 * 1000);
					} catch(InterruptedException ie) {
						throw new TermServerScriptException("Interruption during recovery of :" + msg ,e);
					}*/
				} else {
					throw new TermServerScriptException(msg ,e);
				}
			}
		}
	}
	
	private Concept attemptConceptCreation(Task t, Concept c, String info) throws Exception {
		debug ((dryRun ?"Dry run creating ":"Creating ") + c + info);
		convertStatedRelationshipsToAxioms(c);
		if (!dryRun) {
			validateConcept(t, c);
			c = tsClient.createConcept(c, t.getBranchPath());
		} else {
			c = c.clone("NEW_SCTID");
		}
		incrementSummaryInformation("Concepts created");
		return c;
	}

	private void convertStatedRelationshipsToAxioms(Concept c) {
		//We might have already done this if an error condition has occurred.
		//Skip if there are not stated relationships
		if (c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, ActiveState.BOTH).size() == 0) {
			return;
		}
		//In the case of an inactive concept, we'll inactivate any axioms
		if (c.isActive()) {
			for (Axiom a : c.getClassAxioms()) {
				a.clearRelationships();
			}
			
			//Do we have an existing axiom to use by default?
			Axiom a = c.getFirstActiveClassAxiom();
			a.setModuleId(c.getModuleId());
	
			//We'll remove the stated relationships as they get converted to the axiom
			List<Relationship> rels = c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, ActiveState.BOTH);
			for (Relationship rel : rels) {
				//Did this relationship already come from an axiom?
				//If not and it's inactive, leave it be
				if (!rel.fromAxiom() && !rel.isActive()) {
					continue;
				}
				//If so (or if active), put it back into one
				Axiom thisAxiom = rel.getAxiom() == null ? a : rel.getAxiom();
				
				//The definition status of the axiom needs to match that of the concept
				thisAxiom.setDefinitionStatus(c.getDefinitionStatus());
				
				//Don't add an inactive relationship to an active axiom
				if (thisAxiom.isActive() != rel.isActive()) {
					if (!rel.isActive()) {
						warn("Skipping axiomification of " + rel + " due to active axiom");
					} else {
						throw new IllegalStateException ("Active stated conflict between " + rel + " and " + thisAxiom);
					}
				}
				thisAxiom.getRelationships().add(rel);
				c.removeRelationship(rel);
			}
			
			for (Axiom thisAxiom : new ArrayList<>(c.getClassAxioms())) {
				if (thisAxiom.getRelationships().size() == 0) {
					//Has this axiom been released?  Remove if not and if it's empty
					if (StringUtils.isEmpty(thisAxiom.getId())) {
						c.getClassAxioms().remove(thisAxiom);
					} else {
						throw new IllegalStateException ("Axiom left with no relationships in " + c + ": " + thisAxiom);
					}
				}
			}
		} else {
			//Inactive concept, inactivate any axioms
			for (Axiom thisAxiom : c.getClassAxioms()) {
				thisAxiom.setActive(false);
			}
			//And remove relationships that have come from an axiom
			c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, ActiveState.BOTH)
				.stream()
				.forEach(r -> c.removeRelationship(r, true));   //Safe to remove these if published.
		}
	}
	

	protected void selfGroupAttributes(Task t, Concept c) {
		RelationshipGroup ungrouped = c.getRelationshipGroup(CharacteristicType.STATED_RELATIONSHIP, UNGROUPED);
		if (ungrouped != null) {
			for (Relationship r : ungrouped.getRelationships()) {
				for (Concept selfGrouped : selfGroupedAttributes) {
					if (r.getType().equals(selfGrouped)) {
						r.setGroupId(SnomedUtils.getFirstFreeGroup(c));
					}
				}
			}
		}
	}

	/**
	 * Creates a set of concepts based on a structure around the initial concept 
	 * ie parents as high as is required, siblings and children.
	 * @param initialConcept
	 * @return a list of the new concepts created.
	 */
	protected List<Concept> createConceptStructure(Concept initialConcept) {
		List<Concept> created = new ArrayList<>();
		return created;
	}
	
	protected int deleteConcept(Task t, Concept c) throws TermServerScriptException {
		try {
			debug ((dryRun ?"Dry run deleting ":"Deleting ") + c );
			if (!dryRun) {
				tsClient.deleteConcept(c.getConceptId(), t.getBranchPath());
			}
			return CHANGE_MADE;
		} catch (Exception e) {
			report (t, c, Severity.MEDIUM, ReportActionType.API_ERROR, "Failed to delete concept due to " + e.getMessage());
			return NO_CHANGES_MADE;
		}
	}
	
	protected int deleteReferenceSetMember(Task t, String uuid) throws TermServerScriptException {
		try {
			debug ((dryRun ?"Dry run deleting ":"Deleting ") + uuid );
			if (!dryRun) {
				tsClient.deleteRefsetMember(uuid, t.getBranchPath(), false);  //Don't force!
			}
			return CHANGE_MADE;
		} catch (Exception e) {
			report (t, null, Severity.MEDIUM, ReportActionType.API_ERROR, "Failed to delete refset member " + uuid + " due to " + e.getMessage());
			return NO_CHANGES_MADE;
		}
	}
	
	protected int inactivateRefsetMember(Task t, Concept c, Component i, String info) throws JSONException, TermServerScriptException {
		JSONObject inactivationJson = new JSONObject();
		inactivationJson.put("id", i.getId());
		inactivationJson.put("active", false);
		inactivationJson.put("commitComment", "PWI Duplicate Refset Member fix");
		debug ( (dryRun? "Dry run ":"") + "Updating state of " + c + info);
		if (!dryRun) {
			tsClient.updateRefsetMember(inactivationJson,  t.getBranchPath(), false); //Don't force delete
		}
		return CHANGE_MADE;
	}
	
	public Collection<Concept> findConcepts(String ecl) throws TermServerScriptException {
		return findConcepts(project.getBranchPath(), ecl, false, !safetyProtocolsEnabled(), true);
	}
	
	public Collection<Concept> findConcepts(String ecl, boolean quiet, boolean expectLargeResults) throws TermServerScriptException {
		return findConcepts(ecl, quiet, expectLargeResults, true);
	}
	
	public Collection<Concept> findConcepts(String ecl, boolean quiet, boolean expectLargeResults, boolean useLocalStoreIfSimple) throws TermServerScriptException {
		return findConcepts(project.getBranchPath(), ecl, quiet, expectLargeResults, useLocalStoreIfSimple);
	}
	
	public Collection<Concept> findConcepts(String branch, String ecl, boolean quiet, boolean expectLargeResults, boolean useLocalStoreIfSimple) throws TermServerScriptException {
		EclCache cache = EclCache.getCache(branch, tsClient, gson, gl, quiet);
		cache.engageSafetyProtocol(safetyProtocols);
		boolean wasCached = cache.isCached(ecl);
		Collection<Concept> concepts = cache.findConcepts(branch, ecl, expectLargeResults, useLocalStoreIfSimple); 
		int retry = 0;
		if (concepts.size() == 0 && ++retry < 3) {
			debug("No concepts returned. Double checking that result in 30s...");
			try { Thread.sleep(30*1000); } catch (Exception e) {}
			concepts = cache.findConcepts(branch, ecl, expectLargeResults, useLocalStoreIfSimple); 
		}
		
		//If this is the first time we've seen these results, check for duplicates
		if (!wasCached) {
			debug(concepts.size() + " concepts recovered.  Checking for duplicates...");
			//Failure in the pagination can cause duplicates.  Check for this
			Set<Concept> uniqConcepts = new HashSet<>(concepts);
			if (uniqConcepts.size() != concepts.size()) {
				warn("Duplicates detected " + concepts.size() + " vs " + uniqConcepts.size() + " - identifying...");
				//Work out what the actual duplication is
				for (Concept c : uniqConcepts) {
					concepts.remove(c);
				}
				for (Concept c : concepts) {
					warn ("Duplicate concept received from ECL: " + c);
				}
				throw new TermServerScriptException(concepts.size() + " duplicate concepts returned from ecl: " + ecl + " eg " + concepts.iterator().next());
			}
			debug("No duplicates detected.");
		}
		return concepts; 
	}

	protected List<Component> processFile() throws TermServerScriptException {
		return processFile(inputFile);
	}
	
	protected List<Component> processFile(File file) throws TermServerScriptException {
		Set<Component> allComponents= new LinkedHashSet<>();
		debug ("Loading input file " + file.getAbsolutePath());
		try {
			List<String> lines = Files.readLines(file, Charsets.UTF_8);
			lines = StringUtils.removeBlankLines(lines);
			
			//Are we restarting the file from some line number
			int startPos = (restartPosition == NOT_SET)?0:restartPosition - 1;
			List<Component> components;
			for (int lineNum = startPos; lineNum < lines.size(); lineNum++) {
				if (lineNum == 0  && inputFileHasHeaderRow) {
					continue; //skip header row  
				}
				String[] lineItems;
				if (inputFileDelimiter == CSV_FIELD_DELIMITER) {
					//File format Concept Type, SCTID, FSN with string fields quoted.  Strip quotes also.
					lineItems = splitCarefully(lines.get(lineNum));
				} else {
					lineItems = lines.get(lineNum).replace("\"", "").split(inputFileDelimiter);
				}
				if (lineItems.length >= 1) {
					try{
						components = loadLine(lineItems);
					} catch (Exception e) {
						throw new TermServerScriptException("Failed to load line " + lineNum,e);
					}
					if (components != null && components.size() > 0) {
						allComponents.addAll(components);
					} else {
						if (!expectNullConcepts) {
							debug ("Skipped line " + lineNum + ": '" + lines.get(lineNum) + "', malformed or not required?");
						}
					}
				} else {
					debug ("Skipping blank line " + lineNum);
				}
			}
			addSummaryInformation(CONCEPTS_IN_FILE, allComponents);

		} catch (FileNotFoundException e) {
			throw new TermServerScriptException("Unable to open input file " + file.getAbsolutePath(), e);
		} catch (IOException e) {
			throw new TermServerScriptException("Error while reading input file " + file.getAbsolutePath(), e);
		}
		return new ArrayList<Component>(allComponents);
	}
	
	/*
	 * Splits a line, ensuring that any commas that are within a quoted string are not treated as delimiters
	 * https://stackoverflow.com/questions/1757065/java-splitting-a-comma-separated-string-but-ignoring-commas-in-quotes
	 */
	private String[] splitCarefully(String line) {
		String otherThanQuote = " [^\"] ";
		String quotedString = String.format(" \" %s* \" ", otherThanQuote);
		String regex = String.format("(?x) "+ // enable comments, ignore white spaces
			",                         "+ // match a comma
			"(?=                       "+ // start positive look ahead
			"  (?:                     "+ //   start non-capturing group 1
			"    %s*                   "+ //     match 'otherThanQuote' zero or more times
			"    %s                    "+ //     match 'quotedString'
			"  )*                      "+ //   end group 1 and repeat it zero or more times
			"  %s*                     "+ //   match 'otherThanQuote'
			"  $                       "+ // match the end of the string
			")                         ", // stop positive look ahead
			otherThanQuote, quotedString, otherThanQuote);

		//And now remove the quotes
		String[] items = line.split(regex, -1);
		for (int i=0; i<items.length; i++) {
			items[i] = items[i].replace("\"", "");
		}
		return items;
	}

	public Project getProject() {
		return project;
	}
	
	public void setProject(Project project) {
		this.project = project;
	}

	public void startTimer() {
		startTime = new Date();
	}
	
	public void addSummaryInformation(String item, Object detail) {
		info(item + ": " + detail);
		summaryDetails.put(item, detail);
	}
	
	public void incrementSummaryInformation(String key) {
		if (!quiet) {
			incrementSummaryInformation(key, 1);
		}
	}
	
	public int getSummaryInformationInt(String key) {
		Object info = summaryDetails.get(key);
		if (info == null || !(info instanceof Integer)) {
			return 0;
		}
		return (Integer)info;
	}
	
	public void incrementSummaryInformationQuiet(String key) {
		//There are occasions where we can only capture all information when doing the first pass
		//When we're looking at ALL information eg which concepts do not require changes.
		if (quiet) {
			incrementSummaryInformation(key, 1);
		}
	}
	
	public void initialiseSummaryInformation(String key) {
		summaryDetails.put (key, new Integer(0));
	}
	
	public void incrementSummaryInformation(String key, int incrementAmount) {
		if (!summaryDetails.containsKey(key)) {
			summaryDetails.put (key, new Integer(0));
		}
		int newValue = ((Integer)summaryDetails.get(key)).intValue() + incrementAmount;
		summaryDetails.put(key, newValue);
	}
	
	public void flushFilesSoft() throws TermServerScriptException {
		getReportManager().flushFilesSoft();
	}
	
	public void flushFiles(boolean andClose, boolean withWait) throws TermServerScriptException {
		if (getRF2Manager() != null) {
			getRF2Manager().flushFiles(andClose);
		}
		if (getReportManager() != null) {
			getReportManager().flushFiles(andClose, withWait);
		}
	}
	
	public void flushFilesSafely(boolean andClose) {
		try {
			boolean andWait = false;
			flushFiles(andClose, andWait);
		} catch (Exception e) {
			error("Failed to flush files.", e);
		}
	}
	
	public void flushFilesWithWait(boolean andClose) {
		try {
			boolean andWait = true;
			flushFiles(andClose, andWait);
		} catch (Exception e) {
			error("Failed to flush files.", e);
		}
	}
	
	public void finish() {
		info (BREAK);

		Date endTime = new Date();
		if (startTime != null) {
			long diff = endTime.getTime() - startTime.getTime();
			recordSummaryText ("Completed processing in " + DurationFormatUtils.formatDuration(diff, "HH:mm:ss"));
			recordSummaryText ("Started at: " + startTime);
		}
		recordSummaryText ("Finished at: " + endTime);
		
		if (includeSummaryTab) {
			recordSummaryText("");
		}
		
		List<String> criticalIssues = new ArrayList<String>();
		for (Map.Entry<String, Object> summaryDetail : summaryDetails.entrySet()) {
			String key = summaryDetail.getKey();
			Object value = summaryDetail.getValue();
			String display = "";
			if (value instanceof Collection) {
				display += ((Collection<?>)value).size();
			} else if (key.startsWith(CRITICAL_ISSUE)) {
				criticalIssues.add(key + ": " + value.toString());
				continue;
			} else {
				display = value.toString();
			}
			recordSummaryText (key + (display.isEmpty()?"":": ") + display);
		}
		if (summaryDetails.containsKey("Tasks created") && summaryDetails.containsKey(CONCEPTS_TO_PROCESS) ) {
			if (summaryDetails.get(CONCEPTS_TO_PROCESS) != null &&  summaryDetails.get(CONCEPTS_TO_PROCESS) instanceof Collection) {
				double c = (double)((Collection<?>)summaryDetails.get(CONCEPTS_TO_PROCESS)).size();
				double t = (double)((Integer)summaryDetails.get("Tasks created")).intValue();
				double avg = Math.round((c/t) * 10) / 10.0;
				recordSummaryText ("Concepts per task: " + avg);
			}
		}
		
		if (criticalIssues.size() > 0) {
			recordSummaryText ("\nCritical Issues Encountered\n========================");
			for (String thisCriticalIssue : criticalIssues) {
				recordSummaryText(thisCriticalIssue);
			}
			recordSummaryText("Total Critical Issues Encountered: " + criticalIssues.size());
		}
		flushFilesSafely(true);
	}
	
	private synchronized void recordSummaryText(String msg) {
		info (msg);
		if (getReportManager() != null) {
			if (includeSummaryTab) {
				try {
					writeToReportFile (SECONDARY_REPORT, msg);
				} catch (Exception e) {
					error ("Failed to write summary info: " + msg, e);
				}
			}
		} else {
			info ("Unable to report: " + msg);
		}
	}
	
	protected void writeToRF2File(String fileName, Object[] columns) throws TermServerScriptException {
		getRF2Manager().writeToRF2File(fileName, columns);
	}
	
	protected void writeToReportFile(int reportIdx, String line) throws TermServerScriptException {
		if (getReportManager() == null) {
			throw new TermServerScriptException("Attempted to write to report before Report Manager is available. Check postInit() has been called.\n Message was " + line);
		}
		getReportManager().writeToReportFile(reportIdx, line);
	}
	
	protected void writeToReportFile(String line) throws TermServerScriptException {
		writeToReportFile(0, line);
	}
	

	public String getReportName() {
		if (reportName == null) {
			String fileName = SnomedUtils.deconstructFilename(inputFile)[1];
			String spacer = " ";
			reportName = getScriptName() + (fileName.isEmpty()?"" : spacer + fileName);
			try {
				if (subHierarchy == null && subHierarchyStr != null && !subHierarchyStr.contains(ROOT_CONCEPT.getConceptId())) {
					subHierarchy = gl.getConcept(subHierarchyStr);
				}
				
				if (subHierarchy != null && !subHierarchy.equals(ROOT_CONCEPT)) {
					reportName += spacer + subHierarchy.toStringPref();
				}
				
				if (subHierarchy == null && subHierarchyStr == null && subHierarchyECL != null) {
					//Take the first focus concept
					int cutPoint = subHierarchyECL.indexOf(":");
					if (cutPoint > NOT_SET) {
						int potentialCut = subHierarchyECL.indexOf("MINUS");
						if (potentialCut > NOT_SET && potentialCut < cutPoint) {
							cutPoint = potentialCut;
						}
						reportName += spacer + subHierarchyECL.subSequence(0, cutPoint);
					} else {
						reportName += spacer + gl.getConcept(subHierarchyECL.replaceAll("<", "").trim()).toStringPref();
					}
				}
				
			} catch (Exception e) {
				error ("Recoverable hiccup while setting report name",e);
			}
		}
		//Can't work out why we're seeing null in filenames here
		if (reportName.contains("null")) {
			debug ("TODO: Work out why we're seeing a null when we've recovered the concept from the GL?!");
		}
		
		return reportName;
	}

	protected String getPrettyHistoricalAssociation (Concept c) throws TermServerScriptException {
		String prettyString = "No association specified.";
		if (c.getAssociations(ActiveState.ACTIVE).size() > 0) {
			prettyString = " ";
			for (AssociationEntry assoc : c.getAssociations(ActiveState.ACTIVE)) {
				prettyString += SnomedUtils.deconstructFSN(gl.getConcept(assoc.getRefsetId()).getFsn())[0].replace(" association reference set", "");
				prettyString += " -> ";
				prettyString += gl.getConcept(assoc.getTargetComponentId());
			}
		}
		return prettyString;
	}

	public static List<Concept> asConcepts(Collection<Component> components) {
		List<Concept> concepts = new ArrayList<>();
		for (Component c : components) {
			concepts.add((Concept)c);
		}
		return concepts;
	}
	
	public static List<Component> asComponents(Collection<Concept> concepts) {
		List<Component> components = new ArrayList<>();
		for (Concept c : concepts) {
			components.add((Concept)c);
		}
		return components;
	}
	
	protected void addSynonym(Concept concept, String term, Acceptability acceptability, String[] dialects) {
		if (term.isEmpty()) {
			return;
		}
		Description d = new Description();
		d.setTerm(term);
		d.setActive(true);
		d.setType(DescriptionType.SYNONYM);
		d.setLang(LANG_EN);
		d.setCaseSignificance(StringUtils.calculateCaseSignificance(term));
		d.setAcceptabilityMap(SnomedUtils.createAcceptabilityMap(acceptability, dialects));
		d.setConceptId(concept.getConceptId());
		concept.addDescription(d);
	}
	
	public void report (Task t, Component c, ValidationFailure v) throws TermServerScriptException {
		report (t, c, v.severity, v.reportActionType, v.getMessage());
	}

	public void report(Task task, Component component, Severity severity, ReportActionType actionType, Object... details) throws TermServerScriptException {
		if (quiet) {
			return;
		}
		if (component != null) {
			if (severity.equals(Severity.CRITICAL)) {
				String key = CRITICAL_ISSUE + " encountered for " + component.toString();
				String value = "";
				boolean firstDetail = true;
				for (Object detail : details) {
					if (detail instanceof Object[]) {
						Object[] arr = (Object[]) detail;
						for (Object obj : arr) {
							value += obj + ", ";
						}
					} else {
						if (firstDetail)
							value += detail;
						else {
							firstDetail = false;
							if (detail != null && !detail.toString().isEmpty()) {
								value += ", " + detail;
							}
						}
					}
				}
				addSummaryInformation(key, value);
				info ( key + " : " + value);
			}
		}
		String key = (task == null? "" :  task.getKey());
		String desc = (task == null? "" :  task.getSummary());
		String name = (component == null ? "" : component.getReportedName());
		String type = (component == null ? "" : component.getReportedType());
		String id = (component == null ? "" : component.getId());
		StringBuffer sb = new StringBuffer();
		
		sb.append(key + COMMA + desc + COMMA + id + COMMA_QUOTE)
		.append( name + QUOTE_COMMA);
		if (stateComponentType) {
			sb.append(type + COMMA );
		}
		sb.append( severity + COMMA + actionType );
		for (Object detail : details) {
			if (detail instanceof Object[]) {
				Object[] arr = (Object[]) detail;
				for (Object obj : arr) {
					sb.append(COMMA_QUOTE + obj + QUOTE);
				}
			} else {
				sb.append(COMMA_QUOTE + detail + QUOTE);
			}
		}
		writeToReportFile (sb.toString());
		incrementSummaryInformation("Report lines written");
	}
	

	protected void report (Concept c, Object...details) throws TermServerScriptException {
		report (PRIMARY_REPORT, c, details);
	}
	
	public void report (int reportIdx, Concept c, Object...details) throws TermServerScriptException {
		//Have we whiteListed this concept?
		if (whiteListedConcepts.contains(c)) {
			String detailsStr = details == null ? "No Details" : Arrays.asList(details).stream().map(o -> o.toString()).collect(Collectors.joining(", "));
			warn ("Ignoring whiteListed concept: " + c + " :  " + detailsStr);
			incrementSummaryInformation(WHITE_LISTED_COUNT);
		} else {
			String[] conceptFields = new String[3];
			if (reportNullConcept || c != null) {
				conceptFields[0] = c == null?"": QUOTE + c.getConceptId() + QUOTE;
				conceptFields[1] = c == null?"":c.getFsn();
				
				if (c != null && !StringUtils.isEmpty(c.getFsn())) {
					conceptFields[2] = SnomedUtils.deconstructFSN(c.getFsn())[1];
					if (conceptFields[2] == null) {
						conceptFields[2] = " ";
					}
				} 
			}
			report (reportIdx, conceptFields, details);
		}
	}
	
	protected void report (int reportIdx, Object...details) throws TermServerScriptException {
		StringBuffer sb = new StringBuffer();
		boolean isFirst = true;
		for (Object detail : details) {
			if (detail == null) {
				detail = "";
			}
			if (detail instanceof Boolean) {
				detail = ((Boolean)detail)?"Y":"N";
			}
			boolean isNumeric = StringUtils.isNumeric(detail.toString()) || detail.toString().startsWith(QUOTE);
			String prefix = isFirst ? QUOTE : COMMA_QUOTE;
			if (isNumeric) {
				prefix = isFirst ? "" : COMMA;
			}
			if (detail instanceof String[]) {
				String[] arr = (String[]) detail;
				for (String str : arr) {
					boolean isNestedNumeric = false;
					if (str != null) {
						isNestedNumeric = StringUtils.isNumeric(str) || str.startsWith(QUOTE);
					}
					sb.append((isNestedNumeric?"":prefix) + str + (isNestedNumeric?"":QUOTE));
					prefix = COMMA_QUOTE;
				}
			} else if (detail instanceof Object []) {
				addObjectArray(sb,detail, prefix, isNumeric);
			} else if (detail instanceof int[]) {
				prefix = isFirst ? "" : COMMA;
				boolean isNestedFirst = true;
				int[] arr = (int[]) detail;
				for (int i : arr) {
					sb.append(isNestedFirst?"":COMMA);
					sb.append(prefix + i );
					isNestedFirst = false;
				}
				
			} else {
				sb.append(prefix + detail + (isNumeric?"":QUOTE));
			}
			isFirst = false;
		}
		
		writeToReportFile (reportIdx, sb.toString());
		incrementSummaryInformation("Report lines written");
	}
	
	private void addObjectArray(StringBuffer sb, Object detail, String prefix, boolean isNumeric) {
		Object[] arr = (Object[]) detail;
		for (Object obj : arr) {
			if (obj instanceof String[] || obj instanceof Object[]) {
				addObjectArray(sb,obj, prefix, isNumeric);
			} else if (obj instanceof int[]) {
				for (int data : ((int[])obj)) {
					sb.append(COMMA + data);
				}
			} else {
				if (obj instanceof Boolean) {
					obj = ((Boolean)obj)?"Y":"N";
				}
				String data = (obj==null?"":obj.toString());
				sb.append(prefix + data + (isNumeric?"":QUOTE));
				prefix = COMMA_QUOTE;
			}
		}
	}

	protected void countIssue(Concept c) {
		if (c==null || !whiteListedConcepts.contains(c)) {
			incrementSummaryInformation(ISSUE_COUNT);
		}
	}

	protected List<Concept> determineProximalPrimitiveParents(Concept c) throws TermServerScriptException {
		//Filter for only the primitive ancestors
		//Sort to work with the lowest level concepts first for efficiency
		List<Concept> primitiveAncestors = gl.getAncestorsCache().getAncestors(c).stream()
											.filter(ancestor -> ancestor.getDefinitionStatus().equals(DefinitionStatus.PRIMITIVE))
											.sorted((c1, c2) -> Integer.compare(c2.getDepth(), c1.getDepth()))
											.collect(Collectors.toList());
		
		//Now which of these primitive concepts do not subsume others?
		Set<Concept> subsumers = new HashSet<>();
		for (Concept thisAncestor : primitiveAncestors) {
			//Skip any that have already been identified as subsumers
			if (!subsumers.contains(thisAncestor)) {
				//Does thisAncestor's ancestors contain any of the other candidates?
				Set<Concept> subsumesThisAncestor = thisAncestor.getAncestors(NOT_SET);
				subsumesThisAncestor.retainAll(primitiveAncestors);
				subsumers.addAll(subsumesThisAncestor);
			}
		}
		//Now remove all subsumers from our list, to leave the most specific concepts
		primitiveAncestors.removeAll(subsumers);
		return primitiveAncestors;
	}
	
	public void setQuiet(boolean quiet) {
		this.quiet = quiet;
	}

	public String getEnv() {
		return env;
	}

	public GraphLoader getGraphLoader() {
		return gl;
	}

	public TermServerClient getTSClient() {
		return tsClient;
	}

	public ArchiveManager getArchiveManager() {
		return ArchiveManager.getArchiveManager(this, appContext);
	}

	public File getInputFile() {
		return inputFile;
	}

	public ReportManager getReportManager() {
		return reportManager;
	}
	
	public RF2Manager getRF2Manager() {
		if (rf2Manager == null) {
			rf2Manager = new RF2Manager();
		}
		return rf2Manager;
	}

	public void setReportManager(ReportManager reportManager) {
		this.reportManager = reportManager;
	}
	
	public void setExclusions(String[] exclusions) throws TermServerScriptException {
		this.excludeHierarchies = exclusions;
	}
	
	public Integer countAttributes(Concept c, CharacteristicType cType) {
		int attributeCount = 0;
		for (Relationship r : c.getRelationships(cType, ActiveState.ACTIVE)) {
			if (!r.getType().equals(IS_A)) {
				attributeCount++;
			}
		}
		return attributeCount;
	}

	protected List<Component> loadLine(String[] lineItems) throws TermServerScriptException {
		return null;
	}

	public JobRun getJobRun() {
		return jobRun;
	}
	
	public void setReportName(String reportName) {
		this.reportName = reportName;
	}
	
	public boolean safetyProtocolsEnabled() {
		return safetyProtocols;
	}
	
	public void offlineMode(boolean offline) {
		if (offline) {
			getArchiveManager().setAllowStaleData(true);
		}
		this.offlineMode = offline;
	}
	
	public boolean isOffline() {
		return this.offlineMode;
	}

	public static void runHeadless(Integer envNum) {
		headlessEnvironment = envNum;
	}
	
	protected boolean inScope(Component c) {
		//Do we have a default module id ie for a managed service project?
		if (project.getMetadata() != null && project.getMetadata().getDefaultModuleId() != null) {
			return c.getModuleId().equals(project.getMetadata().getDefaultModuleId());
		}
		return true;
	}
	
	protected boolean isMS() {
		//Do we have a default module id ie for a managed service project?
		if (project.getMetadata() != null && project.getMetadata().getDefaultModuleId() != null) {
			return !project.getMetadata().getDefaultModuleId().equals(SCTID_CORE_MODULE);
		}
		return false;
	}
	
	public static void run(Class<? extends JobClass> jobClazz, String[] args, Map<String, String> parameters) throws TermServerScriptException {
		JobRun jobRun = createJobRunFromArgs(jobClazz.getSimpleName(), args);
		if (parameters != null) {
			for (Map.Entry<String, String> entry : parameters.entrySet()) {
				jobRun.setParameter(entry.getKey(), entry.getValue());
			}
		}
		JobClass job = null;
		try {
			job = jobClazz.newInstance();
			((TermServerScript)job).checkSettingsWithUser(jobRun);
		} catch ( InstantiationException | IllegalAccessException e) {
			throw new TermServerScriptException("Unable to instantiate " + jobClazz.getSimpleName(), e);
		}
		job.instantiate(jobRun, (ApplicationContext)null);
	}

	public boolean getManyTabOutput() {
		return manyTabOutput;
	}

	protected  String getDependencyArchive() {
		return dependencyArchive;
	}
	
}

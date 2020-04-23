package org.ihtsdo.termserver.scripting;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.*;

import org.apache.commons.io.FileUtils;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Project;
import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.client.TermServerClient.*;
import org.ihtsdo.termserver.scripting.dao.ArchiveDataLoader;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.snapshot.SnapshotGenerator;
import org.ihtsdo.termserver.scripting.util.ExceptionUtils;
import org.ihtsdo.termserver.scripting.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class ArchiveManager implements RF2Constants {
	
	static ArchiveManager singleton;

	@Autowired
	private ArchiveDataLoader archiveDataLoader;
	
	protected String dataStoreRoot = "";
	protected GraphLoader gl;
	protected TermServerScript ts;
	protected ApplicationContext appContext;
	private boolean allowStaleData = false;
	private boolean loadDependencyPlusExtensionArchive = false;
	private boolean loadEditionArchive = false;
	private boolean populateHierarchyDepth = true;  //Term contains X needs this
	private boolean populateReleasedFlag = false;
	private boolean populatePreviousTransativeClosure = false;
	private boolean releasedFlagPopulated = false;
	
	private Project currentlyHeldInMemory;
	ZoneId utcZoneID= ZoneId.of("Etc/UTC");
	SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
	
	public static ArchiveManager getArchiveManager(TermServerScript ts, ApplicationContext appContext) {
		if (singleton == null) {
			singleton = new ArchiveManager();
			singleton.appContext = appContext;
		}
		singleton.ts = ts;
		singleton.gl = ts.getGraphLoader();
		return singleton;
	}
	
	@EventListener(ApplicationReadyEvent.class)
	public void init() {
		singleton = this;
	}
	
	private ArchiveManager () {
		//Only access via singleton above
	}
	
	protected void info(String msg) {
		TermServerScript.info(msg);
	}
	
	protected void debug(String msg) {
		TermServerScript.debug(msg);
	}
	
	public static void print (Object msg) {
		System.out.print (msg.toString());
	}
	
	protected Branch loadBranch(Project project) throws TermServerScriptException {
		String branchPath = project.getBranchPath();
		String server = "uknown";
		try {
			debug ("Checking TS branch metadata: " + branchPath);
			server = ts.getTSClient().getUrl();
			Branch branch = ts.getTSClient().getBranch(branchPath);
			//If metadata is empty, or missing previous release, recover parent
			//But timestamp will remain a property of the branch
			//But not if we're already on MAIN and it's STILL missing!
			while (branch.getMetadata() == null || branch.getMetadata().getPreviousRelease() == null) {
				if (branchPath.equals("MAIN")) {
					throw new TermServerScriptException("Metadata data missing in MAIN");
				}
				branchPath = getParentBranch(branchPath);
				Branch parent = loadBranch(new Project().withBranchPath(branchPath));
				branch.setMetadata(parent.getMetadata());
			}
			return branch;
		} catch (Exception e) {
			if (e.getMessage().contains("[404] Not Found")) {
				debug ("Unable to find branch " + branchPath);
				return null;
			}
			throw new TermServerScriptException("Failed to recover " + project + " from TS (" + server + ") due to " + e.getMessage(),e);
		}
	}
	
	private String getParentBranch(String branchPath) {
		//Do we have a path?
		int lastSlash = branchPath.lastIndexOf("/");
		if (lastSlash == NOT_FOUND) {
			if (branchPath.equals("MAIN")) {
				return branchPath;
			} else {
				throw new IllegalStateException ("Root level branch is not MAIN: " + branchPath);
			}
		}
		return branchPath.substring(0, lastSlash);
	}

	public String getPreviousPreviousBranch(Project project) throws TermServerScriptException {
		Branch branch = loadBranch(project);
		String previousRelease = branch.getMetadata().getPreviousRelease();
		try {
			List<CodeSystem> codeSystems = ts.getTSClient().getCodeSystemVersions();
			//Filter out anything that's not a release date, then sort descending
			List<CodeSystem> releases = codeSystems.stream()
			.sorted(Comparator.comparing(CodeSystem::getEffectiveDate).reversed())
			.collect(Collectors.toList());
			
			if (releases.size() < 2) {
				throw new TermServerScriptException("Less than 2 previous releases detected");
			}
			if (!releases.get(0).getEffectiveDate().toString().equals(previousRelease)) {
				TermServerScript.warn("Check here - unexpected previous release: " +  releases.get(0).getEffectiveDate() + " expected " + previousRelease);
			}
			return releases.get(1).getBranchPath();
		} catch (Exception e) {
			throw new TermServerScriptException("Failed to recover child branches due to " + e.getMessage(),e);
		}
	}
	
	public String getPreviousBranch(Project project) throws TermServerScriptException {
		Branch branch = loadBranch(project);
		String previousRelease = branch.getMetadata().getPreviousRelease();
		try {
			List<CodeSystem> codeSystems = ts.getTSClient().getCodeSystemVersions();
			//Filter out anything that's not a release date, then sort descending
			List<CodeSystem> releases = codeSystems.stream()
			.sorted(Comparator.comparing(CodeSystem::getEffectiveDate).reversed())
			.collect(Collectors.toList());
			
			if (releases.size() < 2) {
				throw new TermServerScriptException("Less than 2 previous releases detected");
			}
			if (!releases.get(0).getEffectiveDate().toString().equals(previousRelease)) {
				throw new TermServerScriptException("Check here - unexpected previous release: " +  releases.get(0).getEffectiveDate() + " expected " + previousRelease);
			}
			return releases.get(0).getBranchPath();
		} catch (Exception e) {
			throw new TermServerScriptException("Failed to recover child branches due to " + e.getMessage(),e);
		}
	}

	public void loadProjectSnapshot(boolean fsnOnly) throws TermServerScriptException {
		try {
			if (loadDependencyPlusExtensionArchive) {
				if (StringUtils.isEmpty(ts.getDependencyArchive())) {
					throw new TermServerScriptException("Told to load dependency + extension but no dependency package specified");
				} else {
					File dependency = new File ("releases/" + ts.getDependencyArchive());
					if (dependency.exists()) {
						loadArchive(dependency, fsnOnly, "Snapshot", true);
					} else {
						throw new TermServerScriptException("Dependency Package " + dependency.getAbsolutePath() + " does not exist");
					}
				}
				
			}
			
			
			//If the project specifies its a .zip file, that's another way to know we're loading an edition
			String fileExt = ".zip";
			if (ts.getProject().getKey().endsWith(fileExt)) {
				loadEditionArchive = true;
			}
			
			//Look for an expanded directory by preference
			File snapshot = getSnapshotPath();
			if (!snapshot.exists() && !snapshot.getName().endsWith(fileExt)) {
				//Otherwise, do we have a zip file to play with?
				snapshot = new File (snapshot.getPath() + fileExt);
			}
			
			if (!snapshot.exists()) {
				//If it doesn't exist as a zip file locally either, we can try downloading it from S3
				try {
					String cwd = new File("").getAbsolutePath();
					TermServerScript.info(snapshot + " not found locally in " + cwd + ", attempting to download from S3.");
					getArchiveDataLoader().download(snapshot);
				} catch (TermServerScriptException e) {
					info("Could not find " + snapshot.getName() + " in S3.");
				}
			}
			
			boolean originalStateDataFlag = allowStaleData;
			//If we're loading a particular release, it will be stale
			if (loadEditionArchive || StringUtils.isNumeric(ts.getProject().getKey())) {
				loadEditionArchive = true;
				allowStaleData = true;
				if (loadEditionArchive && !snapshot.exists()) {
					throw new TermServerScriptException ("Could not find " + snapshot + " to import");
				}
			}
			
			Branch branch = null;
			//Are we lacking data, or is our data out of date?  
			boolean isStale = false;
			if (snapshot.exists() && !allowStaleData) {
				branch = loadBranch(ts.getProject());
				isStale = checkIsStale(ts, branch, snapshot);
				if (isStale) {
					TermServerScript.warn(ts.getProject() + " snapshot held locally is stale.  Requesting delta to rebuild...");
				} else {
					TermServerScript.debug(ts.getProject() + " snapshot held locally is sufficiently recent");
				}
			}
			if (true);
			if (!snapshot.exists() || 
					(isStale && !allowStaleData) || 
					(populateReleasedFlag && !releasedFlagPopulated && !loadEditionArchive) ||
					(populatePreviousTransativeClosure && gl.getPreviousTC() == null)) {
				
				if (populateReleasedFlag && !releasedFlagPopulated && !loadEditionArchive) {
					info("Generating fresh snapshot because 'released' flag must be populated");
				} else if (populatePreviousTransativeClosure && gl.getPreviousTC() == null) {
					info("Generating fresh snapshot because previous transative closure must be populated");
				}
				gl.reset();
				generateSnapshot (ts.getProject());
				releasedFlagPopulated=true;
				//We don't need to load the snapshot if we've just generated it
			} else {
				//We might already have this project in memory
				if (currentlyHeldInMemory != null && currentlyHeldInMemory.equals(ts.getProject())) {
					info (ts.getProject() + " already held in memory, no need to reload.  Resetting any issues held against components...");
					gl.makeReady();
				} else {
					if (currentlyHeldInMemory != null) {
						//Make sure the Graph Loader is clean if we're loading a different project
						info (currentlyHeldInMemory.getKey() + " being wiped to make room for " + ts.getProject());
						gl.reset();
						System.gc();
						releasedFlagPopulated = false;
					}
					//Do we also need a fresh snapshot here so we can have the 'released' flag?
					//If we're loading an edition archive then that is - by definition all released.
					if (populateReleasedFlag && !releasedFlagPopulated && !loadEditionArchive) {
						info("Generating fresh snapshot (despite having a non-stale on disk) because 'released' flag must be populated");
						gl.reset();
						generateSnapshot (ts.getProject());
						releasedFlagPopulated=true;
					} else {
						info ("Loading snapshot archive contents into memory...");
						try {
							//This archive is 'current state' so we can't know what is released or not
							//Unless it's an edition archive
							releasedFlagPopulated = loadEditionArchive;
							//We only know if the components are released when loading an edition archive
							Boolean isReleased = loadEditionArchive ? true : null;
							loadArchive(snapshot, fsnOnly, "Snapshot", isReleased);
						} catch (Exception e) {
							TermServerScript.error ("Non-viable snapshot encountered (Exception: " + e.getMessage()  +").", e);
							if (!snapshot.getName().startsWith("releases/")) {
								TermServerScript.info ("Deleting " + snapshot);
								try {
									if (snapshot.isFile()) {
										snapshot.delete();
									} else if (snapshot.isDirectory()) {
										FileUtils.deleteDirectory(snapshot);
									} else {
										throw new TermServerScriptException (snapshot + " is neither file nor directory.");
									}
								} catch (Exception e2) {
									TermServerScript.warn("Failed to delete snapshot " + snapshot + " due to " + e2);
								}
							} else {
								TermServerScript.info ("Not deleting " + snapshot + " as it's a release.");
							}
							//We were trying to load the archive from disk.  If it's been created from a delta, we can try that again
							//Next time round the snapshot on disk won't be detected and we'll take a different code path
							if (!loadEditionArchive) {
								TermServerScript.warn("Attempting to regenerate...");
								loadProjectSnapshot(fsnOnly);
							}
						}
					}
				}
			}
			currentlyHeldInMemory = ts.getProject();
			allowStaleData = originalStateDataFlag;
		} catch (Exception e) {
			String msg = ExceptionUtils.getExceptionCause("Unable to load " + ts.getProject(), e);
			throw new TermServerScriptException (msg, e);
		}
		info ("Snapshot loading complete");
	}
	
	private boolean checkIsStale(TermServerScript ts, Branch branch, File snapshot) throws IOException {
		Date branchHeadTime = new Date(branch.getHeadTimestamp());
		BasicFileAttributes attr = java.nio.file.Files.readAttributes(snapshot.toPath(), BasicFileAttributes.class);
		LocalDateTime snapshotCreation = LocalDateTime.ofInstant(Instant.ofEpochMilli(attr.creationTime().toMillis()), ZoneId.systemDefault());
		//What timezone is that in?
		TimeZone localZone = TimeZone.getDefault();
		ZonedDateTime snapshotCreationLocal = ZonedDateTime.of(snapshotCreation, localZone.toZoneId());
		ZonedDateTime snapshotCreationUTC = snapshotCreationLocal.withZoneSameInstant(utcZoneID);
		ZonedDateTime branchHeadUTC = ZonedDateTime.ofInstant(branchHeadTime.toInstant(), utcZoneID);
		TermServerScript.debug("Comparing branch time: " + branchHeadUTC + " to local " + snapshot.getName() + " snapshot time: " + snapshotCreationUTC);
		return branchHeadUTC.compareTo(snapshotCreationUTC) > 0;
	}

	private void generateSnapshot(Project project) throws TermServerScriptException, IOException {
		File snapshot = getSnapshotPath();
		//Delete the current snapshot if it exists - will be stale
		if (snapshot.isDirectory()) {
			FileUtils.deleteDirectory(snapshot);
		} else {
			java.nio.file.Files.deleteIfExists(snapshot.toPath());
		}
		
		ensureProjectMetadataPopulated(project);
	
		File previous = new File (dataStoreRoot + "releases/"  + project.getMetadata().getPreviousPackage());
		if (!previous.exists()) {
			getArchiveDataLoader().download(previous);
		}
		TermServerScript.info("Building snapshot release based on previous: " + previous);
		
		//In the case of managed service, we will also have a dependency package
		File dependency = null;
		if (project.getMetadata().getDependencyPackage() != null) {
			dependency = new File (dataStoreRoot + "releases/"  + project.getMetadata().getDependencyPackage());
			if (!dependency.exists()) {
				getArchiveDataLoader().download(dependency);
			}
			TermServerScript.info("Building Extension snapshot release also based on dependency: " + dependency);
		}
		
		//Now we need a recent delta to add to it
		File delta = generateDelta(project);
		SnapshotGenerator snapshotGenerator = new SnapshotGenerator();
		snapshotGenerator.setProject(ts.getProject());
		snapshotGenerator.leaveArchiveUncompressed();
		snapshotGenerator.setOutputDirName(snapshot.getPath());
		snapshotGenerator.generateSnapshot(dependency, previous, delta, snapshot);
	}
	
	private ArchiveDataLoader getArchiveDataLoader() throws TermServerScriptException {
		if (archiveDataLoader == null) {
			if (appContext == null) {
				TermServerScript.info("No ArchiveData loader configured, creating one locally...");
				archiveDataLoader = ArchiveDataLoader.create();
			} else {
				archiveDataLoader = appContext.getBean(ArchiveDataLoader.class);
			}
		}
		return archiveDataLoader;
	}

	public File generateDelta(Project project) throws IOException, TermServerScriptException {
		File delta = File.createTempFile("delta_export-", ".zip");
		delta.deleteOnExit();
		ts.getTSClient().export(project.getBranchPath(), null, ExportType.UNPUBLISHED, ExtractType.DELTA, delta);
		return delta;
	}

	private void ensureProjectMetadataPopulated(Project project) throws TermServerScriptException {
		if (project.getMetadata() == null || project.getMetadata().getPreviousPackage() == null) {
			boolean metadataPopulated = false;
			String branchPath = project.getBranchPath();
			while (!metadataPopulated) {
				Branch branch = ts.getTSClient().getBranch(branchPath);
				project.setMetadata(branch.getMetadata());
				if (project.getMetadata() != null && project.getMetadata().getPreviousPackage() != null) {
					metadataPopulated = true;
				} else {
					int cutPoint = branchPath.lastIndexOf("/");
					if (cutPoint == NOT_FOUND) {
						throw new TermServerScriptException("Insufficient metadata found for project " + project.getKey() + " including ancestors to " + branchPath);
					} else {
						branchPath = branchPath.substring(0, cutPoint);
					}
				}
			}
		}
	}

	private File getSnapshotPath() {
		//If the project specifies its a .zip file, that's another way to know we're loading an edition
		String fileExt = ".zip";
		String projectKey = ts.getProject().getKey();
		if (loadEditionArchive || 
				StringUtils.isNumeric(projectKey) ||
				projectKey.endsWith(fileExt)) {
			if (projectKey.endsWith(fileExt)) {
				fileExt = "";
			}
			return new File (dataStoreRoot + "releases/" + projectKey + fileExt);
		} else {
			//Do we have a release effective time as a project?  Or a branch release
			String releaseBranch = detectReleaseBranch(projectKey);
			if (releaseBranch != null) {
				return new File (dataStoreRoot + "releases/" + releaseBranch + ".zip");
			} else  {
				return new File (dataStoreRoot + "snapshots/" + projectKey + "_" + ts.getEnv());
			}
		}
	}

	public String detectReleaseBranch(String projectKey) {
		String releaseBranch = projectKey.replace("MAIN/", "").replace("-", "");
		return StringUtils.isNumeric(releaseBranch) ? releaseBranch : null;
	}

	protected void loadArchive(File archive, boolean fsnOnly, String fileType, Boolean isReleased) throws TermServerScriptException {
		try {
			boolean isDelta = (fileType.equals(DELTA));
			//Are we loading an expanded or compressed archive?
			if (archive.isDirectory()) {
				loadArchiveDirectory(archive, fsnOnly, fileType, isDelta, isReleased);
			} else if (archive.getPath().endsWith(".zip")) {
				TermServerScript.debug("Loading archive file: " + archive);
				loadArchiveZip(archive, fsnOnly, fileType, isDelta, isReleased);
			} else {
				throw new TermServerScriptException("Unrecognised archive : " + archive);
			}
			
			if (gl.getAllConcepts().size() < 300000) {
				throw new TermServerScriptException("Insufficient number of concepts loaded " + gl.getAllConcepts().size() + " - Snapshot archive damaged?");
			}
			
			if (!fsnOnly) {  
				//Check that we've got some descriptions to be sure we've not been given
				//a malformed, or classification style archive.
				debug("Checking first 100 concepts for integrity");
				List<Description> first100Descriptions = gl.getAllConcepts()
						.stream()
						.limit(100)
						.flatMap(c -> c.getDescriptions().stream())
						.collect(Collectors.toList());
				if (first100Descriptions.size() < 100) {
					throw new TermServerScriptException("Failed to find sufficient number of descriptions - classification archive used? Deleting snapshot, please retry.");
				}
				debug("Integrity check complete");
			}
			
			//Are we generating the transitive closure?
			if (fileType.equals(SNAPSHOT) && populatePreviousTransativeClosure) {
				gl.populatePreviousTransativeClosure();
			}
		} catch (IOException e) {
			throw new TermServerScriptException("Failed to extract project state from archive " + archive.getName(), e);
		}
	}

	private void loadArchiveZip(File archive, boolean fsnOnly, String fileType, boolean isDelta, Boolean isReleased) throws IOException, TermServerScriptException {
		ZipInputStream zis = new ZipInputStream(new FileInputStream(archive));
		ZipEntry ze = zis.getNextEntry();
		try {
			while (ze != null) {
				if (!ze.isDirectory()) {
					Path path = Paths.get(ze.getName());
					loadFile(path, zis, fileType, isDelta, fsnOnly, isReleased);
				}
				ze = zis.getNextEntry();
			}
		}  finally {
			try{
				zis.closeEntry();
				zis.close();
			} catch (Exception e){} //Well, we tried.
		}
	}
	
	private void loadArchiveDirectory(File dir, boolean fsnOnly, String fileType, boolean isDelta, Boolean isReleased) throws IOException {
		try (Stream<Path> paths = Files.walk(dir.toPath())) {
			paths.filter(Files::isRegularFile)
			.forEach( path ->  {
				try {
					InputStream is =  toInputStream(path);
					loadFile(path, is , fileType, isDelta, fsnOnly, isReleased);
					is.close();
				} catch (Exception e) {
					throw new RuntimeException ("Faied to load " + path + " due to " + e.getMessage(),e);
				}
			});
		} 
	}
	
	private InputStream toInputStream(Path path) {
		InputStream is;
		try {
			is = new BufferedInputStream(Files.newInputStream(path));
		} catch (IOException e) {
			throw new IllegalStateException("Unable to load " + path, e);
		}
		return is;
	}

	private void loadFile(Path path, InputStream is, String fileType, boolean isDelta, boolean fsnOnly, Boolean isReleased)  {
		try {
			String fileName = path.getFileName().toString();
			if (fileName.contains(fileType)) {
				if (fileName.contains("sct2_Concept_" )) {
					info("Loading Concept " + fileType + " file.");
					gl.loadConceptFile(is, isReleased);
				} else if (fileName.contains("sct2_Relationship_" )) {
					info("Loading Relationship " + fileType + " file.");
					gl.loadRelationships(CharacteristicType.INFERRED_RELATIONSHIP, is, true, isDelta, isReleased);
					if (populateHierarchyDepth) {
						info("Calculating concept depth...");
						gl.populateHierarchyDepth(ROOT_CONCEPT, 0);
					}
				} else if (fileName.contains("sct2_StatedRelationship_" )) {
					info("Loading StatedRelationship " + fileType + " file.");
					gl.loadRelationships(CharacteristicType.STATED_RELATIONSHIP, is, true, isDelta, isReleased);
				} else if (fileName.contains("sct2_sRefset_OWLExpression" ) ||
						   fileName.contains("sct2_sRefset_OWLAxiom" )) {
					info("Loading Axiom " + fileType + " refset file.");
					gl.loadAxioms(is, isDelta, isReleased);
				} else if (fileName.contains("sct2_Description_" )) {
					info("Loading Description " + fileType + " file.");
					gl.loadDescriptionFile(is, fsnOnly, isReleased);
				} else if (fileName.contains("sct2_TextDefinition_" )) {
					info("Loading Text Definition " + fileType + " file.");
					gl.loadDescriptionFile(is, fsnOnly, isReleased);
				} else if (fileName.contains("der2_cRefset_ConceptInactivationIndicatorReferenceSet" )) {
					info("Loading Concept Inactivation Indicator " + fileType + " file.");
					gl.loadInactivationIndicatorFile(is);
				} else if (fileName.contains("der2_cRefset_DescriptionInactivationIndicatorReferenceSet" )) {
					info("Loading Description Inactivation Indicator " + fileType + " file.");
					gl.loadInactivationIndicatorFile(is);
				} else if (fileName.contains("der2_cRefset_AttributeValue" )) {
					info("Loading Concept/Description Inactivation Indicators " + fileType + " file.");
					gl.loadInactivationIndicatorFile(is);
				} else if (fileName.contains("Association" ) || fileName.contains("AssociationReferenceSet" )) {
					info("Loading Historical Association File: " + fileName);
					gl.loadHistoricalAssociationFile(is);
				}
				//If we're loading all terms, load the language refset as well
				if (!fsnOnly && (fileName.contains("English" ) || fileName.contains("Language"))) {
					info("Loading " + fileType + " Language Reference Set File - " + fileName);
					gl.loadLanguageFile(is, isReleased);
				}
			}
		} catch (TermServerScriptException | IOException e) {
			throw new IllegalStateException("Unable to load " + path + " due to " + e.getMessage(), e);
		}
	}

	public boolean isAllowStaleData() {
		return allowStaleData;
	}

	public void setAllowStaleData(boolean allowStaleData) {
		this.allowStaleData = allowStaleData;
	}

	public boolean isLoadDependencyPlusExtensionArchive() {
		return loadDependencyPlusExtensionArchive;
	}

	public void setLoadDependencyPlusExtensionArchive(boolean loadDependencyPlusExtensionArchive) {
		this.loadDependencyPlusExtensionArchive = loadDependencyPlusExtensionArchive;
	}

	public boolean isLoadEditionArchive() {
		return loadEditionArchive;
	}

	public void setLoadEditionArchive(boolean loadEditionArchive) {
		this.loadEditionArchive = loadEditionArchive;
	}

	public boolean isPopulateHierarchyDepth() {
		return populateHierarchyDepth;
	}

	public void setPopulateHierarchyDepth(boolean populateHierarchyDepth) {
		this.populateHierarchyDepth = populateHierarchyDepth;
	}

	public boolean isPopulateReleasedFlag() {
		return populateReleasedFlag;
	}

	public void setPopulateReleasedFlag(boolean populateReleasedFlag) {
		this.populateReleasedFlag = populateReleasedFlag;
	}

	public boolean isPopulatePreviousTransativeClosure() {
		return populatePreviousTransativeClosure;
	}

	public void setPopulatePreviousTransativeClosure(boolean populatePreviousTransativeClosure) {
		this.populatePreviousTransativeClosure = populatePreviousTransativeClosure;
	}

	public boolean isReleasedFlagPopulated() {
		return releasedFlagPopulated;
	}

	public void setReleasedFlagPopulated(boolean releasedFlagPopulated) {
		this.releasedFlagPopulated = releasedFlagPopulated;
	}
}

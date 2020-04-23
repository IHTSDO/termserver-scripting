package org.ihtsdo.termserver.scripting.fixes;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

import javax.mail.*;
import javax.mail.internet.*;

import org.apache.commons.lang.NotImplementedException;
import org.ihtsdo.otf.rest.client.Status;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.*;
import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.*;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;
import org.snomed.otf.scheduler.domain.JobParameters;
import org.snomed.otf.scheduler.domain.JobRun;

import us.monoid.json.*;

/**
 * Reads in a file containing a list of concept SCTIDs and processes them in batches
 * in tasks created on the specified project
 */
public abstract class BatchFix extends TermServerScript implements RF2Constants {
	
	protected int taskSize = 10;
	protected int wiggleRoom = 5;
	protected int failureCount = 0;
	protected int validationCount = 0;
	protected int taskThrottle = 5;
	protected int restartFromTask = NOT_SET;
	protected int conceptThrottle = 2;
	protected String targetAuthor;
	protected String targetReviewer;
	protected String[] author_reviewer;
	protected String[] emailDetails;
	protected boolean selfDetermining = false; //Set to true if the batch fix calculates its own data to process
	protected boolean populateEditPanel = true;
	protected boolean populateTaskDescription = true;
	protected boolean reportNoChange = true;
	protected boolean worksWithConcepts = true;
	protected boolean classifyTasks = false;
	protected boolean validateTasks = false;
	protected boolean groupByIssue = false;
	protected boolean keepIssuesTogether = false;
	protected List<Component> allComponentsToProcess = new ArrayList<>();
	protected List<Component> priorityComponents = new ArrayList<>();
	protected int priorityBatchSize = 10;
	private boolean firstTaskCreated = false;
	public static String DEFAULT_TASK_DESCRIPTION = "Batch Updates - see spreadsheet for details";
	
	protected BatchFix (BatchFix clone) {
		if (clone != null) {
			this.inputFile = clone.inputFile;
			setReportManager(clone.getReportManager());
			this.project = clone.project;
			this.tsClient = clone.tsClient;
			this.scaClient = clone.scaClient;
		}
		this.headers = "TASK_KEY, TASK_DESC, SCTID, FSN, CONCEPT_TYPE, SEVERITY, ACTION_TYPE, ";
	}
	
	public void runJob() throws TermServerScriptException {
		processFile();
	}
	
	protected List<Component> processFile() throws TermServerScriptException {
		startTimer();
		if (selfDetermining) {
			allComponentsToProcess = identifyComponentsToProcess();
		} else {
			allComponentsToProcess = super.processFile();
		}
		batchProcess(formIntoBatch(allComponentsToProcess));
		/*if (emailDetails != null) {
			String msg = "Batch Scripting has completed successfully." + getSummaryText();
			sendEmail(msg, reportFiles[0]);
		}*/
		return allComponentsToProcess;
	}
	
	abstract protected int doFix(Task task, Concept concept, String info) throws TermServerScriptException, ValidationFailure;

	protected List<Component> identifyComponentsToProcess() throws TermServerScriptException {
		//Default implementation identifies no concepts.  Override if required.
		return new ArrayList<Component>();
	}
	
	protected Batch formIntoBatch (List<Component> allComponents) throws TermServerScriptException {
		Batch batch = new Batch(getScriptName());
		Task task = batch.addNewTask(author_reviewer);
		//Do we need to prioritize some components?
		if (priorityComponents.size() > 0) {
			List<Component> unprioritized = new ArrayList<> (allComponents);
			unprioritized.removeAll(priorityComponents);
			allComponents = priorityComponents;
			allComponents.addAll(unprioritized);
		}
		
		//If we're grouping by issue or keeping issues together, then we need to sort by issue
		if (groupByIssue || keepIssuesTogether) {
			allComponents = allComponents.stream()
					.sorted(Comparator.comparing(Component::getIssues))
					.collect(Collectors.toList());
		}
		
		if (allComponents.size() > 0) {
			String lastIssue = allComponents.get(0).getIssues();
			int currentPosition = 0;
			for (Component thisComponent : allComponents) {
				//DRUGS-522 Priority concepts might need a smaller batch size
				int thisTaskMaxSize = priorityComponents.contains(thisComponent) ? priorityBatchSize : taskSize;
				int remainingSpace = thisTaskMaxSize - task.size();
				if (keepIssuesTogether) {
					//If we're on the same issue, don't break the task
					if (!lastIssue.equals(thisComponent.getIssues()) &&
							!peekAheadFits(asConcepts(allComponents), currentPosition,remainingSpace)) {
						task = batch.addNewTask(author_reviewer);
					}
				} else if (task.size() >= thisTaskMaxSize ||
						(groupByIssue && !lastIssue.equals(thisComponent.getIssues()))) {
					task = batch.addNewTask(author_reviewer);
				}
				task.add(thisComponent);
				lastIssue = thisComponent.getIssues();
				currentPosition++;
			}
		}
		addSummaryInformation("Tasks scheduled", batch.getTasks().size());
		addSummaryInformation(CONCEPTS_TO_PROCESS, allComponents.size());
		return batch;
	}
	
	private boolean peekAheadFits(List<Concept> concepts, int pos, int remainingSpace) {
		//Given the issue of the current position, can we fit all successive concepts with the same issue into the 
		//same task, or do we need to start a new one?
		String thisIssue = concepts.get(pos).getIssues();
		int sameIssueCount = 0;
		
		while (pos < concepts.size() -1 && thisIssue.equals(concepts.get(++pos).getIssues())) {
			sameIssueCount++;
		}
	
		return sameIssueCount <= remainingSpace;
	}

	protected Batch formIntoGroupedBatch (List<List<Component>> allComponentList) throws TermServerScriptException {
		Batch batch = new Batch(getScriptName());
		int componentsToProcess = 0;
		for (List<Component> thisSet : allComponentList) {
			Task task = batch.addNewTask(author_reviewer);
			if (thisSet.size() > 0) {
				String lastIssue = thisSet.get(0).getIssues();
				for (Component thisComponent : thisSet) {
					if (task.size() >= taskSize ||
							(groupByIssue && !lastIssue.equals(thisComponent.getIssues()))) {
						task = batch.addNewTask(author_reviewer);
					}
					task.add(thisComponent);
					componentsToProcess++;
					lastIssue = thisComponent.getIssues();
				}
			}
		}
		addSummaryInformation("Tasks scheduled", batch.getTasks().size());
		addSummaryInformation(CONCEPTS_TO_PROCESS, componentsToProcess);
		return batch;
	}

	protected void batchProcess(Batch batch) throws TermServerScriptException {
		int currentTaskNum = 0;
		for (Task task : batch.getTasks()) {
			try {
				currentTaskNum++;
				//If we don't have any concepts in this task eg this is 100% ME file, then skip
				if (task.size() == 0) {
					info ("Skipping Task " + task.getSummary() + " - no concepts to process");
					continue;
				} else if (selfDetermining && restartPosition > 1 && currentTaskNum < restartPosition) {
					//For self determining projects we'll restart based on a task count, rather than the line number in the input file
					info ("Skipping Task " + task.getSummary() + " - restarting from task " + restartPosition);
					continue;
				} else if (restartFromTask != NOT_SET && currentTaskNum < restartFromTask) {
					//For file driven batches, we'll use the r2 restartFromTask setting
					info ("Skipping Task " + task.getSummary() + " - restarting from task " + restartFromTask);
					continue;
				} else if (task.size() > (taskSize + wiggleRoom)) {
					warn (task + " contains " + task.size() + " concepts");
				}
				
				//Create a task for this batch of concepts
				createTask(task);
				String xOfY =  (currentTaskNum) + " of " + batch.getTasks().size();
				info ( (dryRun?"Dry Run " : "Created ") + "task (" + xOfY + "): " + task.getBranchPath());
				incrementSummaryInformation("Tasks created",1);
				
				//Process each component
				int conceptInTask = 0;
				
				//The components in the task might change during processing, so we'll drive this loop with a copy of the initial list
				List<Component> components = new ArrayList<>(task.getComponents());
				for (Component component : components) {
					conceptInTask++;
					processComponent(task, component, conceptInTask, xOfY);
					//Update file after each component processed - if data limits allow
					flushFilesSoft();  //Soft flush is optional
				}
				
				if (!dryRun) {
					populateEditPanel(task);
					updateTask(task);
					
					Classification classification = null;
					if (classifyTasks) {
						info ("Classifying " + task);
						classification = scaClient.classify(task.getKey());
						debug(classification);
					}
					if (validateTasks) {
						info ("Validating " + task);
						Status status = scaClient.validate(task.getKey());
						debug(status);
					}
					
					if (classification != null) {
						try {
							tsClient.waitForCompletion(task.getBranchPath(), classification);
						} catch (Exception e) {
							error("Failed to wait for classification " + classification, e);
						}
					}
				}
			} catch (Exception e) {
				throw new TermServerScriptException("Failed to process batch " + task.getSummary() + " on task " + task.getKey(), e);
			}
			
			if (processingLimit > NOT_SET && currentTaskNum >= processingLimit) {
				info ("Processing limit of " + processingLimit + " tasks reached.  Stopping");
				break;
			}
		}
	}

	private void createTask(Task task) throws TermServerScriptException, InterruptedException {
		if (!dryRun) {
			if (firstTaskCreated) {
				debug ("Letting TS catch up - " + taskThrottle + "s nap.");
				Thread.sleep(taskThrottle * 1000);
			} else {
				firstTaskCreated = true;
			}
			boolean taskCreated = false;
			int taskCreationAttempts = 0; 
			while (!taskCreated) {
				try{
					debug ("Creating jira task on project: " + project);
					String taskDescription;
					if (populateTaskDescription && task.size() <= 150) {
						taskDescription = task.getDescriptionHTML();
					} else {
						taskDescription = DEFAULT_TASK_DESCRIPTION;
						if (task.size() > 150) {
							warn ("Task size " + task.size() + ", cannot populate Jira ticket description, even though populateTaskDescription flag set to true.");
						}
					}
					task.setKey(scaClient.createTask(project.getKey(), task.getSummary(), taskDescription));
					debug ("Creating task branch in terminology server: " + task);
					task.setBranchPath(tsClient.createBranch(project.getBranchPath(), task.getKey()));
					taskCreated = true;
				} catch (Exception e) {
					taskCreationAttempts++;
					try {
						scaClient.deleteTask(project.getKey(), task.getKey(), true);  //Don't worry if deletion fails
					} catch (Exception e2) {}
					
					if (taskCreationAttempts >= 3) {
						throw new TermServerScriptException("Maxed out failure attempts", e);
					}
					warn ("Task creation failed (" + e.getMessage() + "), retrying...");
				}
			}
		} else {
			task.setKey(project + "-" + getNextDryRunNum());
			task.setBranchPath(project.getBranchPath() + "/" + task.getKey());
		}
	}

	private void processComponent(Task task, Component component, int conceptInTask, String xOfY) throws TermServerScriptException {
		try {
			if (!dryRun && task.getComponents().indexOf(component) != 0) {
				Thread.sleep(conceptThrottle * 1000);
			}
			String info = " Task (" + xOfY + ") Concept (" + conceptInTask + " of " + task.getComponents().size() + ")";
			
			int changesMade = 0;
			if (worksWithConcepts) {
				changesMade = doFix(task, (Concept)component, info);
			} else {
				changesMade = doFix(task, component, info);
			}
			if (changesMade == 0 && reportNoChange) {
				report(task, component, Severity.MEDIUM, ReportActionType.NO_CHANGE, "");
			}
			incrementSummaryInformation("Total changes made", changesMade);
		} catch (ValidationFailure f) {
			if (++validationCount > maxFailures) { 
				warn ("Validation failures now " + validationCount);
			}
			report (f);
		} catch (InterruptedException | TermServerScriptException e) {
			report(task, component, Severity.CRITICAL, ReportActionType.API_ERROR, getMessage(e));
			if (++failureCount >= maxFailures) {
				throw new TermServerScriptException ("Failure count exceeded " + maxFailures);
			}
		}
	}

	protected void report(ValidationFailure f) throws TermServerScriptException {
		report(f.getTask(), f.getConcept(), f.getSeverity(), f.getReportActionType(), f.getMessage());
	}

	private void populateEditPanel(Task task) throws TermServerScriptException {
		//Prefill the Edit Panel
		try {
			if (populateEditPanel) {
				scaClient.setEditPanelUIState(project.getKey(), task.getKey(), task.toQuotedList());
			}
			scaClient.setSavedListUIState(project.getKey(), task.getKey(), convertToSavedListJson(task));
		} catch (Exception e) {
			String msg = "Failed to preload edit-panel ui state: " + e.getMessage();
			warn (msg);
			report(task, null, Severity.LOW, ReportActionType.API_ERROR, msg);
		}
	}
	
	private void updateTask(Task task) throws Exception {
		
		String taskDescription = populateTaskDescription ? task.getDescriptionHTML() : "Batch Updates - see spreadsheet for details";
		
		//Reassign the task to the intended author.  Set at task or processing level
		String taskAuthor = task.getAssignedAuthor();
		if (taskAuthor == null) {
			taskAuthor = targetAuthor;
		}
		
		if (taskAuthor != null && !taskAuthor.isEmpty()) {
			String reviewMsg = task.getReviewer() == null? "" : " into review for " + task.getReviewer(); 
			debug("Assigning " + task + " to " + taskAuthor + reviewMsg);
		}
		
		scaClient.updateTask(project.getKey(), task.getKey(), null, taskDescription, taskAuthor, task.getReviewer());
	}

	//Override if working with Refsets or Descriptions directly
	protected int doFix(Task task, Component component, String info) throws TermServerScriptException, ValidationFailure {
		return 0;
	}

	protected int ensureDefinitionStatus(Task t, Concept c, DefinitionStatus targetDefStat) throws TermServerScriptException {
		int changesMade = 0;
		if (!c.getDefinitionStatus().equals(targetDefStat)) {
			report (t, c, Severity.MEDIUM, ReportActionType.CONCEPT_CHANGE_MADE, "Definition status changed to " + targetDefStat);
			c.setDefinitionStatus(targetDefStat);
			changesMade++;
		}
		return changesMade;
	}
	
	protected void init (JobRun jobRun) throws TermServerScriptException {
		super.init(jobRun);
		if (jobRun.getParamValue(DRY_RUN) != null) {
			TermServerScript.dryRun = !jobRun.getParamValue(DRY_RUN).equals("N");
		}
		if (jobRun.getParamValue(CONCEPTS_PER_TASK) != null) {
			taskSize = Integer.parseInt(jobRun.getParamValue(CONCEPTS_PER_TASK));
		}
		author_reviewer = new String[] { jobRun.getUser() };
	}

	protected void init (String[] args) throws TermServerScriptException {
		if (args.length < 3) {
			print("Usage: java <FixClass> [-a author][-a2 reviewer ][-n <taskSize>] [-r <restart position in file>] [-r2 <restart from task #>] [-l <limit> ] [-t taskCreationDelay] -c <authenticatedCookie> [-d <Y/N>] [-p <projectName>] -f <batch file Location>");
			print(" d - dry run");
			System.exit(-1);
		}
		boolean isTaskSize = false;
		boolean isAuthor = false;
		boolean isReviewer = false;
		boolean isLimit = false;
		boolean isTaskThrottle = false;
		boolean isConceptThrottle = false;
		boolean isRestartFromTask = false;
	
		for (String thisArg : args) {
			if (thisArg.equals("-a")) {
				isAuthor = true;
			} else if (thisArg.equals("-a2")) {
				isReviewer = true;
			} else if (thisArg.equals("-n")) {
				isTaskSize = true;
			} else if (thisArg.equals("-l")) {
				isLimit = true;
			} else if (thisArg.equals("-r2")) {
				isRestartFromTask = true;
			} else if (thisArg.equals("-t") || thisArg.equals("-t1")) {
				isTaskThrottle = true;
			} else if (thisArg.equals("-t2")) {
				isConceptThrottle = true;
			} else if (isTaskThrottle) {
				taskThrottle = Integer.parseInt(thisArg);
				isTaskThrottle = false;
			} else if (isConceptThrottle) {
				conceptThrottle = Integer.parseInt(thisArg);
				isConceptThrottle = false;
			} else if (isRestartFromTask) {
				restartFromTask = Integer.parseInt(thisArg);
				isRestartFromTask = false;
			}else if (isAuthor) {
				targetAuthor = thisArg.toLowerCase();
				isAuthor = false;
			} else if (isReviewer) {
				targetReviewer = thisArg.toLowerCase();
				isReviewer = false;
			} else if (isTaskSize) {
				taskSize = Integer.parseInt(thisArg);
				isTaskSize = false;
			} else if (isLimit) {
				processingLimit = Integer.parseInt(thisArg);
				info ("Limiting number of tasks being created to " + processingLimit);
				isLimit = false;
			}
		}
		
		try {
			super.init(args);
		} catch (Exception e) {
			throw new TermServerScriptException("Unable to initialise batch fix",e);
		}
	}
	
	protected void checkSettingsWithUser(JobRun jobRun) throws TermServerScriptException {
		super.checkSettingsWithUser(jobRun);
		print ("Number of concepts per task [" + taskSize + "]: ");
		String response = STDIN.nextLine().trim();
		if (!response.isEmpty()) {
			taskSize = Integer.parseInt(response);
		}
		
		
		if (taskThrottle > 0) {
			print ("Time delay between tasks (throttle) seconds [" +taskThrottle + "]: ");
			response = STDIN.nextLine().trim();
			if (!response.isEmpty()) {
				taskThrottle = Integer.parseInt(response);
			}
		}
		
		if (conceptThrottle > 0) {
			print ("Time delay between concepts (throttle) seconds [" +conceptThrottle + "]: ");
			response = STDIN.nextLine().trim();
			if (!response.isEmpty()) {
				conceptThrottle = Integer.parseInt(response);
			}
		}

		if (!selfDetermining && inputFile == null) {
			if (jobRun.getParamValue(INPUT_FILE) != null) {
				inputFile = new File(jobRun.getParamValue(INPUT_FILE));
			} else {
				throw new TermServerScriptException("No valid batch import file detected in command line arguments");
			}
		}
		
		if (targetAuthor == null) {
			if (jobRun != null && jobRun.getParamValue(AUTHOR) != null) {
				targetAuthor = jobRun.getParamValue(AUTHOR);
			} else {
				throw new TermServerScriptException("No target author detected in command line arguments");
			}
		} else {
			if (targetReviewer != null) {
				author_reviewer = new String[] { targetAuthor, targetReviewer };
			} else {
				author_reviewer = new String[] { targetAuthor };
			}
		}
		
		if (!selfDetermining) {
			info("Reading file from line " + restartPosition + " - " + inputFile.getName());
		}
		
		info ("\nBatching " + taskSize + " concepts per task");
	}
	
	protected int ensureAcceptableParent(Task task, Concept c, Concept acceptableParent) throws TermServerScriptException {
		List<Relationship> statedParents = c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, IS_A, ActiveState.ACTIVE);
		boolean hasAcceptableParent = false;
		int changesMade = 0;
		for (Relationship thisParent : statedParents) {
			if (!thisParent.getTarget().equals(acceptableParent)) {
				report(task, c, Severity.MEDIUM, ReportActionType.RELATIONSHIP_INACTIVATED, "Inactivated unwanted parent: " + thisParent.getTarget());
				thisParent.setActive(false);
				changesMade++;
			} else {
				hasAcceptableParent = true;
			}
		}
		
		if (!hasAcceptableParent) {
			c.addRelationship(IS_A, acceptableParent);
			changesMade++;
			report(task, c, Severity.MEDIUM, ReportActionType.RELATIONSHIP_ADDED, "Added required parent: " + acceptableParent);
		}
		return changesMade;
	}

	
	/**
	 Validate that that any attribute with that attribute type is a descendent of the target Value
	 * @param cardinality 
	 * @throws TermServerScriptException 
	 */
	protected int validateAttributeValues(Task task, Concept concept,
			Concept attributeType, Concept descendentsOfValue, CardinalityExpressions cardinality) throws TermServerScriptException {
		
		List<Relationship> attributes = concept.getRelationships(CharacteristicType.ALL, attributeType, ActiveState.ACTIVE);
		Set<Concept> descendents = ClosureCache.getClosureCache().getClosure(descendentsOfValue);
		for (Relationship thisAttribute : attributes) {
			Concept value = thisAttribute.getTarget();
			if (!descendents.contains(value)) {
				Severity severity = thisAttribute.isActive()?Severity.CRITICAL:Severity.LOW;
				String activeStr = thisAttribute.isActive()?"":"inactive ";
				String relType = thisAttribute.getCharacteristicType().equals(CharacteristicType.STATED_RELATIONSHIP)?"stated ":"inferred ";
				String msg = "Attribute has " + activeStr + relType + "target which is not a descendent of: " + descendentsOfValue;
				report (task, concept, severity, ReportActionType.VALIDATION_ERROR, msg);
			}
		}
		
		int changesMade = 0;
		
		//Now check cardinality on active stated relationships
		attributes = concept.getRelationships(CharacteristicType.STATED_RELATIONSHIP, attributeType, ActiveState.ACTIVE);
		String msg = null;
		switch (cardinality) {
			case EXACTLY_ONE : if (attributes.size() != 1) {
									msg = "Concept has " + attributes.size() + " active stated attributes of type " + attributeType + " expected exactly one.";
								}
								break;
			case AT_LEAST_ONE : if (attributes.size() < 1) {
									msg = "Concept has " + attributes.size() + " active stated attributes of type " + attributeType + " expected one or more.";
								}
								break;
		}
		
		//If we have no stated attributes of the expected type, attempt to pull from the inferred ones
		if (attributes.size() == 0) {
			changesMade = transferInferredRelationshipsToStated(task, concept, attributeType, cardinality);
		}
		
		//If we have an error message but have not made any changes to the concept, then report that error now
		if (msg != null && changesMade == 0) {
			report (task, concept, Severity.HIGH, ReportActionType.VALIDATION_ERROR, msg);
		}
		return changesMade;
	}
	

	private int transferInferredRelationshipsToStated(Task task,
			Concept concept, Concept attributeType, CardinalityExpressions cardinality) throws TermServerScriptException {
		List<Relationship> replacements = concept.getRelationships(CharacteristicType.INFERRED_RELATIONSHIP, attributeType, ActiveState.ACTIVE);
		int changesMade = 0;
		if (replacements.size() == 0) {
			String msg = "Unable to find any inferred " + attributeType + " relationships to state.";
			report(task, concept, Severity.HIGH, ReportActionType.INFO, msg);
		} else if (cardinality.equals(CardinalityExpressions.EXACTLY_ONE) && replacements.size() > 1) {
			String msg = "Found " + replacements.size() + " " + attributeType + " relationships to state but wanted only one!";
			report(task, concept, Severity.HIGH, ReportActionType.INFO, msg);
		} else {
			//Clone the inferred relationships, make them stated and add to concept
			for (Relationship replacement : replacements) {
				Relationship statedClone = replacement.clone(null);
				statedClone.setCharacteristicType(CharacteristicType.STATED_RELATIONSHIP);
				concept.addRelationship(statedClone);
				String msg = "Restated inferred relationship: " + replacement;
				report(task, concept, Severity.MEDIUM, ReportActionType.RELATIONSHIP_ADDED, msg);
				changesMade++;
			}
		}
		return changesMade;
	}
	
	protected int replaceParents(Task t, Concept c, Set<Concept> newParents) throws TermServerScriptException {
		int changesMade = 0;
		Set<Relationship> newParentRels = newParents.stream()
				.map(p -> new Relationship (c, IS_A, p, UNGROUPED))
				.collect(Collectors.toSet());
		
		Set<Relationship> currentParentRels = c.getParents(CharacteristicType.STATED_RELATIONSHIP).stream()
				.map(p -> new Relationship (c, IS_A, p, UNGROUPED))
				.collect(Collectors.toSet());
		
		for (Relationship currentParentRel : currentParentRels) {
			//Is this a relationship we want to keep?
			if (!newParentRels.contains(currentParentRel)) {
				removeRelationship(t, c, currentParentRel);
				changesMade++;
			}
		}
		
		for (Relationship newParentRel : newParentRels) {
			//Is this a relationship we already have?
			if (!currentParentRels.contains(newParentRel)) {
				addRelationship(t, c, newParentRel);
				changesMade++;
			}
		}
		return changesMade;
	}
	
	protected int replaceParents(Task t, Concept c, Concept newParent) throws TermServerScriptException {
		Relationship oldParentRel = null;
		Relationship newParentRel = new Relationship (c, IS_A, newParent, UNGROUPED);
		return replaceParents(t, c, oldParentRel, newParentRel, null);
	}
	
	protected int replaceParent(Task t, Concept c, Concept oldParent, Concept newParent) throws TermServerScriptException {
		Relationship oldParentRel = oldParent == null? null : new Relationship (c, IS_A, oldParent, UNGROUPED);
		Relationship newParentRel = new Relationship (c, IS_A, newParent, UNGROUPED);
		return replaceParents(t, c, oldParentRel, newParentRel, null);
	}
	
	protected int replaceParents(Task t, Concept c, Relationship newParentRel, Object[] additionalDetails) throws TermServerScriptException {
		Relationship oldParentRel = null;
		return replaceParents(t, c, oldParentRel, newParentRel, additionalDetails);
	}
	
	protected int replaceParents(Task t, Concept c, Relationship oldParentRel, Relationship newParentRel, Object[] additionalDetails) throws TermServerScriptException {
		int changesMade = 0;
		List<Relationship> parentRels = new ArrayList<Relationship> (c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, 
																	IS_A,
																	ActiveState.ACTIVE));
		for (Relationship parentRel : parentRels) {
			if ((oldParentRel == null || parentRel.equals(oldParentRel)) && !parentRel.equals(newParentRel)) {
				changesMade += removeParentRelationship (t, parentRel, c, newParentRel.getTarget().toString(), additionalDetails);
			} 
		}
		
		changesMade += addRelationship(t, c, newParentRel);
		return changesMade;
	}

	protected int removeParentRelationship(Task t, Relationship removeMe, Concept c, String retained, Object[] additionalDetails) throws TermServerScriptException {
		//Does this concept in fact have this relationship to remove?
		List<Relationship> matchingRels = c.getRelationships(removeMe, ActiveState.ACTIVE);
		if (matchingRels.size() > 1) {
			throw new IllegalStateException(c + " has multiple parent relationships to " + removeMe.getTarget());
		} else if (matchingRels.size() == 0) {
			return NO_CHANGES_MADE;
		}
		Relationship r = matchingRels.get(0);
		
		//Are we inactivating or deleting this relationship?
		String msg;
		ReportActionType action = ReportActionType.UNKNOWN;
		if (!r.isReleased()) {
			c.removeRelationship(r);
			msg = "Deleted parent relationship: " + r.getTarget();
			action = ReportActionType.RELATIONSHIP_DELETED;
		} else {
			r.setEffectiveTime(null);
			r.setActive(false);
			msg = "Inactivated parent relationship: " + r.getTarget();
			action = ReportActionType.RELATIONSHIP_INACTIVATED;
		}
		
		//Also remove this parent from both stated and inferred hierarchies
		c.removeParent(CharacteristicType.STATED_RELATIONSHIP, r.getTarget());
		c.removeParent(CharacteristicType.INFERRED_RELATIONSHIP, r.getTarget());
		
		if (retained != null && !retained.isEmpty()) {
			msg += " in favour of " + retained;
		}
		
		report (t, c, Severity.LOW, action, msg, c.getDefinitionStatus().toString(), additionalDetails);
		return CHANGE_MADE;
	}
	
	protected void removeRelationships (Task t, Concept c, Concept type, int groupId) throws TermServerScriptException {
		for (Relationship r : c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, type, groupId)) {
			removeRelationship(t, c, r);
		}
	}
	
	protected int removeRelationship(Task t, Concept c, Relationship r) throws TermServerScriptException {
		return removeRelationship(t, c, r, "");
	}
	
	protected int removeRelationship(Task t, Concept c, Relationship r, String reasonPrefix) throws TermServerScriptException {
		//Are we inactivating or deleting this relationship?
		ReportActionType action = ReportActionType.UNKNOWN;
		if (!r.isReleased()) {
			r.setActive(false);
			c.removeRelationship(r);
			action = ReportActionType.RELATIONSHIP_DELETED;
		} else {
			r.setEffectiveTime(null);
			r.setActive(false);
			action = ReportActionType.RELATIONSHIP_INACTIVATED;
		}
		c.recalculateGroups();
		report (t, c, Severity.LOW, action, reasonPrefix + r);
		return CHANGE_MADE;
	}
	
	protected void removeDescription(Task t, Concept c, Description d, InactivationIndicator i) throws TermServerScriptException {
		//Are we inactivating or deleting this relationship?
		if (!d.isReleased()) {
			c.removeDescription(d);
			report (t, c, Severity.LOW, ReportActionType.DESCRIPTION_DELETED, d);
		} else {
			d.setEffectiveTime(null);
			d.setActive(false);
			d.setInactivationIndicator(i);
			report (t, c, Severity.LOW, ReportActionType.DESCRIPTION_INACTIVATED, d, i.toString());
		}
	}
	
	protected Description addDescription(Task t, Concept c, Description d) throws TermServerScriptException {
		Description reuseMe = c.findTerm(d.getTerm());
		if (reuseMe != null) {
			if (reuseMe.isActive()) {
				report(t, c, Severity.HIGH, ReportActionType.VALIDATION_CHECK, "Replacement term already exists active: " + reuseMe);
			} else {
				report(t, c, Severity.MEDIUM, ReportActionType.DESCRIPTION_CHANGE_MADE, "Replacement term already exists inactive.  Reactivating: " + reuseMe);
				reuseMe.setActive(true);
				reuseMe.setInactivationIndicator(null);
			}
			//And copy the acceptability from the one we're replacing
			reuseMe.setAcceptabilityMap(SnomedUtils.mergeAcceptabilityMap(d, reuseMe));
		} else {
			report(t, c, Severity.LOW, ReportActionType.DESCRIPTION_ADDED, d);
			c.addDescription(d);
		}
		return reuseMe == null ? d : reuseMe;
	}
	
	protected Description replaceDescription(Task t, Concept c, Description d, String newTerm, InactivationIndicator indicator) throws TermServerScriptException {
		Description replacement = null;
		Description reuseMe = c.findTerm(newTerm);
		if (reuseMe != null) {
			if (reuseMe.isActive()) {
				report(t, c, Severity.HIGH, ReportActionType.VALIDATION_CHECK, "Replacement term already exists active: " + reuseMe);
			} else {
				report(t, c, Severity.MEDIUM, ReportActionType.DESCRIPTION_CHANGE_MADE, "Replacement term already exists inactive.  Reactivating: " + reuseMe);
				reuseMe.setActive(true);
				reuseMe.setInactivationIndicator(null);
			}
			//And copy the acceptability from the one we're replacing
			reuseMe.setAcceptabilityMap(SnomedUtils.mergeAcceptabilityMap(d, reuseMe));
		} else {
			replacement = d.clone(null); //Includes acceptability and case significance
			replacement.setTerm(newTerm);
			c.addDescription(replacement);
		}
		
		//Are we deleting or inactivating this term?
		if (d != null) {
			removeDescription(t, c, d, newTerm, indicator);
		}
		return replacement == null ? reuseMe : replacement;  //WATCH THAT THE CALLING CODE IS RESPONSIBLE FOR CHECKING THE CASE SIGNIFICANCE - copied from original
	}
	
	protected void removeDescription(Task t, Concept c, Description d, String newTerm, InactivationIndicator indicator) throws TermServerScriptException {
		String change = "";
		ReportActionType action = null;
		if (d.isReleased()) {
			d.setActive(false);
			d.setInactivationIndicator(indicator);
			d.setAcceptabilityMap(new HashMap<>());
			change = "Inactivated";
			action = ReportActionType.DESCRIPTION_INACTIVATED;
		} else {
			c.removeDescription(d);
			change = "Deleted";
			action = ReportActionType.DESCRIPTION_DELETED;
		}
		String msg = change + " " + d + (newTerm == null? "" : " replaced with: " + newTerm);
		report(t, c, Severity.MEDIUM, action, msg);
	}

	protected int addRelationship(Task t, Concept c, Relationship r) throws TermServerScriptException {
		return replaceRelationship(t, c, r.getType(), r.getTarget(), r.getGroupId(), false, true); //Allow other relationships of the same type
	}
	
	protected int replaceRelationship(Task t, Concept c, Concept type, Concept value, int groupId, boolean ensureTypeUnique) throws TermServerScriptException {
		return replaceRelationship(t, c, type, value, groupId, false, false); //don't allow other relationships of the same type
	}
	
	protected int replaceRelationship(Task t, Concept c, Concept type, Concept value, int groupId, boolean ensureTypeUnique, boolean allowSameType) throws TermServerScriptException {
		int changesMade = 0;
		
		if (type == null || value == null) {
			if (value == null) {
				String msg = "Unable to add relationship of type " + type + " due to lack of a value concept";
				report (t, c, Severity.CRITICAL, ReportActionType.API_ERROR, msg);
			} else if (type == null) {
				String msg = "Unable to add relationship with value " + value + " due to lack of a type concept";
				report (t, c, Severity.CRITICAL, ReportActionType.API_ERROR, msg);
			}
			return NO_CHANGES_MADE;
		}
		//Do we already have this relationship active in the target group (or at all if self grouped)?
		List<Relationship> rels;
		if (groupId == SELFGROUPED) {
			rels = c.getRelationships(CharacteristicType.STATED_RELATIONSHIP,
					type,
					value,
					ActiveState.ACTIVE);
		} else {
			rels = c.getRelationships(CharacteristicType.STATED_RELATIONSHIP,
														type,
														value,
														groupId,
														ActiveState.ACTIVE);
		}
		if (rels.size() > 1) {
			report (t, c, Severity.CRITICAL, ReportActionType.VALIDATION_ERROR, "Found two active relationships for " + type + " -> " + value);
			return NO_CHANGES_MADE;
		} else if (rels.size() == 1) {
			report (t, c, Severity.LOW, ReportActionType.NO_CHANGE, "Active relationship already exists ", rels.get(0));
			return NO_CHANGES_MADE;
		}
		
		//Do we have it inactive?
		if (groupId == SELFGROUPED) {
			rels = c.getRelationships(CharacteristicType.STATED_RELATIONSHIP,
					type,
					value,
					ActiveState.INACTIVE);
		} else {
			rels = c.getRelationships(CharacteristicType.STATED_RELATIONSHIP,
														type,
														value,
														groupId,
														ActiveState.INACTIVE);
		}
		if (rels.size() >= 1) {
			Relationship rel = rels.get(0);
			report (t, c, Severity.MEDIUM, ReportActionType.RELATIONSHIP_REACTIVATED, rel);
			rel.setActive(true);
			return CHANGE_MADE;
		}
		
		//Or do we need to create and add?
		//Is this type unique for the concept?  Inactivate any others if so
		//Unless we're allowing multiple rels of the same type
		//Otherwise just remove other relationships of this type in the target group
		if (!allowSameType) {
			rels = c.getRelationships(CharacteristicType.STATED_RELATIONSHIP,
					type,
					ActiveState.ACTIVE);
			for (Relationship rel : rels) {
				if (ensureTypeUnique || rel.getGroupId() == groupId) {
					removeRelationship(t,c,rel);
					changesMade++;
				}
			}
		}
		
		//Add the new relationship
		if (groupId == SELFGROUPED) {
			groupId = SnomedUtils.getFirstFreeGroup(c);
		}
		Relationship newRel = new Relationship (c, type, value, groupId);
		report (t, c, Severity.LOW, ReportActionType.RELATIONSHIP_ADDED, newRel);
		c.addRelationship(newRel);
		changesMade++;
		return changesMade;
	}
	
	protected int replaceRelationship(Task t, Concept c, IRelationshipTemplate from, IRelationshipTemplate to) throws TermServerScriptException {
		int changesMade = 0;
		
		//Do we have any inactive relationships that we could reactivate, rather than creating new ones?
		List<Relationship> inactiveRels = c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, to.getType(), to.getTarget(), ActiveState.INACTIVE);

		List<Relationship> originalRels;
		//If our 'from' is a full relationship, then only replace within that groupId
		if (from instanceof Relationship) {
			originalRels = c.getRelationships((Relationship)from, ActiveState.ACTIVE);
		} else {
			originalRels = c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, from.getType(), from.getTarget(), ActiveState.ACTIVE);
		}
		
		for (Relationship r : originalRels) {
			if (inactiveRels.size() > 0) {
				//We'll reuse and reactivate this row, rather than create a new one
				//Do we have one with the same group, if not, use any
				Relationship bestReactivation = getBestReactivation(inactiveRels, r.getGroupId());
				bestReactivation.setActive(true);
				bestReactivation.setGroupId(r.getGroupId());
				inactiveRels.remove(bestReactivation);
				changesMade++;
				report (t, c, Severity.LOW, ReportActionType.RELATIONSHIP_REACTIVATED, bestReactivation);
			} else {
				Relationship newRel = r.clone();
				newRel.setType(to.getType());
				newRel.setTarget(to.getTarget());
				c.addRelationship(newRel);
				changesMade++;
				report (t, c, Severity.LOW, ReportActionType.RELATIONSHIP_ADDED, newRel);
			}
			
			removeRelationship(t, c, r);
			changesMade++;
		}
		return changesMade;
	}
	
	private Relationship getBestReactivation(List<Relationship> rels, int targetGroupId) {
		for (Relationship r : rels) {
			if (r.getGroupId() == targetGroupId) {
				return r;
			}
		}
		//If not found in the same group, return the first one
		return rels.get(0);
	}

	protected void removeRedundancy(Task t, Concept c, Concept type, int groupNum) throws TermServerScriptException {
		//Remove any redundant attribute in the given group
		List<Concept> allValues = c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, type, groupNum)
									.stream().map(rel -> rel.getTarget()).collect(Collectors.toList());
		for (Concept redundantValue : detectRedundancy(allValues)) {
			Relationship removeMe = new Relationship(c, type, redundantValue, groupNum);
			removeRelationship(t, c, removeMe);
		}
	}
	
	protected List<Concept> detectRedundancy(List<Concept> concepts) throws TermServerScriptException {
		List<Concept> redundant = new ArrayList<>();
		for (Concept thisConcept : concepts) {
			//Is this concept an ancestor of one of the other concepts?  It's redundant if so
			for (Concept otherConcept : concepts) {
				if (!thisConcept.equals(otherConcept) && gl.getAncestorsCache().getAncestors(otherConcept).contains(thisConcept)) {
					redundant.add(thisConcept);
				}
			}
		}
		return redundant;
	}

	protected void validatePrefInFSN(Task task, Concept concept) throws TermServerScriptException {
		//Check that the FSN with the semantic tags stripped off is
		//equal to the preferred terms
		List<Description> preferredTerms = concept.getDescriptions(Acceptability.PREFERRED, DescriptionType.SYNONYM, ActiveState.ACTIVE);
		String trimmedFSN = SnomedUtils.deconstructFSN(concept.getFsn())[0];
		//Special handling for acetaminophen
		if (trimmedFSN.toLowerCase().contains(ACETAMINOPHEN) || trimmedFSN.toLowerCase().contains(PARACETAMOL)) {
			info ("Doing ACETAMINOPHEN processing for " + concept);
		} else {
			for (Description pref : preferredTerms) {
				if (!pref.getTerm().equals(trimmedFSN)) {
					String msg = concept + " has preferred term that does not match FSN: " + pref.getTerm();
					report (task, concept, Severity.HIGH, ReportActionType.VALIDATION_ERROR, msg);
				}
			}
		}
	}
	
	protected void sendEmail(String content, File resultsFile)  {
		Properties props = new Properties();
		props.put("mail.transport.protocol", emailDetails[0]);
		props.put("mail.smtp.host", emailDetails[1]); // smtp.gmail.com?
		props.put("mail.smtp.port", emailDetails[2]);
		props.put("mail.smtp.auth", "true");
		Authenticator authenticator = new Authenticator() {
			protected PasswordAuthentication getPasswordAuthentication() {
			return new PasswordAuthentication(emailDetails[3], emailDetails[4]);
			}
		};
		Session session = Session.getDefaultInstance(props, authenticator);
		try {
			Message msg = new MimeMessage(session);
			msg.setFrom(new InternetAddress("techsupport@ihtsdo.org", "IHTSDO Email"));
			msg.addRecipient(Message.RecipientType.TO, new InternetAddress("user@example.com"));
			msg.setSubject("Terminology Server Batch script complete.");
			
			Multipart mp = new MimeMultipart();
			MimeBodyPart htmlPart = new MimeBodyPart();
			htmlPart.setContent(content, "text/html");
			mp.addBodyPart(htmlPart);

			MimeBodyPart attachment = new MimeBodyPart();
			InputStream attachmentDataStream = new FileInputStream(resultsFile);
			attachment.setFileName(resultsFile.getName());
			attachment.setContent(attachmentDataStream, "application/csv");
			mp.addBodyPart(attachment);
			Transport.send(msg);
		} catch (MessagingException | FileNotFoundException | UnsupportedEncodingException e) {
			info ("Failed to send email " + e.getMessage());
		}
	}

	private JSONObject convertToSavedListJson(Task task) throws JSONException, TermServerScriptException {
		JSONObject savedList = new JSONObject();
		JSONArray items = new JSONArray();
		savedList.put("items", items);
		for (Component c : task.getComponents()) {
			items.put(convertToSavedListJson((Concept)c));
		}
		return savedList;
	}

	private JSONObject convertToSavedListJson(Concept concept) throws JSONException, TermServerScriptException {
		JSONObject jsonObj = new JSONObject();
		jsonObj.put("term", concept.getPreferredSynonym());
		jsonObj.put("active", concept.isActive()?"true":"false");
		JSONObject conceptObj = new JSONObject();
		jsonObj.put("concept", conceptObj);
		conceptObj.put("active", concept.isActive()?"true":"false");
		conceptObj.put("conceptId", concept.getConceptId());
		conceptObj.put("fsn", concept.getFsn());
		conceptObj.put("moduleId", concept.getModuleId());
		conceptObj.put("defintionStatus", concept.getDefinitionStatus());
		return jsonObj;
	}
	
	/**
	 * Takes a remodelled concept and saves it as a new concept, inactivates tbe original
	 * and points to it as a replacement
	 * @throws TermServerScriptException 
	 * @throws JSONException 
	 * @throws TermServerClientException 
	 */
	protected void cloneAndReplace(Concept concept, Task t, InactivationIndicator inactivationIndicator) throws TermServerScriptException {
		String originalId = concept.getConceptId();
		Concept original = loadConcept(originalId, t.getBranchPath());
		//Clone the clone to wipe out all identifiers, just in case it retained any.
		Concept clone = concept.clone();
		clone.setActive(true); //Just incase we've cloned an inactive concept
		Concept savedConcept = createConcept(t, clone, ", clone of " + originalId);
		report (t, savedConcept, Severity.LOW, ReportActionType.CONCEPT_ADDED, "Cloned " + original);
		
		//If the original has stated children, they'll all need to be re-pointed to the clone
		for (Concept child : gl.getConcept(originalId).getChildren(CharacteristicType.STATED_RELATIONSHIP)) {
			Concept loadedChild = loadConcept(child.getConceptId(), t.getBranchPath());
			int changesMade = replaceParent(t, loadedChild, original, savedConcept);
			if (changesMade > 0) {
				updateConcept(t, loadedChild, "");
				report (t, child, Severity.HIGH, ReportActionType.RELATIONSHIP_REPLACED, "New Parent: " + savedConcept);
				//Add the child to the task, after the original
				t.addAfter(child, original);
				incrementSummaryInformation("Total children repointed to cloned concepts");
			} else {
				warn ("Locally loaded ontology thought " + concept + " had stated child " + child + " but TS disagreed.");
			}
		}
		
		//Add our clone to the task, after the original
		t.addAfter(savedConcept, original);
		
		//Now inactivate the original.
		//This will inactivate all relationships and set the DefnStatus to Primitive
		original.setActive(false);
		original.setInactivationIndicator(inactivationIndicator);
		String histAssocStr;
		switch (inactivationIndicator) {
			case AMBIGUOUS : original.setAssociationTargets(AssociationTargets.possEquivTo(savedConcept));
							histAssocStr = "Possibly Equivalent to ";
							break;
			case OUTDATED : original.setAssociationTargets(AssociationTargets.replacedBy(savedConcept));
							histAssocStr = "Replaced by ";
							break;
			default: 
				throw new TermServerScriptException("Unexpected inactivation indicator: " + inactivationIndicator);
		}
		report (t, original, Severity.LOW, ReportActionType.ASSOCIATION_ADDED, histAssocStr + savedConcept);
		
		checkAndReplaceHistoricalAssociations(t, original, savedConcept, inactivationIndicator);
		report(t, original, Severity.LOW, ReportActionType.CONCEPT_INACTIVATED);
		updateConcept(t, original, "");
	}
	
	protected void checkAndReplaceHistoricalAssociations(Task t, Concept inactivateMe, Concept replacing, InactivationIndicator inactivationIndicator) throws TermServerScriptException {
		List<AssociationEntry> histAssocs = gl.usedAsHistoricalAssociationTarget(inactivateMe);
		if (histAssocs != null && histAssocs.size() > 0) {
			for (AssociationEntry histAssoc : histAssocs) {
				Concept source = gl.getConcept(histAssoc.getReferencedComponentId());
				String assocType = gl.getConcept(histAssoc.getRefsetId()).getPreferredSynonym(US_ENG_LANG_REFSET).getTerm().replace("association reference set", "");
				String thisDetail = "Concept was as used as the " + assocType + "target of a historical association for " + source;
				thisDetail += " (since " + (histAssoc.getEffectiveTime().isEmpty()?" prospective release":histAssoc.getEffectiveTime()) + ")";
				if (replacing == null) {
					//In this case we must load the source of this incoming assertion, remove this concept as a target and 
					//if there are no associations left, set the inactivation reason to NCEP
					unpickHistoricalAssociation(t, source, inactivateMe);
				} else {
					report (t, inactivateMe, Severity.HIGH, ReportActionType.INFO, thisDetail);
					replaceHistoricalAssociation(t, source, inactivateMe, replacing, inactivationIndicator);
				}
			}
		}
	}

	private void unpickHistoricalAssociation(Task t, Concept sourceCached, Concept target) throws TermServerScriptException {
		Concept source = gl.getConcept(sourceCached.getConceptId());
		AssociationTargets targets = source.getAssociationTargets();
		int removedCount = targets.remove(target.getConceptId());
		if (removedCount != 1) {
			throw new TermServerScriptException("Unexpectedly removed " + removedCount + " associations from " + source + " when looking for " + target);
		}
		if (targets.size() == 0 && !source.getInactivationIndicator().equals(InactivationIndicator.NONCONFORMANCE_TO_EDITORIAL_POLICY)) {
			source.setInactivationIndicator(InactivationIndicator.NONCONFORMANCE_TO_EDITORIAL_POLICY);
			report (t, source, Severity.HIGH, ReportActionType.ASSOCIATION_REMOVED, "All outgoing associations removed and inactivation indicator switched to NCEP.");
		} else {
			report (t, source, Severity.HIGH, ReportActionType.ASSOCIATION_REMOVED, "No longer associated with " + target, "Remaining : " + targets.toString());
		}
		updateConcept(t, source, null);
		t.addBefore(sourceCached, target);
	}

	protected int inactivateConcept(Task t, Concept c, Concept replacement, InactivationIndicator i) throws TermServerScriptException {
		//Check if the concept we're about to inactivate is used as the target of a historical association
		//and rewire that to point to our new clone
		checkAndReplaceHistoricalAssociations(t, c, replacement, i);
		
		c.setActive(false);
		c.setInactivationIndicator(i);
		if (replacement != null) {
			c.setAssociationTargets(AssociationTargets.possEquivTo(replacement));
		}
		//Inactivated concepts must necessarily be primitive
		c.setDefinitionStatus(DefinitionStatus.PRIMITIVE);
		
		//Need to also remove any unpublished relationships
		List<Relationship> allRelationships = new ArrayList<>(c.getRelationships());
		for (Relationship r : allRelationships) {
			if (r.isActive() && (r.getEffectiveTime() == null || r.getEffectiveTime().isEmpty())) {
				c.removeRelationship(r);
			}
		}
		report (t, c, Severity.LOW, ReportActionType.CONCEPT_INACTIVATED);
		return CHANGE_MADE;
	}

	protected void replaceHistoricalAssociation(Task t, Concept concept, Concept current, Concept replacement, InactivationIndicator inactivationIndicator) throws TermServerScriptException {
		//We need a copy from the TS
		Concept loadedConcept = loadConcept(concept, t.getBranchPath());
		loadedConcept.setInactivationIndicator(inactivationIndicator);
		//Make sure we only have one current association target
		int targetCount = loadedConcept.getAssociationTargets().size();
		if (targetCount > 1) {
			report (t, concept, Severity.HIGH, ReportActionType.INFO, "Replacing 1 historical association out of " + targetCount);
		}
		AssociationTargets targets = loadedConcept.getAssociationTargets();
		targets.remove(current.getConceptId());
		
		String histAssocStr;
		switch (inactivationIndicator) {
			case AMBIGUOUS : targets.getPossEquivTo().add(replacement.getConceptId());
							histAssocStr = "possibly equivalent to ";
							break;
			case OUTDATED : targets.getReplacedBy().add(replacement.getConceptId());
							histAssocStr = "replaced by ";
							break;
			default: 
				throw new TermServerScriptException("Unexpected inactivation indicator: " + inactivationIndicator);
		}
		updateConcept(t, loadedConcept, " with re-jigged inactivation indicator and historical associations");
		report (t, loadedConcept, Severity.HIGH, ReportActionType.ASSOCIATION_ADDED, "InactReason set to " + inactivationIndicator + " and " + histAssocStr + replacement);
	}
	
	protected int checkAndSetProximalPrimitiveParent(Task t, Concept c, Concept newPPP) throws TermServerScriptException {
		return checkAndSetProximalPrimitiveParent(t, c, newPPP, false);
	}
	
	protected int checkAndSetProximalPrimitiveParent(Task t, Concept c, Concept newPPP, boolean checkOnly) throws TermServerScriptException {
		int changesMade = 0;
		
		//Do we in fact need to make any changes here?
		Set<Concept> existingParents = c.getParents(CharacteristicType.STATED_RELATIONSHIP);
		if (existingParents.size() == 1 && existingParents.contains(newPPP)) {
			if (checkOnly) {
				report (t, c, Severity.NONE, ReportActionType.NO_CHANGE, "Single stated parent is already as required - " + newPPP);
			}
			return NO_CHANGES_MADE;
		}
		
		List<Concept> ppps = determineProximalPrimitiveParents(c);
		if (ppps.size() != 1) {
			String pppsStr = ppps.stream()
					.map(p -> p.toString())
					.collect(Collectors.joining(",\n"));
			report (t, c, Severity.MEDIUM, ReportActionType.VALIDATION_CHECK, "Concept found to have " + ppps.size() + " proximal primitive parents.  Cannot state parent as: " + newPPP, pppsStr);
		} else {
			Concept ppp = ppps.get(0);
			//We need to either calculate the ppp as the intended one, or higher than it eg calculated PPP of Disease is OK if we're setting the more specific "Complication"
			if (ppp.equals(newPPP) || gl.getAncestorsCache().getAncestors(newPPP).contains(ppp)) {
				if (!checkOnly) {
					changesMade += setProximalPrimitiveParent(t, c, newPPP);
				} else {
					//If we're just checking, then yes we think we'd have made changes here
					return CHANGE_MADE;
				}
			} else {
				report (t, c, Severity.MEDIUM, ReportActionType.VALIDATION_CHECK, "Calculated PPP " + ppp + " does not match that suggested by template: " + newPPP + ", cannot remodel.");
			}
		}
		return changesMade;
	}

	private int setProximalPrimitiveParent(Task t, Concept c, Concept newParent) throws TermServerScriptException {
		int changesMade = 0;
		List<Relationship> parentRels = c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, IS_A, ActiveState.ACTIVE);
		//Do we in fact need to do anything?
		if (parentRels.size() == 1 && parentRels.get(0).getTarget().equals(newParent)) {
			report (t, c, Severity.NONE, ReportActionType.NO_CHANGE, "Concept already has template PPP: " + newParent);
		} else {
			boolean doAddition = true;
			for (Relationship r : parentRels) {
				if (r.getTarget().equals(newParent)) {
					doAddition = false;
				} else {
					//We can only remove relationships which are subsumed by the new Proximal Primitive Parent
					//OR if the current parent is a supertype of the PPP, such as when we're moving from Disease to Complication.
					//OR if the other parent itself is sufficiently defined and it's PPP is a supertype of the concepts PPP eg Clinical Finding
					Concept thisParent = gl.getConcept(r.getTarget().getConceptId());
					if (gl.getAncestorsCache().getAncestors(thisParent).contains(newParent) || 
						gl.getAncestorsCache().getAncestors(newParent).contains(thisParent)) {
						removeParentRelationship(t, r, c, newParent.toString(), null);
						changesMade++;
					} else if (parentRelationshipRedundantToPPP(thisParent, newParent)){
						report (t, c, Severity.HIGH, ReportActionType.INFO, "SD parent " + thisParent + " considered redundant with presence of " + newParent);
						removeParentRelationship(t, r, c, newParent.toString(), null);
						changesMade++;
					} else {
						report (t, c, Severity.MEDIUM, ReportActionType.NO_CHANGE, "Unable to remove parent " + thisParent + " because it it not subsumed by " + newParent );
					}
				}
			}

			if (doAddition) {
				Relationship newParentRel = new Relationship(c, IS_A, newParent, 0);
				changesMade += addRelationship(t, c, newParentRel);
			}
		}
		return changesMade;
	}
	
	private boolean parentRelationshipRedundantToPPP(Concept existingParent, Concept newParent) throws TermServerScriptException {
		AncestorsCache cache = gl.getAncestorsCache();
		//Is the existing parent's ancestry sufficiently defined up to a point that is above the new parent?
		boolean isRedundant = false;
		if (existingParent.getDefinitionStatus().equals(DefinitionStatus.FULLY_DEFINED)) {
			List<Concept> ppps = determineProximalPrimitiveParents(existingParent);
			for (Concept thisPPP : ppps) {
				if (cache.getAncestors(newParent).contains(thisPPP)) {
					isRedundant = true;
				} else {
					return false;
				}
			}
		}
		return isRedundant;
	}

	public int applyRemodelledGroups(Task t, Concept c, List<RelationshipGroup> groups) throws TermServerScriptException {
		int changesMade = 0;
		List<Relationship> availableForReuse = new ArrayList<>();
		Set<String> idsUsed = new HashSet<>();
		for (RelationshipGroup group : groups) {
			if (group != null) {
				
				//Do we need to retire any existing relationships?
				for (Relationship potentialRemoval : c.getRelationshipGroupSafely(CharacteristicType.STATED_RELATIONSHIP, group.getGroupId()).getRelationships()) {
					if (!group.getRelationships().contains(potentialRemoval)) {
						TermServerScript.warn ("Removing " + potentialRemoval + " from " + c);
						availableForReuse.add(potentialRemoval);
						removeRelationship(t, c, potentialRemoval);
						changesMade++;
					}
				}
				
				//If we've used the same relationship twice, the 2nd instance should have a new SCTID
				for (Relationship r : group.getRelationships()) {
					if (idsUsed.contains(r.getId())) {
						TermServerScript.warn ("Mutliple use of: " + r);
						r.setRelationshipId(null);
					} else if (r.getId() != null && !r.getId().isEmpty()) {
						idsUsed.add(r.getId());
					}
				}
				
				changesMade += c.addRelationshipGroup(group, availableForReuse);
			}
		}
		return changesMade;
	}
	
	protected List<Component> loadLine(String[] lineItems)
			throws TermServerScriptException {
		throw new NotImplementedException("This class self determines concepts to process");
	}
	
	public void setStandardParameters(JobParameters param) {
		
	}
	
	public void recoverStandardParameter(JobRun run) {
		
	}

}

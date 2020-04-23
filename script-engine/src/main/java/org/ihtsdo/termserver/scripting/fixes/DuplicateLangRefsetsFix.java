package org.ihtsdo.termserver.scripting.fixes;

import java.io.IOException;
import java.util.*;

import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Task;
import org.ihtsdo.termserver.scripting.ValidationFailure;
import org.ihtsdo.termserver.scripting.GraphLoader.DuplicatePair;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.Concept;
import org.ihtsdo.termserver.scripting.domain.LangRefsetEntry;

import us.monoid.json.JSONException;

public class DuplicateLangRefsetsFix extends BatchFix {
	
	protected DuplicateLangRefsetsFix(BatchFix clone) {
		super(clone);
	}

	public static void main(String[] args) throws TermServerScriptException, IOException, InterruptedException {
		DuplicateLangRefsetsFix fix = new DuplicateLangRefsetsFix(null);
		try {
			ReportSheetManager.targetFolderId = "1fIHGIgbsdSfh5euzO3YKOSeHw4QHCM-m";  //Ad-hoc batch updates
			fix.selfDetermining = true;
			fix.getArchiveManager().populateReleasedFlag = true;
			fix.init(args);
			fix.loadProjectSnapshot(false);  //Load all descriptions
			if (fix.gl.getDuplicateLangRefsetEntriesMap() == null) {
				throw new TermServerScriptException("Graph Loader did not detect any duplicate LangRefsetEntries");
			}
			fix.postInit();
			fix.processFile();
		} finally {
			fix.finish();
		}
	}

	@Override
	protected int doFix(Task t, Concept c, String info) throws TermServerScriptException {
		int changesMade = 0;
		try {
			//Find all refsetIds to be deleted for this concept
			for (DuplicatePair dups : gl.getDuplicateLangRefsetEntriesMap().get(c)) {
				LangRefsetEntry l1 = (LangRefsetEntry)dups.getKeep();
				LangRefsetEntry l2 = (LangRefsetEntry)dups.getInactivate();
				report (t, c, Severity.LOW, ReportActionType.REFSET_MEMBER_REMOVED, l2.toString(true));
				report (t, c, Severity.LOW, ReportActionType.NO_CHANGE, l1.toString(true));
				changesMade += inactivateRefsetMember(t, c, l2, info);
			}
		} catch (Exception e) {
			throw new TermServerScriptException("Failed to update refset entry for " + c, e);
		}
		return changesMade;
	}
	
	@Override
	protected List<Component> identifyComponentsToProcess() throws TermServerScriptException {
		info ("Identifying concepts to process");
		return new ArrayList<>(gl.getDuplicateLangRefsetEntriesMap().keySet());
	}

/*	public static void main(String[] args) throws JSONException, IOException, TermServerScriptException {
		String url = "http://localhost:8080/";
		//CONREQEXT-1715
		String branch = "MAIN/CONREQEXT/CONREQEXT-1740";
//		String branch = "MAIN/CONREQEXT/FIXTEST";
		TermServerClient client = new TermServerClient(url + "snowowl/snomed-ct/v2", null);
		String [] toDelete = {"2549528011","40528016","40525018","2773447011","2838341018","3289886013"};
		
		List<String> descriptionIds = new ArrayList<>();
		descriptionIds.addAll(Arrays.asList(toDelete));
		
		List<String> refsetIds = Arrays.asList("900000000000508004", "900000000000509007");

		for (String descriptionId : descriptionIds) {
			System.out.println("---------Description id:" + descriptionId + "------------");
			for (String refsetId : refsetIds) {
				System.out.println("----------Language refsetId:" + refsetId + "-------------");
				final JSONArray langRefsetMembers = client.getLangRefsetMembers(descriptionId, refsetId, branch);
				// Find newly created active duplicate member to delete
				if (langRefsetMembers.length() == 2) {
					String acceptabilityId = null;
					String duplicateRefMemberId = null;
					JSONObject langRefsetToUpdate = null;
					for (int i=0;i<langRefsetMembers.length();i++) {
						JSONObject jsonObject = langRefsetMembers.getJSONObject(i);
						System.out.println("item" + i);
						System.out.println(jsonObject);
						boolean isActive = jsonObject.getBoolean("active");
						if (isActive) {
							duplicateRefMemberId = (String)jsonObject.get("id");
							acceptabilityId = jsonObject.getString("acceptabilityId");
							
						} else {
							
							langRefsetToUpdate = jsonObject;
						}
					}
					if (duplicateRefMemberId != null) {
						System.out.println("To be deleted:");
						System.out.println(duplicateRefMemberId);
						//To delete the duplicate not released language refset member
					    client.deleteRefsetMember(duplicateRefMemberId,branch,false);
					}
					
					if (langRefsetToUpdate != null && acceptabilityId != null) {
						langRefsetToUpdate.put("active", true);
						langRefsetToUpdate.put("acceptabilityId", acceptabilityId);
						langRefsetToUpdate.put("commitComment", "mch fix");
						System.out.println("Updated:");
						System.out.println(langRefsetToUpdate);
						// update existing updated language refest component
					    client.updateRefsetMember(langRefsetToUpdate, branch,false);
					}

				} else {
					System.out.println("No duplcate found!");
				}
			}
		}
	}*/
}

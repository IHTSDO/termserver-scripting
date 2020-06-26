package org.ihtsdo.termserver.scripting.fixes;

import java.io.IOException;
import java.util.*;

import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.*;
import org.ihtsdo.termserver.scripting.GraphLoader.DuplicatePair;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.Concept;
import org.ihtsdo.termserver.scripting.domain.LangRefsetEntry;
import org.springframework.util.StringUtils;


public class DuplicateLangRefsetsFix extends BatchFix {
	
	protected DuplicateLangRefsetsFix(BatchFix clone) {
		super(clone);
	}

	public static void main(String[] args) throws TermServerScriptException, IOException, InterruptedException {
		DuplicateLangRefsetsFix fix = new DuplicateLangRefsetsFix(null);
		try {
			ReportSheetManager.targetFolderId = "1fIHGIgbsdSfh5euzO3YKOSeHw4QHCM-m";  //Ad-hoc batch updates
			fix.selfDetermining = true;
			fix.getArchiveManager().setPopulateReleasedFlag(true);
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
				if (StringUtils.isEmpty(l2.getEffectiveTime())) {
					changesMade += deleteRefsetMember(t, l2.getId());
				} else {
					l2.setActive(false);
					changesMade += inactivateRefsetMember(t, c, l2.toRefsetEntry(), info);
				}
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

}

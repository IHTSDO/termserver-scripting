package org.ihtsdo.termserver.scripting.delta;

import java.io.File;
import java.io.IOException;
import java.util.*;

import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.ValidationFailure;

import org.ihtsdo.termserver.scripting.dao.ReportManager;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;

import com.google.common.base.Charsets;
import com.google.common.io.Files;

/* INFRA-2868
 * For the time being, we don't allow historical association indicators to be reused,
 * but they are.
 * So we must take the current values and copy them into a new record with a new UUID
 * then revert the existing record to its previous state (pulling from associationrefset_f)
 * and inactivate it.
 * 
 * The file can be created with:
		select ad.id, ad.active, ad.refsetid, ad.referencedcomponentid, 
		ad.targetcomponentid, af.effectiveTime, af.active, af.refsetid, 
		af.targetComponentId
		from associationrefset_d ad, associationrefset_f af
		where ad.id = af.id
		and af.effectiveTime < ad.effectiveTime
		and af.effectiveTime = (select max(`effectivetime`) from associationrefset_f af2 where ad.id = af2.id and effectiveTime < 20190131)
		and ( not ad.refsetid = af.refsetid OR not ad.referencedComponentid = af.referencedComponentid OR not ad.targetComponentid = af.targetComponentid )
*/
public class FixReusedAssociations extends DeltaGenerator implements RF2Constants{
	
	enum IDX { ORIG_ID, NEW_ACTIVE, NEW_REFSETID, NEW_SCTID, NEW_TARGET, ORIG_EFFECTIVE_TIME,
		ORIG_ACTIVE, ORIG_REFSETID, ORIG_TARGET }
	
	public static void main(String[] args) throws TermServerScriptException, IOException, InterruptedException {
		FixReusedAssociations delta = new FixReusedAssociations();
		try {
			ReportSheetManager.targetFolderId = "1fIHGIgbsdSfh5euzO3YKOSeHw4QHCM-m"; //Ad-Hoc Batch Updates
			delta.newIdsRequired = false; // We'll only be modifying existing descriptions
			delta.init(args);
			delta.getArchiveManager().setAllowStaleData(true);
			delta.loadProjectSnapshot(false); //Need all descriptions loaded.
			delta.process();
			delta.flushFiles(false, true); //Need to flush files before zipping
			SnomedUtils.createArchive(new File(delta.outputDirName));
		} finally {
			delta.finish();
		}
	}

	private void process() throws ValidationFailure, TermServerScriptException, IOException {
		//Work through all inactive concepts and check the inactivation indicator on all
		//active descriptions
		List<String> lines = Files.readLines(inputFile, Charsets.UTF_8);
		for (String line : lines) {
			String[] columns = line.split(TAB);
			Concept c = gl.getConcept(columns[IDX.NEW_SCTID.ordinal()]);
			//Firstly, write a row that reverts the original row back to it's previous values, but inactive
			AssociationEntry origAssoc = new AssociationEntry();
			origAssoc.setId(columns[IDX.ORIG_ID.ordinal()]);
			//EffectiveTime will be null, active flag will be null
			origAssoc.setActive(false);
			origAssoc.setModuleId(SCTID_CORE_MODULE);
			origAssoc.setRefsetId(columns[IDX.ORIG_REFSETID.ordinal()]);
			origAssoc.setTargetComponentId(columns[IDX.ORIG_TARGET.ordinal()]);
			origAssoc.setReferencedComponentId(columns[IDX.NEW_SCTID.ordinal()]);
			origAssoc.setDirty();
			outputRF2(origAssoc);
			report (c, "Recreating original data, inactive", origAssoc.toRF2());
			
			//Now if the new value is not active, we don't want to create a born inactive component
			if (columns[IDX.NEW_ACTIVE.ordinal()].equals("1")) {
				AssociationEntry newAssoc = origAssoc.clone();
				newAssoc.setRefsetId(columns[IDX.NEW_REFSETID.ordinal()]);
				newAssoc.setTargetComponentId(columns[IDX.NEW_TARGET.ordinal()]);
				newAssoc.setActive(true);
				newAssoc.setDirty();
				outputRF2(newAssoc);
				report (c, "Regenerating new data with own UUID", newAssoc.toRF2());
			} else {
				report (c, "New values marked as inactive, leaving original", columns[IDX.ORIG_ID.ordinal()]);
			}
		}
	}

}

package org.ihtsdo.termserver.scripting.delta;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.apache.commons.lang.StringUtils;
import org.ihtsdo.termserver.scripting.IdGenerator;
import org.ihtsdo.termserver.scripting.TermServerScript;
import org.ihtsdo.termserver.scripting.TermServerScriptException;
import org.ihtsdo.termserver.scripting.client.SnowOwlClientException;
import org.ihtsdo.termserver.scripting.domain.Concept;
import org.ihtsdo.termserver.scripting.domain.Description;
import org.ihtsdo.termserver.scripting.domain.InactivationIndicatorEntry;
import org.ihtsdo.termserver.scripting.domain.LangRefsetEntry;
import org.ihtsdo.termserver.scripting.domain.Relationship;

public abstract class DeltaGenerator extends TermServerScript {
	
	protected String outputDirName = "output";
	protected String packageRoot;
	protected String today = new SimpleDateFormat("yyyyMMdd").format(new Date());
	protected String packageDir;
	protected String conDeltaFilename;
	protected String relDeltaFilename;
	protected String attribValDeltaFilename;
	protected String sRelDeltaFilename;
	protected String descDeltaFilename;
	protected String langDeltaFilename;
	protected String edition = "INT";
	
	protected String languageCode = "en";
	protected boolean isExtension = false;
	protected boolean newIdsRequired = true;
	protected String moduleId="900000000000207008";
	protected String nameSpace="0";
	protected String[] langRefsetIds = new String[] { "900000000000508004",  //GB
											"900000000000509007" }; //US


	
	protected String[] conHeader = new String[] {"id","effectiveTime","active","moduleId","definitionStatusId"};
	protected String[] descHeader = new String[] {"id","effectiveTime","active","moduleId","conceptId","languageCode","typeId","term","caseSignificanceId"};
	protected String[] relHeader = new String[] {"id","effectiveTime","active","moduleId","sourceId","destinationId","relationshipGroup","typeId","characteristicTypeId","modifierId"};
	protected String[] langHeader = new String[] {"id","effectiveTime","active","moduleId","refsetId","referencedComponentId","acceptabilityId"};
	protected String[] attribValHeader = new String[] {"id","effectiveTime","active","moduleId","refsetId","referencedComponentId","valueId"};

	protected IdGenerator conIdGenerator;
	protected IdGenerator descIdGenerator;
	protected IdGenerator relIdGenerator;
	
	protected void report(Concept concept, Description d, Severity severity, ReportActionType actionType, String actionDetail) {
		String line = "";
		
		if (d==null) {
			line = concept.getConceptId() + COMMA + COMMA_QUOTE + 
					concept.getFsn() + QUOTE_COMMA_QUOTE; 
		} else {
			line = concept.getConceptId() + COMMA + 
				d.getDescriptionId() + COMMA_QUOTE + 
				d.getTerm() + QUOTE_COMMA_QUOTE ; 
		}
		line += severity + QUOTE_COMMA_QUOTE + 
				actionType.toString() + QUOTE_COMMA_QUOTE +
				actionDetail + QUOTE;
		writeToReportFile(line);
	}
	
	protected void report(Concept concept, Severity severity, ReportActionType actionType, String actionDetail) {
		report (concept, concept.getFSNDescription(), severity, actionType, actionDetail);
	}
	
	protected void init (String[] args) throws IOException, TermServerScriptException, SnowOwlClientException, SnowOwlClientException {
		super.init(args);
		
		for (int x=0; x<args.length; x++) {
			if (args[x].equals("-m")) {
				moduleId = args[++x];
			}
			if (args[x].equals("-iC")) {
				conIdGenerator = IdGenerator.initiateIdGenerator(args[++x], PartitionIdentifier.CONCEPT);
				conIdGenerator.setNamespace(nameSpace);
				conIdGenerator.isExtension(isExtension);
			}
			if (args[x].equals("-iD")) {
				descIdGenerator = IdGenerator.initiateIdGenerator(args[++x], PartitionIdentifier.DESCRIPTION);
				descIdGenerator.setNamespace(nameSpace);
				descIdGenerator.isExtension(isExtension);
			}
			if (args[x].equals("-iR")) {
				relIdGenerator = IdGenerator.initiateIdGenerator(args[++x], PartitionIdentifier.RELATIONSHIP);
				relIdGenerator.setNamespace(nameSpace);
				relIdGenerator.isExtension(isExtension);
			}
		}

		print ("Targetting which namespace? [" + nameSpace + "]: ");
		String response = STDIN.nextLine().trim();
		if (!response.isEmpty()) {
			nameSpace = response;
		}
		
		print ("Targetting which moduleId? [" + moduleId + "]: ");
		response = STDIN.nextLine().trim();
		if (!response.isEmpty()) {
			moduleId = response;
		}
		
		print ("Targetting which language code? [" + languageCode + "]: ");
		response = STDIN.nextLine().trim();
		if (!response.isEmpty()) {
			languageCode = response;
		}
		
		String langRefsetIdStr = StringUtils.join(langRefsetIds, ",");  
		print ("Targetting which language refset(s)? [" + langRefsetIdStr + "]: ");
		response = STDIN.nextLine().trim();
		if (!response.isEmpty()) {
			langRefsetIds = response.split(COMMA);
		}
		
		print ("What's the Edition? [" + edition + "]: ");
		response = STDIN.nextLine().trim();
		if (!response.isEmpty()) {
			edition = response;
		}
		
		if (moduleId.isEmpty() || langRefsetIds  == null) {
			String msg = "Require both moduleId and langRefset Id to be specified (-m -l parameters)";
			throw new TermServerScriptException(msg);
		}
		
		if (newIdsRequired && descIdGenerator == null) {
			throw new TermServerScriptException("Command line arguments must supply a list of available sctid using the -iC/D/R option");
		}
		initialiseReportFile("Concept,DescSctId,Term,Severity,Action,Detail");
		//Don't add to previously exported data
		File outputDir = new File (outputDirName);
		int increment = 0;
		while (outputDir.exists()) {
			String proposedOutputDirName = outputDirName + "_" + (++increment) ;
			outputDir = new File(proposedOutputDirName);
		}
		outputDirName = outputDir.getName();
		packageRoot = outputDirName + File.separator + "SnomedCT_RF2Release_" + edition +"_";
		packageDir = packageRoot + today + File.separator;
		println ("Outputting data to " + packageDir);
		initialiseFileHeaders();
	}
	
	public void finish() {
		super.finish();
		if (conIdGenerator != null) {
			println(conIdGenerator.finish());
		}
		if (descIdGenerator != null) {
			println(descIdGenerator.finish());
		}
		if (relIdGenerator != null) {
			println(relIdGenerator.finish());
		}
	}
	
	protected void initialiseFileHeaders() throws TermServerScriptException {
		String termDir = packageDir +"Delta/Terminology/";
		String refDir =  packageDir +"Delta/Refset/";
		conDeltaFilename = termDir + "sct2_Concept_Delta_"+edition+"_" + today + ".txt";
		writeToRF2File(conDeltaFilename, conHeader);
		
		relDeltaFilename = termDir + "sct2_Relationship_Delta_"+edition+"_" + today + ".txt";
		writeToRF2File(relDeltaFilename, relHeader);

		sRelDeltaFilename = termDir + "sct2_StatedRelationship_Delta_"+edition+"_" + today + ".txt";
		writeToRF2File(sRelDeltaFilename, relHeader);
		
		descDeltaFilename = termDir + "sct2_Description_Delta-"+languageCode+"_"+edition+"_" + today + ".txt";
		writeToRF2File(descDeltaFilename, descHeader);
		
		langDeltaFilename = refDir + "Language/der2_cRefset_LanguageDelta-"+languageCode+"_"+edition+"_" + today + ".txt";
		writeToRF2File(langDeltaFilename, langHeader);
		
		attribValDeltaFilename = refDir + "Content/der2_cRefset_AttributeValueDelta_"+edition+"_" + today + ".txt";
		writeToRF2File(attribValDeltaFilename, attribValHeader);
	}

	protected void outputRF2(Description d) throws TermServerScriptException {
		if (d.isDirty()) {
			writeToRF2File(descDeltaFilename, d.toRF2());
		}
		for (LangRefsetEntry lang : d.getLangRefsetEntries()) {
			if (lang.isDirty()) {
				writeToRF2File(langDeltaFilename, lang.toRF2());
			}
		}
	}

	protected void outputRF2(Relationship r) throws TermServerScriptException {
		if (r.isDirty()) {
			switch (r.getCharacteristicType()) {
				case STATED_RELATIONSHIP : writeToRF2File(sRelDeltaFilename, r.toRF2());
				break;
				case INFERRED_RELATIONSHIP : 
				default: writeToRF2File(relDeltaFilename, r.toRF2());
			}
		}
	}
	
	protected void outputRF2(InactivationIndicatorEntry i) throws TermServerScriptException {
		if (i.isDirty()) {
			writeToRF2File(attribValDeltaFilename, i.toRF2());
		}
	}

	
	protected void outputRF2(Concept c) throws TermServerScriptException {
		if (c.isDirty()) {
			writeToRF2File(conDeltaFilename, c.toRF2());
		}
		
		for (Description d : c.getDescriptions(ActiveState.BOTH)) {
			outputRF2(d);  //Will output langrefset in turn
		}
		
		for (Relationship r : c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, ActiveState.BOTH)) {
			outputRF2(r);
		}
		
		for (InactivationIndicatorEntry i: c.getInactivationIndicatorEntries()) {
			outputRF2(i);
		}
	}

}
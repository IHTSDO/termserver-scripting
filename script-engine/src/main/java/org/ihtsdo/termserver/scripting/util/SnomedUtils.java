package org.ihtsdo.termserver.scripting.util;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.validator.routines.checkdigit.VerhoeffCheckDigit;
import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component.ComponentType;
import org.ihtsdo.termserver.scripting.AncestorsCache;
import org.ihtsdo.termserver.scripting.DescendantsCache;
import org.ihtsdo.termserver.scripting.GraphLoader;
import org.ihtsdo.termserver.scripting.TermServerScript;
import org.ihtsdo.termserver.scripting.domain.*;

import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SnomedUtils implements RF2Constants {

	private static final SimpleDateFormat EFFECTIVE_DATE_FORMAT = new SimpleDateFormat("yyyyMMdd");
	private static VerhoeffCheckDigit verhoeffCheck = new VerhoeffCheckDigit();
	
	public static String isValid(String sctId, PartitionIdentifier partitionIdentifier) {
		String errorMsg=null;
		
		if (!StringUtils.isNumeric(sctId)) {
			return "SCTID is not entirely numeric: '" + sctId + "'";
		}
		
		//Are we checking a specific partition?
		if (partitionIdentifier != null) {
			int partitionNumber = Integer.valueOf("" + sctId.charAt(sctId.length() -2));
			if (partitionNumber != partitionIdentifier.ordinal()) {
				errorMsg = sctId + " does not exist in partition " + partitionIdentifier.toString();
			}
		}
		
		if (!verhoeffCheck.isValid(sctId)) {
			errorMsg = sctId + " does not exhibit a valid check digit";
		}
		return errorMsg;
	}

	public static void isValid(String sctId, PartitionIdentifier partitionIdentifier,
			boolean errorIfInvalid) throws TermServerScriptException {
		String errMsg = isValid(sctId,partitionIdentifier);
		if (errorIfInvalid && errMsg != null) {
			throw new TermServerScriptException(errMsg);
		}
	}
	
	public static String[] deconstructFSN(String fsn) {
		return deconstructFSN(fsn, false);  //Noisy by default
	}

	public static String[] deconstructFSN(String fsn, boolean quiet) {
		String[] elements = new String[2];
		if (fsn == null) {
			return elements;
		}
		int cutPoint = fsn.lastIndexOf(SEMANTIC_TAG_START);
		if (cutPoint == -1) {
			if (!quiet) {
				System.out.println("'" + fsn + "' does not contain a semantic tag!");
			}
			elements[0] = fsn;
		} else {
			elements[0] = fsn.substring(0, cutPoint).trim();
			elements[1] = fsn.substring(cutPoint);
		}
		return elements;
	}
	
	public static String toString(Map<String, Acceptability> acceptabilityMap) {
		if (acceptabilityMap == null) {
			return "";
		}
		try {
			String US = "N";
			String GB = "N";
			if (acceptabilityMap.containsKey(US_ENG_LANG_REFSET)) {
				US = translateAcceptability(acceptabilityMap.get(US_ENG_LANG_REFSET));
			}
			
			if (acceptabilityMap.containsKey(GB_ENG_LANG_REFSET)) {
				GB = translateAcceptability(acceptabilityMap.get(GB_ENG_LANG_REFSET));
			}
			
			return "US: " + US + ", GB: " + GB;
		} catch (TermServerScriptException e) {
			System.out.println("Failed to convert acceptability map to string: " + e);
		}
		return "";
	}
	
	public static String translateAcceptability (Acceptability a) throws TermServerScriptException {
		if (a.equals(Acceptability.PREFERRED)) {
			return "P";
		}
		
		if (a.equals(Acceptability.ACCEPTABLE)) {
			return "A";
		}
		throw new TermServerScriptException("Unable to translate Acceptability " + a);
	}
	
	public static String translateAcceptabilityToSCTID(Acceptability a) throws TermServerScriptException {
		if (a.equals(Acceptability.PREFERRED)) {
			return SCTID_PREFERRED_TERM;
		}
		
		if (a.equals(Acceptability.ACCEPTABLE)) {
			return SCTID_ACCEPTABLE_TERM;
		}
		throw new TermServerScriptException("Unable to translate Acceptability " + a);
	}
	
	public static Acceptability translateAcceptabilityFromChar (char a) throws TermServerScriptException {
		if (a == 'P') {
			return Acceptability.PREFERRED;
		}
		
		if (a == 'A') {
			return Acceptability.ACCEPTABLE;
		}
		
		if (a == 'N') {
			return null;
		}
		throw new TermServerScriptException("Unable to translate Acceptability " + a);
	}
	
	public static Acceptability translateAcceptability(String sctid) throws TermServerScriptException {
		if (sctid.equals(SCTID_ACCEPTABLE_TERM)) {
			return Acceptability.ACCEPTABLE;
		}
		
		if (sctid.equals(SCTID_PREFERRED_TERM)) {
			return Acceptability.PREFERRED;
		} 
		
		throw new TermServerScriptException("Unable to translate Acceptability '" + sctid + "'");
	}
	
	public static String[] translateLangRefset(Description d) throws TermServerScriptException {
		String[] acceptabilities = new String[] {"N","N"};
		for (LangRefsetEntry entry : d.getLangRefsetEntries(ActiveState.ACTIVE)) {
			int idx = entry.getRefsetId().equals(US_ENG_LANG_REFSET)?0:1;
			Acceptability a = translateAcceptability(entry.getAcceptabilityId());
			switch (a) {
				case ACCEPTABLE : acceptabilities[idx] = "A";
									break;
				case PREFERRED : acceptabilities[idx] = "P";
									break;
				default :
					throw new TermServerScriptException("Unable to translate Acceptability '" + a + "'");
			}
		}
		return acceptabilities;
	}

	public static Map<String, Acceptability> createAcceptabilityMap(Acceptability acceptability, String[] dialects) {
		Map<String, Acceptability> aMap = new HashMap<String, Acceptability>();
		for (String dialect : dialects) {
			aMap.put(dialect, acceptability);
		}
		return aMap;
	}
	
	public static Map<String, Acceptability> createPreferredAcceptableMap(String preferredRefset, String acceptableRefset) {
		Map<String, Acceptability> aMap = new HashMap<String, Acceptability>();
		aMap.put(preferredRefset, Acceptability.PREFERRED);
		aMap.put(acceptableRefset, Acceptability.ACCEPTABLE);
		return aMap;
	}
	
	/**
	 * Merge two Acceptability maps such that a PREFERRED overrides an ACCEPTABLE
	 * AND ACCEPTABLE overrides not acceptable.
	 */
	public static Map<String, Acceptability> mergeAcceptabilityMap (Map<String, Acceptability> left, Map<String, Acceptability> right) {
		Set<String> dialects = new HashSet<String>();
		dialects.addAll(left.keySet());
		dialects.addAll(right.keySet());
		Map<String, Acceptability> merged = new HashMap<String, Acceptability>();
		
		for (String thisDialect : dialects) {
			if (!left.containsKey(thisDialect) && right.containsKey(thisDialect)) {
				merged.put(thisDialect, right.get(thisDialect));
			} 
			if (!right.containsKey(thisDialect) && left.containsKey(thisDialect)) {
				merged.put(thisDialect, left.get(thisDialect));
			} 
			if (left.containsKey(thisDialect) && right.containsKey(thisDialect)) {
				if (left.get(thisDialect).equals(Acceptability.PREFERRED) || right.get(thisDialect).equals(Acceptability.PREFERRED)) {
					merged.put(thisDialect, Acceptability.PREFERRED);
				} else {
					merged.put(thisDialect, Acceptability.ACCEPTABLE);
				}
			}
		}
		return merged;
	}
	
	public static void demoteAcceptabilityMap (Description d) {
		for (String refset : d.getAcceptabilityMap().keySet()) {
			d.getAcceptabilityMap().put(refset, Acceptability.ACCEPTABLE);
		}
	}
	
	public static Map<String, Acceptability> mergeAcceptabilityMap (Description left,Description right) {
		return mergeAcceptabilityMap(left.getAcceptabilityMap(), right.getAcceptabilityMap());
	}
		
	
	/**
	 * 2 points for preferred, 1 point for acceptable
	 */
	public static int accetabilityScore (Map<String, Acceptability> AcceptabilityMap) {
		int score = 0;
		for (Acceptability a : AcceptabilityMap.values()) {
			if (a.equals(Acceptability.PREFERRED)) {
				score += 2;
			} else if (a.equals(Acceptability.ACCEPTABLE)) {
				score += 1;
			}
		}
		return score;
	}
	
	public static String initialCapitalOnly (String str) {
		if (str == null || str.isEmpty() || str.length() < 2) {
			return str;
		}
		return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
	}
	
	/**
	 * @return an array of 3 elements containing:  The path, the filename, the file extension (if it exists) or empty strings
	 */
	public static String[] deconstructFilename(File file) {
		String[] parts = new String[] {"","",""};
		
		if (file== null) {
			return parts;
		}
		parts[0] = file.getAbsolutePath().substring(0, file.getAbsolutePath().lastIndexOf(File.separator));
		if (file.getName().lastIndexOf(".") > 0) {
			parts[1] = file.getAbsolutePath().substring(parts[0].length() + 1, file.getAbsolutePath().lastIndexOf("."));
			parts[2] = file.getAbsolutePath().substring(file.getAbsolutePath().lastIndexOf(".") + 1);
		} else {
			parts[1] = file.getName();
		}
		
		return parts;
	}

	public static String translateDescType(DescriptionType type) throws TermServerScriptException {
		switch (type) {
			case FSN : return FSN;
			case SYNONYM : return SYN;
			case TEXT_DEFINITION : return DEF;
		}
		throw new TermServerScriptException("Unable to translate description type " + type);
	}

	public static DescriptionType translateDescType(String descTypeId) throws TermServerScriptException {
		switch (descTypeId) {
			case FSN : return DescriptionType.FSN;
			case SYN : return DescriptionType.SYNONYM;
			case DEF : return DescriptionType.TEXT_DEFINITION; 
		}
		throw new TermServerScriptException("Unable to translate description type: " + descTypeId);
	}
	
	public static String getStackTrace (Exception e) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		return sw.toString(); // stack trace as a string
	}
	
	public static File createArchive(File dirToZip) throws TermServerScriptException {
		File outputFile;
		try {
			// The zip filename will be the name of the first thing in the zip location
			// ie in this case the directory SnomedCT_RF1Release_INT_20150731
			String zipFileName = dirToZip.listFiles()[0].getName() + ".zip";
			int fileNameModifier = 1;
			while (new File(zipFileName).exists()) {
				zipFileName = dirToZip.listFiles()[0].getName() + "_" + fileNameModifier++ + ".zip";
			}
			outputFile = new File(zipFileName);
			ZipOutputStream out = new ZipOutputStream(new FileOutputStream(outputFile));
			String rootLocation = dirToZip.getAbsolutePath() + File.separator;
			TermServerScript.info("Creating archive : " + zipFileName + " from files found in " + rootLocation);
			addDir(rootLocation, dirToZip, out);
			out.close();
		} catch (IOException e) {
			throw new TermServerScriptException("Failed to create archive from " + dirToZip, e);
		} finally {
			try {
				FileUtils.deleteDirectory(dirToZip);
			} catch (IOException e) {}
		}
		TermServerScript.info("Created archive: " + outputFile);
		
		return outputFile;
	}
	
	public static void addDir(String rootLocation, File dirObj, ZipOutputStream out) throws IOException {
		File[] files = dirObj.listFiles();
		byte[] tmpBuf = new byte[1024];

		for (int i = 0; i < files.length; i++) {
			if (files[i].isDirectory()) {
				addDir(rootLocation, files[i], out);
				continue;
			}
			FileInputStream in = new FileInputStream(files[i].getAbsolutePath());
			String relativePath = files[i].getAbsolutePath().substring(rootLocation.length());
			TermServerScript.debug(" Adding: " + relativePath);
			out.putNextEntry(new ZipEntry(relativePath));
			int len;
			while ((len = in.read(tmpBuf)) > 0) {
				out.write(tmpBuf, 0, len);
			}
			out.closeEntry();
			in.close();
		}
	}
	
	public static boolean conceptHasActiveState(Concept c, ActiveState a) {
		boolean hasActiveState = false;
		if (a.equals(ActiveState.BOTH) ||
			(a.equals(ActiveState.ACTIVE) && c.isActive()) ||
			(a.equals(ActiveState.INACTIVE) && !c.isActive())) {
			hasActiveState = true;
		}
		return hasActiveState;
	}
	//TODO See if the JSON will allow us to create the abstract "Component" which allows us to do this with one function
	public static boolean descriptionHasActiveState(Description d, ActiveState a) {
		boolean hasActiveState = false;
		if (a.equals(ActiveState.BOTH) ||
			(a.equals(ActiveState.ACTIVE) && d.isActive()) ||
			(a.equals(ActiveState.INACTIVE) && !d.isActive())) {
			hasActiveState = true;
		}
		return hasActiveState;
	}

	//Merge the lang refset entries of a into b such that b obtains the 
	//union of the two sets and preferred trumps acceptable
	public static boolean mergeLangRefsetEntries(Description a,
			Description b) {
		boolean changesMade = false;
		for (LangRefsetEntry la : a.getLangRefsetEntries(ActiveState.ACTIVE)) {
			boolean bHasThis = false;
			//First check for existing active entries.  If they're not found, search inactive
			for (LangRefsetEntry lb : b.getLangRefsetEntries(ActiveState.ACTIVE)) {
				if (lb.getRefsetId().equals(la.getRefsetId())) {
					bHasThis = true;
					if (!lb.getAcceptabilityId().equals(la.getAcceptabilityId())) {
						if (la.getAcceptabilityId().equals(SCTID_PREFERRED_TERM)) {
							lb.setAcceptabilityId(SCTID_PREFERRED_TERM);
							lb.setEffectiveTime(null);
							changesMade = true;
						}
					}
				}
			}
			
			if (!bHasThis) {
				for (LangRefsetEntry lb : b.getLangRefsetEntries(ActiveState.INACTIVE)) {
					if (lb.getRefsetId().equals(la.getRefsetId())) {
						bHasThis = true;
						lb.setActive(true);
						changesMade = true;
						lb.setEffectiveTime(null);
						//As well as activating, are we also promoting to preferred?
						if (!lb.getAcceptabilityId().equals(la.getAcceptabilityId())) {
							if (la.getAcceptabilityId().equals(SCTID_PREFERRED_TERM)) {
								lb.setAcceptabilityId(SCTID_PREFERRED_TERM);
							}
						}
					}
				}
			}
			
			//If we didn't find any existing lang refset for this dialect then copy it from "a"
			if (!bHasThis) {
				b.getLangRefsetEntries().add(la.clone(b.getDescriptionId(), false));
				changesMade = true;
			}
		}
		return changesMade;
	}

	public static String translateCharacteristicType(
			CharacteristicType characteristicType) {
		switch (characteristicType) {
			case STATED_RELATIONSHIP : return SCTID_STATED_RELATIONSHIP;
			case INFERRED_RELATIONSHIP : return SCTID_INFERRED_RELATIONSHIP;
			case QUALIFYING_RELATIONSHIP : return SCTID_QUALIFYING_RELATIONSHIP;
			case ADDITIONAL_RELATIONSHIP : return SCTID_ADDITIONAL_RELATIONSHIP;
			default : return "";
		}
	}
	
	public static CharacteristicType translateCharacteristicType(String characteristicTypeId) {
		switch (characteristicTypeId) {
			case SCTID_STATED_RELATIONSHIP : return CharacteristicType.STATED_RELATIONSHIP;
			case SCTID_INFERRED_RELATIONSHIP : return CharacteristicType.INFERRED_RELATIONSHIP;
			case SCTID_QUALIFYING_RELATIONSHIP : return CharacteristicType.QUALIFYING_RELATIONSHIP;
			case SCTID_ADDITIONAL_RELATIONSHIP : return CharacteristicType.ADDITIONAL_RELATIONSHIP;
			default : throw new IllegalArgumentException("Unexpected characteristicTypeId " + characteristicTypeId);
		}
	}

	public static String translateModifier(Modifier modifier) {
		switch (modifier) {
			case UNIVERSAL : return SCTID_UNIVERSAL_MODIFIER;
			case EXISTENTIAL :
			default : return SCTID_EXISTENTIAL_MODIFIER;
		}
	}
	
	public static Modifier translateModifier(String modifierSCTID) {
		switch (modifierSCTID) {
			case SCTID_UNIVERSAL_MODIFIER : return Modifier.UNIVERSAL;
			case SCTID_EXISTENTIAL_MODIFIER  :
			default : return Modifier.EXISTENTIAL;
		}
	}
	

	public static DefinitionStatus translateDefnStatus(String defnStatusSctId) {
		switch (defnStatusSctId) {
			case SCTID_PRIMITIVE : return DefinitionStatus.PRIMITIVE;
			case SCTID_FULLY_DEFINED: return DefinitionStatus.FULLY_DEFINED;
			default:
		}
		return null;
	}
	
	public static String translateDefnStatusToSctid(DefinitionStatus defn) {
		switch (defn) {
			case PRIMITIVE: return SCTID_PRIMITIVE;
			case FULLY_DEFINED: return SCTID_FULLY_DEFINED;
			default:
		}
		return null;
	}
	
	public static String translateDefnStatus(DefinitionStatus defn) {
		switch (defn) {
			case PRIMITIVE: return "P";
			case FULLY_DEFINED: return "SD";
			default:
		}
		return null;
	}
	
	public static DefinitionStatus translateDefnStatusStr(String defnStr) throws TermServerScriptException {
		switch (defnStr) {
			case "P": return DefinitionStatus.PRIMITIVE;
			case "FD":
			case "SD": return DefinitionStatus.FULLY_DEFINED;
			default: throw new TermServerScriptException("Unrecognised definition status :" + defnStr);
		}
	}
	

	public static boolean translateActive(ActiveState active) throws TermServerScriptException {
		switch (active) {
			case ACTIVE : return true;
			case INACTIVE : return false;
			default: throw new TermServerScriptException("Unable to translate " + active + " into boolean state");
		}
	}

	public static ChangeStatus promoteLangRefsetEntries(Description d, String[] dialects) throws TermServerScriptException {
		//For each dialect check if we have an active existing entry that could be modified, 
		//inactive entry that could be activated and/or modified
		//or add a new entry
		ChangeStatus changeStatus = ChangeStatus.NO_CHANGE_MADE;
		for (String thisDialectSctid : dialects) {
			//Do we have an active entry we could change?
			List<LangRefsetEntry> langRefsetEntries = d.getLangRefsetEntries(ActiveState.ACTIVE, thisDialectSctid);
			if (langRefsetEntries.size() >1) {
				throw new TermServerScriptException("Description has more than one active langrefset entry for a given dialect: " + d);
			}
			changeStatus = promoteLangRefsetEntry(d, langRefsetEntries);
			//Have we achieved a PT?  Activate inactive term if not.
			if (changeStatus.equals(ChangeStatus.NO_CHANGE_MADE)) {
				changeStatus = promoteLangRefsetEntry(d, d.getLangRefsetEntries(ActiveState.INACTIVE, thisDialectSctid));
			}
			//Still no?  Create a new entry
			if (changeStatus.equals(ChangeStatus.NO_CHANGE_MADE)) {
				createLangRefsetEntry(d,thisDialectSctid, SCTID_PREFERRED_TERM);
				changeStatus = ChangeStatus.CHANGE_MADE;
			}
		}
		return changeStatus;
	}

	private static void createLangRefsetEntry(Description d,
			String thisDialectSctid, String AcceptabilitySctid) {
		LangRefsetEntry l = new LangRefsetEntry();
		l.setId(UUID.randomUUID().toString());
		l.setActive(true);
		l.setModuleId(d.getModuleId());
		l.setRefsetId(thisDialectSctid);
		l.setReferencedComponentId(d.getDescriptionId());
		l.setDirty();
		d.getLangRefsetEntries().add(l);
	}

	private static ChangeStatus promoteLangRefsetEntry(Description d, List<LangRefsetEntry> langRefsetEntries) throws TermServerScriptException {
		ChangeStatus changeStatus = ChangeStatus.NO_CHANGE_MADE;
		for (LangRefsetEntry thisDialectEntry : langRefsetEntries) {
			if (!thisDialectEntry.getAcceptabilityId().equals(SCTID_PREFERRED_TERM)) {
				thisDialectEntry.setAcceptabilityId(SCTID_PREFERRED_TERM);
				thisDialectEntry.setEffectiveTime(null);
				thisDialectEntry.setActive(true);
				thisDialectEntry.isDirty();
				changeStatus = ChangeStatus.CHANGE_MADE;
				break;
			} else {
				if (!thisDialectEntry.isActive()) {
					thisDialectEntry.setEffectiveTime(null);
					thisDialectEntry.setActive(true);
					changeStatus = ChangeStatus.CHANGE_MADE;
					break;
				} else {
					//dialect is preferred and is alreayd active so no change needed. Say this so that we don't try anything else
					changeStatus = ChangeStatus.CHANGE_NOT_REQUIRED;
				}
			}
		}
		return changeStatus;
	}
	
	public static CaseSignificance translateCaseSignificance(String caseSignificanceIndicatorStr) throws TermServerScriptException {
		switch (caseSignificanceIndicatorStr) {
			case "ENTIRE_TERM_CASE_SENSITIVE" : return CaseSignificance.ENTIRE_TERM_CASE_SENSITIVE;
			case "CASE_INSENSITIVE" : return CaseSignificance.CASE_INSENSITIVE;
			case "INITIAL_CHARACTER_CASE_INSENSITIVE" : return CaseSignificance.INITIAL_CHARACTER_CASE_INSENSITIVE;
			default :
		}
		throw new TermServerScriptException("Do not recognise case significance indicator : " + caseSignificanceIndicatorStr);
	}
	
	public static CaseSignificance translateCaseSignificanceFromString(String caseSignificanceIndicatorStr) throws TermServerScriptException {
		switch (caseSignificanceIndicatorStr) {
			case "CS" : return CaseSignificance.ENTIRE_TERM_CASE_SENSITIVE;
			case "ci" : return CaseSignificance.CASE_INSENSITIVE;
			case "cI" : return CaseSignificance.INITIAL_CHARACTER_CASE_INSENSITIVE;
			default :
		}
		throw new TermServerScriptException("Do not recognise case significance indicator : " + caseSignificanceIndicatorStr);
	}
	
	public static String translateCaseSignificanceToSctId(String caseSignificanceIndicator) throws TermServerScriptException {
		switch (caseSignificanceIndicator) {
			case "CS" : return SCTID_ENTIRE_TERM_CASE_SENSITIVE;
			case "ci" : return SCTID_ENTIRE_TERM_CASE_INSENSITIVE;
			case "cl" :
			case "cI" : return SCTID_ONLY_INITIAL_CHAR_CASE_INSENSITIVE;
			default :
		}
		throw new TermServerScriptException("Do not recognise case significance indicator : " + caseSignificanceIndicator);
	}
	
	public static String translateCaseSignificanceToSctId(CaseSignificance caseSignificance) throws TermServerScriptException {
		switch (caseSignificance) {
			case ENTIRE_TERM_CASE_SENSITIVE : return SCTID_ENTIRE_TERM_CASE_SENSITIVE;
			case CASE_INSENSITIVE : return SCTID_ENTIRE_TERM_CASE_INSENSITIVE;
			case INITIAL_CHARACTER_CASE_INSENSITIVE : return SCTID_ONLY_INITIAL_CHAR_CASE_INSENSITIVE;
			default :
		}
		throw new TermServerScriptException("Do not recognise case significance indicator : " + caseSignificance);
	}

	public static String translateCaseSignificanceFromSctId(
			String caseSignificanceSctId) throws TermServerScriptException {
		switch (caseSignificanceSctId) {
			case SCTID_ENTIRE_TERM_CASE_SENSITIVE: return "CS";
			case SCTID_ENTIRE_TERM_CASE_INSENSITIVE: return "ci";
			case SCTID_ONLY_INITIAL_CHAR_CASE_INSENSITIVE : return "cI";
			default :
		}
		throw new TermServerScriptException("Do not recognise case significance indicator : " + caseSignificanceSctId);
	}
	
	public static String translateCaseSignificanceFromEnum(
			CaseSignificance caseSignificance) throws TermServerScriptException {
		switch (caseSignificance) {
			case ENTIRE_TERM_CASE_SENSITIVE: return "CS";
			case CASE_INSENSITIVE: return "ci";
			case INITIAL_CHARACTER_CASE_INSENSITIVE : return "cI";
			default :
		}
		throw new TermServerScriptException("Do not recognise case significance indicator : " + caseSignificance);
	}
	
	public static CaseSignificance translateCaseSignificanceToEnum(
			String caseSignificanceSctId) throws TermServerScriptException {
		switch (caseSignificanceSctId) {
			case SCTID_ENTIRE_TERM_CASE_SENSITIVE: return CaseSignificance.ENTIRE_TERM_CASE_SENSITIVE;
			case SCTID_ENTIRE_TERM_CASE_INSENSITIVE: return CaseSignificance.CASE_INSENSITIVE;
			case SCTID_ONLY_INITIAL_CHAR_CASE_INSENSITIVE : return CaseSignificance.INITIAL_CHARACTER_CASE_INSENSITIVE;
			default :
		}
		throw new TermServerScriptException("Do not recognise case significance indicator : " + caseSignificanceSctId);
	}

	public static InactivationIndicator translateInactivationIndicator(String indicatorSctId) {
		switch (indicatorSctId) {
			case SCTID_INACT_AMBIGUOUS: return InactivationIndicator.AMBIGUOUS;
			case SCTID_INACT_MOVED_ELSEWHERE : return InactivationIndicator.MOVED_ELSEWHERE;
			case SCTID_INACT_CONCEPT_NON_CURRENT : return InactivationIndicator.CONCEPT_NON_CURRENT;
			case SCTID_INACT_DUPLICATE : return InactivationIndicator.DUPLICATE;
			case SCTID_INACT_ERRONEOUS : return InactivationIndicator.ERRONEOUS;
			case SCTID_INACT_INAPPROPRIATE : return InactivationIndicator.INAPPROPRIATE;
			case SCTID_INACT_LIMITED : return InactivationIndicator.LIMITED;
			case SCTID_INACT_OUTDATED : return InactivationIndicator.OUTDATED; 
			case SCTID_INACT_PENDING_MOVE : return InactivationIndicator.PENDING_MOVE;
			case SCTID_INACT_NON_CONFORMANCE: return InactivationIndicator.NONCONFORMANCE_TO_EDITORIAL_POLICY;
			case SCTID_INACT_NOT_EQUIVALENT : return InactivationIndicator.NOT_EQUIVALENT;
			default: throw new IllegalArgumentException("Unrecognised inactivation indicator value " + indicatorSctId);
		}
	}
	
	public static Association translateAssociation(String assocSctId) {
		switch (assocSctId) {
			case SCTID_ASSOC_WAS_A_REFSETID: return Association.WAS_A;
			case SCTID_ASSOC_REPLACED_BY_REFSETID : return Association.REPLACED_BY;
			case SCTID_ASSOC_SAME_AS_REFSETID : return Association.SAME_AS;
			case SCTID_ASSOC_MOVED_TO_REFSETID : return Association.MOVED_TO;
			case SCTID_ASSOC_POSS_EQUIV_REFSETID : return Association.POSS_EQUIV_TO;
			case SCTID_ASSOC_ALTERNATIVE_REFSETID : return Association.ALTERNATIVE;
			case SCTID_ASSOC_ANATOMY_STRUC_ENTIRE_REFSETID : return Association.ANATOMY_STRUC_ENTIRE;
			default: throw new IllegalArgumentException("Unrecognised historical association indicator value " + assocSctId);
		}
	}
	
	public static String prettyPrintHistoricalAssociations(Concept c, GraphLoader gl) throws TermServerScriptException {
		String associations = "";
		boolean isFirst = true;
		for (AssociationEntry assoc : c.getAssociationEntries())  {
			if (assoc.isActive()) {
				if (!isFirst) {
					associations += "\n";
				} else {
					isFirst = false;
				}
				associations += translateAssociation(assoc.getRefsetId()) + " " + gl.getConcept(assoc.getTargetComponentId());
			}
		}
		return associations;
	}
	
	public static File ensureFileExists(String fileName) throws TermServerScriptException {
		if (StringUtils.isEmpty(fileName)) {
			throw new TermServerScriptException ("Blank file name specified");
		}
		
		File file = new File(fileName);
		try {
			if (!file.exists()) {
				if (file.getParentFile() != null) {
					file.getParentFile().mkdirs();
				}
				file.createNewFile();
			}
		} catch (IOException e) {
			throw new TermServerScriptException("Failed to create file " + fileName,e);
		}
		return file;
	}
	
	public static boolean isConceptType(Concept c, ConceptType conceptType) throws TermServerScriptException {
		//Does this concept know it's type?  Assign if not
		if (c.getConceptType() == null) {
			populateConceptType(c);
		}
		return c.getConceptType().equals(conceptType);
	}
	
	public static boolean isConceptType(Concept c, ConceptType[] conceptTypes) throws TermServerScriptException {
		//Does this concept know it's type?  Assign if not
		if (c.getConceptType() == null) {
			populateConceptType(c);
		}
		for (ConceptType conceptType : conceptTypes) {
			if (c.getConceptType().equals(conceptType)) {
				return true;
			}
		}
		return false;
	}

	public static void populateConceptType(Concept c) throws TermServerScriptException {
		if (c.getFsn() == null) {
			determineConceptTypeFromAttributes(c, CharacteristicType.STATED_RELATIONSHIP);
		} else {
			String semTag = SnomedUtils.deconstructFSN(c.getFsn())[1];
			boolean isOnly = isOnly(c);
			switch (semTag) {
				case DrugUtils.MP : c.setConceptType(isOnly ? ConceptType.MEDICINAL_PRODUCT_ONLY : ConceptType.MEDICINAL_PRODUCT);
											break;
				case DrugUtils.MPF : c.setConceptType(isOnly ? ConceptType.MEDICINAL_PRODUCT_FORM_ONLY : ConceptType.MEDICINAL_PRODUCT_FORM);
											break;
				case DrugUtils.CD : c.setConceptType(ConceptType.CLINICAL_DRUG);
											break;
				case DrugUtils.PRODUCT : c.setConceptType(ConceptType.PRODUCT);
											checkForGroupers(c);  //May further refine the concept type
											break;
				default : c.setConceptType(ConceptType.UNKNOWN);
			}
		}
	}
	
	private static void checkForGroupers(Concept c) throws TermServerScriptException {
		GraphLoader gl = GraphLoader.getGraphLoader();
		
		//We're only going to consider sufficiently defined concepts here.
		if (!c.getDefinitionStatus().equals(DefinitionStatus.FULLY_DEFINED)) {
			return;
		}
		
		//Get the local cached copy of the concept so that we have the fully hierarchy tree populated
		Concept cCached = gl.getConcept(c.getConceptId());
		
		Concept dispositions = gl.getConcept("766779001 |Medicinal product categorized by disposition (product)|");
		Concept structures = gl.getConcept("763760008 |Medicinal product categorized by structure (product)|");
		boolean isStructure = false;
		boolean isDisposition = false;
		
		if (cCached.getAncestors(NOT_SET).contains(structures)) {
			isStructure = true;
		}
		
		if (cCached.getAncestors(NOT_SET).contains(dispositions)) {
			isDisposition = true;
		}
		
		if (isStructure && isDisposition) {
			c.setConceptType(ConceptType.STRUCTURE_AND_DISPOSITION_GROUPER);
		} else if (isStructure) {
			c.setConceptType(ConceptType.STRUCTURAL_GROUPER);
		} else if (isDisposition) {
			c.setConceptType(ConceptType.DISPOSITION_GROUPER);
		}
	}

	//Concepts with a base active ingredient count are "Only"
	private static boolean isOnly(Concept c) {
		return c.getRelationships(CharacteristicType.INFERRED_RELATIONSHIP, COUNT_BASE_ACTIVE_INGREDIENT, ActiveState.ACTIVE).size() > 0;
	}

	private static void determineConceptTypeFromAttributes(Concept c, CharacteristicType charType) {
		try {
			//Do we have ingredients?  We're at least an MP
			if (getTargets(c, new Concept[] { HAS_ACTIVE_INGRED, HAS_PRECISE_INGRED }, charType).size() > 0) {
				c.setConceptType(ConceptType.MEDICINAL_PRODUCT);
				//Do we also have dose form?  If so, MPF
				if (getTargets(c, new Concept[] { HAS_MANUFACTURED_DOSE_FORM }, charType).size() > 0) {
					c.setConceptType(ConceptType.MEDICINAL_PRODUCT_FORM);
					//And if we have strength, CD
					if (getTargets(c, new Concept[] { HAS_CONC_STRENGTH_DENOM_UNIT, HAS_PRES_STRENGTH_UNIT }, charType).size() > 0) {
						c.setConceptType(ConceptType.CLINICAL_DRUG);
					}
				}
			}
		} catch (Exception e) {
			TermServerScript.warn("Unable to determine concept type of " + c + " due to " + e);
		}
	}

	//Return a set of groups where there is an active non-isa relationship
	public static Set<Integer> getActiveGroups(Concept c) {
		return getActiveGroups(c, CharacteristicType.STATED_RELATIONSHIP);
	}
	
	public static Set<Integer> getActiveGroups(Concept c, CharacteristicType charType) {
		Set<Integer> activeGroups = new HashSet<>();
		for (Relationship r : c.getRelationships(charType, ActiveState.ACTIVE)) {
			if (!r.getType().equals(IS_A)) {
				activeGroups.add((int)r.getGroupId());
			}
		}
		return activeGroups;
	}
	
	public static int getFirstFreeGroup(Concept c) {
		return getFirstFreeGroup(c, CharacteristicType.STATED_RELATIONSHIP);
	}
	
	public static int getFirstFreeGroup(Concept c,CharacteristicType charType) {
		Set<Integer> activeGroups = getActiveGroups(c, charType);
		for (int i = 1; ; i++) {
			if (!activeGroups.contains(i)) {
				return i;
			}
		}
	}
	
	public static boolean termAlreadyExists(Concept concept, String newTerm) {
		return termAlreadyExists(concept, newTerm, ActiveState.BOTH);
	}

	public static boolean termAlreadyExists(Concept concept, String newTerm, ActiveState activeState) {
		boolean termAlreadyExists = false;
		for (Description description : concept.getDescriptions(activeState)) {
			if (description.getTerm() != null && description.getTerm().equals(newTerm)) {
				termAlreadyExists = true;
			}
		}
		return termAlreadyExists;
	}
	
	public static Map<String, Acceptability> createAcceptabilityMap(AcceptabilityMode acceptabilityMode) {
		Map<String, Acceptability> aMap = new HashMap<String, Acceptability>();
		//Note that when a term is preferred in one dialect, we'll make it acceptable in the other
		switch (acceptabilityMode) {
			case PREFERRED_BOTH :
				aMap.put(US_ENG_LANG_REFSET, Acceptability.PREFERRED);
				aMap.put(GB_ENG_LANG_REFSET, Acceptability.PREFERRED);
				break;
			case PREFERRED_US :
				aMap.put(US_ENG_LANG_REFSET, Acceptability.PREFERRED);
				aMap.put(GB_ENG_LANG_REFSET, Acceptability.ACCEPTABLE);
				break;
			case PREFERRED_GB :
				aMap.put(US_ENG_LANG_REFSET, Acceptability.ACCEPTABLE);
				aMap.put(GB_ENG_LANG_REFSET, Acceptability.PREFERRED);
				break;
			case ACCEPTABLE_BOTH :
				aMap.put(US_ENG_LANG_REFSET, Acceptability.ACCEPTABLE);
				aMap.put(GB_ENG_LANG_REFSET, Acceptability.ACCEPTABLE);
				break;
			case ACCEPTABLE_US :
				aMap.put(US_ENG_LANG_REFSET, Acceptability.ACCEPTABLE);
				break;
			case ACCEPTABLE_GB :
				aMap.put(GB_ENG_LANG_REFSET, Acceptability.ACCEPTABLE);
				break;
		}
		return aMap;
	}

	public static String getPT(String sctId) throws TermServerScriptException {
		return GraphLoader.getGraphLoader().getConcept(sctId).getPreferredSynonym(US_ENG_LANG_REFSET).getTerm();
	}

	public static Concept createConcept(String term, String semTag, Concept parent) {
		Concept newConcept = Concept.withDefaults(null);
		addDefaultTerms(newConcept, term, semTag);
		if (parent != null) {
			Relationship parentRel = new Relationship (null, IS_A, parent, UNGROUPED);
			newConcept.addRelationship(parentRel);
		}
		return newConcept;
	}

	public static Concept createConcept(String sctId, String fsn) {
		Concept newConcept = Concept.withDefaults(sctId);
		String[] parts = deconstructFSN(fsn);
		addDefaultTerms(newConcept, parts[0], parts[1]);
		return newConcept;
	}
	
	
	private static void addDefaultTerms(Concept c, String term, String semTag) {
		Description fsn = Description.withDefaults(term == null? null : term + " " + semTag, DescriptionType.FSN, Acceptability.PREFERRED);
		c.addDescription(fsn);
		
		Description pt = Description.withDefaults(term, DescriptionType.SYNONYM, Acceptability.PREFERRED);
		c.addDescription(pt, true);  //Allow duplication - we might have a null term if we don't know enough to create one yet.
	}
	
	//Where we have multiple potential responses eg concentration or presentation strength, return the first one found given the 
	//order specified by the array
	public static Concept getTarget(Concept c, Concept[] types, int groupId, CharacteristicType charType) throws TermServerScriptException {
		return getTarget(c, types, groupId, charType, false);
	}
	
	//Where we have multiple potential responses eg concentration or presentation strength, return the first one found given the 
	//order specified by the array
	public static Concept getTarget(Concept c, Concept[] types, int groupId, CharacteristicType charType, boolean allowNewConcepts) throws TermServerScriptException {
		for (Concept type : types) {
			Set<Relationship> rels = c.getRelationships(charType, type, groupId);
			if (rels.size() > 1) {
				TermServerScript.warn(c + " has multiple " + type + " in group " + groupId);
			} else if (rels.size() == 1) {
				if (allowNewConcepts) {
					return rels.iterator().next().getTarget();
				} else {
					//This might not be the full concept, so recover it fully from our loaded cache
					return GraphLoader.getGraphLoader().getConcept(rels.iterator().next().getTarget().getConceptId());
				}
			}
		}
		return null;
	}
	
	public static Concept getTarget(RelationshipGroup g, Concept[] types, boolean allowNewConcepts) throws TermServerScriptException {
		for (Concept type : types) {
			Set<Relationship> rels = g.getRelationships(type);
			if (rels.size() > 1) {
				TermServerScript.warn(g + " has multiple " + type);
			} else if (rels.size() == 1) {
				if (allowNewConcepts) {
					return rels.iterator().next().getTarget();
				} else {
					//This might not be the full concept, so recover it fully from our loaded cache
					return GraphLoader.getGraphLoader().getConcept(rels.iterator().next().getTarget().getConceptId());
				}
			}
		}
		return null;
	}
	
	public static String getConcreteValue(Concept c, Concept[] types, int groupId, CharacteristicType charType) throws TermServerScriptException {
		for (Concept type : types) {
			Set<Relationship> rels = c.getRelationships(charType, type, groupId);
			if (rels.size() > 1) {
				TermServerScript.warn(c + " has multiple " + type + " in group " + groupId);
			} else if (rels.size() == 1) {
				Relationship r = rels.iterator().next();
				if (!r.isConcrete()) {
					throw new IllegalArgumentException("Attempt to recover concrete value from non-concrete relationship: " + r);
				}
				return r.getValue().toString();
			}
		}
		return null;
	}
	
	public static Set<Concept> getTargets(Concept c, Concept[] types, CharacteristicType charType) throws TermServerScriptException {
		Set<Concept> targets = new HashSet<>();
		for (Concept type : types) {
			Set<Relationship> rels = c.getRelationships(charType, type, ActiveState.ACTIVE);
			targets.addAll(rels.stream().filter(Relationship::isNotConcrete).map(Relationship::getTarget).collect(Collectors.toSet()));
		}
		return targets;
	}
	
	public static String getModel(Expressable c, CharacteristicType charType) {
		return getModel(c, charType, false);
	}
	
	public static String getModel(Expressable c, CharacteristicType charType, boolean includeParents) {
		String model = "";
		
		if (includeParents) {
			model = c.getDefinitionStatus().equals(DefinitionStatus.FULLY_DEFINED) ? "=== " : "<<< ";
			String parentStr = c.getParents(charType).stream()
					.map(p -> p.getFsn())
					.collect(Collectors.joining(" + "));
			model += parentStr;
			if (SnomedUtils.countAttributes(c, charType) > 0) {
				model +=" : ";
			}
			model += "\n";
		}
		boolean isFirst = true;
		for (RelationshipGroup g : c.getRelationshipGroups(charType)) {
			if (!isFirst) {
				model += ", \n";
			} else isFirst = false;
			model += g;
		}
		//Split into separate lines so we can see better
		model = model.replaceAll ("\\{", "\\{  ").replaceAll("\\,", "\\,\n   ");
		return model;
	}
	
	public static Integer countAttributes(Expressable c, CharacteristicType charType) {
		int attributeCount = 0;
		for (Relationship r : c.getRelationships(charType, ActiveState.ACTIVE)) {
			if (!r.getType().equals(IS_A)) {
				attributeCount++;
			}
		}
		return attributeCount;
	}

	/**
	 * @return true if the concept has the specified type as an attribute
	 */
	public static boolean hasType(CharacteristicType charType, Concept c, Concept type) {
		for (Relationship r : c.getRelationships(charType, ActiveState.ACTIVE)) {
			if (r.getType().equals(type)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * @return true if the concept has one of the specified types as an attribute
	 */
	public static boolean hasType(CharacteristicType charType, Concept c, Set<Concept> types) {
		for (Concept type : types) {
			if (hasType(charType, c, type)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * @return true if the concept has the specified attributeValue
	 */
	public static boolean hasValue(CharacteristicType charType, Concept c, Concept value) {
		for (Relationship r : c.getRelationships(charType, ActiveState.ACTIVE)) {
			if (r.getTarget().equals(value)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return the highest concept reached before hitting "end"
	 */
	public static Concept getHighestAncestorBefore(Concept start, Concept end) {
		Set<Concept> topLevelAncestors = getHighestAncestorsBefore(start, end);
		if (topLevelAncestors.size() > 1) {
			throw new IllegalArgumentException(start + " has multiple ancestors immediately before " + end);
		} else if (topLevelAncestors.isEmpty()) {
			throw new IllegalArgumentException("Failed to find ancestors of " + start + " before " + end);
		}
		return topLevelAncestors.iterator().next();
	}
	
	public static Set<Concept> getHighestAncestorsBefore(Concept start, Concept end) {
		if (start == null) {
			throw new IllegalStateException("Asked to find highest ancestor - start concept not specified");
		}
		if (end == null) {
			throw new IllegalStateException("Asked to find highest ancestor - end concept not specified");
		}
		Set<Concept> topLevelAncestors = new HashSet<>();
		for (Concept parent : start.getParents(CharacteristicType.INFERRED_RELATIONSHIP)) {
			if (parent.equals(end)) {
				return Collections.singleton(start);
			} else if (parent.equals(ROOT_CONCEPT)) {
				throw new IllegalStateException(start + " reached ROOT before finding " + end);
			} else {
				topLevelAncestors.addAll(getHighestAncestorsBefore(parent, end));
			}
		}
		return topLevelAncestors;
	}

	/**
	 * @return true if the attribute types/values (including IS A) of concept a are more specific than b
	 */
	public static boolean hasMoreSpecificModel(Concept a, Concept b, AncestorsCache cache) {
		boolean moreSpecificAttributeDetected = false;
		//Work through all the attributes of a and see if the ancestors match an attribute in b
		nextRel:
		for (Relationship r : a.getRelationships(CharacteristicType.STATED_RELATIONSHIP, ActiveState.ACTIVE)) {
			//Filter for matching types (same or less specific) and values (same or less specific)
			//If the relationship is not identical, then it's more specific
			//TODO Strictly we should consider the RELS in equivalent groups
			Set<Relationship> matchingRels = b.getRelationships(CharacteristicType.STATED_RELATIONSHIP, ActiveState.ACTIVE)
												.stream()
												.filter(br -> cache.getAncestorsOrSelfSafely(r.getType()).contains(br.getType()))
												.filter(br -> cache.getAncestorsOrSelfSafely(r.getTarget()).contains(br.getTarget()))
												.collect(Collectors.toSet());
			//If there are no matching rels, then these concept models are disjoint
			if (matchingRels.size() == 0) {
				return false;
			}
			//If we have an exact match, move on to the next relationship.  Otherwise, we've
			//found a more specific attribute
			for (Relationship thisMatch : matchingRels) {
				if (thisMatch.getType().equals(r.getType()) &&
						thisMatch.getTarget().equals(r.getTarget())) {
					continue nextRel;
				}
			}
			moreSpecificAttributeDetected = true;
		}
		return moreSpecificAttributeDetected;
	}
	
	//Return true if a is more specific than b.   False if they're the same
	public static boolean isMoreSpecific(Relationship a, Relationship b, AncestorsCache cache) throws TermServerScriptException {
		boolean sameType = false;
		boolean moreSpecificType = false;
		//Compare Types
		if (a.getType().equals(b.getType())) {
			sameType = true;
		} else if (cache.getAncestors(a.getType()).contains(b.getType())) {
			moreSpecificType = true;
		} else {
			return false;
		}
		
		//If type is same or more specific, how does value compare?
		boolean sameValue = false;
		boolean moreSpecificValue = false;
		if (a.isConcrete() && b.isConcrete()) {
			if (a.getValue().equals(b.getValue())) {
				sameValue = true;
			}
		} else {
			if (a.getTarget().equals(b.getTarget())) {
				sameValue = true;
			} else if (cache.getAncestors(a.getTarget()).contains(b.getTarget())) {
				moreSpecificValue = true;
			} else {
				return false;
			}
		}
		
		//If they're exactly the same, then it's not MORE specific
		if (sameType && sameValue) {
			return false;
		} else if (moreSpecificType || moreSpecificValue) {
			return true;
		}
		return false;
	}
	

	/*Very similar to "moreSpecific", but here we're saying the type might be more 
	 * specific and the value less so, or visa versa.
	 * This will also pick up the "more specific" case, so check those first
	 */
	public static boolean inconsistentSubsumption(Relationship a, Relationship b, AncestorsCache cache) throws TermServerScriptException {
		boolean sameType = false;
		boolean subsumptionRelationshipType = false;
		//Compare Types
		if (a.getType().equals(b.getType())) {
			sameType = true;
		} else if (cache.getAncestors(a.getType()).contains(b.getType()) ||
				cache.getAncestors(b.getType()).contains(a.getType())) {
			subsumptionRelationshipType = true;
		} else {
			return false;
		}
		
		//If type has some subsumption relationship how does value compare?
		boolean sameValue = false;
		boolean subsumptionRelationshipValue = false;
		if (a.isConcrete() && b.isConcrete()) {
			if (a.getValue().equals(b.getValue())) {
				sameValue = true;
			}
		} else {
			if (a.getTarget().equals(b.getTarget())) {
				sameValue = true;
			} else if (cache.getAncestors(a.getTarget()).contains(b.getTarget()) ||
					cache.getAncestors(b.getTarget()).contains(a.getTarget())) {
				subsumptionRelationshipValue = true;
			} else {
				return false;
			}
		}
		
		//If they're exactly the same, then it's not MORE specific
		if (sameType && sameValue) {
			return false;
		} else if (subsumptionRelationshipType || subsumptionRelationshipValue) {
			return true;
		}
		return false;
	}
	
	/**
	 * @return true if a is same or more specific than b
	 * Note a may have more attributes than b, as long as all of b's attributes are 
	 * present (or more specific) in a, then the presence of a covers b.  
	 * b is redundant
	 */
	public static boolean covers(RelationshipGroup a, RelationshipGroup b, AncestorsCache cache) throws TermServerScriptException {
		for (Relationship rb : b.getRelationships()) {
			boolean matchFound = false;
			for (Relationship ra : a.getRelationships()) {
				if (ra.equalsTypeAndTargetValue(rb) || isMoreSpecific(ra, rb, cache)) {
					matchFound = true;
					break;
				}
			}
			if (!matchFound) {
				return false;
			}
		}
		//If we get here, all relationships in group "b" match, or are more general
		//than those in group a
		return true;
	}

	/*
	 * Get active relationships which are the same as, or descendants of the stated types and values
	 */
	public static Set<Relationship> getSubsumedRelationships(Concept c, Concept type, Concept target,
			CharacteristicType charType, AncestorsCache cache) throws TermServerScriptException {
		Set<Relationship> matchedRelationships = new HashSet<>();
		for (Relationship r : c.getRelationships(charType, ActiveState.ACTIVE)) {
			//Does this type have the desired type as self or ancestor?  Need local copies to do subsumption testing
			Concept rType = GraphLoader.getGraphLoader().getConcept(r.getType().getConceptId());
			Concept rTarget = GraphLoader.getGraphLoader().getConcept(r.getTarget().getConceptId());
			if (cache.getAncestorsOrSelf(rType).contains(type)) {
				if (cache.getAncestorsOrSelf(rTarget).contains(target)) {
					matchedRelationships.add(r);
				}
			}
		}
		return matchedRelationships;
	}

	/*
	 * @return true if r1 and r2 can be found grouped together in the specified characteristic type
	 */
	public static boolean isGroupedWith(Relationship r1, Relationship r2, Concept c, CharacteristicType charType) {
		if (r1.equalsTypeAndTargetValue(r2)) {
			throw new IllegalArgumentException("Cannot answer if " + r1 + " is grouped with itself");
		}
		
		for (RelationshipGroup group : c.getRelationshipGroups(charType)) {
			boolean foundFirst = false;
			for (Relationship r : group.getRelationships()) {
				if (r.equalsTypeAndTargetValue(r1) || r.equalsTypeAndTargetValue(r2)) {
					if (foundFirst) { //already seen one
						return true;
					} else {
						foundFirst = true;
					}
				}
			}
		}
		return false;
	}

	public static Set<RelationshipGroup> appearsInGroups(Concept c, Relationship findMe, CharacteristicType charType) {
		//Return all groups containing r's type and value
		Set<RelationshipGroup> groups = new HashSet<>();
		
		nextGroup:
		for (RelationshipGroup group : c.getRelationshipGroups(charType)) {
			for (Relationship r : group.getRelationships()) {
				if (r.equalsTypeAndTargetValue(findMe)) {
					groups.add(group);
					continue nextGroup;
				}
			}
		}
		return groups;
	}
	
	public static void removeRedundancies(Set<Concept> concepts) throws TermServerScriptException {
		Set<Concept> redundant = new HashSet<>();
		DescendantsCache cache = GraphLoader.getGraphLoader().getDescendantsCache();
		//For each concept, it is redundant if any of it's descendants are also present
		for (Concept concept : concepts) {
			Set<Concept> descendants = new HashSet<>(cache.getDescendents(concept));
			descendants.retainAll(concepts);
			if (descendants.size() > 0) {
				redundant.add(concept);
			}
		}
		concepts.removeAll(redundant);
	}

	/**
	 * @return TRUE if the concept or any descriptions, relationships have a null effective Time
	 */
	public static boolean hasNewChanges(Concept c) {
		if (StringUtils.isEmpty(c.getEffectiveTime())) {
			return true;
		}
		
		for (Description d : c.getDescriptions()) {
			if (StringUtils.isEmpty(d.getEffectiveTime())) {
				return true;
			}
		}
		
		for (Relationship r : c.getRelationships(CharacteristicType.STATED_RELATIONSHIP, ActiveState.BOTH)) {
			if (StringUtils.isEmpty(r.getEffectiveTime())) {
				return true;
			}
		}
		return false;
	}

	public static ComponentType getComponentType(String sctId) throws TermServerScriptException {
		//Check out the 2nd to last character - indicates type of component
		String penultimate = sctId.substring(sctId.length() - 2, sctId.length() - 1);
		switch (penultimate) {
			case "0" : return ComponentType.CONCEPT;
			case "1" : return ComponentType.DESCRIPTION;
			case "2" : return ComponentType.INFERRED_RELATIONSHIP;
			default : throw new TermServerScriptException("Unable to determine component type of: " + sctId);
		}
	}

	public static boolean hasAcceptabilityInDialect(Description d, String langRefsetId,
			Acceptability targetAcceptability) throws TermServerScriptException {
		
		Acceptability acceptability = d.getAcceptability(langRefsetId);
		if (targetAcceptability == null && acceptability == null) {
			return true;
		} else if (acceptability == null || targetAcceptability == null) {
			return false;
		}
		
		//Once we're here, the descriptions acceptability cannot be null.
		if (targetAcceptability == Acceptability.BOTH) {
			return true;
		}
		return acceptability.equals(targetAcceptability);
	}

	//Check every stated relationship is exactly matched by an inferred one
	//Actually we have to check for matching groups to ensure we're checking like-for-like
	public static boolean inferredMatchesStated(Concept c) {
		Collection<RelationshipGroup> statedGroups = c.getRelationshipGroups(CharacteristicType.STATED_RELATIONSHIP);
		Collection<RelationshipGroup> inferredGroups = c.getRelationshipGroups(CharacteristicType.INFERRED_RELATIONSHIP);
		
		//First of all, do we have the same number?
		if (statedGroups.size() != inferredGroups.size()) {
			return false;
		}
		nextStatedGroup:
		for (RelationshipGroup statedGroup : statedGroups) {
			for (RelationshipGroup inferredGroup : inferredGroups) {
				if (statedGroup.equals(inferredGroup)) {
					continue nextStatedGroup;
				}
			}
			//If we get to here, we've failed to find an inferred group to match the stated one
			return false;
		}
		return true;
	}

	public static Concept getHistoricalParent(Concept c) throws TermServerScriptException {
		if (c.isActive()) {
			throw new TermServerScriptException("Attempted to find historical parent of an active concept: " + c);
		}
		//TODO Sort the parent relationships by time so we get the one most recently inactivated
		List<Concept> parents = c.getRelationships().stream()
				.filter(r -> r.getType().equals(IS_A))
				.map(r -> r.getTarget())
				.collect(Collectors.toList());
		
		//Return the first one that is still active
		for (Concept parent : parents) {
			if (parent.isActive()) {
				return parent;
			}
		}
		
		//Otherwise, find the parent's parent and see if that's 
		//TODO This could be done as a breadth first recursive search
		for (Concept parent : parents) {
			Concept grandParent = getHistoricalParent(parent);
			if (grandParent != null) {
				return grandParent;
			}
		}
		return null;
	}

	public static String populateFSNs(String stl) throws TermServerScriptException {
		//Loop through string and replace any numbers that aren't followed by a pipe
		//with the full string
		GraphLoader gl = GraphLoader.getGraphLoader();
		
		int origLength = stl.length();
		
		//Easiest to remove all pipes and then replace all
		stl = stl.replaceAll("\\|.*?\\|", "").replaceAll("  ", "");
		Pattern p = Pattern.compile("\\d{8,}");
		Matcher m = p.matcher(stl);
		StringBuffer sb = new StringBuffer();
		while (m.find()) {
			String sctId = m.group();
			Concept c = gl.getConceptSafely(sctId);
			if (c == null) {
				throw new TermServerScriptException("Unknown concept encountered in STL: " + sctId);
			}
			m.appendReplacement(sb, c.toString());
		}
		m.appendTail(sb);
		
		if (sb.length() < origLength) {
			TermServerScript.warn ("Populating FSNs has reduced overall length - check: '" + sb + "'");
		}
		return sb.toString();
	}

	/**
	 * @return the list ordered so that FSN is returned first, then PT, then acceptable synonyms
	 */
	public static void prioritise(List<Description> descriptions) {
		Collections.sort(descriptions, new Comparator<Description>() {
			@Override
			public int compare(Description d1, Description d2) {
			return priority(d2).compareTo(priority(d1));
			}
		});
	}
	
	private static Integer priority(Description d) {
		if (d.getType().equals(DescriptionType.FSN)) {
			return 2;
		} else if (d.isPreferred()) {
			return 1;
		}
		return 0;
	}

	public static Set<Concept> hasParents(Set<Concept> matchParents, Set<Concept> range, int limit) {
		Set<Concept> matching = new HashSet<>();
		for (Concept testMe : range) {
			if (testMe.getParents(CharacteristicType.INFERRED_RELATIONSHIP).containsAll(matchParents)) {
				matching.add(testMe);
			}
			if (matching.size() >= limit) {
				break;
			}
		}
		return matching;
	}

	public static int notNullCount(Concept[] concepts) {
		int notNullCount = 0;
		for (Concept concept : concepts) {
			if (concept != null) {
				notNullCount++;
			}
		}
		return notNullCount;
	}

	public static int appearances(Concept[] concepts, Concept conceptOfInterest) {
		int appearances = 0;
		for (Concept concept : concepts) {
			if (concept != null && concept.equals(conceptOfInterest)) {
				appearances++;
			}
		}
		return appearances;
	}

	public static Collection<RelationshipGroup> toGroups(Collection<Relationship> relationships) {
		Map<Integer, RelationshipGroup> groupMap = new HashMap<>();
		for (Relationship r : relationships) {
			RelationshipGroup g = groupMap.get(r.getGroupId());
			if (g == null) {
				g = new RelationshipGroup(r.getGroupId());
				groupMap.put(r.getGroupId(), g);
			}
			g.addRelationship(r);
		}
		return groupMap.values();
	}

	public static RelationshipGroup findMatchingGroup(Concept c, RelationshipGroup g, CharacteristicType charType) {
		nextPotentialMatch:
		for (RelationshipGroup potentialMatch : c.getRelationshipGroups(charType)) {
			if (potentialMatch.size() == g.size()) {
				nextRelationship:
				for (Relationship r1 : potentialMatch.getRelationships()) {
					for (Relationship r2 : g.getRelationships()) {
						if (r1.getType().equals(r2.getType())) {
							if (r1.isConcrete() && r2.isConcrete()) {
								if (r1.getValue().equals(r2.getValue())) {
									continue nextRelationship;
								}
							} else {
								if (r1.getTarget().equals(r2.getTarget())) {
									continue nextRelationship;
								}
							}
						}
					}
					//No match found for r1 in g.  Try next group.
					continue nextPotentialMatch; 
				}
			//All r1s found a match - we've found a group match
			return potentialMatch;
			}
		}
		return null;
	}

	public static boolean isFreeGroup(CharacteristicType charType, Concept c, int checkIfFree) {
		for (RelationshipGroup g : c.getRelationshipGroups(charType)) {
			if (g.getGroupId() == checkIfFree) {
				return false;
			}
		}
		return true;
	}
	
	public static String makeMachineReadable (String exp) {
		StringBuffer hrExp = new StringBuffer(exp);
		makeMachineReadable(hrExp);
		exp = hrExp.toString().toUpperCase();
		exp = exp.replaceAll("OR", " OR ").replaceAll("AND", " AND ").replaceAll("MINUS", " MINUS ");
		return exp;
	}
	
	public static void makeMachineReadable (StringBuffer hrExp) {
		int pipeIdx =  hrExp.indexOf(PIPE);
		while (pipeIdx != -1) {
			int endIdx = findEndOfTerm(hrExp, pipeIdx);
			hrExp.delete(pipeIdx, endIdx);
			pipeIdx =  hrExp.indexOf(PIPE);
		}
		remove(hrExp, SPACE_CHAR);
	}
	
	private static int findEndOfTerm(StringBuffer hrExp, int searchStart) {
		int endIdx = indexOf(hrExp, termTerminators, searchStart+1);
		//If we didn't find a terminator, cut to the end.
		if (endIdx == -1) {
			endIdx = hrExp.length();
		} else {
			//If the character found as a terminator is a pipe, then cut that too
			if (hrExp.charAt(endIdx) == PIPE_CHAR) {
				endIdx++;
			} else if (hrExp.charAt(endIdx) == ATTRIBUTE_SEPARATOR.charAt(0)) {
				//If the character is a comma, then it might be a comma inside a term so find out if the next token is a number
				if (!StringUtils.isNumericSpace(hrExp.substring(endIdx+1, endIdx+5))) {
					//OK it's a term, so find the new actual end. 
					endIdx = findEndOfTerm(hrExp, endIdx);
				}
			}
		}
		return endIdx;
	}
	
	static void remove (StringBuffer haystack, char needle) {
		for (int idx = 0; idx < haystack.length(); idx++) {
			if (haystack.charAt(idx) == needle) {
				haystack.deleteCharAt(idx);
				idx --;
			}
		}
	}
	
	static int indexOf (StringBuffer haystack, char[] needles, int startFrom) {
		for (int idx = startFrom; idx < haystack.length(); idx++) {
			for (char thisNeedle : needles) {
				if (haystack.charAt(idx) == thisNeedle) {
					return idx;
				}
			}
		}
		return -1;
	}

	public static boolean containsAttributeOrMoreSpecific(Concept c, RelationshipTemplate targetAttribute, DescendantsCache cache) throws TermServerScriptException {
		Set<Concept> types = cache.getDescendentsOrSelf(targetAttribute.getType());
		//If there's no attribute value specified, we'll match on just the target type
		Set<Concept> values = targetAttribute.getTarget() == null ? null : cache.getDescendentsOrSelf(targetAttribute.getTarget());
		return c.getRelationships().stream()
				.filter(r -> r.isActive())
				.filter(r -> r.getCharacteristicType().equals(targetAttribute.getCharacteristicType()))
				.filter(r -> types.contains(r.getType()))
				.filter(r -> {
					if (r.isNotConcrete()) {
						return values == null || values.contains(r.getTarget());
					} else {
						return r.getValue().equals(targetAttribute.getValue());
					}
				})
				.collect(Collectors.toList()).size() > 0;
	}

	public static boolean startsWithSCTID(String str) {
		//Can we find an SCTID in this string?
		str = str.trim().replaceAll(ESCAPED_PIPE, " ");
		String[] parts = str.split(SPACE);
		String errMsg = isValid(parts[0], null);
		return errMsg == null;
	}

	public static String getAssociationType(AssociationEntry a) throws TermServerScriptException {
		Concept assocType = GraphLoader.getGraphLoader().getConcept(a.getRefsetId());
		return assocType.getPreferredSynonym().replace(" association reference set", "");
	}

	public static Integer compareEffectiveDate(String effectiveDate1, String effectiveDate2) {
		if (effectiveDate1 == null || effectiveDate2 == null) {
			return null;
		}

		try {
			Date date = EFFECTIVE_DATE_FORMAT.parse(effectiveDate1);
			Date date2 = EFFECTIVE_DATE_FORMAT.parse(effectiveDate2);
			return date.compareTo(date2);
		} catch (ParseException e) {
		}
		return null;
	}

	public static boolean isConceptSctid(String componentId) {
		//A zero in the penultimate character indicates a concept SCTID
		if (StringUtils.isEmpty(componentId)) {
			return false;
		}
		return componentId.charAt(componentId.length()-2) == '0';
	}

	public static Component getParentComponent(RefsetMember rm, GraphLoader gl) throws TermServerScriptException {
		if (rm == null || StringUtils.isEmpty(rm.getReferencedComponentId())) {
			if (isConceptSctid(rm.getReferencedComponentId())) {
				//Don't validate, don't create if required
				return gl.getConcept(rm.getReferencedComponentId(), false, false);
			} else {
				return gl.getDescription(rm.getReferencedComponentId(), false, false);
			}
		}
		return null;
	}

	/*
	 * Adds a historical association using the string based map format as per the Terminology Server's API
	 */
	public static void addHistoricalAssociationInTsForm(Concept c, AssociationEntry histAssoc) {
		//The TS form can only store active associations
		if (histAssoc.isActive()) {
			AssociationTargets targets = c.getAssociationTargets();
			String target = histAssoc.getTargetComponentId();
			switch (histAssoc.getRefsetId()) {
				case (SCTID_ASSOC_MOVED_TO_REFSETID) : targets.getMovedTo().add(target);
														break;
				case (SCTID_ASSOC_WAS_A_REFSETID) : targets.getWasA().add(target);
														break;
				case (SCTID_ASSOC_REPLACED_BY_REFSETID) : targets.getReplacedBy().add(target);
														break;
				case (SCTID_ASSOC_SAME_AS_REFSETID) : targets.getSameAs().add(target);
														break;	
				case (SCTID_ASSOC_POSS_EQUIV_REFSETID) : targets.getPossEquivTo().add(target);
														break;	
			}
		}
	}

	public static boolean inModule(Component c, String[] modules) {
		for (String module : modules) {
			if (c.getModuleId().equals(module)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Search concept for a group which features the same relationship 
	 * as that of "type" that can be found in the specfied group
	 * @throws TermServerScriptException 
	 */
	public static RelationshipGroup findMatchingGroup(Concept c, RelationshipGroup g,
			Concept type, CharacteristicType charType, boolean allowNewConcepts) throws TermServerScriptException {
		Concept targetValue = getTarget(g, new Concept[] { type }, allowNewConcepts);
		//Do we have that same type/value in concept?
		for (RelationshipGroup checkGroup : c.getRelationshipGroups(charType)) {
			Concept matchedValue =  getTarget(checkGroup, new Concept[] { type }, allowNewConcepts);
			if (matchedValue != null && matchedValue.equals(targetValue)) {
				return checkGroup;
			}
		}
		return null;
	}

	public static boolean isECL(String ecl) {
		if (ecl.contains("<") || ecl.contains("{") || ecl.contains(":") || ecl.toUpperCase().contains("MINUS")) {
			return true;
		}
		return false;
	}

	public static String formatReleaseDate(String effectiveTime) throws TermServerScriptException {
		if (effectiveTime.length() != 8) {
			throw new TermServerScriptException("Malformed effectiveTime '" + effectiveTime + "'");
		}
		return effectiveTime.substring(0,4) + "-" + effectiveTime.substring(4,6) + "-" + effectiveTime.substring(6,8);
	}

	public static boolean hasUsGbPtVariance(Concept c) throws TermServerScriptException {
		Description usPT = c.getPreferredSynonym(US_ENG_LANG_REFSET);
		Description gbPT = c.getPreferredSynonym(GB_ENG_LANG_REFSET);
		if (usPT == null || gbPT == null || usPT.equals(gbPT)) {
			return false;
		}
		return true;
	}

	//Do we in fact have ANY descriptions which are acceptable in US but not in GB AND visa versa
	public static boolean hasUsGbVariance(Concept c) throws TermServerScriptException {
		boolean hasUsSpecific = false;
		for (Description d : c.getDescriptions(US_ENG_LANG_REFSET, Acceptability.BOTH, DescriptionType.SYNONYM, ActiveState.ACTIVE)) {
			if (!d.isAcceptable(GB_ENG_LANG_REFSET) && !d.isPreferred(GB_ENG_LANG_REFSET)) {
				hasUsSpecific = true;
				break;
			}
		}
		
		boolean hasGbSpecific = false;
		for (Description d : c.getDescriptions(GB_ENG_LANG_REFSET, Acceptability.BOTH, DescriptionType.SYNONYM, ActiveState.ACTIVE)) {
			if (!d.isAcceptable(US_ENG_LANG_REFSET) && !d.isPreferred(US_ENG_LANG_REFSET)) {
				hasGbSpecific = true;
				break;
			}
		}
		
		return hasUsSpecific && hasGbSpecific;
	}

	public static boolean hasLangRefsetDifference(String descId, Concept a, Concept b) {
		//Check that we have this description in both concepts (eg local and extension branch)
		Description descA = a.getDescription(descId);
		Description descB = b.getDescription(descId);
		if (descA == null || descB == null) {
			return true;
		}
		
		Map<String, Acceptability> mapAcceptA = descA.getAcceptabilityMap();
		Map<String, Acceptability> mapAcceptB = descB.getAcceptabilityMap();
		
		if ((mapAcceptA == null || mapAcceptA.isEmpty()) && (mapAcceptB == null || mapAcceptB.isEmpty())) {
			return false;
		}
		
		if ((mapAcceptA == null && (mapAcceptB != null && !mapAcceptB.isEmpty())) ||
			(mapAcceptA != null && (mapAcceptB == null || mapAcceptB.isEmpty())) ||
			(mapAcceptA != null && mapAcceptA.size() != mapAcceptB.size())) {
			return true;
		}
		
		for (String refsetId : mapAcceptA.keySet()) {
			Acceptability acceptA = mapAcceptA.get(refsetId);
			Acceptability acceptB = mapAcceptB.get(refsetId);
			if (acceptB == null || !acceptA.equals(acceptB)) {
				return true;
			}
		}
		
		return false;
	}

	public static boolean hasDescActiveStateDifference(String descId, Concept a, Concept b) {
		//Check that we have this description in both concepts (eg local and extension branch)
		Description descA = a.getDescription(descId);
		Description descB = b.getDescription(descId);
		if (descA == null || descB == null) {
			return true;
		}
		return descA.isActive() != descB.isActive();
	}
	
	public static String shortestTerm(Concept c) {
		String shortestTerm = null;
		for (Description d : c.getDescriptions(ActiveState.ACTIVE)) {
			if (shortestTerm == null || d.getTerm().length() < shortestTerm.length()) {
				shortestTerm = d.getTerm();
			}
		}
		return shortestTerm;
	}

	public static boolean isEmpty(Object obj) {
		if (obj == null) {
			return true;
		}
		return StringUtils.isEmpty(obj.toString());
	}

	public static String[] deconstructSCTIDFsn(String sctidFsn) {
		String[] parts = new String[2];
		String[] split = sctidFsn.split(ESCAPED_PIPE);
		parts[0] = split[0].trim();
		parts[1] = split[1];
		return parts;
	}
}

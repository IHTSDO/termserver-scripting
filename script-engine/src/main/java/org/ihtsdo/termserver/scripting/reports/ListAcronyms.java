package org.ihtsdo.termserver.scripting.reports;

import java.io.IOException;
import java.io.PrintStream;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.domain.*;

/**
 * WIPEDGUIDE-114 
 */
public class ListAcronyms extends TermServerReport {
	
	static String regex ="[A-Z]{2,}";
	Pattern pattern;
	Map<String, String> expansionMap = new TreeMap<>();
	Map<String, List<Concept>> acryonymUseage = new HashMap<>();
	Set<String> acronymFoundWithExpansion = new HashSet<>();
	
	public static void main(String[] args) throws TermServerScriptException, IOException {
		ListAcronyms report = new ListAcronyms();
		try {
			report.pattern = Pattern.compile(regex);
			report.additionalReportColumns="Expansion, Found Together, Usage Count, Examples";
			report.init(args);
			report.loadProjectSnapshot(false);  //Load all descriptions
			report.postInit();
			report.findAcronyms();
			report.listAcronyms();
		} catch (Exception e) {
			info("Failed to ListAcceptableSynonyms due to " + e.getMessage());
			e.printStackTrace(new PrintStream(System.out));
		} finally {
			report.finish();
		}
	}

	private void findAcronyms() throws TermServerScriptException {
		Set<String> acronyms = new HashSet<>();
		//for (Concept c : Collections.singleton(gl.getConcept("788109007"))) {
		for (Concept c : gl.getAllConcepts()) {
			if (!c.isActive()) {
				continue;
			}
			acronyms.clear();
			for (Description d : c.getDescriptions(ActiveState.ACTIVE)) {
				if (d.isPreferred() && !d.getType().equals(DescriptionType.TEXT_DEFINITION)) {
					addAcronyms(acronyms, d.getTerm());
				}
			}
			//Can we find expanded versions of these acronyms?
			for (String acronym : acronyms) {
				if (expansionMap.get(acronym) == null) {
					populateExpansionMap(c, acronym);
				}
				
				List<Concept> useage = acryonymUseage.get(acronym);
				if (useage == null) {
					useage = new ArrayList<>();
					acryonymUseage.put(acronym, useage);
				}
				useage.add(c);
			}
		}
	}
	
	private void listAcronyms() throws TermServerScriptException {
		for (String acronym : expansionMap.keySet()) {
			List<Concept> usage = acryonymUseage.get(acronym);
			String examples = usage.stream()
					.map(c -> c.toString())
					.limit(5)
					.collect(Collectors.joining(", \n"));
			report(PRIMARY_REPORT, acronym, expansionMap.get(acronym), acronymFoundWithExpansion.contains(acronym)?"Y":"N" ,usage.size(), examples);
			countIssue(null);
		}
	}

	private void populateExpansionMap(Concept c, String acronym) {
		boolean expansionFound = false;
		expansionMap.put(acronym, null);
		//Find successive words that match the letters of the acronym
		String expansion = "";
		String acronymLower = acronym.toLowerCase();
		nextDescription:
		for (Description d : c.getDescriptions(ActiveState.ACTIVE)) {
			String[] words = d.getTerm().split(" ");
			String[] wordsLower = d.getTerm().toLowerCase().split(" ");
			//Do we have enough words to satisfy our acronym?
			if (words.length < acronym.length()) {
				continue;
			}
			int lettersMatched = 0;
			nextWord:
			for (int wordIdx = 0; wordIdx < words.length; wordIdx++) {
				if (wordsLower[wordIdx].charAt(0) == acronymLower.charAt(0)) {
					expansion = words[wordIdx];
					lettersMatched = 1;
					for (int acroIdx = 1; acroIdx < acronym.length() && (wordIdx+acroIdx) < wordsLower.length; acroIdx++) {
						if (wordsLower[wordIdx+acroIdx].charAt(0) != acronymLower.charAt(acroIdx)) {
							continue nextWord;
						}
						expansion += " " + words[wordIdx+acroIdx];
						lettersMatched++;
					}
					//Subsequent words all matched our acronym, we can stop now
					//But did we find enough?
					if (lettersMatched == acronym.length()) {
						expansionMap.put(acronym, expansion);
						expansionFound = true;
						break nextDescription;
					} 
				}
			}
		}
		
		//Can we find the acronym in the same description as the expansion?
		if (expansionFound) {
			for (Description d : c.getDescriptions(ActiveState.ACTIVE)) {
				if (d.getTerm().contains(acronym) && d.getTerm().contains(expansion)) {
					acronymFoundWithExpansion.add(acronym);
				}
			}
		}
	}

	private void addAcronyms(Set<String> acronyms, String term) {
		Matcher matcher = pattern.matcher(term);
		while (matcher.find()) {
			acronyms.add(matcher.group());
		}
	}

}

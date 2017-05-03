package org.ihtsdo.termserver.scripting.reports;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.ihtsdo.termserver.scripting.GraphLoader;
import org.ihtsdo.termserver.scripting.TermServerScript;
import org.ihtsdo.termserver.scripting.TermServerScriptException;
import org.ihtsdo.termserver.scripting.client.SnowOwlClientException;
import org.ihtsdo.termserver.scripting.domain.Concept;
import org.ihtsdo.termserver.scripting.domain.Description;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;

import com.google.common.base.Charsets;
import com.google.common.io.Files;

/**
 * Reports all concepts that contains an international term without the national equivalent, or vis versa.
 */
public class BanUsanReport extends TermServerScript{
	
	GraphLoader gl = GraphLoader.getGraphLoader();
	String matchText = "+";
	List<NationalTermRule> nationalTermRules;
	
	public static void main(String[] args) throws TermServerScriptException, IOException, SnowOwlClientException {
		BanUsanReport report = new BanUsanReport();
		try {
			report.init(args);
			report.loadProjectSnapshot(false);  //Load all descriptions
			report.reportUnMatchedNationalTerms();
		} catch (Exception e) {
			println("Failed to produce Description Report due to " + e.getMessage());
			e.printStackTrace(new PrintStream(System.out));
		} finally {
			report.finish();
		}
	}
	
	private void reportUnMatchedNationalTerms() throws TermServerScriptException {
		for (NationalTermRule nationalTermRule : nationalTermRules) {
			Set<Concept> concepts = evaluateExpression(nationalTermRule.expression);
			reportUnMatchedNationalTerm(nationalTermRule, concepts);
		}
	}

	private Set<Concept> evaluateExpression(String humanReadableExpression) throws TermServerScriptException {
		StringBuffer expression = new StringBuffer(humanReadableExpression);
		SnomedUtils.makeMachineReadable(expression);
		String[] parts = expression.toString().split(UNION);
		Set<Concept> expansion = new HashSet<Concept>();
		for (String part : parts) {
			Set<Concept> partExpansion = expandExpression(part);
			expansion.addAll(partExpansion);
		}
		return expansion;
	}

	private Set<Concept> expandExpression(String expressionPart) throws TermServerScriptException {
		String subHierarchySCTID = "";
		boolean includeSelf = false;
		if (expressionPart.startsWith(DESCENDANT_OR_SELF)) {
			subHierarchySCTID = expressionPart.substring(2);
			includeSelf = true;
		} else if (expressionPart.startsWith(DESCENDANT)) {
			subHierarchySCTID = expressionPart.substring(1);
		}
		Concept subHierarchy = gl.getConcept(subHierarchySCTID);
		Set<Concept> expansion = subHierarchy.getDescendents(NOT_SET);
		if (includeSelf) {
			expansion.add(subHierarchy);
		}
		return expansion;
	}

	private void reportUnMatchedNationalTerm(NationalTermRule nationalTermRule, Set<Concept> concepts) throws TermServerScriptException {
		for (Concept c : concepts) {
			if (c.isActive()) {
				Description internationalTerm = null;
				Description nationalTerm = null;
				for (Description d : c.getDescriptions(ActiveState.ACTIVE)) {
					if (d.getTerm().contains(nationalTermRule.nationalTerm)) {
						nationalTerm = d;
					}
					
					if (d.getTerm().contains(nationalTermRule.internationalTerm)) {
						internationalTerm = d;
					}
				}
				Description failure = null;
				String issue = "";
				if (internationalTerm == null && nationalTerm != null) {
					issue = "National Term '" + nationalTermRule.nationalTerm +
							"' exists without equivalent International Term '" +
							nationalTermRule.internationalTerm;
					failure = nationalTerm;
				} else if (internationalTerm != null && nationalTerm == null) {
					issue = "International Term '" + nationalTermRule.internationalTerm +
							"' exists without equivalent National Term '" +
							nationalTermRule.nationalTerm;
					failure = nationalTerm;
				}
				//TODO Add check that national term is not acceptable in other lang refsets
				
				if (failure != null) {
					report(c, failure, issue);
				}
			}
		}
	}

	protected void report (Concept c, Description d, String issue) {
		String line = 	c.getConceptId() + COMMA_QUOTE + 
						c.getFsn() + QUOTE_COMMA_QUOTE + 
						d.getDescriptionId() + QUOTE_COMMA_QUOTE +
						d.getTerm() + QUOTE_COMMA_QUOTE +
						issue + QUOTE;
		writeToFile(line);
	}
	
	protected void init(String[] args) throws IOException, TermServerScriptException {
		super.init(args);
		boolean fileLoaded = false;
		for (int i=0; i < args.length; i++) {
			if (args[i].equalsIgnoreCase("-z")) {
				loadNationalTerms(args[i+1]);
				fileLoaded = true;
			}
		}
		
		if (!fileLoaded) {
			println ("Failed to find Ban/Usan file to load.  Specify path with 'z' command line parameter");
			System.exit(1);
		}

		SimpleDateFormat df = new SimpleDateFormat("yyyyMMdd_HHmmss");
		String reportFilename = getScriptName() + /*filter +*/ "_" + project.toLowerCase() + "_" + df.format(new Date()) + "_" + env  + ".csv";
		reportFile = new File(outputDir, reportFilename);
		reportFile.createNewFile();
		println ("Outputting Report to " + reportFile.getAbsolutePath());
		writeToFile ("Concept, FSN, Desc_SCTID, Term, Issue");
	}

	private void loadNationalTerms(String fileName) throws TermServerScriptException {
		try {
			File nationalTerms = new File(fileName);
			List<String> lines = Files.readLines(nationalTerms, Charsets.UTF_8);
			println ("Loading National Terms from " + fileName);
			nationalTermRules = new ArrayList<NationalTermRule>();
			for (String line : lines) {
				NationalTermRule newRule = importNationalTermRule(line);
				nationalTermRules.add(newRule);
			}
		} catch (Exception e) {
			throw new TermServerScriptException("Unable to import national terms file " + fileName, e);
		}
	}

	@Override
	protected Concept loadLine(String[] lineItems)
			throws TermServerScriptException {
		return null;
	}
	
	private NationalTermRule importNationalTermRule(String line) {
		String[] column = line.split(TAB);
		NationalTermRule newRule = new NationalTermRule();
		newRule.internationalTerm = column[0];
		newRule.nationalTerm = column[1];
		newRule.dialect = column[2];
		newRule.expression = column[3];
		return newRule;
	}
	
	class NationalTermRule {
		String internationalTerm;
		String nationalTerm;
		String dialect;
		String expression;
	}
}
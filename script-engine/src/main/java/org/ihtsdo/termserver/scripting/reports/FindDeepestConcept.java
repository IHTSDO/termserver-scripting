package org.ihtsdo.termserver.scripting.reports;

import java.io.IOException;

import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.domain.Concept;

/**
 * Answer a quick question: What is the deepest concept ie via longest path from Root?
 */
public class FindDeepestConcept extends TermServerReport {
	
	
	public static void main(String[] args) throws TermServerScriptException, IOException, InterruptedException {
		FindDeepestConcept report = new FindDeepestConcept();
		report.init(args);
		report.loadProjectSnapshot(false);  //Load all descriptions
		report.findDeepestConcept();
	}

	private void findDeepestConcept() throws TermServerScriptException {
		Concept deepestConcept = ROOT_CONCEPT;
		for (Concept c : gl.getAllConcepts()) {
			if (c.getMaxDepth() > deepestConcept.getMaxDepth()) {
				deepestConcept = c;
			}
		}
		info ("Deepest Concept is " + deepestConcept + " with longest path: " + deepestConcept.getMaxDepth() + " and shortest: " + deepestConcept.getDepth());
	}

}

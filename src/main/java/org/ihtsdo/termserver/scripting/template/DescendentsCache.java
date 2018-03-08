package org.ihtsdo.termserver.scripting.template;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.ihtsdo.termserver.scripting.GraphLoader;
import org.ihtsdo.termserver.scripting.TermServerScriptException;
import org.ihtsdo.termserver.scripting.domain.Concept;
import org.ihtsdo.termserver.scripting.domain.RF2Constants;

public class DescendentsCache implements RF2Constants {

	Map<Concept, Set<Concept>> descendentOrSelfCache = new HashMap<>();
	
	public Set<Concept> getDescendentsOrSelf (Concept c) throws TermServerScriptException {
		Set<Concept> descendents = descendentOrSelfCache.get(c);
		if (descendents == null) {
			//Ensure we're working with the local copy rather than TS JSON
			Concept localConcept = GraphLoader.getGraphLoader().getConcept(c.getConceptId());
			descendents = localConcept.getDescendents(NOT_SET);
			descendents.add(localConcept); //Or Self
			descendentOrSelfCache.put(localConcept, descendents);
		}
		return descendents;
	}
}
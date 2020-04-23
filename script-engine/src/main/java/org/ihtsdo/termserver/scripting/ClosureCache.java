package org.ihtsdo.termserver.scripting;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.ihtsdo.otf.exception.TermServerScriptException;
import org.ihtsdo.termserver.scripting.domain.Concept;
import org.ihtsdo.termserver.scripting.domain.RF2Constants;

public class ClosureCache implements RF2Constants{

	static ClosureCache singleton = null;
	private GraphLoader gl = null;
	private Map<Concept, Set<Concept>> closureCache = null;
	
	public static ClosureCache getClosureCache() {
		if (singleton == null) {
			singleton = new ClosureCache();
			singleton.closureCache = new HashMap<Concept, Set<Concept>>();
			singleton.gl = GraphLoader.getGraphLoader();
		}
		return singleton;
	}
	
	public Set<Concept> getClosure(Concept c) throws TermServerScriptException {
		if (!closureCache.containsKey(c)) {
			Concept preLoadedConcept = gl.getConcept(c.getConceptId());
			Set<Concept> descendents = preLoadedConcept.getDescendents(NOT_SET);
			closureCache.put(c, descendents);
		}
		return closureCache.get(c);
	}
	
}

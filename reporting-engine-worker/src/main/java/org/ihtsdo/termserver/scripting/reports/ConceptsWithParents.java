package org.ihtsdo.termserver.scripting.reports;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import org.ihtsdo.termserver.job.ReportClass;
import org.ihtsdo.termserver.scripting.TermServerScriptException;
import org.ihtsdo.termserver.scripting.client.SnowOwlClientException;
import org.ihtsdo.termserver.scripting.dao.ReportSheetManager;
import org.ihtsdo.termserver.scripting.domain.*;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;
import org.snomed.otf.scheduler.domain.*;

public class ConceptsWithParents extends TermServerReport implements ReportClass {
	
	Map<String, Map<String, Concept>> semanticTagHierarchy = new HashMap<>();
	
	public static void main(String[] args) throws TermServerScriptException, IOException, SnowOwlClientException {
		TermServerReport.run(ConceptsWithParents.class, args);
	}
	
	public void init (JobRun run) throws TermServerScriptException {
		ReportSheetManager.targetFolderId = "1E6kDgFExNA9CRd25yZk_Y7l-KWRf8k6B"; //Drugs / Re-terming
		additionalReportColumns="FSN, DEF_STATUS, ConceptType, ImmedateStatedParent, Inferred Parents, PARENT'S PARENT";
		super.init(run);
	}

	@Override
	public Job getJob() {
		String[] parameterNames = new String[] { "SubHierarchy" };
		return new Job("Concepts with Parents",
						"Lists concepts along with their parents, and their parent's parents",
						parameterNames);
	}

	public void runJob() throws TermServerScriptException {
		
		//TODO work through all our subhierarchies and join them together
		//so we don't get duplicates
		Concept dispositions = gl.getConcept("766779001 |Medicinal product categorized by disposition (product)|");
		Concept structures = gl.getConcept("763760008 |Medicinal product categorized by structure (product)|");
		Set<Concept> conceptsOfInterest = dispositions.getDescendents(NOT_SET);
		conceptsOfInterest.addAll(structures.getDescendents(NOT_SET));
		ConceptType[] typesOfInterest = new ConceptType[] { ConceptType.STRUCTURE_AND_DISPOSITION_GROUPER,
															ConceptType.STRUCTURAL_GROUPER,
															ConceptType.DISPOSITION_GROUPER };
		
		for (Concept c : conceptsOfInterest) {
			if (SnomedUtils.isConceptType(c, typesOfInterest)) {
				SnomedUtils.populateConceptType(c);
				List<Concept> statedParents = c.getParents(CharacteristicType.STATED_RELATIONSHIP);
				String statedParentsStr = statedParents.stream().map(p->p.toString())
						.collect(Collectors.joining(",\n"));
				List<Concept> parents = c.getParents(CharacteristicType.INFERRED_RELATIONSHIP);
				String parentsStr = parents.stream().map(p->p.toString())
						.collect(Collectors.joining(",\n"));
				String parentsParentsStr = parents.stream()
						.flatMap(p->p.getParents(CharacteristicType.INFERRED_RELATIONSHIP).stream())
						.map(pp->pp.toString())
						.collect(Collectors.joining(",\n"));
				String defn = SnomedUtils.translateDefnStatus(c.getDefinitionStatus());
				report (c, defn, c.getConceptType(), statedParentsStr, parentsStr, parentsParentsStr);
				incrementSummaryInformation("Concepts reported");
			}
		}
	}



}

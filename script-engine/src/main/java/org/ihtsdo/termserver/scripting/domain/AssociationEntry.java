package org.ihtsdo.termserver.scripting.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component;
import org.ihtsdo.termserver.scripting.GraphLoader;
import org.ihtsdo.termserver.scripting.TermServerScript;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;

//id	effectiveTime	active	moduleId 	refsetId 	referencedComponentId	targetComponentId
public class AssociationEntry extends RefsetMember implements RF2Constants {

	public static String TARGET_COMPONENT_ID = "targetComponentId";
	
	public AssociationEntry clone(String newComponentSctId) {
		AssociationEntry clone = new AssociationEntry();
		clone.id = UUID.randomUUID().toString();
		clone.effectiveTime = null;
		clone.moduleId = this.moduleId;
		clone.active = this.active;
		clone.refsetId = this.refsetId;
		clone.referencedComponentId = newComponentSctId;
		clone.setTargetComponentId(this.getTargetComponentId());
		clone.isDirty = true; //New components need to be written to any delta
		return clone;
	}
	
	public AssociationEntry clone() {
		return clone(this.referencedComponentId);
	}
	
	public String toString() {
		String activeIndicator = isActive()?"":"*";
		return "[" + activeIndicator + "HA]:" + id + " - " + refsetId + " : " + referencedComponentId + "->" + getTargetComponentId();
	}
	
	public String toVerboseString(){
		try {
			String source = GraphLoader.getGraphLoader().getConcept(referencedComponentId).toString();
			String refset = SnomedUtils.getPT(refsetId).replace("association reference set","");
			String target = GraphLoader.getGraphLoader().getConcept(getTargetComponentId()).toString();
			return source + " " + refset + " " + target;
		} catch (Exception e) {
			throw new IllegalArgumentException("Unable to form association string",e);
		}
	}
	
	public static AssociationEntry fromRf2(String[] lineItems) {
		AssociationEntry h = new AssociationEntry();
		h.setId(lineItems[ASSOC_IDX_ID]);
		h.setEffectiveTime(lineItems[ASSOC_IDX_EFFECTIVETIME]);
		h.setActive(lineItems[ASSOC_IDX_ACTIVE].equals("1"));
		h.setModuleId(lineItems[ASSOC_IDX_MODULID]);
		h.setRefsetId(lineItems[ASSOC_IDX_REFSETID]);
		h.setReferencedComponentId(lineItems[ASSOC_IDX_REFCOMPID]);
		if (lineItems.length <= ASSOC_IDX_TARGET) {
			TermServerScript.warn(" HistAssoc " + lineItems[ASSOC_IDX_ID] + " missing targetComponetId");
		} else {
			h.setTargetComponentId(lineItems[ASSOC_IDX_TARGET]);
		}
		return h;
	}
	
	public String[] toRF2() {
		return new String[] { id, 
				(effectiveTime==null?"":effectiveTime), 
				(active?"1":"0"),
				moduleId, refsetId,
				referencedComponentId,
				getTargetComponentId()
		};
	}
	
	public String[] toRF2Deletion() {
		return new String[] { id, 
				(effectiveTime==null?"":effectiveTime), 
				deletionEffectiveTime,
				(active?"1":"0"),
				"1",
				moduleId, refsetId,
				referencedComponentId,
				getTargetComponentId()
		};
	}

	public String getTargetComponentId() {
		return getField(TARGET_COMPONENT_ID);
	}
	public void setTargetComponentId(String targetComponentId) {
		if (getTargetComponentId() != null && !getTargetComponentId().equals(targetComponentId)) {
			isDirty = true;
		}
		setField(TARGET_COMPONENT_ID, targetComponentId);
	}

	@Override
	public ComponentType getComponentType() {
		return ComponentType.HISTORICAL_ASSOCIATION;
	}
	
	@Override 
	public boolean equals(Object o) {
		if (o instanceof AssociationEntry) {
			return this.getId().equals(((AssociationEntry)o).getId());
		}
		return false;
	}

	@Override
	public List<String> fieldComparison(Component other) {
		AssociationEntry otherA = (AssociationEntry)other;
		List<String> differences = new ArrayList<>();
		String name = this.getClass().getSimpleName(); 
		commonFieldComparison(otherA, differences);
		
		if (!this.getTargetComponentId().equals(otherA.getTargetComponentId())) {
			differences.add("TargetComponentId is different in " + name + ": " + this.getTargetComponentId() + " vs " + otherA.getTargetComponentId());
		}
		return differences;
	}
}

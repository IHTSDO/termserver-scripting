package org.ihtsdo.termserver.scripting.domain;

import java.util.*;

import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component;

//id	effectiveTime	active	moduleId	refsetId	referencedComponentId	valueId
public class InactivationIndicatorEntry extends RefsetMember implements RF2Constants {

	private static String VALUE_ID = "valueId";
	
	public InactivationIndicatorEntry clone(String newComponentSctId) {
		InactivationIndicatorEntry clone = new InactivationIndicatorEntry();
		clone.id = UUID.randomUUID().toString();
		clone.effectiveTime = null;
		clone.moduleId = this.moduleId;
		clone.active = this.active;
		clone.refsetId = this.refsetId;
		clone.referencedComponentId = newComponentSctId;
		clone.setInactivationReasonId(getInactivationReasonId());
		clone.isDirty = true; //New components need to be written to any delta
		return clone;
	}
	private static InactivationIndicatorEntry withDefaults() {
		InactivationIndicatorEntry i = new InactivationIndicatorEntry();
		i.setId(UUID.randomUUID().toString());
		i.setModuleId(SCTID_CORE_MODULE);
		i.setActive(true);
		i.setDirty();
		return i;
	}
	
	public static InactivationIndicatorEntry withDefaults (Concept c) {
		InactivationIndicatorEntry i = withDefaults();
		i.setRefsetId(SCTID_CON_INACT_IND_REFSET);
		i.setReferencedComponentId(c.getId());
		return i;
	}
	
	public static InactivationIndicatorEntry withDefaults (Description d) {
		InactivationIndicatorEntry i = withDefaults();
		i.setRefsetId(SCTID_DESC_INACT_IND_REFSET);
		i.setReferencedComponentId(d.getId());
		i.setDirty();
		return i;
	}
	
	public String toString() {
		String activeIndicator = isActive()?"":"*";
		return "[" + activeIndicator + "IA]:" + id + " - " + refsetId + " : " + referencedComponentId + "->" + getInactivationReasonId();
	}
	
	public String[] toRF2() {
		return new String[] { id, 
				(effectiveTime==null?"":effectiveTime), 
				(active?"1":"0"),
				moduleId, refsetId,
				referencedComponentId,
				getInactivationReasonId()
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
				getInactivationReasonId()
		};
	}

	public String getInactivationReasonId() {
		return getField(VALUE_ID);
	}
	public void setInactivationReasonId(String inactivationReasonId) {
		if (getInactivationReasonId() != null && !getInactivationReasonId().equals(inactivationReasonId)) {
			isDirty = true;
		}
		setField(VALUE_ID,inactivationReasonId);
	}

	public static InactivationIndicatorEntry fromRf2(String[] lineItems) {
		InactivationIndicatorEntry i = new InactivationIndicatorEntry();
		i.setId(lineItems[INACT_IDX_ID]);
		i.setEffectiveTime(lineItems[INACT_IDX_EFFECTIVETIME]);
		i.setActive(lineItems[INACT_IDX_ACTIVE].equals("1"));
		i.setModuleId(lineItems[INACT_IDX_MODULID]);
		i.setRefsetId(lineItems[INACT_IDX_REFSETID]);
		i.setReferencedComponentId(lineItems[INACT_IDX_REFCOMPID]);
		i.setInactivationReasonId(lineItems[INACT_IDX_REASON_ID]);
		return i;
	}
	
	@Override 
	public boolean equals(Object o) {
		if (o instanceof InactivationIndicatorEntry) {
			return this.getId().equals(((InactivationIndicatorEntry)o).getId());
		}
		return false;
	}
	
	public List<String> fieldComparison(Component other) {
		InactivationIndicatorEntry otherI = (InactivationIndicatorEntry)other;
		List<String> differences = new ArrayList<>();
		String name = this.getClass().getSimpleName(); 
		commonFieldComparison(otherI, differences);
		
		if (!this.getInactivationReasonId().equals(otherI.getInactivationReasonId())) {
			differences.add("InactivationReasonId is different in " + name + ": " + this.getInactivationReasonId() + " vs " + otherI.getInactivationReasonId());
		}
		return differences;
	}
}

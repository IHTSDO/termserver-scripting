package org.ihtsdo.termserver.scripting.domain;

import java.util.*;

import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component;
import org.ihtsdo.termserver.scripting.util.SnomedUtils;

//id	effectiveTime	active	moduleId	refsetId	referencedComponentId	acceptabilityId
public class LangRefsetEntry extends RefsetMember implements RF2Constants{

	private static String ACCEPTABILITY_ID = "acceptabilityId";
	
	public LangRefsetEntry clone(String newDescriptionSctId) {
		LangRefsetEntry clone = new LangRefsetEntry();
		clone.id = UUID.randomUUID().toString();
		clone.effectiveTime = null;
		clone.moduleId = this.moduleId;
		clone.active = this.active;
		clone.refsetId = this.refsetId;
		clone.referencedComponentId = newDescriptionSctId;
		clone.setAcceptabilityId(getAcceptabilityId());
		clone.setDirty(); //New components need to be written to any delta
		return clone;
	}
	
	public String[] toRF2() {
		return new String[] { id, 
				(effectiveTime==null?"":effectiveTime), 
				(active?"1":"0"),
				moduleId, refsetId,
				referencedComponentId,
				getAcceptabilityId()
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
				getAcceptabilityId()
		};
	}

	public String getAcceptabilityId() {
		return getField(ACCEPTABILITY_ID);
	}
	public void setAcceptabilityId(String acceptabilityId) {
		if (getAcceptabilityId() != null && !getAcceptabilityId().equals(acceptabilityId)) {
			setDirty();
		}
		this.setField(ACCEPTABILITY_ID,acceptabilityId);
	}
	
	public static LangRefsetEntry fromRf2 (String[] lineItems) {
		LangRefsetEntry l = new LangRefsetEntry();
		l.setId(lineItems[LANG_IDX_ID]);
		l.setEffectiveTime(lineItems[LANG_IDX_EFFECTIVETIME]);
		l.setActive(lineItems[LANG_IDX_ACTIVE].equals("1"));
		l.setModuleId(lineItems[LANG_IDX_MODULID]);
		l.setRefsetId(lineItems[LANG_IDX_REFSETID]);
		l.setReferencedComponentId(lineItems[LANG_IDX_REFCOMPID]);
		l.setAcceptabilityId(lineItems[LANG_IDX_ACCEPTABILITY_ID]);
		l.setClean();
		return l;
	}
	
	@Override
	public boolean equals(Object other) {
		if (other == this) {
			return true;
		}
		if ((other instanceof LangRefsetEntry) == false) {
			return false;
		}
		LangRefsetEntry rhs = ((LangRefsetEntry) other);
		//If both sides have an SCTID, then compare that
		if (this.getId() != null && rhs.getId() != null) {
			return this.getId().equals(rhs.getId());
		}
		//TODO Otherwise compare contents
		return false;
	}
	
	@Override
	public String toString() {
		return toString(false);
	}
	
	public String toString(boolean detail) {
		try {
		return "[ " + getReferencedComponentId() + " is " + 
				SnomedUtils.translateAcceptability(getAcceptabilityId()) + " in " +
				getRefsetId() + 
				(detail? " - ET" + effectiveTime : "" ) +
				(detail? " - " + id : "" ) +
				" ]";
		} catch (Exception e) {
			return e.getMessage();
		}
	}

	@Override
	public ComponentType getComponentType() {
		return ComponentType.LANGREFSET;
	}
	
	@Override
	public List<String> fieldComparison(Component other) {
		LangRefsetEntry otherL = (LangRefsetEntry)other;
		List<String> differences = new ArrayList<>();
		String name = this.getClass().getSimpleName(); 
		commonFieldComparison(otherL, differences);
		
		if (!this.getAcceptabilityId().equals(otherL.getAcceptabilityId())) {
			differences.add("InactivationReasonId is different in " + name + ": " + this.getAcceptabilityId() + " vs " + otherL.getAcceptabilityId());
		}
		return differences;
	}

	public static LangRefsetEntry withDefaults(Description d, String refsetId, String acceptabilityId) {
		LangRefsetEntry entry = new LangRefsetEntry();
		entry.id = UUID.randomUUID().toString();
		entry.effectiveTime = null;
		entry.active = true;
		entry.refsetId = refsetId;
		entry.referencedComponentId = d.getDescriptionId();
		entry.setAcceptabilityId(acceptabilityId);
		entry.moduleId = SCTID_CORE_MODULE;
		return entry;
	}

}

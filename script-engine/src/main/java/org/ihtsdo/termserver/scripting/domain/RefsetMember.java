package org.ihtsdo.termserver.scripting.domain;

import java.util.*;

import org.apache.commons.lang.NotImplementedException;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.Component;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class 

RefsetMember extends Component {

	@SerializedName("active")
	@Expose
	protected  Boolean active;
	@SerializedName("released")
	@Expose
	protected  Boolean released;
	@SerializedName("releasedEffectiveTime")
	@Expose
	protected  Integer releasedEffectiveTime;
	@SerializedName("memberId")
	@Expose
	protected  String id;
	@SerializedName("moduleId")
	@Expose
	protected  String moduleId;
	@SerializedName("refsetId")
	@Expose
	protected  String refsetId;
	@SerializedName("referencedComponentId")
	@Expose
	protected  String referencedComponentId;
	@SerializedName("additionalFields")
	@Expose
	protected  Map<String, String> additionalFields = new HashMap<>();
	@SerializedName("referencedComponent")
	@Expose
	protected Object referencedComponent;
	@SerializedName("effectiveTime")
	@Expose
	protected  String effectiveTime;
	
	protected String deletionEffectiveTime;
	
	protected boolean isDeleted = false;
	
	public RefsetMember() {}
	
	public RefsetMember(String refsetId, Concept referencedCompoment, String[] additionalValues) {
		this.refsetId = refsetId;
		this.referencedComponent = referencedCompoment;
	}

	public void setEffectiveTime(String effectiveTime) {
		if (this.effectiveTime != null && !this.effectiveTime.isEmpty() && effectiveTime == null) {
			//Are we resetting this component to mark a change?
			setDirty();
		}
		this.effectiveTime = effectiveTime;
	}
	
	public String getModuleId() {
		return moduleId;
	}
	
	public void setModuleId(String moduleId) {
		if (this.moduleId != null && !this.moduleId.equals(moduleId)) {
			setDirty();
			this.effectiveTime = null;
		}
		this.moduleId = moduleId;
	}
	public boolean isActive() {
		return active;
	}
	public void setActive(boolean newActiveState) {
		if (this.active != null && this.active != newActiveState) {
			setDirty();
			setEffectiveTime(null);
		}
		this.active = newActiveState;
	}
	public boolean isDeleted() {
		return isDeleted;
	}
	public Boolean getReleased() {
		return released;
	}

	public void setReleased(Boolean released) {
		this.released = released;
	}

	public Integer getReleasedEffectiveTime() {
		return releasedEffectiveTime;
	}

	public void setReleasedEffectiveTime(Integer releasedEffectiveTime) {
		this.releasedEffectiveTime = releasedEffectiveTime;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getRefsetId() {
		return refsetId;
	}

	public void setRefsetId(String refsetId) {
		this.refsetId = refsetId;
	}

	public String getReferencedComponentId() {
		return referencedComponentId;
	}

	public void setReferencedComponentId(String referencedComponentId) {
		this.referencedComponentId = referencedComponentId;
	}

	public Map<String, String> getAdditionalFields() {
		return additionalFields;
	}

	public void setAdditionalFields(Map<String, String> additionalFields) {
		this.additionalFields = additionalFields;
	}
	
	public String getField(String key) {
		return this.additionalFields.get(key);
	}
	
	public void setField(String key, String value) {
		this.additionalFields.put(key, value);
	}

	/*public Object getReferencedComponent() {
		return referencedComponent;
	}*/

	public void setReferencedComponent(Object referencedComponent) {
		this.referencedComponent = referencedComponent;
	}

	public String getEffectiveTime() {
		return effectiveTime;
	}

	@Override
	public String getReportedName() {
		throw new NotImplementedException();
	}

	@Override
	public String getReportedType() {
		return getComponentType().toString();
	}

	@Override
	public ComponentType getComponentType() {
		throw new NotImplementedException();
	}

	@Override
	public String[] toRF2() throws Exception {
		throw new NotImplementedException();
	}

	@Override
	public List<String> fieldComparison(Component other) {
		throw new NotImplementedException();
	}
	
	public Boolean isReleased() {
		if (released == null) {
			return !(effectiveTime == null || effectiveTime.isEmpty());
		}
		return released;
	}
	
	public void delete (String deletionEffectiveTime) {
		this.deletionEffectiveTime = deletionEffectiveTime;
		this.isDeleted = true;
	}
}

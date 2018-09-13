
package org.ihtsdo.termserver.scripting.domain;

import org.apache.commons.lang.NotImplementedException;
import org.ihtsdo.termserver.scripting.TermServerScriptException;
import org.ihtsdo.termserver.scripting.domain.RF2Constants.ComponentType;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class RefsetEntry extends Component {

    @SerializedName("id")
    @Expose
    private String id;
    @SerializedName("effectiveTime")
    @Expose
    private String effectiveTime;
    @SerializedName("released")
    @Expose
    private Boolean released;
    @SerializedName("active")
    @Expose
    private Boolean active;
    @SerializedName("moduleId")
    @Expose
    private String moduleId;
    @SerializedName("referencedComponent")
    @Expose
    private ReferencedComponent referencedComponent;
    @SerializedName("referenceSetId")
    @Expose
    private String referenceSetId;
    @SerializedName("valueId")
    @Expose
    private String valueId;
    @SerializedName("commitComment")
    @Expose
    private String commitComment = "TermserverScript update";

    /**
     * No args constructor for use in serialization
     * 
     */
    public RefsetEntry() {
    }

    /**
     * 
     * @param released
     * @param id
     * @param referenceSetId
     * @param moduleId
     * @param valueId
     * @param active
     * @param referencedComponent
     */
    public RefsetEntry(String id, Boolean released, Boolean active, String moduleId, ReferencedComponent referencedComponent, String referenceSetId, String valueId) {
        super();
        this.id = id;
        this.released = released;
        this.active = active;
        this.moduleId = moduleId;
        this.referencedComponent = referencedComponent;
        this.referenceSetId = referenceSetId;
        this.valueId = valueId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Boolean getReleased() {
        return released;
    }

    public void setReleased(Boolean released) {
        this.released = released;
    }

    public Boolean getActive() {
        return active;
    }
    
    public boolean isActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public String getModuleId() {
        return moduleId;
    }

    public void setModuleId(String moduleId) {
        this.moduleId = moduleId;
    }

    public ReferencedComponent getReferencedComponent() {
        return referencedComponent;
    }

    public void setReferencedComponent(ReferencedComponent referencedComponent) {
        this.referencedComponent = referencedComponent;
    }

    public String getReferenceSetId() {
        return referenceSetId;
    }

    public void setReferenceSetId(String referenceSetId) {
        this.referenceSetId = referenceSetId;
    }

    public String getValueId() {
        return valueId;
    }

    public void setValueId(String valueId) {
        this.valueId = valueId;
    }

	@Override
	public String getReportedName() {
		return referenceSetId;
	}

	@Override
	public String getReportedType() {
		return "RefsetEntry";
	}
	
	public String toString() {
		return id;
	}
	

    public String getCommitComment() {
    	return commitComment;
    }

	public String getEffectiveTime() {
		return effectiveTime;
	}

	public void setEffectiveTime(String effectiveTime) {
		this.effectiveTime = effectiveTime;
	}

	@Override
	public String[] toRF2() throws TermServerScriptException {
		throw new NotImplementedException();
	}

	@Override
	public ComponentType getComponentType() {
		// TODO Auto-generated method stub
		return null;
	}

}
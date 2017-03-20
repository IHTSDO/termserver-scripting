package org.ihtsdo.snowowl.authoring.batchimport.api.pojo.task;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(as = AuthoringTask.class)
public interface AuthoringTaskUpdateRequest extends AuthoringTaskCreateRequest {

	TaskStatus getStatus();

	void setStatus(TaskStatus status);

	User getAssignee();

	void setAssignee(User assignee);

	User getReviewer();

	void setReviewer(User reviewer);
}
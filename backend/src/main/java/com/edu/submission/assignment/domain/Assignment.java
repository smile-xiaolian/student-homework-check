package com.edu.submission.assignment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("app.assignment")
public class Assignment {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID courseId;
    private String title;
    private String description;
    private String status;
    private OffsetDateTime publishAt;
    private OffsetDateTime openAt;
    private OffsetDateTime deadlineAt;
    private OffsetDateTime lateDeadlineAt;
    private Boolean allowLate;
    private Boolean allowMultiVersion;
    private String effectiveRule;
    private Boolean checkEnabled;
    private String checkConfig;
    private String rubricDefinition;
    private String allowedFileTypes;
    private OffsetDateTime scheduledPublishAt;
    private BigDecimal weight;
    private UUID createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long rowVersion;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getCourseId() { return courseId; }
    public void setCourseId(UUID courseId) { this.courseId = courseId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getPublishAt() { return publishAt; }
    public void setPublishAt(OffsetDateTime publishAt) { this.publishAt = publishAt; }
    public OffsetDateTime getOpenAt() { return openAt; }
    public void setOpenAt(OffsetDateTime openAt) { this.openAt = openAt; }
    public OffsetDateTime getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(OffsetDateTime deadlineAt) { this.deadlineAt = deadlineAt; }
    public OffsetDateTime getLateDeadlineAt() { return lateDeadlineAt; }
    public void setLateDeadlineAt(OffsetDateTime lateDeadlineAt) { this.lateDeadlineAt = lateDeadlineAt; }
    public Boolean getAllowLate() { return allowLate; }
    public void setAllowLate(Boolean allowLate) { this.allowLate = allowLate; }
    public Boolean getAllowMultiVersion() { return allowMultiVersion; }
    public void setAllowMultiVersion(Boolean allowMultiVersion) { this.allowMultiVersion = allowMultiVersion; }
    public String getEffectiveRule() { return effectiveRule; }
    public void setEffectiveRule(String effectiveRule) { this.effectiveRule = effectiveRule; }
    public Boolean getCheckEnabled() { return checkEnabled; }
    public void setCheckEnabled(Boolean checkEnabled) { this.checkEnabled = checkEnabled; }
    public String getCheckConfig() { return checkConfig; }
    public void setCheckConfig(String checkConfig) { this.checkConfig = checkConfig; }
    public String getRubricDefinition() { return rubricDefinition; }
    public void setRubricDefinition(String rubricDefinition) { this.rubricDefinition = rubricDefinition; }
    public String getAllowedFileTypes() { return allowedFileTypes; }
    public void setAllowedFileTypes(String allowedFileTypes) { this.allowedFileTypes = allowedFileTypes; }
    public OffsetDateTime getScheduledPublishAt() { return scheduledPublishAt; }
    public void setScheduledPublishAt(OffsetDateTime scheduledPublishAt) { this.scheduledPublishAt = scheduledPublishAt; }
    public BigDecimal getWeight() { return weight; }
    public void setWeight(BigDecimal weight) { this.weight = weight; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Long getRowVersion() { return rowVersion; }
    public void setRowVersion(Long rowVersion) { this.rowVersion = rowVersion; }
}


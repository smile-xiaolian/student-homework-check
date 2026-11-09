package com.edu.submission.submission.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("app.submission")
public class Submission {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID assignmentId;
    private UUID submitterId;
    private Integer versionNo;
    private String status;
    private Boolean isEffective;
    private Boolean isLate;
    private OffsetDateTime submittedAt;
    private OffsetDateTime effectiveDeadlineAt;
    private String onlineText;
    private String onlineTextDigest;
    private String inputDigest;
    private Boolean integrityConsent;
    private OffsetDateTime integrityConsentAt;
    private String idempotencyKey;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long rowVersion;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAssignmentId() { return assignmentId; }
    public void setAssignmentId(UUID assignmentId) { this.assignmentId = assignmentId; }
    public UUID getSubmitterId() { return submitterId; }
    public void setSubmitterId(UUID submitterId) { this.submitterId = submitterId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Boolean getIsEffective() { return isEffective; }
    public void setIsEffective(Boolean effective) { isEffective = effective; }
    public Boolean getIsLate() { return isLate; }
    public void setIsLate(Boolean late) { isLate = late; }
    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(OffsetDateTime submittedAt) { this.submittedAt = submittedAt; }
    public OffsetDateTime getEffectiveDeadlineAt() { return effectiveDeadlineAt; }
    public void setEffectiveDeadlineAt(OffsetDateTime effectiveDeadlineAt) { this.effectiveDeadlineAt = effectiveDeadlineAt; }
    public String getOnlineText() { return onlineText; }
    public void setOnlineText(String onlineText) { this.onlineText = onlineText; }
    public String getOnlineTextDigest() { return onlineTextDigest; }
    public void setOnlineTextDigest(String onlineTextDigest) { this.onlineTextDigest = onlineTextDigest; }
    public String getInputDigest() { return inputDigest; }
    public void setInputDigest(String inputDigest) { this.inputDigest = inputDigest; }
    public Boolean getIntegrityConsent() { return integrityConsent; }
    public void setIntegrityConsent(Boolean integrityConsent) { this.integrityConsent = integrityConsent; }
    public OffsetDateTime getIntegrityConsentAt() { return integrityConsentAt; }
    public void setIntegrityConsentAt(OffsetDateTime integrityConsentAt) { this.integrityConsentAt = integrityConsentAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Long getRowVersion() { return rowVersion; }
    public void setRowVersion(Long rowVersion) { this.rowVersion = rowVersion; }
}


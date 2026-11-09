package com.edu.submission.submission.adapter.in.dto;

import com.edu.submission.checking.domain.CheckTask;
import com.edu.submission.submission.domain.Submission;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record SubmissionView(
        UUID id,
        UUID assignmentId,
        UUID submitterId,
        String submitterName,
        int versionNo,
        String status,
        boolean effective,
        boolean late,
        OffsetDateTime submittedAt,
        OffsetDateTime effectiveDeadlineAt,
        String onlineText,
        boolean integrityConsent,
        UUID checkTaskId,
        String checkTaskStatus,
        List<SubmissionFileView> files,
        OffsetDateTime createdAt,
        long rowVersion
) {
    public static SubmissionView from(Submission submission, String submitterName, CheckTask task,
                                      List<SubmissionFileView> files) {
        return new SubmissionView(
                submission.getId(), submission.getAssignmentId(), submission.getSubmitterId(),
                submitterName, submission.getVersionNo(), submission.getStatus(),
                Boolean.TRUE.equals(submission.getIsEffective()),
                Boolean.TRUE.equals(submission.getIsLate()), submission.getSubmittedAt(),
                submission.getEffectiveDeadlineAt(), submission.getOnlineText(),
                Boolean.TRUE.equals(submission.getIntegrityConsent()),
                task == null ? null : task.getId(), task == null ? null : task.getStatus(),
                files == null ? List.of() : files,
                submission.getCreatedAt(),
                submission.getRowVersion() == null ? 0L : submission.getRowVersion());
    }
}


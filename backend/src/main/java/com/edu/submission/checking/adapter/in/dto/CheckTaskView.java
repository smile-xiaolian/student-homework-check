package com.edu.submission.checking.adapter.in.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record CheckTaskView(
        UUID id,
        String subjectType,
        String taskType,
        UUID assignmentId,
        UUID submissionId,
        String status,
        String stage,
        int progress,
        UUID reportId,
        Integer reportVersion,
        int attempt,
        int maxAttempts,
        int retryAfterSeconds,
        Map<String, Object> error,
        OffsetDateTime queuedAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt
) {
}

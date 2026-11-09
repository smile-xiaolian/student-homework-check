package com.edu.submission.review.adapter.in.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ReviewQueueItem(
        UUID submissionId,
        UUID submitterId,
        String submitterName,
        int versionNo,
        OffsetDateTime submittedAt,
        boolean late,
        BigDecimal checkScore,
        String checkLevel,
        String reviewStatus,
        String gradeStatus
) {
}

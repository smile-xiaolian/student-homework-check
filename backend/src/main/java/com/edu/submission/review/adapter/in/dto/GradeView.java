package com.edu.submission.review.adapter.in.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record GradeView(
        UUID id,
        UUID submissionId,
        String submitterName,
        int versionNo,
        String status,
        BigDecimal rawScore,
        BigDecimal lateDeduction,
        BigDecimal finalScore,
        String checkVerdict,
        String comment,
        boolean published,
        OffsetDateTime publishedAt
) {
}

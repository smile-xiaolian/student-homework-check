package com.edu.submission.review.adapter.in.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ReviewView(
        UUID id,
        UUID submissionId,
        UUID reviewerId,
        String status,
        BigDecimal rawScore,
        BigDecimal lateDeduction,
        BigDecimal finalScore,
        JsonNode dimensionScores,
        JsonNode annotations,
        String checkVerdict,
        String comment,
        OffsetDateTime submittedAt,
        OffsetDateTime updatedAt,
        long rowVersion
) {
}

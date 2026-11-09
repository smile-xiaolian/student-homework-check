package com.edu.submission.checking.adapter.in.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record CheckReportView(
        UUID id,
        UUID checkTaskId,
        UUID submissionId,
        UUID assignmentId,
        int reportVersion,
        boolean isCurrent,
        BigDecimal overallScore,
        BigDecimal textScore,
        BigDecimal structureScore,
        String level,
        String algorithmVersion,
        JsonNode params,
        int sourceCount,
        String topSourceType,
        BigDecimal topSourceSimilarity,
        String reportDigest,
        OffsetDateTime generatedAt,
        Map<String, String> links
) {
}

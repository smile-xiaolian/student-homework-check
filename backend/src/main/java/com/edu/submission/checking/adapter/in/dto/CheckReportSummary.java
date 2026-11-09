package com.edu.submission.checking.adapter.in.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CheckReportSummary(
        UUID id,
        int reportVersion,
        boolean isCurrent,
        BigDecimal overallScore,
        String level,
        OffsetDateTime generatedAt
) {
}

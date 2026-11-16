package com.edu.submission.review.adapter.in.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import java.util.UUID;

public record ReviewSaveRequest(
        @DecimalMin(value = "0", message = "得分不能为负")
        @DecimalMax(value = "100", message = "得分不能超过 100")
        BigDecimal rawScore,
        JsonNode dimensionScores,
        JsonNode annotations,
        String checkVerdict,
        String comment,
        UUID feedbackFileId,
        Long expectedRowVersion
) {
}

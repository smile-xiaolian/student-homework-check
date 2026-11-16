package com.edu.submission.review.adapter.in.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record GradeModifyRequest(
        @DecimalMin(value = "0", message = "得分不能为负")
        @DecimalMax(value = "100", message = "得分不能超过 100")
        BigDecimal rawScore,
        String checkVerdict,
        String comment,
        @Size(min = 8, message = "更正原因不能少于 8 个字符")
        String reason,
        UUID feedbackFileId,
        Long expectedRowVersion
) {
}

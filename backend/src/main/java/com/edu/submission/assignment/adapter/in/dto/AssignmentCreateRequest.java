package com.edu.submission.assignment.adapter.in.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record AssignmentCreateRequest(
        @NotBlank(message = "请输入作业标题")
        @Size(max = 256, message = "作业标题过长")
        String title,
        @Size(max = 100000, message = "作业要求过长")
        String description,
        OffsetDateTime openAt,
        @NotNull(message = "请选择截止时间")
        OffsetDateTime deadlineAt,
        OffsetDateTime lateDeadlineAt,
        Boolean allowLate,
        Boolean allowMultiVersion,
        @Pattern(regexp = "LAST_SUBMISSION|MANUAL", message = "生效版本规则不合法")
        String effectiveRule,
        Boolean checkEnabled,
        JsonNode checkConfig,
        JsonNode rubricDefinition,
        List<String> allowedFileTypes,
        OffsetDateTime scheduledPublishAt,
        @DecimalMin(value = "0", message = "作业权重不能为负")
        @DecimalMax(value = "100", message = "作业权重不能超过 100")
        BigDecimal weight
) {
}


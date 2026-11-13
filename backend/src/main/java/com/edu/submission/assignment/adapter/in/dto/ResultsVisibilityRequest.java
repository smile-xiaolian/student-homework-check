package com.edu.submission.assignment.adapter.in.dto;

import jakarta.validation.constraints.NotNull;

public record ResultsVisibilityRequest(
        @NotNull(message = "请指定是否允许学生查看评审结果")
        Boolean visible
) {
}

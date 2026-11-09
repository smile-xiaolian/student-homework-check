package com.edu.submission.course.adapter.in.dto;

import jakarta.validation.constraints.Size;

public record CourseUpdateRequest(
        @Size(min = 1, max = 128, message = "课程名称长度应为 1-128 个字符")
        String name,
        @Size(max = 5000, message = "课程简介过长")
        String description
) {
}


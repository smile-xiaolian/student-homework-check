package com.edu.submission.course.adapter.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CourseCreateRequest(
        @NotBlank(message = "请输入课程代码")
        @Size(max = 64, message = "课程代码过长")
        String courseCode,
        @NotBlank(message = "请输入课程名称")
        @Size(max = 128, message = "课程名称过长")
        String name,
        @NotBlank(message = "请输入学期")
        @Size(max = 32, message = "学期标识过长")
        String semester,
        @Size(max = 5000, message = "课程简介过长")
        String description
) {
}


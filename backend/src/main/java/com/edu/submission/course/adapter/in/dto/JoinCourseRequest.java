package com.edu.submission.course.adapter.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinCourseRequest(
        @NotBlank(message = "请输入邀请码")
        @Size(max = 32, message = "邀请码过长")
        String inviteCode
) {
}


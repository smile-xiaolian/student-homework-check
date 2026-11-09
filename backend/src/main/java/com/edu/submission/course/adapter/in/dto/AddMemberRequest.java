package com.edu.submission.course.adapter.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddMemberRequest(
        @NotBlank(message = "请输入学工号")
        @Size(max = 32, message = "学工号过长")
        String userNo,
        @Pattern(regexp = "STUDENT|ASSISTANT", message = "成员角色不合法")
        String memberRole
) {
}


package com.edu.submission.identity.adapter.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordChangeRequest(
        @NotBlank(message = "请输入原密码")
        String currentPassword,
        @NotBlank(message = "请输入新密码")
        @Size(min = 8, max = 128, message = "密码长度应为 8-128 个字符")
        String newPassword
) {
}


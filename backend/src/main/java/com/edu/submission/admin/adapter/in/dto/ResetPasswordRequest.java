package com.edu.submission.admin.adapter.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank(message = "请输入新密码")
        @Size(min = 8, max = 128, message = "密码长度应为 8-128 个字符")
        String newPassword
) {
}


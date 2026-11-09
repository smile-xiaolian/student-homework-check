package com.edu.submission.identity.adapter.in.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record ProfileUpdateRequest(
        @Size(min = 1, max = 64, message = "姓名长度应为 1-64 个字符")
        String displayName,
        @Email(message = "邮箱格式不正确")
        @Size(max = 255, message = "邮箱过长")
        String email,
        @Size(max = 32, message = "手机号过长")
        String phone
) {
}


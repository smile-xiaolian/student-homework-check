package com.edu.submission.identity.adapter.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "请输入学工号或登录名")
        @Size(max = 64, message = "登录名过长")
        String loginName,
        @NotBlank(message = "请输入密码")
        @Size(max = 128, message = "密码过长")
        String password
) {
}


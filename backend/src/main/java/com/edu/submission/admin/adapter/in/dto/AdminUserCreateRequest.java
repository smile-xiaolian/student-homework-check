package com.edu.submission.admin.adapter.in.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminUserCreateRequest(
        @NotBlank(message = "请输入学工号")
        @Size(max = 32, message = "学工号过长")
        String userNo,
        @Size(max = 64, message = "登录名过长")
        String loginName,
        @NotBlank(message = "请输入姓名")
        @Size(max = 64, message = "姓名过长")
        String displayName,
        @Email(message = "邮箱格式不正确")
        @Size(max = 255, message = "邮箱过长")
        String email,
        @Size(max = 32, message = "手机号过长")
        String phone,
        @NotBlank(message = "请选择角色")
        @Pattern(regexp = "STUDENT|TEACHER|ASSISTANT|ADMIN", message = "角色不合法")
        String role,
        @NotBlank(message = "请输入初始密码")
        @Size(min = 8, max = 128, message = "密码长度应为 8-128 个字符")
        String password
) {
}


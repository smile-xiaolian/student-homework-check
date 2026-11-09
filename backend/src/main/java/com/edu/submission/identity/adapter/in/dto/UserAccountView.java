package com.edu.submission.identity.adapter.in.dto;

import com.edu.submission.identity.domain.UserAccount;
import com.edu.submission.identity.domain.UserRole;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record UserAccountView(
        UUID id,
        String userNo,
        String loginName,
        String displayName,
        String email,
        String phone,
        List<String> roles,
        String status,
        OffsetDateTime lastLoginAt,
        OffsetDateTime createdAt,
        long rowVersion
) {
    public static UserAccountView from(UserAccount user) {
        return new UserAccountView(
                user.getId(),
                user.getUserNo(),
                user.getLoginName(),
                user.getDisplayName(),
                user.getEmail(),
                user.getPhone(),
                List.of(user.getRole().name()),
                user.getStatus().name(),
                user.getLastLoginAt(),
                user.getCreatedAt(),
                user.getRowVersion() == null ? 0L : user.getRowVersion());
    }

    public static UserAccountView masked(UserAccount user) {
        return new UserAccountView(
                user.getId(),
                user.getUserNo(),
                user.getLoginName(),
                user.getDisplayName(),
                maskEmail(user.getEmail()),
                maskPhone(user.getPhone()),
                List.of(user.getRole().name()),
                user.getStatus().name(),
                user.getLastLoginAt(),
                user.getCreatedAt(),
                user.getRowVersion() == null ? 0L : user.getRowVersion());
    }

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    public static boolean isPrivileged(UserRole role) {
        return role == UserRole.ADMIN || role == UserRole.TEACHER || role == UserRole.ASSISTANT;
    }
}


package com.edu.submission.identity.adapter.in.dto;

import com.edu.submission.identity.domain.UserAccount;
import java.util.List;
import java.util.UUID;

public record MeView(
        UUID id,
        String userNo,
        String loginName,
        String displayName,
        String email,
        String phone,
        List<String> roles,
        List<String> permissions,
        String status,
        boolean mustChangePassword
) {
    public static MeView from(UserAccount user) {
        return new MeView(
                user.getId(), user.getUserNo(), user.getLoginName(), user.getDisplayName(),
                user.getEmail(), user.getPhone(), List.of(user.getRole().name()),
                user.getRole().permissions(), user.getStatus().name(), false);
    }
}


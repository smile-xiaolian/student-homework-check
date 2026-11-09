package com.edu.submission.shared.security;

import com.edu.submission.identity.domain.UserAccount;
import com.edu.submission.identity.domain.UserRole;
import java.util.UUID;

public record UserPrincipal(
        UUID id,
        String userNo,
        String loginName,
        String displayName,
        UserRole role,
        int tokenVersion
) {
    public static UserPrincipal from(UserAccount user) {
        return new UserPrincipal(
                user.getId(), user.getUserNo(), user.getLoginName(), user.getDisplayName(),
                user.getRole(), user.getTokenVersion());
    }
}


package com.edu.submission.shared.security;

import com.edu.submission.shared.api.ApiException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static UserPrincipal require() {
        return optional().orElseThrow(() -> ApiException.unauthorized(
                "AUTH_REQUIRED", "请先登录", "登录状态已失效，请重新登录。"));
    }

    public static Optional<UserPrincipal> optional() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof UserPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }
}


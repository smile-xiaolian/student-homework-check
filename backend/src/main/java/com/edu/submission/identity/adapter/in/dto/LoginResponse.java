package com.edu.submission.identity.adapter.in.dto;

import java.util.List;
import java.util.UUID;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        SessionUser user
) {
    public record SessionUser(
            UUID id,
            String userNo,
            String displayName,
            List<String> roles,
            List<String> permissions,
            boolean mustChangePassword
    ) {
    }
}


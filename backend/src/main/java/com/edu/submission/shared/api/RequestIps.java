package com.edu.submission.shared.api;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestIps {

    private RequestIps() {
    }

    public static String from(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            return first.length() > 64 ? null : first;
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.length() > 64 ? null : remote;
    }
}


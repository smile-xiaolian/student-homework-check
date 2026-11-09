package com.edu.submission.identity.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import java.util.List;

public enum UserRole {
    STUDENT("STUDENT"),
    TEACHER("TEACHER"),
    ASSISTANT("ASSISTANT"),
    ADMIN("ADMIN");

    @EnumValue
    private final String databaseValue;

    UserRole(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }

    public List<String> permissions() {
        return switch (this) {
            case STUDENT -> List.of(
                    "course:read", "assignment:read", "submission:write", "check:read",
                    "grade:read", "notification:read");
            case TEACHER -> List.of(
                    "course:read", "course:write", "assignment:read", "assignment:write",
                    "submission:read", "check:read", "review:write", "grade:publish",
                    "notification:read", "analytics:read");
            case ASSISTANT -> List.of(
                    "course:read", "assignment:read", "submission:read", "check:read",
                    "review:write", "notification:read");
            case ADMIN -> List.of(
                    "course:read", "course:write", "assignment:read", "assignment:write",
                    "submission:read", "submission:write", "check:read", "check:manage",
                    "review:write", "grade:publish", "notification:read", "analytics:read",
                    "user:manage", "system:manage", "audit:read");
        };
    }
}


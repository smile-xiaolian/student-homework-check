package com.edu.submission.identity.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum UserStatus {
    PENDING("PENDING"),
    ACTIVE("ACTIVE"),
    LOCKED("LOCKED"),
    DISABLED("DISABLED");

    @EnumValue
    private final String databaseValue;

    UserStatus(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }
}


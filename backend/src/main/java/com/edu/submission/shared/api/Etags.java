package com.edu.submission.shared.api;

import java.util.OptionalLong;

public final class Etags {

    private Etags() {
    }

    public static String format(long rowVersion) {
        return "\"row-" + rowVersion + "\"";
    }

    public static OptionalLong parse(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return OptionalLong.empty();
        }
        String value = ifMatch.trim().replace("\"", "");
        if (value.startsWith("row-")) {
            value = value.substring(4);
        }
        try {
            return OptionalLong.of(Long.parseLong(value));
        } catch (NumberFormatException exception) {
            return OptionalLong.empty();
        }
    }
}


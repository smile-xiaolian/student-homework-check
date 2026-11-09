package com.edu.submission.admin.adapter.in.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record SystemConfigView(
        String key,
        JsonNode value,
        String valueType,
        String group,
        String description,
        long version,
        OffsetDateTime updatedAt
) {
}

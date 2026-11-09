package com.edu.submission.file.adapter.in.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FileObjectView(
        UUID id,
        String originalName,
        String mimeType,
        long sizeBytes,
        String sha256,
        String scanStatus,
        OffsetDateTime createdAt
) {
}

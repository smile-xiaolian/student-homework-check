package com.edu.submission.submission.adapter.in.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SubmissionFileView(
        UUID id,
        String originalName,
        String mimeType,
        long sizeBytes,
        String sha256,
        String scanStatus,
        OffsetDateTime createdAt
) {
}

package com.edu.submission.library.adapter.in.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LibraryEntryView(
        UUID id,
        String originalName,
        long sizeBytes,
        long seq,
        OffsetDateTime createdAt
) {
}

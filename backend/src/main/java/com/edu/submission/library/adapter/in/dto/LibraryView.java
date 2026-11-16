package com.edu.submission.library.adapter.in.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LibraryView(
        UUID id,
        String name,
        String libraryType,
        String status,
        long entryCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

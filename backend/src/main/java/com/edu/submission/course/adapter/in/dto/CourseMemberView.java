package com.edu.submission.course.adapter.in.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CourseMemberView(
        UUID id,
        UUID userId,
        String userNo,
        String displayName,
        String memberRole,
        String status,
        OffsetDateTime joinedAt
) {
}


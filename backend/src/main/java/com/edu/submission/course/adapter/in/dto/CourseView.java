package com.edu.submission.course.adapter.in.dto;

import com.edu.submission.course.domain.Course;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CourseView(
        UUID id,
        String courseCode,
        String name,
        String semester,
        UUID ownerId,
        String description,
        String status,
        String inviteCode,
        boolean readOnly,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long rowVersion
) {
    public static CourseView from(Course course, boolean includeInviteCode) {
        return new CourseView(
                course.getId(), course.getCourseCode(), course.getName(), course.getSemester(),
                course.getOwnerId(), course.getDescription(), course.getStatus(),
                includeInviteCode ? course.getInviteCode() : null,
                "ARCHIVED".equals(course.getStatus()), course.getCreatedAt(), course.getUpdatedAt(),
                course.getRowVersion() == null ? 0L : course.getRowVersion());
    }
}


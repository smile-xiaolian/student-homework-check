package com.edu.submission.assignment.adapter.in.dto;

import com.edu.submission.assignment.domain.Assignment;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AssignmentView(
        UUID id,
        UUID courseId,
        String title,
        String description,
        String status,
        OffsetDateTime publishAt,
        OffsetDateTime openAt,
        OffsetDateTime deadlineAt,
        OffsetDateTime lateDeadlineAt,
        boolean allowLate,
        boolean allowMultiVersion,
        String effectiveRule,
        boolean checkEnabled,
        JsonNode checkConfig,
        JsonNode rubricDefinition,
        List<String> allowedFileTypes,
        OffsetDateTime scheduledPublishAt,
        BigDecimal weight,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long rowVersion
) {
    public static AssignmentView from(Assignment assignment, JsonNode checkConfig,
                                      JsonNode rubric, List<String> allowedFileTypes,
                                      boolean includeCheckConfig) {
        return new AssignmentView(
                assignment.getId(), assignment.getCourseId(), assignment.getTitle(),
                assignment.getDescription(), assignment.getStatus(), assignment.getPublishAt(),
                assignment.getOpenAt(), assignment.getDeadlineAt(), assignment.getLateDeadlineAt(),
                Boolean.TRUE.equals(assignment.getAllowLate()),
                Boolean.TRUE.equals(assignment.getAllowMultiVersion()),
                assignment.getEffectiveRule(), Boolean.TRUE.equals(assignment.getCheckEnabled()),
                includeCheckConfig ? checkConfig : null, rubric,
                allowedFileTypes == null ? List.of() : allowedFileTypes,
                assignment.getScheduledPublishAt(), assignment.getWeight(),
                assignment.getCreatedAt(), assignment.getUpdatedAt(),
                assignment.getRowVersion() == null ? 0L : assignment.getRowVersion());
    }
}


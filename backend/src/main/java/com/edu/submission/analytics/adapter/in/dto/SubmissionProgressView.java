package com.edu.submission.analytics.adapter.in.dto;

import java.util.UUID;

public record SubmissionProgressView(
        UUID assignmentId,
        String assignmentTitle,
        long memberCount,
        long submittedCount,
        long lateCount,
        long checkingCount,
        long completedCount,
        long unsubmittedCount,
        double submitRate
) {
}

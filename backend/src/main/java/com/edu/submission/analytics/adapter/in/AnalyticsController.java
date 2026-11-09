package com.edu.submission.analytics.adapter.in;

import com.edu.submission.analytics.adapter.in.dto.SubmissionProgressView;
import com.edu.submission.analytics.application.AnalyticsService;
import com.edu.submission.shared.security.CurrentUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/assignments/{id}/submission-progress")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public SubmissionProgressView submissionProgress(@PathVariable UUID id) {
        return analyticsService.submissionProgress(CurrentUser.require(), id);
    }
}

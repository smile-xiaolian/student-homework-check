package com.edu.submission.submission.adapter.in;

import com.edu.submission.shared.security.CurrentUser;
import com.edu.submission.submission.adapter.in.dto.SubmissionCreateRequest;
import com.edu.submission.submission.adapter.in.dto.SubmissionView;
import com.edu.submission.submission.application.SubmissionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SubmissionController {

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @PostMapping("/assignments/{assignmentId}/submissions")
    @PreAuthorize("hasRole('STUDENT')")
    public SubmissionView submit(@PathVariable UUID assignmentId,
                                 @Valid @RequestBody SubmissionCreateRequest request,
                                 @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return submissionService.submitOnline(CurrentUser.require(), assignmentId, request, idempotencyKey);
    }

    @GetMapping("/assignments/{assignmentId}/submissions/mine")
    public List<SubmissionView> mine(@PathVariable UUID assignmentId) {
        return submissionService.listMine(CurrentUser.require(), assignmentId);
    }

    @GetMapping("/assignments/{assignmentId}/submissions")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public List<SubmissionView> list(@PathVariable UUID assignmentId,
                                     @RequestParam(name = "allVersions", required = false, defaultValue = "false")
                                     boolean allVersions) {
        return submissionService.listForAssignment(CurrentUser.require(), assignmentId, allVersions);
    }

    @GetMapping("/submissions/{id}")
    public SubmissionView get(@PathVariable UUID id) {
        return submissionService.get(CurrentUser.require(), id);
    }

    @PostMapping("/submissions/{id}/effective")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<SubmissionView> setEffective(@PathVariable UUID id) {
        return ResponseEntity.ok(submissionService.setEffective(CurrentUser.require(), id));
    }
}


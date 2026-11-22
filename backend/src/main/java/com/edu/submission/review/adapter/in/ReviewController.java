package com.edu.submission.review.adapter.in;

import com.edu.submission.review.adapter.in.dto.BatchScorePreviewItem;
import com.edu.submission.review.adapter.in.dto.BatchScoreRequest;
import com.edu.submission.review.adapter.in.dto.GradeModifyRequest;
import com.edu.submission.review.adapter.in.dto.GradeView;
import com.edu.submission.review.adapter.in.dto.ReviewQueueItem;
import com.edu.submission.review.adapter.in.dto.ReviewSaveRequest;
import com.edu.submission.review.adapter.in.dto.ReviewView;
import com.edu.submission.review.application.ReviewService;
import com.edu.submission.shared.api.PageResult;
import com.edu.submission.shared.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/assignments/{id}/review-queue")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public PageResult<ReviewQueueItem> queue(@PathVariable UUID id,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size,
                                             @RequestParam(required = false) String reviewStatus,
                                             @RequestParam(required = false) String checkLevel,
                                             @RequestParam(required = false) Boolean late,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String sort) {
        return reviewService.reviewQueue(CurrentUser.require(), id, page, size,
                reviewStatus, checkLevel, late, keyword, sort);
    }

    @GetMapping("/submissions/{id}/review")
    public ReviewView review(@PathVariable UUID id) {
        // 不做角色限制：教师/助查看评审草稿，学生在“结果可见”时查看本人评审结果（由 Service 层鉴权）
        return reviewService.getReview(CurrentUser.require(), id);
    }

    @PutMapping("/submissions/{id}/review")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ReviewView save(@PathVariable UUID id, @Valid @RequestBody ReviewSaveRequest request) {
        return reviewService.save(CurrentUser.require(), id, request);
    }

    @PostMapping("/submissions/{id}/review/submit")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ReviewView submit(@PathVariable UUID id) {
        return reviewService.submit(CurrentUser.require(), id);
    }

    @GetMapping("/submissions/{id}/grade")
    public GradeView grade(@PathVariable UUID id) {
        return reviewService.grade(CurrentUser.require(), id);
    }

    @PatchMapping("/submissions/{id}/grade")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public GradeView modifyGrade(@PathVariable UUID id, @Valid @RequestBody GradeModifyRequest request) {
        return reviewService.modifyGrade(CurrentUser.require(), id, request);
    }

    @GetMapping("/assignments/{id}/grades")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public List<GradeView> grades(@PathVariable UUID id) {
        return reviewService.grades(CurrentUser.require(), id);
    }

    @PostMapping("/assignments/{id}/grades/publish")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public Map<String, Object> publish(@PathVariable UUID id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        List<UUID> submissionIds = List.of();
        if (body != null && body.get("submissionIds") instanceof List<?> values) {
            submissionIds = values.stream().map(value -> UUID.fromString(String.valueOf(value))).toList();
        }
        return reviewService.publish(CurrentUser.require(), id, submissionIds);
    }

    @PostMapping("/assignments/{id}/grades/batch-score/preview")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public List<BatchScorePreviewItem> batchScorePreview(@PathVariable UUID id,
                                                         @Valid @RequestBody BatchScoreRequest request) {
        return reviewService.batchScorePreview(CurrentUser.require(), id, request);
    }

    @PostMapping("/assignments/{id}/grades/batch-score")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public Map<String, Object> batchScore(@PathVariable UUID id,
                                          @Valid @RequestBody BatchScoreRequest request) {
        return reviewService.batchScore(CurrentUser.require(), id, request);
    }
}

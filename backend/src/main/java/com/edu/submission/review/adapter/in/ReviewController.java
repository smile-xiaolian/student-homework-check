package com.edu.submission.review.adapter.in;

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
                                             @RequestParam(required = false) Integer size) {
        return reviewService.reviewQueue(CurrentUser.require(), id, page, size);
    }

    @GetMapping("/submissions/{id}/review")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ReviewView review(@PathVariable UUID id) {
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
}

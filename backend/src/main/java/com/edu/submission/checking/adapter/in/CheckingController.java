package com.edu.submission.checking.adapter.in;

import com.edu.submission.checking.adapter.in.dto.CheckReportSummary;
import com.edu.submission.checking.adapter.in.dto.CheckReportView;
import com.edu.submission.checking.adapter.in.dto.CheckTaskView;
import com.edu.submission.checking.application.CheckingQueryService;
import com.edu.submission.shared.security.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
public class CheckingController {

    private final CheckingQueryService checkingQueryService;

    public CheckingController(CheckingQueryService checkingQueryService) {
        this.checkingQueryService = checkingQueryService;
    }

    @GetMapping("/check-tasks/{id}")
    public CheckTaskView task(@PathVariable UUID id) {
        return checkingQueryService.task(CurrentUser.require(), id);
    }

    @GetMapping("/submissions/{id}/check-reports/current")
    public CheckReportView currentReport(@PathVariable UUID id) {
        return checkingQueryService.currentReport(CurrentUser.require(), id);
    }

    @GetMapping("/submissions/{id}/check-reports")
    public Map<String, Object> reportHistory(@PathVariable UUID id) {
        List<CheckReportSummary> items = checkingQueryService.reportHistory(CurrentUser.require(), id);
        return Map.of("items", items);
    }

    @GetMapping("/check-reports/{id}")
    public CheckReportView report(@PathVariable UUID id) {
        return checkingQueryService.report(CurrentUser.require(), id);
    }

    @GetMapping("/check-reports/{id}/segments")
    public Map<String, Object> segments(@PathVariable UUID id,
                                        @RequestParam(required = false) Integer size) {
        return checkingQueryService.segments(CurrentUser.require(), id, size);
    }

    @GetMapping("/check-reports/{id}/sources")
    public Map<String, Object> sources(@PathVariable UUID id,
                                       @RequestParam(required = false) Integer size) {
        return checkingQueryService.sources(CurrentUser.require(), id, size);
    }

    @GetMapping("/check-reports/{id}/manifest")
    public Map<String, Object> manifest(@PathVariable UUID id,
                                        @RequestParam(required = false) String bucket,
                                        @RequestParam(required = false) Boolean included) {
        return checkingQueryService.manifest(CurrentUser.require(), id, bucket, included);
    }

    @GetMapping(value = "/check-reports/{id}/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> html(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .header("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; img-src data:; sandbox")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(checkingQueryService.html(CurrentUser.require(), id));
    }

    @GetMapping("/check-reports/{id}/verify")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public Map<String, Object> verify(@PathVariable UUID id) {
        return checkingQueryService.verify(CurrentUser.require(), id);
    }

    @PostMapping("/submissions/{id}/rechecks")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<Map<String, Object>> recheck(@PathVariable UUID id,
                                                       @RequestBody(required = false) Map<String, Object> body,
                                                       @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        String reason = body == null ? null : String.valueOf(body.getOrDefault("reason", ""));
        return ResponseEntity.accepted().body(
                checkingQueryService.recheck(CurrentUser.require(), id, reason, key));
    }
}

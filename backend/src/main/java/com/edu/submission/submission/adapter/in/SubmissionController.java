package com.edu.submission.submission.adapter.in;

import com.edu.submission.shared.api.ApiException;
import com.edu.submission.shared.security.CurrentUser;
import com.edu.submission.submission.adapter.in.dto.SubmissionCreateRequest;
import com.edu.submission.submission.adapter.in.dto.SubmissionView;
import com.edu.submission.submission.application.SubmissionService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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

    @GetMapping("/assignments/{assignmentId}/submissions/zip")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<StreamingResponseBody> downloadZip(@PathVariable UUID assignmentId,
                                                             @RequestParam("ids") String ids) {
        var principal = CurrentUser.require();
        List<UUID> submissionIds = parseZipIds(ids);
        SubmissionService.SubmissionsZip prep =
                submissionService.prepareZip(principal, assignmentId, submissionIds);
        StreamingResponseBody body = outputStream -> submissionService.writeZip(prep, outputStream);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("submissions-" + assignmentId + ".zip", StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(body);
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

    /** 解析逗号分隔的提交编号：≤50，否则 422 ZIP_TOO_MANY。 */
    private List<UUID> parseZipIds(String ids) {
        if (ids == null || ids.isBlank()) {
            throw ApiException.unprocessable("ZIP_IDS_REQUIRED", "请选择要下载的提交",
                    "至少选择一个提交。");
        }
        String[] tokens = ids.split(",");
        if (tokens.length > 50) {
            throw ApiException.unprocessable("ZIP_TOO_MANY", "批量下载数量过多",
                    "单次最多下载 50 个提交。");
        }
        List<UUID> result = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                result.add(UUID.fromString(trimmed));
            } catch (IllegalArgumentException exception) {
                throw ApiException.unprocessable("ZIP_IDS_INVALID", "提交编号不合法",
                        "请检查下载参数。");
            }
        }
        if (result.isEmpty()) {
            throw ApiException.unprocessable("ZIP_IDS_REQUIRED", "请选择要下载的提交",
                    "至少选择一个提交。");
        }
        return result;
    }
}


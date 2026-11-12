package com.edu.submission.submission.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.edu.submission.assignment.application.AssignmentService;
import com.edu.submission.assignment.domain.Assignment;
import com.edu.submission.audit.AuditService;
import com.edu.submission.checking.application.CheckTaskService;
import com.edu.submission.checking.domain.CheckTask;
import com.edu.submission.course.application.CourseService;
import com.edu.submission.course.domain.Course;
import com.edu.submission.course.domain.CourseMember;
import com.edu.submission.identity.application.IdentityQueryService;
import com.edu.submission.identity.domain.UserAccount;
import com.edu.submission.shared.api.ApiException;
import com.edu.submission.shared.crypto.Digests;
import com.edu.submission.shared.ids.Ids;
import com.edu.submission.shared.security.UserPrincipal;
import com.edu.submission.submission.adapter.in.dto.SubmissionCreateRequest;
import com.edu.submission.submission.adapter.in.dto.SubmissionFileView;
import com.edu.submission.submission.adapter.in.dto.SubmissionView;
import com.edu.submission.submission.adapter.out.SubmissionMapper;
import com.edu.submission.submission.domain.Submission;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubmissionService {

    private final SubmissionMapper submissionMapper;
    private final AssignmentService assignmentService;
    private final CourseService courseService;
    private final CheckTaskService checkTaskService;
    private final IdentityQueryService identityQueryService;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    public SubmissionService(SubmissionMapper submissionMapper,
                             AssignmentService assignmentService,
                             CourseService courseService,
                             CheckTaskService checkTaskService,
                             IdentityQueryService identityQueryService,
                             AuditService auditService,
                             JdbcTemplate jdbcTemplate) {
        this.submissionMapper = submissionMapper;
        this.assignmentService = assignmentService;
        this.courseService = courseService;
        this.checkTaskService = checkTaskService;
        this.identityQueryService = identityQueryService;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public SubmissionView submitOnline(UserPrincipal principal, UUID assignmentId,
                                       SubmissionCreateRequest request, String headerIdempotencyKey) {
        Assignment assignment = assignmentService.requireAssignment(assignmentId);
        Course course = courseService.requireCourse(assignment.getCourseId());
        CourseMember membership = courseService.findMembership(course.getId(), principal.id());
        if (membership == null || !"ACTIVE".equals(membership.getStatus())
                || !"STUDENT".equals(membership.getMemberRole())) {
            throw ApiException.forbidden("SUBMISSION_FORBIDDEN", "无法提交作业",
                    "当前账号不是该课程的学生。");
        }
        if (!"PUBLISHED".equals(assignment.getStatus())) {
            throw ApiException.conflict("ASSIGNMENT_NOT_PUBLISHED", "作业不可提交",
                    "作业尚未发布或已关闭。");
        }
        if (!"ACTIVE".equals(course.getStatus())) {
            throw ApiException.conflict("COURSE_NOT_ACTIVE", "课程不可提交", "课程已归档或未激活。");
        }

        String text = request.onlineText() == null || request.onlineText().isBlank()
                ? null : request.onlineText();
        if (text != null && text.length() > 1_000_000) {
            throw ApiException.unprocessable("SUBMISSION_CONTENT_TOO_LARGE", "提交内容过长",
                    "在线文本不能超过 100 万字符。");
        }
        List<UUID> requestedFileIds = normalizeFileIds(request.fileObjectIds());

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean late = false;
        OffsetDateTime effectiveDeadline = assignment.getDeadlineAt();
        if (assignment.getOpenAt() != null && now.isBefore(assignment.getOpenAt())) {
            throw ApiException.conflict("SUBMISSION_NOT_OPEN", "提交尚未开放",
                    "请在作业开放时间后再提交。");
        }
        if (now.isAfter(assignment.getDeadlineAt())) {
            boolean withinLateWindow = Boolean.TRUE.equals(assignment.getAllowLate())
                    && assignment.getLateDeadlineAt() != null
                    && !now.isAfter(assignment.getLateDeadlineAt());
            if (!withinLateWindow) {
                throw ApiException.conflict("SUBMISSION_DEADLINE_PASSED", "提交已截止",
                        "该作业已截止且未允许补交。");
            }
            late = true;
            effectiveDeadline = assignment.getLateDeadlineAt();
        }

        String idempotencyKey = headerIdempotencyKey;
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = request.idempotencyKey();
        }
        idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (idempotencyKey != null && idempotencyKey.length() > 128) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_INVALID", "幂等键不合法",
                    "幂等键不能超过 128 个字符。");
        }
        if (idempotencyKey != null) {
            Submission existing = submissionMapper.findByIdempotency(principal.id(), assignmentId, idempotencyKey);
            if (existing != null) {
                return toView(existing, principal.displayName());
            }
        }

        if (text == null && requestedFileIds.isEmpty()) {
            throw ApiException.unprocessable("SUBMISSION_CONTENT_REQUIRED", "提交内容不能为空",
                    "请输入在线文本或上传至少一个文件。");
        }
        List<PreparedFile> files = validateFiles(assignmentId, principal, requestedFileIds);

        submissionMapper.acquireVersionLock(principal.id() + ":" + assignmentId);
        List<Submission> history = submissionMapper.findByAssignmentAndSubmitter(assignmentId, principal.id());
        if (!Boolean.TRUE.equals(assignment.getAllowMultiVersion()) && !history.isEmpty()) {
            throw ApiException.conflict("MULTI_VERSION_NOT_ALLOWED", "不允许多版本提交",
                    "该作业只允许提交一个版本。");
        }
        boolean first = history.isEmpty();
        if (first && !Boolean.TRUE.equals(request.integrityConsent())) {
            throw ApiException.unprocessable("INTEGRITY_CONSENT_REQUIRED", "请签署诚信声明",
                    "首次提交前必须确认学术诚信声明。");
        }
        boolean effective = first || "LAST_SUBMISSION".equals(assignment.getEffectiveRule());
        if (effective) {
            submissionMapper.update(null, new LambdaUpdateWrapper<Submission>()
                    .eq(Submission::getAssignmentId, assignmentId)
                    .eq(Submission::getSubmitterId, principal.id())
                    .eq(Submission::getIsEffective, true)
                    .set(Submission::getIsEffective, false));
        }

        Submission submission = new Submission();
        submission.setId(Ids.newUuidV7());
        submission.setAssignmentId(assignmentId);
        submission.setSubmitterId(principal.id());
        submission.setVersionNo(submissionMapper.maxVersionNo(assignmentId, principal.id()) + 1);
        submission.setStatus(Boolean.TRUE.equals(assignment.getCheckEnabled()) ? "CHECKING" : "SUBMITTED");
        submission.setIsEffective(effective);
        submission.setIsLate(late);
        submission.setSubmittedAt(now);
        submission.setEffectiveDeadlineAt(effectiveDeadline);
        submission.setOnlineText(text);
        String textDigest = text == null ? null : Digests.sha256Hex(text);
        String inputDigest = inputDigest(text, files);
        submission.setOnlineTextDigest(textDigest);
        submission.setInputDigest(inputDigest);
        boolean consent = first || history.stream().anyMatch(item -> Boolean.TRUE.equals(item.getIntegrityConsent()));
        submission.setIntegrityConsent(consent);
        submission.setIntegrityConsentAt(consent
                ? (first ? now : history.get(0).getIntegrityConsentAt()) : null);
        submission.setIdempotencyKey(idempotencyKey);
        submission.setRowVersion(0L);
        submissionMapper.insert(submission);
        insertSubmissionFiles(submission.getId(), files);

        CheckTask task = null;
        if (Boolean.TRUE.equals(assignment.getCheckEnabled())) {
            try {
                task = checkTaskService.enqueueFormal(submission, assignment, principal.id(),
                        files.isEmpty() ? null : files.get(0).id(), inputDigest);
            } catch (RuntimeException exception) {
                submission.setStatus("CHECK_FAILED");
                submission.setRowVersion(1L);
                submissionMapper.updateById(submission);
                auditService.recordUser(principal.id(), "check.enqueue.failed", "SUBMISSION",
                        submission.getId(), "FAILED", Map.of("error", exception.getClass().getSimpleName()), null);
            }
        }
        auditService.recordUser(principal.id(), "submission.create", "SUBMISSION", submission.getId(),
                "SUCCESS", Map.of("assignmentId", assignmentId.toString(),
                        "versionNo", submission.getVersionNo(), "late", late, "fileCount", files.size()), null);
        return toView(submission, principal.displayName());
    }

    @Transactional(readOnly = true)
    public List<SubmissionView> listMine(UserPrincipal principal, UUID assignmentId) {
        Assignment assignment = assignmentService.requireAssignment(assignmentId);
        Course course = courseService.requireCourse(assignment.getCourseId());
        courseService.requireReadable(course, principal);
        return submissionMapper.findByAssignmentAndSubmitter(assignmentId, principal.id()).stream()
                .map(submission -> toView(submission, principal.displayName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SubmissionView> listForAssignment(UserPrincipal principal, UUID assignmentId, boolean allVersions) {
        Assignment assignment = assignmentService.requireAssignment(assignmentId);
        Course course = courseService.requireCourse(assignment.getCourseId());
        courseService.requireManage(course, principal);
        List<Submission> submissions = allVersions
                ? submissionMapper.findByAssignment(assignmentId)
                : submissionMapper.findEffectiveByAssignment(assignmentId);
        return submissions.stream()
                .map(submission -> toView(submission, displayName(submission.getSubmitterId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public SubmissionView get(UserPrincipal principal, UUID submissionId) {
        Submission submission = requireSubmission(submissionId);
        Assignment assignment = assignmentService.requireAssignment(submission.getAssignmentId());
        Course course = courseService.requireCourse(assignment.getCourseId());
        boolean owner = submission.getSubmitterId().equals(principal.id());
        if (!owner) {
            courseService.requireManage(course, principal);
        }
        return toView(submission, displayName(submission.getSubmitterId()));
    }

    @Transactional
    public SubmissionView setEffective(UserPrincipal principal, UUID submissionId) {
        Submission submission = requireSubmission(submissionId);
        Assignment assignment = assignmentService.requireAssignment(submission.getAssignmentId());
        Course course = courseService.requireCourse(assignment.getCourseId());
        courseService.requireManage(course, principal);
        submissionMapper.update(null, new LambdaUpdateWrapper<Submission>()
                .eq(Submission::getAssignmentId, submission.getAssignmentId())
                .eq(Submission::getSubmitterId, submission.getSubmitterId())
                .eq(Submission::getIsEffective, true)
                .set(Submission::getIsEffective, false));
        submission.setIsEffective(true);
        submission.setRowVersion((submission.getRowVersion() == null ? 0L : submission.getRowVersion()) + 1);
        submissionMapper.updateById(submission);
        auditService.recordUser(principal.id(), "submission.effective", "SUBMISSION", submissionId,
                "SUCCESS", Map.of(), null);
        return toView(submission, displayName(submission.getSubmitterId()));
    }

    private SubmissionView toView(Submission submission, String submitterName) {
        CheckTask task = checkTaskService.findLatestForSubmission(submission.getId());
        return SubmissionView.from(submission, submitterName, task, findFiles(submission.getId()));
    }

    private List<UUID> normalizeFileIds(List<UUID> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = requested.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > 10) {
            throw ApiException.unprocessable("SUBMISSION_FILE_LIMIT", "附件数量过多",
                    "单次提交最多包含 10 个文件。");
        }
        return ids;
    }

    private List<PreparedFile> validateFiles(UUID assignmentId, UserPrincipal principal, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<PreparedFile> rows = jdbcTemplate.query(
                "SELECT id, original_name, sha256, object_key, created_by, scan_status " +
                        "FROM app.file_object WHERE id IN (" + placeholders + ")",
                (rs, rowNum) -> new PreparedFile(
                        rs.getObject("id", UUID.class), rs.getString("original_name"),
                        rs.getString("sha256"), rs.getString("object_key"),
                        rs.getObject("created_by", UUID.class), rs.getString("scan_status")),
                ids.toArray());
        if (rows.size() != ids.size()) {
            throw ApiException.unprocessable("SUBMISSION_FILE_NOT_FOUND", "附件不存在",
                    "部分附件已失效，请重新上传。");
        }
        String expectedPrefix = "submissions/" + assignmentId + "/" + principal.id() + "/";
        Map<UUID, PreparedFile> byId = new HashMap<>();
        for (PreparedFile file : rows) {
            if (!principal.id().equals(file.createdBy()) || !file.objectKey().startsWith(expectedPrefix)) {
                throw ApiException.forbidden("SUBMISSION_FILE_FORBIDDEN", "无权使用该附件",
                        "附件必须由当前账号为本次作业上传。");
            }
            if (!"CLEAN".equals(file.scanStatus())) {
                throw ApiException.conflict("SUBMISSION_FILE_NOT_READY", "附件尚未通过校验",
                        "请稍后重新提交。");
            }
            byId.put(file.id(), file);
        }
        Long used = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app.submission_file WHERE file_object_id IN (" + placeholders + ")",
                Long.class, ids.toArray());
        if (used != null && used > 0) {
            throw ApiException.conflict("SUBMISSION_FILE_ALREADY_USED", "附件已被提交",
                    "请勿重复使用已提交的附件。");
        }
        List<PreparedFile> ordered = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            ordered.add(byId.get(id));
        }
        return ordered;
    }

    private void insertSubmissionFiles(UUID submissionId, List<PreparedFile> files) {
        for (int index = 0; index < files.size(); index++) {
            PreparedFile file = files.get(index);
            jdbcTemplate.update(
                    "INSERT INTO app.submission_file (id, submission_id, file_object_id, display_name, sort_order) " +
                            "VALUES (?, ?, ?, ?, ?)",
                    Ids.newUuidV7(), submissionId, file.id(), file.originalName(), index);
        }
    }

    private List<SubmissionFileView> findFiles(UUID submissionId) {
        return jdbcTemplate.query(
                "SELECT fo.id, fo.original_name, fo.mime_type, fo.size_bytes, fo.sha256, " +
                        "fo.scan_status, fo.created_at FROM app.submission_file sf " +
                        "JOIN app.file_object fo ON fo.id = sf.file_object_id " +
                        "WHERE sf.submission_id = ? ORDER BY sf.sort_order, sf.created_at",
                (rs, rowNum) -> new SubmissionFileView(
                        rs.getObject("id", UUID.class), rs.getString("original_name"),
                        rs.getString("mime_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                        rs.getString("scan_status"), rs.getObject("created_at", OffsetDateTime.class)),
                submissionId);
    }

    private String inputDigest(String text, List<PreparedFile> files) {
        if (files.isEmpty()) {
            return text == null ? null : Digests.sha256Hex(text);
        }
        StringBuilder canonical = new StringBuilder();
        if (text != null) {
            canonical.append("text:").append(Digests.sha256Hex(text));
        }
        for (PreparedFile file : files) {
            canonical.append("|file:").append(file.sha256());
        }
        return Digests.sha256Hex(canonical.toString());
    }

    private String displayName(UUID userId) {
        return identityQueryService.findById(userId).map(UserAccount::getDisplayName).orElse("未知用户");
    }

    private Submission requireSubmission(UUID submissionId) {
        Submission submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            throw ApiException.notFound("SUBMISSION_NOT_FOUND", "提交不存在", "提交可能已被删除。");
        }
        return submission;
    }

    private record PreparedFile(UUID id, String originalName, String sha256, String objectKey,
                                UUID createdBy, String scanStatus) {
    }
}


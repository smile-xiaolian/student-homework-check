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
import com.edu.submission.shared.storage.ObjectStorageService;
import com.edu.submission.submission.adapter.in.dto.SubmissionCreateRequest;
import com.edu.submission.submission.adapter.in.dto.SubmissionFileView;
import com.edu.submission.submission.adapter.in.dto.SubmissionView;
import com.edu.submission.submission.adapter.out.SubmissionMapper;
import com.edu.submission.submission.domain.Submission;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
    private final ObjectStorageService storageService;

    public SubmissionService(SubmissionMapper submissionMapper,
                             AssignmentService assignmentService,
                             CourseService courseService,
                             CheckTaskService checkTaskService,
                             IdentityQueryService identityQueryService,
                             AuditService auditService,
                             JdbcTemplate jdbcTemplate,
                             ObjectStorageService storageService) {
        this.submissionMapper = submissionMapper;
        this.assignmentService = assignmentService;
        this.courseService = courseService;
        this.checkTaskService = checkTaskService;
        this.identityQueryService = identityQueryService;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
        this.storageService = storageService;
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
            throw ApiException.conflict("ASSIGNMENT_NOT_SUBMITTABLE", "作业不可提交",
                    "作业尚未发布、已关闭或已归档。");
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

    /**
     * 批量下载 ZIP 准备（FR-R-06）：校验管理者权限并预取提交元数据与附件引用（不含文件内容与在线文本）。
     * 在线文本与对象内容在 {@link #writeZip} 流式写出阶段按需读取，避免一次性载入内存。
     */
    @Transactional(readOnly = true)
    public SubmissionsZip prepareZip(UserPrincipal principal, UUID assignmentId, List<UUID> submissionIds) {
        Assignment assignment = assignmentService.requireAssignment(assignmentId);
        Course course = courseService.requireCourse(assignment.getCourseId());
        courseService.requireManage(course, principal);
        if (submissionIds == null || submissionIds.isEmpty()) {
            throw ApiException.unprocessable("ZIP_IDS_REQUIRED", "请选择要下载的提交",
                    "至少选择一个提交。");
        }
        if (submissionIds.size() > 50) {
            throw ApiException.unprocessable("ZIP_TOO_MANY", "批量下载数量过多",
                    "单次最多下载 50 个提交。");
        }
        String placeholders = String.join(",", Collections.nCopies(submissionIds.size(), "?"));
        List<Object> submissionArgs = new ArrayList<>(submissionIds.size() + 1);
        submissionArgs.add(assignmentId);
        submissionArgs.addAll(submissionIds);
        List<ZipSubmission> submissions = jdbcTemplate.query(
                "SELECT s.id, s.version_no, u.user_no, u.display_name FROM app.submission s " +
                        "JOIN app.user_account u ON u.id = s.submitter_id " +
                        "WHERE s.assignment_id = ? AND s.id IN (" + placeholders + ")",
                (rs, rowNum) -> new ZipSubmission(
                        rs.getObject("id", UUID.class), rs.getInt("version_no"),
                        rs.getString("user_no"), rs.getString("display_name")),
                submissionArgs.toArray());
        List<ZipFile> files = jdbcTemplate.query(
                "SELECT sf.submission_id, fo.object_key, fo.original_name FROM app.submission_file sf " +
                        "JOIN app.file_object fo ON fo.id = sf.file_object_id " +
                        "WHERE sf.submission_id IN (" + placeholders + ")",
                (rs, rowNum) -> new ZipFile(
                        rs.getObject("submission_id", UUID.class),
                        rs.getString("object_key"), rs.getString("original_name")),
                submissionIds.toArray());
        Map<UUID, List<ZipFile>> bySubmission = new LinkedHashMap<>();
        for (ZipFile file : files) {
            bySubmission.computeIfAbsent(file.submissionId(), key -> new ArrayList<>()).add(file);
        }
        return new SubmissionsZip(submissions, bySubmission);
    }

    /**
     * 流式写 ZIP：对每个提交写 {学号}_{姓名}_v{版本}/online.txt（在线文本非空时），
     * 附件按 object_key 通过 ObjectStorageService.getStream 分块复制到
     * {学号}_{姓名}_v{版本}/{originalName}。整体不载入内存，单文件以 8KB 缓冲流式复制。
     */
    public void writeZip(SubmissionsZip prep, OutputStream outputStream) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            for (ZipSubmission submission : prep.submissions()) {
                String folder = zipFolder(submission);
                String onlineText = jdbcTemplate.query(
                        "SELECT online_text FROM app.submission WHERE id = ?",
                        rs -> rs.next() ? rs.getString(1) : null, submission.id());
                if (onlineText != null && !onlineText.isBlank()) {
                    zip.putNextEntry(new ZipEntry(folder + "/online.txt"));
                    zip.write(onlineText.getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
                Set<String> used = new HashSet<>();
                for (ZipFile file : prep.filesBySubmission().getOrDefault(submission.id(), List.of())) {
                    String name = safeEntryName(file.originalName(), used);
                    zip.putNextEntry(new ZipEntry(folder + "/" + name));
                    try (InputStream in = storageService.getStream(file.objectKey())) {
                        copy(in, zip);
                    }
                    zip.closeEntry();
                }
            }
        }
    }

    private String zipFolder(ZipSubmission submission) {
        return sanitizePathSegment(submission.userNo()) + "_"
                + sanitizePathSegment(submission.displayName()) + "_v" + submission.versionNo();
    }

    /** 清洗路径分隔符与控制字符，保证 ZIP 内路径安全（防目录穿越）。 */
    private String sanitizePathSegment(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.replace('\\', '/');
        int last = normalized.lastIndexOf('/');
        if (last >= 0) {
            normalized = normalized.substring(last + 1);
        }
        normalized = normalized.replaceAll("[\\r\\n\\t/\\\\:*?\"<>|]", "_");
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private String safeEntryName(String originalName, Set<String> used) {
        String base = sanitizePathSegment(originalName);
        if (base.isBlank() || "unknown".equals(base)) {
            base = "file";
        }
        String candidate = base;
        int suffix = 1;
        while (!used.add(candidate)) {
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                candidate = base.substring(0, dot) + "_" + (suffix++) + base.substring(dot);
            } else {
                candidate = base + "_" + (suffix++);
            }
        }
        return candidate;
    }

    private void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
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

    /** 批量下载 ZIP 的准备结果：提交元数据 + 按提交分组的附件引用（不含内容）。 */
    public record SubmissionsZip(List<ZipSubmission> submissions,
                                 Map<UUID, List<ZipFile>> filesBySubmission) {
    }

    public record ZipSubmission(UUID id, int versionNo, String userNo, String displayName) {
    }

    public record ZipFile(UUID submissionId, String objectKey, String originalName) {
    }
}


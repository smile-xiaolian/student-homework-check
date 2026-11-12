package com.edu.submission.file.application;

import com.edu.submission.assignment.domain.Assignment;
import com.edu.submission.assignment.adapter.out.AssignmentMapper;
import com.edu.submission.audit.AuditService;
import com.edu.submission.course.application.CourseService;
import com.edu.submission.course.domain.Course;
import com.edu.submission.course.domain.CourseMember;
import com.edu.submission.file.adapter.in.dto.FileObjectView;
import com.edu.submission.shared.api.ApiException;
import com.edu.submission.shared.config.StorageProperties;
import com.edu.submission.shared.crypto.Digests;
import com.edu.submission.shared.ids.Ids;
import com.edu.submission.shared.security.UserPrincipal;
import com.edu.submission.shared.storage.ObjectStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileUploadService {

    private final JdbcTemplate jdbcTemplate;
    private final AssignmentMapper assignmentMapper;
    private final CourseService courseService;
    private final ObjectStorageService storageService;
    private final StorageProperties storageProperties;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public FileUploadService(JdbcTemplate jdbcTemplate, AssignmentMapper assignmentMapper,
                             CourseService courseService, ObjectStorageService storageService,
                             StorageProperties storageProperties, ObjectMapper objectMapper,
                             AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.assignmentMapper = assignmentMapper;
        this.courseService = courseService;
        this.storageService = storageService;
        this.storageProperties = storageProperties;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Transactional
    public FileObjectView upload(UserPrincipal principal, UUID assignmentId, MultipartFile file) {
        Assignment assignment = assignmentMapper.selectById(assignmentId);
        if (assignment == null) {
            throw ApiException.notFound("ASSIGNMENT_NOT_FOUND", "作业不存在", "作业可能已被删除。");
        }
        Course course = courseService.requireCourse(assignment.getCourseId());
        courseService.requireReadable(course, principal);
        CourseMember membership = courseService.findMembership(course.getId(), principal.id());
        if (membership == null || !"ACTIVE".equals(membership.getStatus())
                || !"STUDENT".equals(membership.getMemberRole())) {
            throw ApiException.forbidden("FILE_UPLOAD_FORBIDDEN", "无权上传提交附件",
                    "只有该课程的学生可以上传作业文件。");
        }
        if (!"PUBLISHED".equals(assignment.getStatus()) || !"ACTIVE".equals(course.getStatus())) {
            throw ApiException.conflict("ASSIGNMENT_NOT_SUBMITTABLE", "当前作业不可提交",
                    "作业尚未发布或课程已归档。");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.unprocessable("FILE_REQUIRED", "请选择要上传的文件", "文件内容不能为空。");
        }
        String originalName = safeName(file.getOriginalFilename());
        String extension = extension(originalName);
        long maxSize = longConfig("file.max_size_bytes", 104_857_600L);
        if (file.getSize() > maxSize) {
            throw ApiException.unprocessable("FILE_TOO_LARGE", "文件超过大小限制",
                    "单个文件不能超过 " + (maxSize / 1024 / 1024) + " MB。");
        }
        if (!effectiveAllowedExtensions(assignment).contains(extension)) {
            throw ApiException.unprocessable("FILE_TYPE_NOT_ALLOWED", "不支持该文件类型",
                    "该作业允许的扩展名：" + String.join(", ", effectiveAllowedExtensions(assignment)) + "。");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw ApiException.unprocessable("FILE_READ_FAILED", "文件读取失败", "请重新选择文件后上传。");
        }
        String digest = Digests.sha256Hex(content);
        UUID fileId = Ids.newUuidV7();
        String objectKey = "submissions/" + assignmentId + "/" + principal.id() + "/" + fileId + "." + extension;
        storageService.put(objectKey, content, contentType(file.getContentType(), extension));
        jdbcTemplate.update(
                "INSERT INTO app.file_object (id, bucket, object_key, original_name, mime_type, size_bytes, " +
                        "sha256, scan_status, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, 'CLEAN', ?)",
                fileId, storageProperties.bucket(), objectKey, originalName,
                contentType(file.getContentType(), extension), content.length, digest, principal.id());
        auditService.recordUser(principal.id(), "file.upload", "FILE_OBJECT", fileId, "SUCCESS",
                Map.of("assignmentId", assignmentId, "name", originalName, "size", content.length), null);
        return new FileObjectView(fileId, originalName, contentType(file.getContentType(), extension),
                content.length, digest, "CLEAN", OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public String presignedUrl(UserPrincipal principal, UUID fileId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT fo.object_key, fo.created_by, s.id AS submission_id, a.course_id " +
                        "FROM app.file_object fo LEFT JOIN app.submission_file sf ON sf.file_object_id = fo.id " +
                        "LEFT JOIN app.submission s ON s.id = sf.submission_id " +
                        "LEFT JOIN app.assignment a ON a.id = s.assignment_id WHERE fo.id = ? LIMIT 1", fileId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("FILE_NOT_FOUND", "文件不存在", "文件可能已被删除。");
        }
        Map<String, Object> row = rows.get(0);
        UUID ownerId = (UUID) row.get("created_by");
        boolean ownFile = principal.id().equals(ownerId);
        UUID courseId = (UUID) row.get("course_id");
        if (!ownFile) {
            if (courseId == null) {
                throw ApiException.forbidden("FILE_ACCESS_DENIED", "无权下载文件", "该文件不属于当前账号。");
            }
            Course course = courseService.requireCourse(courseId);
            courseService.requireReadable(course, principal);
        }
        return storageService.presignedGetUrl((String) row.get("object_key"), java.time.Duration.ofMinutes(5));
    }

    @Transactional(readOnly = true)
    public DownloadedFile download(UserPrincipal principal, UUID fileId) {
        List<StoredFileRef> rows = jdbcTemplate.query(
                "SELECT fo.object_key, fo.original_name, fo.mime_type, fo.created_by, fo.scan_status, " +
                        "(SELECT a.course_id FROM app.submission_file sf " +
                        " JOIN app.submission s ON s.id = sf.submission_id " +
                        " JOIN app.assignment a ON a.id = s.assignment_id " +
                        " WHERE sf.file_object_id = fo.id LIMIT 1) AS submission_course_id, " +
                        "(SELECT a.course_id FROM app.assignment_attachment af " +
                        " JOIN app.assignment a ON a.id = af.assignment_id " +
                        " WHERE af.file_object_id = fo.id LIMIT 1) AS attachment_course_id " +
                        "FROM app.file_object fo WHERE fo.id = ? LIMIT 1",
                (rs, rowNum) -> new StoredFileRef(
                        rs.getString("object_key"), rs.getString("original_name"), rs.getString("mime_type"),
                        rs.getObject("created_by", UUID.class), rs.getString("scan_status"),
                        rs.getObject("submission_course_id", UUID.class),
                        rs.getObject("attachment_course_id", UUID.class)),
                fileId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("FILE_NOT_FOUND", "文件不存在", "文件可能已被删除。");
        }
        StoredFileRef file = rows.get(0);
        if (!principal.id().equals(file.createdBy())) {
            UUID courseId = file.submissionCourseId() == null
                    ? file.attachmentCourseId() : file.submissionCourseId();
            if (courseId == null) {
                throw ApiException.forbidden("FILE_ACCESS_DENIED", "无权下载文件", "该文件不属于当前账号。");
            }
            Course course = courseService.requireCourse(courseId);
            courseService.requireManage(course, principal);
        }
        if ("INFECTED".equals(file.scanStatus()) || "FAILED".equals(file.scanStatus())) {
            throw ApiException.conflict("FILE_NOT_AVAILABLE", "文件不可下载", "文件未通过安全检查。");
        }
        return new DownloadedFile(file.originalName(), file.mimeType(),
                storageService.get(file.objectKey()));
    }

    /** 生效的扩展名白名单：全局配置 ∩ 作业级限制（作业未配置限制时仅用全局配置）。 */
    private List<String> effectiveAllowedExtensions(Assignment assignment) {
        List<String> global = allowedExtensions();
        List<String> restricted = assignmentAllowedExtensions(assignment);
        if (restricted.isEmpty()) {
            return global;
        }
        return global.stream().filter(restricted::contains).toList();
    }

    private List<String> assignmentAllowedExtensions(Assignment assignment) {
        String raw = assignment.getAllowedFileTypes();
        if (raw == null || raw.isBlank() || "[]".equals(raw)) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> values = new java.util.ArrayList<>();
            for (JsonNode item : node) {
                String value = item.asText("");
                if (!value.isBlank()) {
                    values.add(value.toLowerCase(Locale.ROOT));
                }
            }
            return values;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<String> allowedExtensions() {
        String raw = jdbcTemplate.query(
                "SELECT value::text FROM app.system_config WHERE config_key = 'file.allowed_extensions'",
                rs -> rs.next() ? rs.getString(1) : null);
        if (raw == null) {
            return List.of("pdf", "docx", "doc", "txt", "md", "java", "py", "c", "cpp", "h", "js", "ts", "go");
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            List<String> values = new java.util.ArrayList<>();
            node.forEach(item -> values.add(item.asText().toLowerCase(Locale.ROOT)));
            return values;
        } catch (Exception exception) {
            return List.of("pdf", "docx", "doc", "txt", "md", "java", "py", "c", "cpp", "h", "js", "ts", "go");
        }
    }

    private long longConfig(String key, long fallback) {
        try {
            Long value = jdbcTemplate.query(
                    "SELECT value::text FROM app.system_config WHERE config_key = ?",
                    rs -> rs.next() ? Long.parseLong(rs.getString(1)) : null, key);
            return value == null ? fallback : value;
        } catch (Exception exception) {
            return fallback;
        }
    }

    private String safeName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "upload.bin";
        }
        String normalized = originalName.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("[\\r\\n\\t]", "_");
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String contentType(String provided, String extension) {
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "txt", "md", "java", "py", "c", "cpp", "h", "js", "ts", "go" -> "text/plain";
            default -> "application/octet-stream";
        };
    }

    public record DownloadedFile(String originalName, String mimeType, byte[] content) {
    }

    private record StoredFileRef(String objectKey, String originalName, String mimeType,
                                 UUID createdBy, String scanStatus, UUID submissionCourseId,
                                 UUID attachmentCourseId) {
    }
}

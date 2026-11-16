package com.edu.submission.library.application;

import com.edu.submission.audit.AuditService;
import com.edu.submission.course.application.CourseService;
import com.edu.submission.course.domain.Course;
import com.edu.submission.library.adapter.in.dto.LibraryCreateRequest;
import com.edu.submission.library.adapter.in.dto.LibraryEntryView;
import com.edu.submission.library.adapter.in.dto.LibraryView;
import com.edu.submission.shared.api.ApiException;
import com.edu.submission.shared.config.StorageProperties;
import com.edu.submission.shared.crypto.Digests;
import com.edu.submission.shared.ids.Ids;
import com.edu.submission.shared.security.UserPrincipal;
import com.edu.submission.shared.storage.ObjectStorageService;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LibraryService {

    private static final long MAX_ENTRY_BYTES = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "docx", "txt", "md", "java", "py", "c", "cpp", "js", "ts", "go", "cs", "rs");

    private final JdbcTemplate jdbcTemplate;
    private final CourseService courseService;
    private final ObjectStorageService storageService;
    private final StorageProperties storageProperties;
    private final AuditService auditService;

    public LibraryService(JdbcTemplate jdbcTemplate, CourseService courseService,
                          ObjectStorageService storageService, StorageProperties storageProperties,
                          AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.courseService = courseService;
        this.storageService = storageService;
        this.storageProperties = storageProperties;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<LibraryView> list(UserPrincipal principal, UUID courseId) {
        Course course = courseService.requireCourse(courseId);
        courseService.requireManage(course, principal);
        return jdbcTemplate.query(
                "SELECT l.id, l.name, l.library_type, l.status, l.created_at, l.updated_at, " +
                        "(SELECT count(*) FROM app.library_entry e WHERE e.library_id = l.id AND e.status = 'ACTIVE') AS entry_count " +
                        "FROM app.check_library l WHERE l.course_id = ? ORDER BY l.created_at DESC",
                (rs, index) -> new LibraryView(
                        rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("library_type"), rs.getString("status"),
                        rs.getLong("entry_count"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)),
                courseId);
    }

    @Transactional
    public LibraryView create(UserPrincipal principal, UUID courseId, LibraryCreateRequest request) {
        Course course = courseService.requireCourse(courseId);
        courseService.requireManage(course, principal);
        assertCourseActive(course);
        String name = request.name().trim();
        String libraryType = request.libraryType() == null ? "" : request.libraryType().trim();
        if (!"WHITELIST".equals(libraryType)) {
            throw ApiException.unprocessable("LIBRARY_TYPE_NOT_SUPPORTED", "库类型不支持",
                    "当前仅支持白名单（WHITELIST）比对库。");
        }
        List<UUID> existing = jdbcTemplate.query(
                "SELECT id FROM app.check_library WHERE course_id = ? AND name = ? AND library_type = ? LIMIT 1",
                (rs, index) -> rs.getObject("id", UUID.class), courseId, name, libraryType);
        if (!existing.isEmpty()) {
            return view(existing.get(0));
        }
        UUID libraryId = Ids.newUuidV7();
        try {
            jdbcTemplate.update(
                    "INSERT INTO app.check_library (id, name, library_type, scope_key, course_id, status, " +
                            "source_config, created_by, row_version) VALUES (?, ?, ?, ?, ?, 'ACTIVE', '{}'::jsonb, ?, 0)",
                    libraryId, name, libraryType, courseId.toString(), courseId, principal.id());
        } catch (DuplicateKeyException exception) {
            // 同类型同课程已存在（uk_check_library_type_scope）：幂等返回
            List<UUID> byType = jdbcTemplate.query(
                    "SELECT id FROM app.check_library WHERE course_id = ? AND library_type = ? LIMIT 1",
                    (rs, index) -> rs.getObject("id", UUID.class), courseId, libraryType);
            if (!byType.isEmpty()) {
                return view(byType.get(0));
            }
            throw exception;
        }
        auditService.recordUser(principal.id(), "library.create", "CHECK_LIBRARY", libraryId, "SUCCESS",
                Map.of("courseId", courseId.toString(), "name", name, "libraryType", libraryType), null);
        return view(libraryId);
    }

    @Transactional
    public void delete(UserPrincipal principal, UUID libraryId) {
        LibraryRef library = requireLibrary(libraryId);
        Course course = courseService.requireCourse(library.courseId());
        boolean admin = "ADMIN".equals(principal.role().name());
        if (!admin && !library.createdBy().equals(principal.id())) {
            throw ApiException.forbidden("LIBRARY_DELETE_FORBIDDEN", "无权删除比对库",
                    "仅管理员或比对库创建人可以删除。");
        }
        assertCourseActive(course);
        if (!"ACTIVE".equals(library.status())) {
            throw ApiException.conflict("LIBRARY_INACTIVE", "比对库不可删除", "比对库当前不是启用状态。");
        }
        jdbcTemplate.update("DELETE FROM app.library_entry WHERE library_id = ?", libraryId);
        jdbcTemplate.update("DELETE FROM app.check_library WHERE id = ?", libraryId);
        auditService.recordUser(principal.id(), "library.delete", "CHECK_LIBRARY", libraryId, "SUCCESS",
                Map.of("courseId", library.courseId().toString(), "name", library.name()), null);
    }

    @Transactional
    public LibraryEntryView addEntry(UserPrincipal principal, UUID libraryId, MultipartFile file) {
        LibraryRef library = requireLibrary(libraryId);
        Course course = courseService.requireCourse(library.courseId());
        courseService.requireManage(course, principal);
        assertCourseActive(course);
        assertLibraryActive(library);
        if (file == null || file.isEmpty()) {
            throw ApiException.unprocessable("LIBRARY_FILE_INVALID", "文件不合法", "请选择要上传的文件。");
        }
        String originalName = safeName(file.getOriginalFilename());
        String extension = extension(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw ApiException.unprocessable("LIBRARY_FILE_INVALID", "文件类型不支持",
                    "比对库支持的类型：" + String.join("、", List.of("pdf", "docx", "txt", "md", "java", "py",
                            "c", "cpp", "js", "ts", "go", "cs", "rs")) + "。");
        }
        if (file.getSize() > MAX_ENTRY_BYTES) {
            throw ApiException.unprocessable("LIBRARY_FILE_INVALID", "文件超过大小限制",
                    "比对库单文件不能超过 20 MB。");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw ApiException.unprocessable("LIBRARY_FILE_INVALID", "文件读取失败", "请重新选择文件后上传。");
        }
        String digest = Digests.sha256Hex(content);
        UUID fileId = Ids.newUuidV7();
        String objectKey = "library/" + libraryId + "/" + fileId + "." + extension;
        storageService.put(objectKey, content, contentType(file.getContentType(), extension));
        jdbcTemplate.update(
                "INSERT INTO app.file_object (id, bucket, object_key, original_name, mime_type, size_bytes, " +
                        "sha256, scan_status, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, 'CLEAN', ?)",
                fileId, storageProperties.bucket(), objectKey, originalName,
                contentType(file.getContentType(), extension), content.length, digest, principal.id());
        Long nextSeq = jdbcTemplate.queryForObject(
                "SELECT coalesce(max(seq), 0) + 1 FROM app.library_entry WHERE library_id = ?",
                Long.class, libraryId);
        long seq = nextSeq == null ? 1 : nextSeq;
        UUID entryId = Ids.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO app.library_entry (id, library_id, file_object_id, owner_hash, content_hash, " +
                        "seq, status, index_status) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 'INDEXED')",
                entryId, libraryId, fileId, digest, digest, seq);
        auditService.recordUser(principal.id(), "library.entry.add", "LIBRARY_ENTRY", entryId, "SUCCESS",
                Map.of("libraryId", libraryId.toString(), "name", originalName,
                        "size", content.length, "seq", seq), null);
        return new LibraryEntryView(entryId, originalName, content.length, seq, OffsetDateTime.now());
    }

    @Transactional
    public void removeEntry(UserPrincipal principal, UUID libraryId, UUID entryId) {
        LibraryRef library = requireLibrary(libraryId);
        Course course = courseService.requireCourse(library.courseId());
        courseService.requireManage(course, principal);
        assertCourseActive(course);
        assertLibraryActive(library);
        int updated = jdbcTemplate.update(
                "UPDATE app.library_entry SET status = 'INACTIVE' WHERE id = ? AND library_id = ? AND status = 'ACTIVE'",
                entryId, libraryId);
        if (updated == 0) {
            // 幂等：条目不存在或已失效视为成功
            return;
        }
        auditService.recordUser(principal.id(), "library.entry.remove", "LIBRARY_ENTRY", entryId, "SUCCESS",
                Map.of("libraryId", libraryId.toString()), null);
    }

    @Transactional(readOnly = true)
    public List<LibraryEntryView> entries(UserPrincipal principal, UUID libraryId) {
        LibraryRef library = requireLibrary(libraryId);
        Course course = courseService.requireCourse(library.courseId());
        courseService.requireManage(course, principal);
        return jdbcTemplate.query(
                "SELECT e.id, fo.original_name, fo.size_bytes, e.seq, e.created_at " +
                        "FROM app.library_entry e JOIN app.file_object fo ON fo.id = e.file_object_id " +
                        "WHERE e.library_id = ? AND e.status = 'ACTIVE' ORDER BY e.seq",
                (rs, index) -> new LibraryEntryView(
                        rs.getObject("id", UUID.class), rs.getString("original_name"),
                        rs.getLong("size_bytes"), rs.getLong("seq"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                libraryId);
    }

    private LibraryView view(UUID libraryId) {
        List<LibraryView> rows = jdbcTemplate.query(
                "SELECT l.id, l.name, l.library_type, l.status, l.created_at, l.updated_at, " +
                        "(SELECT count(*) FROM app.library_entry e WHERE e.library_id = l.id AND e.status = 'ACTIVE') AS entry_count " +
                        "FROM app.check_library l WHERE l.id = ?",
                (rs, index) -> new LibraryView(
                        rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("library_type"), rs.getString("status"),
                        rs.getLong("entry_count"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)),
                libraryId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("LIBRARY_NOT_FOUND", "比对库不存在", "比对库可能已被删除。");
        }
        return rows.get(0);
    }

    private LibraryRef requireLibrary(UUID libraryId) {
        List<LibraryRef> rows = jdbcTemplate.query(
                "SELECT id, course_id, created_by, status, name FROM app.check_library WHERE id = ?",
                (rs, index) -> new LibraryRef(rs.getObject("id", UUID.class),
                        rs.getObject("course_id", UUID.class), rs.getObject("created_by", UUID.class),
                        rs.getString("status"), rs.getString("name")),
                libraryId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("LIBRARY_NOT_FOUND", "比对库不存在", "比对库可能已被删除。");
        }
        return rows.get(0);
    }

    private void assertCourseActive(Course course) {
        if (!"ACTIVE".equals(course.getStatus())) {
            throw ApiException.conflict("COURSE_ARCHIVED", "课程已归档", "归档课程为只读状态。");
        }
    }

    private void assertLibraryActive(LibraryRef library) {
        if (!"ACTIVE".equals(library.status())) {
            throw ApiException.conflict("LIBRARY_INACTIVE", "比对库不可用", "比对库当前不是启用状态。");
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
            case "txt", "md", "java", "py", "c", "cpp", "js", "ts", "go", "cs", "rs" -> "text/plain";
            default -> "application/octet-stream";
        };
    }

    private record LibraryRef(UUID id, UUID courseId, UUID createdBy, String status, String name) {
    }
}

package com.edu.submission.review.application;

import com.edu.submission.audit.AuditService;
import com.edu.submission.course.application.CourseService;
import com.edu.submission.course.domain.Course;
import com.edu.submission.review.adapter.in.dto.GradeView;
import com.edu.submission.review.adapter.in.dto.ReviewSaveRequest;
import com.edu.submission.review.adapter.in.dto.ReviewQueueItem;
import com.edu.submission.review.adapter.in.dto.ReviewView;
import com.edu.submission.shared.api.ApiException;
import com.edu.submission.shared.api.PageQuery;
import com.edu.submission.shared.api.PageResult;
import com.edu.submission.shared.api.Pagination;
import com.edu.submission.shared.ids.Ids;
import com.edu.submission.shared.security.UserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {

    private final JdbcTemplate jdbcTemplate;
    private final CourseService courseService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ReviewService(JdbcTemplate jdbcTemplate, CourseService courseService,
                         AuditService auditService, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.courseService = courseService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ReviewView getReview(UserPrincipal principal, UUID submissionId) {
        SubmissionRef submission = requireSubmissionReadable(principal, submissionId);
        List<ReviewView> rows = jdbcTemplate.query(
                "SELECT id, submission_id, reviewer_id, status, raw_score, late_deduction, final_score, " +
                        "dimension_scores::text AS dimension_scores, annotations::text AS annotations, " +
                        "check_verdict, comment, submitted_at, updated_at, row_version " +
                        "FROM app.grade WHERE submission_id = ? AND (reviewer_id = ? OR status = 'PUBLISHED') " +
                        "ORDER BY updated_at DESC LIMIT 1",
                (rs, index) -> new ReviewView(
                        rs.getObject("id", UUID.class), rs.getObject("submission_id", UUID.class),
                        rs.getObject("reviewer_id", UUID.class), rs.getString("status"),
                        rs.getBigDecimal("raw_score"), rs.getBigDecimal("late_deduction"),
                        rs.getBigDecimal("final_score"), readJson(rs.getString("dimension_scores")),
                        readJson(rs.getString("annotations")), rs.getString("check_verdict"),
                        rs.getString("comment"), rs.getObject("submitted_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class), rs.getLong("row_version")),
                submissionId, principal.id());
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        return new ReviewView(null, submissionId, principal.id(), "NOT_STARTED", null,
                BigDecimal.ZERO, null, objectMapper.createArrayNode(), objectMapper.createArrayNode(),
                null, null, null, null, 0L);
    }

    @Transactional(readOnly = true)
    public PageResult<ReviewQueueItem> reviewQueue(UserPrincipal principal, UUID assignmentId,
                                                   Integer page, Integer size) {
        UUID courseId = assignmentCourse(assignmentId);
        Course course = courseService.requireCourse(courseId);
        courseService.requireManage(course, principal);
        PageQuery query = PageQuery.of(page, size);
        List<ReviewQueueItem> items = jdbcTemplate.query(
                "SELECT s.id AS submission_id, s.submitter_id, u.display_name, s.version_no, s.submitted_at, " +
                        "s.is_late, r.overall_score AS check_score, r.level AS check_level, " +
                        "coalesce(g.status, 'NOT_STARTED') AS review_status, g.status AS grade_status " +
                        "FROM app.submission s JOIN app.user_account u ON u.id = s.submitter_id " +
                        "LEFT JOIN app.check_report r ON r.submission_id = s.id AND r.is_current = true " +
                        "LEFT JOIN app.grade g ON g.submission_id = s.id " +
                        "WHERE s.assignment_id = ? AND s.is_effective = true " +
                        "AND s.status NOT IN ('WITHDRAWN','PENDING_SCAN') " +
                        "ORDER BY s.is_late DESC, s.submitted_at ASC LIMIT ? OFFSET ?",
                (rs, index) -> new ReviewQueueItem(
                        rs.getObject("submission_id", UUID.class), rs.getObject("submitter_id", UUID.class),
                        rs.getString("display_name"), rs.getInt("version_no"),
                        rs.getObject("submitted_at", OffsetDateTime.class), rs.getBoolean("is_late"),
                        rs.getBigDecimal("check_score"), rs.getString("check_level"),
                        rs.getString("review_status"), rs.getString("grade_status")),
                assignmentId, query.size(), query.offset());
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app.submission WHERE assignment_id = ? AND is_effective = true " +
                        "AND status NOT IN ('WITHDRAWN','PENDING_SCAN')",
                Long.class, assignmentId);
        return new PageResult<>(items, Pagination.of(query.page(), query.size(),
                total == null ? 0 : total));
    }

    @Transactional
    public ReviewView save(UserPrincipal principal, UUID submissionId, ReviewSaveRequest request) {
        SubmissionRef submission = requireSubmissionManageAccess(principal, submissionId);
        if (request.rawScore() == null) {
            throw ApiException.unprocessable("REVIEW_SCORE_REQUIRED", "请填写评审得分", "原始得分不能为空。");
        }
        String verdict = normalizeVerdict(request.checkVerdict());
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT id, reviewer_id, status, row_version FROM app.grade WHERE submission_id = ?", submissionId);
        JsonNode dimensions = request.dimensionScores() == null
                ? objectMapper.createArrayNode() : request.dimensionScores();
        JsonNode annotations = request.annotations() == null
                ? objectMapper.createArrayNode() : request.annotations();
        String dimensionJson = writeJson(dimensions);
        String annotationJson = writeJson(annotations);
        BigDecimal deduction = lateDeduction(submissionId, request.rawScore());
        BigDecimal finalScore = request.rawScore().subtract(deduction).max(BigDecimal.ZERO);
        if (existing.isEmpty()) {
            UUID gradeId = Ids.newUuidV7();
            jdbcTemplate.update(
                    "INSERT INTO app.grade (id, submission_id, reviewer_id, reviewer_type, status, raw_score, " +
                            "late_deduction, final_score, dimension_scores, annotations, check_verdict, comment, row_version) " +
                            "VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, 0)",
                    gradeId, submissionId, principal.id(),
                    "ASSISTANT".equals(principal.role().name()) ? "ASSISTANT" : "TEACHER",
                    request.rawScore(), deduction, finalScore, dimensionJson, annotationJson,
                    verdict, blankToNull(request.comment()));
        } else {
            Map<String, Object> grade = existing.get(0);
            UUID reviewerId = (UUID) grade.get("reviewer_id");
            String status = (String) grade.get("status");
            if (!principal.id().equals(reviewerId) && !"ADMIN".equals(principal.role().name())) {
                throw ApiException.forbidden("REVIEW_OWNER_MISMATCH", "评审草稿属于其他评审人",
                        "请由原评审人继续处理，或联系管理员调整评审分配。");
            }
            if ("PUBLISHED".equals(status)) {
                throw ApiException.conflict("REVIEW_PUBLISHED", "成绩已发布", "已发布成绩不能通过评审草稿修改。");
            }
            long version = ((Number) grade.get("row_version")).longValue();
            if (request.expectedRowVersion() != null && request.expectedRowVersion() != version) {
                throw ApiException.conflict("REVIEW_VERSION_CONFLICT", "评审内容已被更新", "请刷新后重试。");
            }
            jdbcTemplate.update(
                    "UPDATE app.grade SET raw_score = ?, late_deduction = ?, final_score = ?, " +
                            "dimension_scores = ?::jsonb, " +
                            "annotations = ?::jsonb, check_verdict = ?, comment = ?, row_version = row_version + 1 " +
                            "WHERE submission_id = ? AND row_version = ?",
                    request.rawScore(), deduction, finalScore, dimensionJson, annotationJson, verdict,
                    blankToNull(request.comment()), submissionId, version);
        }
        auditService.recordUser(principal.id(), "review.save", "SUBMISSION", submissionId,
                "SUCCESS", Map.of("rawScore", request.rawScore(), "lateDeduction", deduction), null);
        return getReview(principal, submissionId);
    }

    @Transactional
    public ReviewView submit(UserPrincipal principal, UUID submissionId) {
        SubmissionRef submission = requireSubmissionManageAccess(principal, submissionId);
        int updated = jdbcTemplate.update(
                "UPDATE app.grade SET status = 'SUBMITTED', submitted_at = now(), row_version = row_version + 1 " +
                        "WHERE submission_id = ? AND reviewer_id = ? AND status = 'DRAFT'",
                submissionId, principal.id());
        if (updated == 0) {
            throw ApiException.conflict("REVIEW_NOT_SAVED", "评审草稿不可提交",
                    "请先保存评审草稿，或该评审已提交。");
        }
        jdbcTemplate.update("UPDATE app.submission SET status = 'REVIEWED', row_version = row_version + 1 " +
                "WHERE id = ?", submissionId);
        auditService.recordUser(principal.id(), "review.submit", "SUBMISSION", submissionId,
                "SUCCESS", Map.of(), null);
        return getReview(principal, submissionId);
    }

    @Transactional(readOnly = true)
    public GradeView grade(UserPrincipal principal, UUID submissionId) {
        SubmissionRef submission = requireSubmissionReadable(principal, submissionId);
        if (!submission.submitterId().equals(principal.id())) {
            Course course = courseService.requireCourse(submission.courseId());
            courseService.requireManage(course, principal);
        }
        List<GradeView> rows = jdbcTemplate.query(
                "SELECT g.id, g.submission_id, u.display_name AS submitter_name, s.version_no, g.status, " +
                        "g.raw_score, g.late_deduction, g.final_score, g.check_verdict, g.comment, " +
                        "g.published, g.published_at " +
                        "FROM app.grade g JOIN app.submission s ON s.id = g.submission_id " +
                        "JOIN app.user_account u ON u.id = s.submitter_id WHERE g.submission_id = ?",
                (rs, index) -> new GradeView(
                        rs.getObject("id", UUID.class), rs.getObject("submission_id", UUID.class),
                        rs.getString("submitter_name"), rs.getInt("version_no"), rs.getString("status"),
                        rs.getBigDecimal("raw_score"), rs.getBigDecimal("late_deduction"),
                        rs.getBigDecimal("final_score"), rs.getString("check_verdict"), rs.getString("comment"),
                        rs.getBoolean("published"), rs.getObject("published_at", OffsetDateTime.class)),
                submissionId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("GRADE_NOT_FOUND", "成绩尚未生成", "评审完成后成绩才会生成。");
        }
        GradeView grade = rows.get(0);
        if ("STUDENT".equals(principal.role().name()) && !grade.published()) {
            throw ApiException.notFound("GRADE_NOT_PUBLISHED", "成绩尚未发布", "请等待教师发布成绩。");
        }
        return grade;
    }

    @Transactional(readOnly = true)
    public List<GradeView> grades(UserPrincipal principal, UUID assignmentId) {
        UUID courseId = assignmentCourse(assignmentId);
        Course course = courseService.requireCourse(courseId);
        courseService.requireReadable(course, principal);
        return jdbcTemplate.query(
                "SELECT g.id, g.submission_id, u.display_name AS submitter_name, s.version_no, g.status, " +
                        "g.raw_score, g.late_deduction, g.final_score, g.check_verdict, g.comment, " +
                        "g.published, g.published_at FROM app.grade g " +
                        "JOIN app.submission s ON s.id = g.submission_id " +
                        "JOIN app.user_account u ON u.id = s.submitter_id " +
                        "WHERE s.assignment_id = ? AND s.is_effective = true ORDER BY g.final_score DESC",
                (rs, index) -> new GradeView(
                        rs.getObject("id", UUID.class), rs.getObject("submission_id", UUID.class),
                        rs.getString("submitter_name"), rs.getInt("version_no"), rs.getString("status"),
                        rs.getBigDecimal("raw_score"), rs.getBigDecimal("late_deduction"),
                        rs.getBigDecimal("final_score"), rs.getString("check_verdict"), rs.getString("comment"),
                        rs.getBoolean("published"), rs.getObject("published_at", OffsetDateTime.class)),
                assignmentId);
    }

    @Transactional
    public Map<String, Object> publish(UserPrincipal principal, UUID assignmentId, List<UUID> submissionIds) {
        Course course = courseService.requireCourse(assignmentCourse(assignmentId));
        courseService.requireManage(course, principal);
        int count;
        if (submissionIds == null || submissionIds.isEmpty()) {
            count = jdbcTemplate.update(
                    "UPDATE app.grade g SET status = 'PUBLISHED', published = true, published_at = now(), " +
                            "published_by = ?, row_version = row_version + 1 FROM app.submission s " +
                            "WHERE g.submission_id = s.id AND s.assignment_id = ? AND g.status = 'SUBMITTED'",
                    principal.id(), assignmentId);
            jdbcTemplate.update(
                    "UPDATE app.submission s SET status = 'PUBLISHED', row_version = row_version + 1 " +
                            "FROM app.grade g WHERE g.submission_id = s.id AND s.assignment_id = ? AND g.published = true",
                    assignmentId);
        } else {
            count = 0;
            for (UUID submissionId : submissionIds) {
                count += jdbcTemplate.update(
                        "UPDATE app.grade g SET status = 'PUBLISHED', published = true, published_at = now(), " +
                                "published_by = ?, row_version = row_version + 1 FROM app.submission s " +
                                "WHERE g.submission_id = s.id AND g.submission_id = ? AND s.assignment_id = ? " +
                                "AND g.status = 'SUBMITTED'", principal.id(), submissionId, assignmentId);
                jdbcTemplate.update("UPDATE app.submission s SET status = 'PUBLISHED' WHERE s.id = ? " +
                        "AND s.assignment_id = ? AND EXISTS (SELECT 1 FROM app.grade g " +
                        "WHERE g.submission_id = s.id AND g.published = true)",
                        submissionId, assignmentId);
            }
        }
        auditService.recordUser(principal.id(), "grade.publish", "ASSIGNMENT", assignmentId,
                "SUCCESS", Map.of("publishedCount", count), null);
        return Map.of("assignmentId", assignmentId, "publishedCount", count);
    }

    private SubmissionRef requireSubmissionReadable(UserPrincipal principal, UUID submissionId) {
        SubmissionRef submission = submissionRef(submissionId);
        Course course = courseService.requireCourse(submission.courseId());
        courseService.requireReadable(course, principal);
        return submission;
    }

    private SubmissionRef requireSubmissionManageAccess(UserPrincipal principal, UUID submissionId) {
        SubmissionRef submission = submissionRef(submissionId);
        Course course = courseService.requireCourse(submission.courseId());
        courseService.requireManage(course, principal);
        return submission;
    }

    private SubmissionRef submissionRef(UUID submissionId) {
        List<SubmissionRef> rows = jdbcTemplate.query(
                "SELECT s.id, s.assignment_id, s.submitter_id, a.course_id FROM app.submission s " +
                        "JOIN app.assignment a ON a.id = s.assignment_id WHERE s.id = ?",
                (rs, index) -> new SubmissionRef(rs.getObject("id", UUID.class),
                        rs.getObject("assignment_id", UUID.class), rs.getObject("submitter_id", UUID.class),
                        rs.getObject("course_id", UUID.class)), submissionId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("SUBMISSION_NOT_FOUND", "提交不存在", "提交可能已被删除。");
        }
        return rows.get(0);
    }

    private UUID assignmentCourse(UUID assignmentId) {
        List<UUID> rows = jdbcTemplate.query("SELECT course_id FROM app.assignment WHERE id = ?",
                (rs, index) -> rs.getObject("course_id", UUID.class), assignmentId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("ASSIGNMENT_NOT_FOUND", "作业不存在", "作业可能已被删除。");
        }
        return rows.get(0);
    }

    private BigDecimal lateDeduction(UUID submissionId, BigDecimal rawScore) {
        List<LateInfo> rows = jdbcTemplate.query(
                "SELECT is_late, submitted_at, effective_deadline_at FROM app.submission WHERE id = ?",
                (rs, index) -> new LateInfo(rs.getBoolean("is_late"),
                        rs.getObject("submitted_at", OffsetDateTime.class),
                        rs.getObject("effective_deadline_at", OffsetDateTime.class)),
                submissionId);
        if (rows.isEmpty() || !rows.get(0).late()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        LateInfo info = rows.get(0);
        long secondsLate = Math.max(1, Duration.between(info.deadline(), info.submittedAt()).getSeconds());
        long daysLate = Math.max(1, (secondsLate + 86_399) / 86_400);
        JsonNode rule = systemConfig("submission.late_penalty");
        String type = rule.path("type").asText("DAILY_PERCENT");
        BigDecimal value = BigDecimal.valueOf(rule.path("value").asDouble(0));
        BigDecimal maxDeduction = rule.path("maxDeduction").isNumber()
                ? BigDecimal.valueOf(rule.path("maxDeduction").asDouble())
                : new BigDecimal("100");
        BigDecimal deduction = "DAILY_PERCENT".equals(type)
                ? rawScore.multiply(value).multiply(BigDecimal.valueOf(daysLate))
                        .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)
                : value.multiply(BigDecimal.valueOf(daysLate));
        return deduction.max(BigDecimal.ZERO).min(maxDeduction).setScale(2, RoundingMode.HALF_UP);
    }

    private JsonNode systemConfig(String key) {
        String raw = jdbcTemplate.query(
                "SELECT value::text FROM app.system_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getString(1) : null, key);
        if (raw == null) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode readJson(String value) {
        try {
            return value == null ? objectMapper.createArrayNode() : objectMapper.readTree(value);
        } catch (Exception exception) {
            return objectMapper.createArrayNode();
        }
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw ApiException.unprocessable("REVIEW_JSON_INVALID", "评审数据格式错误", "请检查评分维度与批注内容。");
        }
    }

    private String normalizeVerdict(String verdict) {
        if (verdict == null || verdict.isBlank()) {
            return "ORIGINAL";
        }
        if (!List.of("ORIGINAL", "SUSPECTED_PLAGIARISM", "INTERVIEW_REQUIRED").contains(verdict)) {
            throw ApiException.unprocessable("CHECK_VERDICT_INVALID", "查重结论不合法", "请选择系统提供的结论。");
        }
        return verdict;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record SubmissionRef(UUID submissionId, UUID assignmentId, UUID submitterId, UUID courseId) {
    }

    private record LateInfo(boolean late, OffsetDateTime submittedAt, OffsetDateTime deadline) {
    }
}

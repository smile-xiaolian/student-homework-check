package com.edu.submission.review.application;

import com.edu.submission.audit.AuditService;
import com.edu.submission.course.application.CourseService;
import com.edu.submission.course.domain.Course;
import com.edu.submission.course.domain.CourseMember;
import com.edu.submission.notification.application.NotificationService;
import com.edu.submission.review.adapter.in.dto.BatchScorePreviewItem;
import com.edu.submission.review.adapter.in.dto.BatchScoreRequest;
import com.edu.submission.review.adapter.in.dto.GradeModifyRequest;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {

    private final JdbcTemplate jdbcTemplate;
    private final CourseService courseService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ReviewService(JdbcTemplate jdbcTemplate, CourseService courseService,
                         NotificationService notificationService,
                         AuditService auditService, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.courseService = courseService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ReviewView getReview(UserPrincipal principal, UUID submissionId) {
        SubmissionRef submission = requireSubmissionReadable(principal, submissionId);
        if ("STUDENT".equals(principal.role().name())
                && !submission.submitterId().equals(principal.id())) {
            throw ApiException.forbidden("REVIEW_FORBIDDEN", "无权查看评审", "学生只能查看本人提交的评审结果。");
        }
        // 学生可见：已发布成绩，或作业开启“评审结果学生可见”后的已提交评审（DRAFT 草稿始终不可见）
        boolean resultsVisible = assignmentResultsVisible(submission.assignmentId());
        List<ReviewView> rows = jdbcTemplate.query(
                "SELECT g.id, g.submission_id, g.reviewer_id, g.status, g.raw_score, g.late_deduction, " +
                        "g.final_score, g.dimension_scores::text AS dimension_scores, " +
                        "g.annotations::text AS annotations, g.check_verdict, g.comment, " +
                        "g.submitted_at, g.updated_at, g.row_version, g.feedback_file_id, " +
                        "fo.original_name AS feedback_file_name " +
                        "FROM app.grade g LEFT JOIN app.file_object fo ON fo.id = g.feedback_file_id " +
                        "WHERE g.submission_id = ? AND (g.reviewer_id = ? OR g.status = 'PUBLISHED' " +
                        "OR (g.status = 'SUBMITTED' AND ?)) " +
                        "ORDER BY g.updated_at DESC LIMIT 1",
                (rs, index) -> new ReviewView(
                        rs.getObject("id", UUID.class), rs.getObject("submission_id", UUID.class),
                        rs.getObject("reviewer_id", UUID.class), rs.getString("status"),
                        rs.getBigDecimal("raw_score"), rs.getBigDecimal("late_deduction"),
                        rs.getBigDecimal("final_score"), readJson(rs.getString("dimension_scores")),
                        readJson(rs.getString("annotations")), rs.getString("check_verdict"),
                        rs.getString("comment"), rs.getObject("submitted_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class), rs.getLong("row_version"),
                        rs.getObject("feedback_file_id", UUID.class), rs.getString("feedback_file_name")),
                submissionId, principal.id(), resultsVisible);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        return new ReviewView(null, submissionId, principal.id(), "NOT_STARTED", null,
                BigDecimal.ZERO, null, objectMapper.createArrayNode(), objectMapper.createArrayNode(),
                null, null, null, null, 0L, null, null);
    }

    @Transactional(readOnly = true)
    public PageResult<ReviewQueueItem> reviewQueue(UserPrincipal principal, UUID assignmentId,
                                                   Integer page, Integer size,
                                                   String reviewStatus, String checkLevel, Boolean late,
                                                   String keyword, String sort) {
        UUID courseId = assignmentCourse(assignmentId);
        Course course = courseService.requireCourse(courseId);
        courseService.requireManage(course, principal);
        PageQuery query = PageQuery.of(page, size);

        // 动态筛选：按评审状态 / 查重等级 / 是否迟交 / 姓名或学号 过滤
        StringBuilder where = new StringBuilder(
                " WHERE s.assignment_id = ? AND s.is_effective = true " +
                        "AND s.status NOT IN ('WITHDRAWN','PENDING_SCAN')");
        List<Object> args = new java.util.ArrayList<>();
        args.add(assignmentId);
        if (reviewStatus != null && !reviewStatus.isBlank()) {
            where.append(" AND coalesce(g.status, 'NOT_STARTED') = ?");
            args.add(reviewStatus);
        }
        if (checkLevel != null && !checkLevel.isBlank()) {
            where.append(" AND r.level = ?");
            args.add(checkLevel);
        }
        if (late != null) {
            where.append(" AND s.is_late = ?");
            args.add(late);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (u.display_name ILIKE ? OR u.user_no ILIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }

        // 排序：按前端选中的排序项；未指定时保持严重级置顶的默认顺序
        String orderBy = switch (sort == null ? "" : sort) {
            case "checkScore" -> "ORDER BY r.overall_score DESC NULLS LAST, s.submitted_at ASC";
            case "submitterId" -> "ORDER BY u.user_no ASC, s.submitted_at ASC";
            case "submittedAt" -> "ORDER BY s.submitted_at ASC";
            case "reviewStatus" -> "ORDER BY CASE coalesce(g.status, 'NOT_STARTED') " +
                    "WHEN 'NOT_STARTED' THEN 0 WHEN 'DRAFT' THEN 1 WHEN 'SUBMITTED' THEN 2 " +
                    "WHEN 'PUBLISHED' THEN 3 ELSE 4 END, s.submitted_at ASC";
            default -> "ORDER BY CASE WHEN r.level = 'SEVERE' THEN 0 ELSE 1 END, " +
                    "s.is_late DESC, s.submitted_at ASC";
        };

        List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add(query.offset());
        List<ReviewQueueItem> items = jdbcTemplate.query(
                "SELECT s.id AS submission_id, s.submitter_id, u.display_name, s.version_no, s.submitted_at, " +
                        "s.is_late, r.overall_score AS check_score, r.level AS check_level, " +
                        "coalesce(g.status, 'NOT_STARTED') AS review_status, g.status AS grade_status " +
                        "FROM app.submission s JOIN app.user_account u ON u.id = s.submitter_id " +
                        "LEFT JOIN app.check_report r ON r.submission_id = s.id AND r.is_current = true " +
                        "LEFT JOIN app.grade g ON g.submission_id = s.id " +
                        where + " " + orderBy + " LIMIT ? OFFSET ?",
                (rs, index) -> new ReviewQueueItem(
                        rs.getObject("submission_id", UUID.class), rs.getObject("submitter_id", UUID.class),
                        rs.getString("display_name"), rs.getInt("version_no"),
                        rs.getObject("submitted_at", OffsetDateTime.class), rs.getBoolean("is_late"),
                        rs.getBigDecimal("check_score"), rs.getString("check_level"),
                        rs.getString("review_status"), rs.getString("grade_status")),
                pageArgs.toArray());
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(distinct s.id) FROM app.submission s " +
                        "JOIN app.user_account u ON u.id = s.submitter_id " +
                        "LEFT JOIN app.check_report r ON r.submission_id = s.id AND r.is_current = true " +
                        "LEFT JOIN app.grade g ON g.submission_id = s.id " + where,
                Long.class, args.toArray());
        return new PageResult<>(items, Pagination.of(query.page(), query.size(),
                total == null ? 0 : total));
    }

    @Transactional
    public ReviewView save(UserPrincipal principal, UUID submissionId, ReviewSaveRequest request) {
        SubmissionRef submission = requireSubmissionReviewAccess(principal, submissionId);
        if (request.rawScore() == null) {
            throw ApiException.unprocessable("REVIEW_SCORE_REQUIRED", "请填写评审得分", "原始得分不能为空。");
        }
        String verdict = normalizeVerdict(request.checkVerdict());
        validateFeedbackFile(principal, request.feedbackFileId());
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
                            "late_deduction, final_score, dimension_scores, annotations, check_verdict, comment, " +
                            "feedback_file_id, row_version) " +
                            "VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, 0)",
                    gradeId, submissionId, principal.id(),
                    "ASSISTANT".equals(principal.role().name()) ? "ASSISTANT" : "TEACHER",
                    request.rawScore(), deduction, finalScore, dimensionJson, annotationJson,
                    verdict, blankToNull(request.comment()), request.feedbackFileId());
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
                            "annotations = ?::jsonb, check_verdict = ?, comment = ?, feedback_file_id = ?, " +
                            "row_version = row_version + 1 " +
                            "WHERE submission_id = ? AND row_version = ?",
                    request.rawScore(), deduction, finalScore, dimensionJson, annotationJson, verdict,
                    blankToNull(request.comment()), request.feedbackFileId(), submissionId, version);
        }
        auditService.recordUser(principal.id(), "review.save", "SUBMISSION", submissionId,
                "SUCCESS", Map.of("rawScore", request.rawScore(), "lateDeduction", deduction), null);
        return getReview(principal, submissionId);
    }

    @Transactional
    public ReviewView submit(UserPrincipal principal, UUID submissionId) {
        SubmissionRef submission = requireSubmissionReviewAccess(principal, submissionId);
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
                        "g.published, g.published_at, g.feedback_file_id, fo.original_name AS feedback_file_name, " +
                        "(SELECT count(*) FROM app.grade_modification gm WHERE gm.submission_id = g.submission_id) AS modification_count " +
                        "FROM app.grade g JOIN app.submission s ON s.id = g.submission_id " +
                        "JOIN app.user_account u ON u.id = s.submitter_id " +
                        "LEFT JOIN app.file_object fo ON fo.id = g.feedback_file_id " +
                        "WHERE g.submission_id = ?",
                (rs, index) -> new GradeView(
                        rs.getObject("id", UUID.class), rs.getObject("submission_id", UUID.class),
                        rs.getString("submitter_name"), rs.getInt("version_no"), rs.getString("status"),
                        rs.getBigDecimal("raw_score"), rs.getBigDecimal("late_deduction"),
                        rs.getBigDecimal("final_score"), rs.getString("check_verdict"), rs.getString("comment"),
                        rs.getBoolean("published"), rs.getObject("published_at", OffsetDateTime.class),
                        rs.getLong("modification_count"), rs.getObject("feedback_file_id", UUID.class),
                        rs.getString("feedback_file_name")),
                submissionId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("GRADE_NOT_FOUND", "成绩尚未生成", "评审完成后成绩才会生成。");
        }
        GradeView grade = rows.get(0);
        if ("STUDENT".equals(principal.role().name()) && !grade.published()
                && !(assignmentResultsVisible(submission.assignmentId())
                        && !"DRAFT".equals(grade.status()))) {
            throw ApiException.notFound("GRADE_NOT_PUBLISHED", "成绩尚未开放查看",
                    "请等待教师开放评审结果查看。");
        }
        return grade;
    }

    @Transactional(readOnly = true)
    public List<GradeView> grades(UserPrincipal principal, UUID assignmentId) {
        UUID courseId = assignmentCourse(assignmentId);
        Course course = courseService.requireCourse(courseId);
        courseService.requireManage(course, principal);
        return jdbcTemplate.query(
                "SELECT g.id, g.submission_id, u.display_name AS submitter_name, s.version_no, g.status, " +
                        "g.raw_score, g.late_deduction, g.final_score, g.check_verdict, g.comment, " +
                        "g.published, g.published_at, g.feedback_file_id, fo.original_name AS feedback_file_name, " +
                        "(SELECT count(*) FROM app.grade_modification gm WHERE gm.submission_id = g.submission_id) AS modification_count " +
                        "FROM app.grade g " +
                        "JOIN app.submission s ON s.id = g.submission_id " +
                        "JOIN app.user_account u ON u.id = s.submitter_id " +
                        "LEFT JOIN app.file_object fo ON fo.id = g.feedback_file_id " +
                        "WHERE s.assignment_id = ? AND s.is_effective = true ORDER BY g.final_score DESC",
                (rs, index) -> new GradeView(
                        rs.getObject("id", UUID.class), rs.getObject("submission_id", UUID.class),
                        rs.getString("submitter_name"), rs.getInt("version_no"), rs.getString("status"),
                        rs.getBigDecimal("raw_score"), rs.getBigDecimal("late_deduction"),
                        rs.getBigDecimal("final_score"), rs.getString("check_verdict"), rs.getString("comment"),
                        rs.getBoolean("published"), rs.getObject("published_at", OffsetDateTime.class),
                        rs.getLong("modification_count"), rs.getObject("feedback_file_id", UUID.class),
                        rs.getString("feedback_file_name")),
                assignmentId);
    }

    @Transactional
    public Map<String, Object> publish(UserPrincipal principal, UUID assignmentId, List<UUID> submissionIds) {
        Course course = courseService.requireCourse(assignmentCourse(assignmentId));
        courseService.requireManage(course, principal);
        // 先查出本次将被发布（本次新发布）的提交：submissionId -> submitterId，用于发布后通知
        List<PublishTarget> targets = new java.util.ArrayList<>();
        if (submissionIds == null || submissionIds.isEmpty()) {
            targets.addAll(jdbcTemplate.query(
                    "SELECT s.id AS submission_id, s.submitter_id, a.title, g.final_score " +
                            "FROM app.grade g JOIN app.submission s ON s.id = g.submission_id " +
                            "JOIN app.assignment a ON a.id = s.assignment_id " +
                            "WHERE s.assignment_id = ? AND g.status = 'SUBMITTED'",
                    (rs, index) -> new PublishTarget(rs.getObject("submission_id", UUID.class),
                            rs.getObject("submitter_id", UUID.class), rs.getString("title"),
                            rs.getBigDecimal("final_score")),
                    assignmentId));
        } else {
            for (UUID submissionId : submissionIds) {
                targets.addAll(jdbcTemplate.query(
                        "SELECT s.id AS submission_id, s.submitter_id, a.title, g.final_score " +
                                "FROM app.grade g JOIN app.submission s ON s.id = g.submission_id " +
                                "JOIN app.assignment a ON a.id = s.assignment_id " +
                                "WHERE s.id = ? AND s.assignment_id = ? AND g.status = 'SUBMITTED'",
                        (rs, index) -> new PublishTarget(rs.getObject("submission_id", UUID.class),
                                rs.getObject("submitter_id", UUID.class), rs.getString("title"),
                                rs.getBigDecimal("final_score")),
                        submissionId, assignmentId));
            }
        }
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
        // 成绩发布通知：仅通知本次新发布的提交，向提交人推送；目标指向作业，学生可跳转查看成绩
        for (PublishTarget target : targets) {
            notificationService.notifyUsers(List.of(target.submitterId()), "GRADE_PUBLISHED",
                    "成绩已发布",
                    "作业《" + target.title() + "》成绩已发布，最终得分：" + target.finalScore() + " 分。",
                    "ASSIGNMENT", assignmentId);
        }
        auditService.recordUser(principal.id(), "grade.publish", "ASSIGNMENT", assignmentId,
                "SUCCESS", Map.of("publishedCount", count, "notifiedCount", targets.size()), null);
        return Map.of("assignmentId", assignmentId, "publishedCount", count);
    }

    /**
     * 成绩更正：仅 SUBMITTED / PUBLISHED 状态可更正；修改前值写入 grade_modification 留痕；
     * 若原状态为 PUBLISHED，更正后重新向提交人推送“成绩已更新”通知。
     */
    @Transactional
    public GradeView modifyGrade(UserPrincipal principal, UUID submissionId, GradeModifyRequest request) {
        SubmissionRef submission = requireSubmissionManageAccess(principal, submissionId);
        List<Map<String, Object>> gradeRows = jdbcTemplate.queryForList(
                "SELECT id, status, raw_score, late_deduction, final_score, check_verdict, comment, " +
                        "feedback_file_id, row_version FROM app.grade WHERE submission_id = ?", submissionId);
        if (gradeRows.isEmpty()) {
            throw ApiException.notFound("GRADE_NOT_FOUND", "成绩尚未生成", "评审完成后成绩才会生成。");
        }
        Map<String, Object> grade = gradeRows.get(0);
        String status = (String) grade.get("status");
        if (!List.of("SUBMITTED", "PUBLISHED").contains(status)) {
            throw ApiException.conflict("GRADE_NOT_MODIFIABLE", "成绩不可更正",
                    "仅已提交或已发布的成绩可以更正。");
        }
        if (request.rawScore() == null) {
            throw ApiException.unprocessable("GRADE_MODIFY_SCORE_REQUIRED", "请填写更正后的得分",
                    "更正后的原始得分不能为空。");
        }
        if (request.rawScore().compareTo(BigDecimal.ZERO) < 0
                || request.rawScore().compareTo(new BigDecimal("100")) > 0) {
            throw ApiException.unprocessable("GRADE_MODIFY_SCORE_INVALID", "得分不合法",
                    "得分必须在 0-100 之间。");
        }
        String reason = request.reason() == null ? null : request.reason().trim();
        if (reason == null || reason.length() < 8) {
            throw ApiException.unprocessable("GRADE_MODIFY_REASON_REQUIRED", "请填写更正原因",
                    "更正原因不能少于 8 个字符。");
        }
        String verdict = normalizeVerdict(request.checkVerdict());
        validateFeedbackFile(principal, request.feedbackFileId());
        long version = ((Number) grade.get("row_version")).longValue();
        if (request.expectedRowVersion() != null && request.expectedRowVersion() != version) {
            throw ApiException.conflict("GRADE_VERSION_CONFLICT", "成绩已被更新",
                    "请刷新后重试。");
        }
        BigDecimal deduction = lateDeduction(submissionId, request.rawScore());
        BigDecimal finalScore = request.rawScore().subtract(deduction).max(BigDecimal.ZERO);
        UUID gradeId = (UUID) grade.get("id");
        String beforeComment = (String) grade.get("comment");
        String afterComment = blankToNull(request.comment());
        jdbcTemplate.update(
                "INSERT INTO app.grade_modification (id, grade_id, submission_id, assignment_id, modified_by, " +
                        "before_raw_score, before_final_score, after_raw_score, after_final_score, " +
                        "before_check_verdict, after_check_verdict, before_comment, after_comment, reason) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Ids.newUuidV7(), gradeId, submissionId, submission.assignmentId(), principal.id(),
                grade.get("raw_score"), grade.get("final_score"), request.rawScore(), finalScore,
                grade.get("check_verdict"), verdict, beforeComment, afterComment, reason);
        jdbcTemplate.update(
                "UPDATE app.grade SET raw_score = ?, late_deduction = ?, final_score = ?, " +
                        "check_verdict = ?, comment = ?, feedback_file_id = ?, row_version = row_version + 1 " +
                        "WHERE submission_id = ? AND row_version = ?",
                request.rawScore(), deduction, finalScore, verdict, afterComment,
                request.feedbackFileId(), submissionId, version);
        if ("PUBLISHED".equals(status)) {
            String title = assignmentTitle(submission.assignmentId());
            notificationService.notifyUsers(List.of(submission.submitterId()), "GRADE_PUBLISHED",
                    "成绩已更新",
                    "作业《" + title + "》成绩已更新，最终得分：" + finalScore + " 分。",
                    "ASSIGNMENT", submission.assignmentId());
        }
        auditService.recordUser(principal.id(), "grade.modify", "SUBMISSION", submissionId, "SUCCESS",
                Map.of("reason", reason,
                        "beforeRaw", grade.get("raw_score"), "afterRaw", request.rawScore(),
                        "beforeFinal", grade.get("final_score"), "afterFinal", finalScore,
                        "wasPublished", "PUBLISHED".equals(status)), null);
        return grade(principal, submissionId);
    }

    /**
     * 批量打分预览（FR-R-06）：计算每个提交的目标分值，不落库。
     * ALL_MAX = 量规总分（无则 100）；BY_CHECK_LEVEL = PASS 90 / WARNING 75 / SEVERE 60；
     * FIXED = fixedScore。
     */
    @Transactional(readOnly = true)
    public List<BatchScorePreviewItem> batchScorePreview(UserPrincipal principal, UUID assignmentId,
                                                         BatchScoreRequest request) {
        courseService.requireManage(courseService.requireCourse(assignmentCourse(assignmentId)), principal);
        return prepareBatchItems(assignmentId, request).stream()
                .map(item -> new BatchScorePreviewItem(item.submissionId(), item.submitterName(),
                        item.checkLevel(), item.before(), item.after()))
                .toList();
    }

    /**
     * 批量打分执行（FR-R-06）：仅 status IN ('SUBMITTED','PUBLISHED') 的成绩可改；
     * 每次修改前值写入 grade_modification（reason）；返回 {affected, updated, previews}。
     */
    @Transactional
    public Map<String, Object> batchScore(UserPrincipal principal, UUID assignmentId,
                                          BatchScoreRequest request) {
        courseService.requireManage(courseService.requireCourse(assignmentCourse(assignmentId)), principal);
        List<PreparedBatchItem> prepared = prepareBatchItems(assignmentId, request);
        String reason = request.reason().trim();
        int affected = 0;
        int updated = 0;
        List<BatchScorePreviewItem> previews = new ArrayList<>(prepared.size());
        for (PreparedBatchItem item : prepared) {
            previews.add(new BatchScorePreviewItem(item.submissionId(), item.submitterName(),
                    item.checkLevel(), item.before(), item.after()));
            List<Map<String, Object>> grades = jdbcTemplate.queryForList(
                    "SELECT id, status, raw_score, late_deduction, final_score, row_version " +
                            "FROM app.grade WHERE submission_id = ?", item.submissionId());
            if (grades.isEmpty()) {
                continue;
            }
            Map<String, Object> grade = grades.get(0);
            String status = (String) grade.get("status");
            if (!List.of("SUBMITTED", "PUBLISHED").contains(status)) {
                continue;
            }
            affected++;
            BigDecimal deduction = lateDeduction(item.submissionId(), item.after());
            BigDecimal finalScore = item.after().subtract(deduction).max(BigDecimal.ZERO);
            int changed = jdbcTemplate.update(
                    "UPDATE app.grade SET raw_score = ?, late_deduction = ?, final_score = ?, " +
                            "row_version = row_version + 1 " +
                            "WHERE submission_id = ? AND status IN ('SUBMITTED','PUBLISHED')",
                    item.after(), deduction, finalScore, item.submissionId());
            if (changed > 0) {
                updated++;
                jdbcTemplate.update(
                        "INSERT INTO app.grade_modification (id, grade_id, submission_id, assignment_id, " +
                                "modified_by, before_raw_score, before_final_score, after_raw_score, " +
                                "after_final_score, before_check_verdict, after_check_verdict, " +
                                "before_comment, after_comment, reason) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, ?)",
                        Ids.newUuidV7(), grade.get("id"), item.submissionId(), assignmentId, principal.id(),
                        grade.get("raw_score"), grade.get("final_score"), item.after(), finalScore, reason);
            }
        }
        auditService.recordUser(principal.id(), "grade.batch_score", "ASSIGNMENT", assignmentId,
                "SUCCESS", Map.of("affected", affected, "updated", updated,
                        "previewed", previews.size(), "rule", request.rule()), null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("affected", affected);
        result.put("updated", updated);
        result.put("previews", previews);
        return result;
    }

    /** 校验批量打分请求并计算每个提交的目标分值（不落库）。 */
    private List<PreparedBatchItem> prepareBatchItems(UUID assignmentId, BatchScoreRequest request) {
        List<UUID> ids = request.submissionIds();
        if (ids == null || ids.isEmpty()) {
            throw ApiException.unprocessable("BATCH_SUBMISSIONS_REQUIRED", "请选择要打分的提交",
                    "至少选择一个提交。");
        }
        if (ids.size() > 200) {
            throw ApiException.unprocessable("BATCH_TOO_MANY", "批量打分数量过多",
                    "单次最多对 200 个提交打分。");
        }
        String rule = request.rule();
        if (rule == null || !Set.of("ALL_MAX", "BY_CHECK_LEVEL", "FIXED").contains(rule)) {
            throw ApiException.unprocessable("BATCH_RULE_INVALID", "打分规则不合法",
                    "请选择系统提供的打分规则。");
        }
        BigDecimal fixedScore = request.fixedScore();
        if ("FIXED".equals(rule)) {
            if (fixedScore == null) {
                throw ApiException.unprocessable("BATCH_FIXED_SCORE_REQUIRED", "请填写固定分值",
                        "固定分值不能为空。");
            }
            if (fixedScore.compareTo(BigDecimal.ZERO) < 0
                    || fixedScore.compareTo(new BigDecimal("100")) > 0) {
                throw ApiException.unprocessable("BATCH_FIXED_SCORE_INVALID", "固定分值不合法",
                        "固定分值必须在 0-100 之间。");
            }
        }
        String reason = request.reason() == null ? null : request.reason().trim();
        if (reason == null || reason.length() < 8) {
            throw ApiException.unprocessable("BATCH_REASON_REQUIRED", "请填写打分原因",
                    "打分原因不能少于 8 个字符。");
        }
        BigDecimal allMax = allMaxScore(assignmentId);
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> arguments = new ArrayList<>(ids.size() + 1);
        arguments.add(assignmentId);
        arguments.addAll(ids);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.id AS submission_id, u.display_name AS submitter_name, " +
                        "r.level AS check_level, g.final_score AS before_score " +
                        "FROM app.submission s JOIN app.user_account u ON u.id = s.submitter_id " +
                        "LEFT JOIN app.check_report r ON r.submission_id = s.id AND r.is_current = true " +
                        "LEFT JOIN app.grade g ON g.submission_id = s.id " +
                        "WHERE s.assignment_id = ? AND s.id IN (" + placeholders + ")",
                arguments.toArray());
        Map<UUID, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byId.put((UUID) row.get("submission_id"), row);
        }
        List<PreparedBatchItem> items = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Map<String, Object> row = byId.get(id);
            if (row == null) {
                continue;
            }
            String submitterName = (String) row.get("submitter_name");
            String checkLevel = (String) row.get("check_level");
            BigDecimal before = (BigDecimal) row.get("before_score");
            BigDecimal after = computeAfter(rule, fixedScore, allMax, checkLevel);
            items.add(new PreparedBatchItem(id, submitterName, checkLevel, before, after));
        }
        return items;
    }

    private BigDecimal computeAfter(String rule, BigDecimal fixedScore, BigDecimal allMax,
                                    String checkLevel) {
        return switch (rule) {
            case "ALL_MAX" -> allMax;
            case "BY_CHECK_LEVEL" -> switch (checkLevel == null ? "UNKNOWN" : checkLevel) {
                case "PASS" -> new BigDecimal("90");
                case "SEVERE" -> new BigDecimal("60");
                // WARNING / UNKNOWN / 无报告（NULL）默认取 75
                default -> new BigDecimal("75");
            };
            case "FIXED" -> fixedScore;
            default -> throw new IllegalStateException("unreachable rule: " + rule);
        };
    }

    /** ALL_MAX 目标分：量规总分（rubric.totalScore），未配置量规则 100。 */
    private BigDecimal allMaxScore(UUID assignmentId) {
        String rubric = jdbcTemplate.query(
                "SELECT rubric_definition::text FROM app.assignment WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, assignmentId);
        if (rubric != null && !rubric.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(rubric);
                double total = node.path("totalScore").asDouble(0);
                if (total > 0) {
                    return BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP);
                }
            } catch (Exception exception) {
                // 量规解析失败则回退 100
            }
        }
        return new BigDecimal("100");
    }

    private String assignmentTitle(UUID assignmentId) {
        List<String> titles = jdbcTemplate.query(
                "SELECT title FROM app.assignment WHERE id = ?",
                (rs, index) -> rs.getString("title"), assignmentId);
        return titles.isEmpty() ? "作业" : titles.get(0);
    }

    /** 反馈附件校验：若指定 feedbackFileId，要求该 file_object 存在且属于当前评审人。 */
    private void validateFeedbackFile(UserPrincipal principal, UUID feedbackFileId) {
        if (feedbackFileId == null) {
            return;
        }
        List<UUID> owners = jdbcTemplate.query(
                "SELECT created_by FROM app.file_object WHERE id = ?",
                (rs, index) -> rs.getObject("created_by", UUID.class), feedbackFileId);
        if (owners.isEmpty() || !principal.id().equals(owners.get(0))) {
            throw ApiException.unprocessable("FEEDBACK_FILE_INVALID", "反馈附件无效",
                    "反馈附件不存在或不属于当前账号。");
        }
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

    /**
     * 评审起草/提交访问控制：允许 ADMIN、课程 owner、INSTRUCTOR 成员，
     * 以及该课程 member_role='ASSISTANT' 且 status='ACTIVE' 的成员（助教可评审，不可发布成绩）。
     */
    private SubmissionRef requireSubmissionReviewAccess(UserPrincipal principal, UUID submissionId) {
        SubmissionRef submission = submissionRef(submissionId);
        Course course = courseService.requireCourse(submission.courseId());
        if (principal.role().name().equals("ADMIN") || course.getOwnerId().equals(principal.id())) {
            return submission;
        }
        CourseMember member = courseService.findMembership(course.getId(), principal.id());
        if (member == null || !"ACTIVE".equals(member.getStatus())
                || (!"INSTRUCTOR".equals(member.getMemberRole()) && !"ASSISTANT".equals(member.getMemberRole()))) {
            throw ApiException.forbidden("COURSE_MANAGE_FORBIDDEN", "无权管理课程",
                    "只有课程负责人、授课教师或助教可以执行此操作。");
        }
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

    private boolean assignmentResultsVisible(UUID assignmentId) {
        List<Boolean> rows = jdbcTemplate.query(
                "SELECT results_visible FROM app.assignment WHERE id = ?",
                (rs, index) -> rs.getBoolean("results_visible"), assignmentId);
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
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

    private record PublishTarget(UUID submissionId, UUID submitterId, String title, BigDecimal finalScore) {
    }

    private record PreparedBatchItem(UUID submissionId, String submitterName, String checkLevel,
                                     BigDecimal before, BigDecimal after) {
    }
}

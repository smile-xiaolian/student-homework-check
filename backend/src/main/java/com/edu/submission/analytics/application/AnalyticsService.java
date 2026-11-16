package com.edu.submission.analytics.application;

import com.edu.submission.analytics.adapter.in.dto.SubmissionProgressView;
import com.edu.submission.course.application.CourseService;
import com.edu.submission.course.domain.Course;
import com.edu.submission.shared.api.ApiException;
import com.edu.submission.shared.security.UserPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsService {

    private final JdbcTemplate jdbcTemplate;
    private final CourseService courseService;

    public AnalyticsService(JdbcTemplate jdbcTemplate, CourseService courseService) {
        this.jdbcTemplate = jdbcTemplate;
        this.courseService = courseService;
    }

    @Transactional(readOnly = true)
    public SubmissionProgressView submissionProgress(UserPrincipal principal, UUID assignmentId) {
        List<AssignmentRef> assignments = jdbcTemplate.query(
                "SELECT id, title, course_id FROM app.assignment WHERE id = ?",
                (rs, index) -> new AssignmentRef(rs.getObject("id", UUID.class),
                        rs.getString("title"), rs.getObject("course_id", UUID.class)), assignmentId);
        if (assignments.isEmpty()) {
            throw ApiException.notFound("ASSIGNMENT_NOT_FOUND", "作业不存在", "作业可能已被删除。");
        }
        AssignmentRef assignment = assignments.get(0);
        Course course = courseService.requireCourse(assignment.courseId());
        courseService.requireManage(course, principal);

        Counts counts = jdbcTemplate.queryForObject(
                "SELECT " +
                        "(SELECT count(*) FROM app.course_member cm WHERE cm.course_id = a.course_id " +
                        " AND cm.member_role = 'STUDENT' AND cm.status = 'ACTIVE') AS member_count, " +
                        "(SELECT count(*) FROM app.submission s WHERE s.assignment_id = a.id " +
                        " AND s.is_effective = true) AS submitted_count, " +
                        "(SELECT count(*) FROM app.submission s WHERE s.assignment_id = a.id " +
                        " AND s.is_effective = true AND s.is_late = true) AS late_count, " +
                        "(SELECT count(*) FROM app.submission s WHERE s.assignment_id = a.id " +
                        " AND s.is_effective = true AND s.status IN ('PENDING_SCAN','CHECKING')) AS checking_count, " +
                        "(SELECT count(*) FROM app.submission s WHERE s.assignment_id = a.id " +
                        " AND s.is_effective = true AND s.status IN " +
                        " ('CHECK_COMPLETED','REVIEWED','PUBLISHED')) AS completed_count " +
                        "FROM app.assignment a WHERE a.id = ?",
                (rs, index) -> new Counts(rs.getLong("member_count"), rs.getLong("submitted_count"),
                        rs.getLong("late_count"), rs.getLong("checking_count"),
                        rs.getLong("completed_count")),
                assignmentId);
        Counts value = counts == null ? new Counts(0, 0, 0, 0, 0) : counts;
        long unsubmitted = Math.max(0, value.members() - value.submitted());
        double rate = value.members() == 0 ? 0
                : Math.round(value.submitted() * 10000.0 / value.members()) / 100.0;
        return new SubmissionProgressView(assignment.id(), assignment.title(), value.members(),
                value.submitted(), value.late(), value.checking(), value.completed(),
                unsubmitted, rate);
    }

    /** 成绩分布：统计生效版本且已提交/已发布成绩的 final_score，按固定分段输出。 */
    @Transactional(readOnly = true)
    public Map<String, Object> scoreDistribution(UserPrincipal principal, UUID assignmentId) {
        List<UUID> courseIds = jdbcTemplate.query(
                "SELECT course_id FROM app.assignment WHERE id = ?",
                (rs, index) -> rs.getObject("course_id", UUID.class), assignmentId);
        if (courseIds.isEmpty()) {
            throw ApiException.notFound("ASSIGNMENT_NOT_FOUND", "作业不存在", "作业可能已被删除。");
        }
        Course course = courseService.requireCourse(courseIds.get(0));
        courseService.requireManage(course, principal);

        List<BigDecimal> scores = jdbcTemplate.query(
                "SELECT g.final_score FROM app.grade g JOIN app.submission s ON s.id = g.submission_id " +
                        "WHERE s.assignment_id = ? AND s.is_effective = true " +
                        "AND g.status IN ('SUBMITTED','PUBLISHED')",
                (rs, index) -> rs.getBigDecimal("final_score"), assignmentId);

        int[] mins = {0, 60, 70, 80, 90};
        int[] maxes = {59, 69, 79, 89, 100};
        String[] labels = {"0-59", "60-69", "70-79", "80-89", "90-100"};
        List<Map<String, Object>> bands = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            int min = mins[i];
            int nextMin = i == labels.length - 1 ? 101 : maxes[i] + 1;
            long bandCount = scores.stream()
                    .filter(score -> score.doubleValue() >= min && score.doubleValue() < nextMin)
                    .count();
            Map<String, Object> band = new LinkedHashMap<>();
            band.put("label", labels[i]);
            band.put("min", min);
            band.put("max", maxes[i]);
            band.put("count", bandCount);
            bands.add(band);
        }

        List<BigDecimal> sorted = scores.stream().sorted().toList();
        int count = sorted.size();
        BigDecimal mean = count == 0 ? BigDecimal.ZERO
                : scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        BigDecimal median;
        if (count == 0) {
            median = BigDecimal.ZERO;
        } else if (count % 2 == 1) {
            median = sorted.get(count / 2);
        } else {
            median = sorted.get(count / 2 - 1).add(sorted.get(count / 2))
                    .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        }
        BigDecimal min = count == 0 ? BigDecimal.ZERO : sorted.get(0);
        BigDecimal max = count == 0 ? BigDecimal.ZERO : sorted.get(count - 1);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assignmentId", assignmentId);
        result.put("bands", bands);
        result.put("mean", mean);
        result.put("median", median);
        result.put("min", min);
        result.put("max", max);
        result.put("count", count);
        return result;
    }

    /** 查重分布：按当前报告 level 统计 PASS/WARNING/SEVERE/UNKNOWN，并列出 SEVERE 高危名单。 */
    @Transactional(readOnly = true)
    public Map<String, Object> checkDistribution(UserPrincipal principal, UUID assignmentId) {
        List<UUID> courseIds = jdbcTemplate.query(
                "SELECT course_id FROM app.assignment WHERE id = ?",
                (rs, index) -> rs.getObject("course_id", UUID.class), assignmentId);
        if (courseIds.isEmpty()) {
            throw ApiException.notFound("ASSIGNMENT_NOT_FOUND", "作业不存在", "作业可能已被删除。");
        }
        Course course = courseService.requireCourse(courseIds.get(0));
        courseService.requireManage(course, principal);

        Map<String, Long> counts = jdbcTemplate.queryForObject(
                "SELECT " +
                        "count(*) FILTER (WHERE r.level = 'PASS') AS pass_count, " +
                        "count(*) FILTER (WHERE r.level = 'WARNING') AS warning_count, " +
                        "count(*) FILTER (WHERE r.level = 'SEVERE') AS severe_count, " +
                        "count(*) FILTER (WHERE r.level IS NULL OR r.level NOT IN ('PASS','WARNING','SEVERE')) AS unknown_count " +
                        "FROM app.submission s " +
                        "LEFT JOIN app.check_report r ON r.submission_id = s.id AND r.is_current = true " +
                        "WHERE s.assignment_id = ? AND s.is_effective = true " +
                        "AND s.status NOT IN ('WITHDRAWN','PENDING_SCAN')",
                (rs, index) -> Map.of(
                        "pass", rs.getLong("pass_count"),
                        "warning", rs.getLong("warning_count"),
                        "severe", rs.getLong("severe_count"),
                        "unknown", rs.getLong("unknown_count")),
                assignmentId);
        Map<String, Long> value = counts == null
                ? Map.of("pass", 0L, "warning", 0L, "severe", 0L, "unknown", 0L) : counts;

        List<Map<String, Object>> highRisk = jdbcTemplate.query(
                "SELECT s.id AS submission_id, u.display_name AS submitter_name, s.version_no, " +
                        "r.overall_score, r.level " +
                        "FROM app.submission s " +
                        "JOIN app.user_account u ON u.id = s.submitter_id " +
                        "JOIN app.check_report r ON r.submission_id = s.id AND r.is_current = true " +
                        "WHERE s.assignment_id = ? AND s.is_effective = true " +
                        "AND s.status NOT IN ('WITHDRAWN','PENDING_SCAN') AND r.level = 'SEVERE' " +
                        "ORDER BY r.overall_score DESC LIMIT 20",
                (rs, index) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("submissionId", rs.getObject("submission_id"));
                    row.put("submitterName", rs.getString("submitter_name"));
                    row.put("versionNo", rs.getInt("version_no"));
                    row.put("overallScore", rs.getBigDecimal("overall_score"));
                    row.put("level", rs.getString("level"));
                    return row;
                }, assignmentId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assignmentId", assignmentId);
        result.put("passCount", value.get("pass"));
        result.put("warningCount", value.get("warning"));
        result.put("severeCount", value.get("severe"));
        result.put("unknownCount", value.get("unknown"));
        result.put("highRisk", highRisk);
        return result;
    }

    private record AssignmentRef(UUID id, String title, UUID courseId) {
    }

    private record Counts(long members, long submitted, long late, long checking, long completed) {
    }
}

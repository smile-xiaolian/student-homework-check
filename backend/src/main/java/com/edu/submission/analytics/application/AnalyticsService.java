package com.edu.submission.analytics.application;

import com.edu.submission.analytics.adapter.in.dto.SubmissionProgressView;
import com.edu.submission.course.application.CourseService;
import com.edu.submission.course.domain.Course;
import com.edu.submission.shared.api.ApiException;
import com.edu.submission.shared.security.UserPrincipal;
import java.util.List;
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

    private record AssignmentRef(UUID id, String title, UUID courseId) {
    }

    private record Counts(long members, long submitted, long late, long checking, long completed) {
    }
}

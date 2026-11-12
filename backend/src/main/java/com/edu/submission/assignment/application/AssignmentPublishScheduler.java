package com.edu.submission.assignment.application;

import com.edu.submission.audit.AuditService;
import com.edu.submission.course.application.CourseService;
import com.edu.submission.course.domain.Course;
import com.edu.submission.notification.application.NotificationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 定时发布调度器：周期扫描到达定时发布时间的草稿作业并自动发布。
 * 使用条件 UPDATE（status='DRAFT' AND scheduled_publish_at IS NOT NULL）做 CAS，
 * 避免与教师手动发布并发时重复发布、重复通知。
 */
@Component
public class AssignmentPublishScheduler {

    private static final Logger log = LoggerFactory.getLogger(AssignmentPublishScheduler.class);

    private final JdbcTemplate jdbcTemplate;
    private final CourseService courseService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    public AssignmentPublishScheduler(JdbcTemplate jdbcTemplate,
                                      CourseService courseService,
                                      NotificationService notificationService,
                                      AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.courseService = courseService;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    @Transactional
    public void publishDueAssignments() {
        List<UUID> due = jdbcTemplate.query(
                "SELECT id FROM app.assignment " +
                        "WHERE status = 'DRAFT' AND scheduled_publish_at IS NOT NULL AND scheduled_publish_at <= now()",
                (rs, index) -> rs.getObject("id", UUID.class));
        for (UUID assignmentId : due) {
            try {
                publishOne(assignmentId);
            } catch (RuntimeException exception) {
                log.error("scheduled publish failed for assignment {}", assignmentId, exception);
            }
        }
    }

    private void publishOne(UUID assignmentId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT course_id, title FROM app.assignment WHERE id = ?", assignmentId);
        if (rows.isEmpty()) {
            return;
        }
        UUID courseId = (UUID) rows.get(0).get("course_id");
        String title = (String) rows.get(0).get("title");
        Course course = courseService.requireCourse(courseId);
        if (!"ACTIVE".equals(course.getStatus())) {
            // 课程已归档等永久性不可发布场景：取消定时，避免无限重试
            jdbcTemplate.update(
                    "UPDATE app.assignment SET scheduled_publish_at = NULL, row_version = row_version + 1 " +
                            "WHERE id = ? AND status = 'DRAFT'", assignmentId);
            auditService.record(null, "SYSTEM", "assignment.publish.scheduled", "ASSIGNMENT",
                    assignmentId, "DENIED", Map.of("reason", "course_not_active"), null, null);
            log.warn("cancelled scheduled publish for assignment {} because course {} is not active",
                    assignmentId, courseId);
            return;
        }
        int updated = jdbcTemplate.update(
                "UPDATE app.assignment SET status = 'PUBLISHED', publish_at = now(), " +
                        "scheduled_publish_at = NULL, row_version = row_version + 1 " +
                        "WHERE id = ? AND status = 'DRAFT' AND scheduled_publish_at IS NOT NULL",
                assignmentId);
        if (updated == 1) {
            notificationService.notifyCourseStudents(course.getId(), "ASSIGNMENT_PUBLISHED",
                    "新作业已发布", "课程《" + course.getName() + "》发布了作业：" + title,
                    "ASSIGNMENT", assignmentId);
            auditService.record(null, "SYSTEM", "assignment.publish.scheduled", "ASSIGNMENT",
                    assignmentId, "SUCCESS", Map.of(), null, null);
            log.info("assignment {} published by scheduler", assignmentId);
        }
    }
}

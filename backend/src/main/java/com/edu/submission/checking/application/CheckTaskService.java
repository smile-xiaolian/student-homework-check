package com.edu.submission.checking.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edu.submission.assignment.domain.Assignment;
import com.edu.submission.audit.AuditService;
import com.edu.submission.checking.adapter.out.CheckTaskMapper;
import com.edu.submission.checking.domain.CheckTask;
import com.edu.submission.course.application.CourseService;
import com.edu.submission.course.domain.Course;
import com.edu.submission.shared.api.ApiException;
import com.edu.submission.shared.crypto.Digests;
import com.edu.submission.shared.ids.Ids;
import com.edu.submission.shared.security.UserPrincipal;
import com.edu.submission.submission.domain.Submission;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckTaskService {

    private final CheckTaskMapper checkTaskMapper;
    private final JdbcTemplate jdbcTemplate;
    private final CourseService courseService;
    private final AuditService auditService;

    public CheckTaskService(CheckTaskMapper checkTaskMapper, JdbcTemplate jdbcTemplate,
                            CourseService courseService, AuditService auditService) {
        this.checkTaskMapper = checkTaskMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.courseService = courseService;
        this.auditService = auditService;
    }

    @Transactional
    public CheckTask enqueueFormal(Submission submission, Assignment assignment, UUID actorId,
                                   UUID inputFileObjectId, String inputTextDigest) {
        String key = "FORMAL:" + submission.getId();
        CheckTask existing = findByIdempotencyKey(key);
        if (existing != null) {
            return existing;
        }
        String config = assignment.getCheckConfig() == null || assignment.getCheckConfig().isBlank()
                ? "{}" : assignment.getCheckConfig();
        CheckTask task = new CheckTask();
        task.setId(Ids.newUuidV7());
        task.setSubjectType("SUBMISSION");
        task.setSubmissionId(submission.getId());
        task.setAssignmentId(assignment.getId());
        task.setTaskType("FORMAL");
        task.setStatus("QUEUED");
        task.setTaskVersion(0);
        task.setAttempt(0);
        task.setMaxAttempts(3);
        task.setIdempotencyKey(key);
        task.setConfigSnapshot(config);
        task.setConfigDigest(Digests.sha256Hex(config));
        task.setInputFileObjectId(inputFileObjectId);
        task.setInputTextDigest(inputTextDigest);
        task.setLibraryWatermarks("{}");
        task.setScheduledAt(OffsetDateTime.now(ZoneOffset.UTC));
        task.setCreatedBy(actorId);
        try {
            checkTaskMapper.insert(task);
            return task;
        } catch (DuplicateKeyException exception) {
            CheckTask duplicate = findByIdempotencyKey(key);
            if (duplicate != null) {
                return duplicate;
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public CheckTask findLatestForSubmission(UUID submissionId) {
        return checkTaskMapper.findLatestBySubmission(submissionId);
    }

    @Transactional(readOnly = true)
    public long queueLength() {
        return checkTaskMapper.selectCount(new LambdaQueryWrapper<CheckTask>()
                .eq(CheckTask::getStatus, "QUEUED"));
    }

    @Transactional(readOnly = true)
    public long runningCount() {
        return checkTaskMapper.selectCount(new LambdaQueryWrapper<CheckTask>()
                .in(CheckTask::getStatus, "PREPROCESSING", "COMPARING", "REPORTING"));
    }

    /** 失败任务人工重入队：仅 FAILED 可重入队，重置任务并同步将提交从 CHECK_FAILED 置回 CHECKING。 */
    @Transactional
    public Map<String, Object> retry(UserPrincipal principal, UUID taskId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, submission_id, assignment_id, status FROM app.check_task WHERE id = ?", taskId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("CHECK_TASK_NOT_FOUND", "查重任务不存在", "任务可能已被删除。");
        }
        Map<String, Object> task = rows.get(0);
        String status = (String) task.get("status");
        if (!"FAILED".equals(status)) {
            throw ApiException.conflict("CHECK_TASK_NOT_RETRYABLE", "任务不可重入队",
                    "仅失败状态的查重任务可以重新入队。");
        }
        UUID assignmentId = (UUID) task.get("assignment_id");
        List<UUID> courseIds = jdbcTemplate.query(
                "SELECT course_id FROM app.assignment WHERE id = ?",
                (rs, index) -> rs.getObject("course_id", UUID.class), assignmentId);
        if (!courseIds.isEmpty()) {
            Course course = courseService.requireCourse(courseIds.get(0));
            courseService.requireManage(course, principal);
        }
        UUID submissionId = (UUID) task.get("submission_id");
        jdbcTemplate.update(
                "UPDATE app.check_task SET status = 'QUEUED', lease_owner = NULL, lease_until = NULL, " +
                        "attempt = 0, error_code = NULL, error_detail = NULL, scheduled_at = now(), " +
                        "requeue_count = requeue_count + 1 WHERE id = ?",
                taskId);
        if (submissionId != null) {
            jdbcTemplate.update(
                    "UPDATE app.submission SET status = 'CHECKING', row_version = row_version + 1 " +
                            "WHERE id = ? AND status = 'CHECK_FAILED'",
                    submissionId);
        }
        auditService.recordUser(principal.id(), "check.task.retry", "CHECK_TASK", taskId, "SUCCESS",
                Map.of("submissionId", submissionId == null ? null : submissionId.toString()), null);
        return Map.of("taskId", taskId, "submissionId", submissionId, "status", "QUEUED");
    }

    private CheckTask findByIdempotencyKey(String key) {
        return checkTaskMapper.selectOne(new LambdaQueryWrapper<CheckTask>()
                .eq(CheckTask::getIdempotencyKey, key)
                .last("LIMIT 1"));
    }
}


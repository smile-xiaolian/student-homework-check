package com.edu.submission.checking.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edu.submission.assignment.domain.Assignment;
import com.edu.submission.checking.adapter.out.CheckTaskMapper;
import com.edu.submission.checking.domain.CheckTask;
import com.edu.submission.shared.crypto.Digests;
import com.edu.submission.shared.ids.Ids;
import com.edu.submission.submission.domain.Submission;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckTaskService {

    private final CheckTaskMapper checkTaskMapper;

    public CheckTaskService(CheckTaskMapper checkTaskMapper) {
        this.checkTaskMapper = checkTaskMapper;
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

    private CheckTask findByIdempotencyKey(String key) {
        return checkTaskMapper.selectOne(new LambdaQueryWrapper<CheckTask>()
                .eq(CheckTask::getIdempotencyKey, key)
                .last("LIMIT 1"));
    }
}


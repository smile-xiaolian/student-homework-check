package com.edu.submission.submission.adapter.out;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edu.submission.submission.domain.Submission;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SubmissionMapper extends BaseMapper<Submission> {

    @Select("""
            SELECT * FROM app.submission
            WHERE assignment_id = #{assignmentId} AND submitter_id = #{submitterId}
            ORDER BY version_no DESC
            """)
    List<Submission> findByAssignmentAndSubmitter(@Param("assignmentId") UUID assignmentId,
                                                  @Param("submitterId") UUID submitterId);

    @Select("SELECT COALESCE(MAX(version_no), 0) FROM app.submission WHERE assignment_id = #{assignmentId} AND submitter_id = #{submitterId}")
    int maxVersionNo(@Param("assignmentId") UUID assignmentId,
                     @Param("submitterId") UUID submitterId);

    @Select("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(#{lockKey}))) AS lock")
    Integer acquireVersionLock(@Param("lockKey") String lockKey);

    @Select("""
            SELECT * FROM app.submission
            WHERE submitter_id = #{submitterId} AND assignment_id = #{assignmentId}
              AND idempotency_key = #{idempotencyKey}
            LIMIT 1
            """)
    Submission findByIdempotency(@Param("submitterId") UUID submitterId,
                                 @Param("assignmentId") UUID assignmentId,
                                 @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT s.* FROM app.submission s
            JOIN app.user_account u ON u.id = s.submitter_id
            WHERE s.assignment_id = #{assignmentId}
            ORDER BY s.submitted_at DESC
            """)
    List<Submission> findByAssignment(@Param("assignmentId") UUID assignmentId);

    @Select("""
            SELECT s.* FROM app.submission s
            JOIN app.user_account u ON u.id = s.submitter_id
            WHERE s.assignment_id = #{assignmentId} AND s.is_effective = true
            ORDER BY s.submitted_at DESC
            """)
    List<Submission> findEffectiveByAssignment(@Param("assignmentId") UUID assignmentId);
}


package com.edu.submission.checking.adapter.out;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edu.submission.checking.domain.CheckTask;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CheckTaskMapper extends BaseMapper<CheckTask> {

    @Select("""
            SELECT * FROM app.check_task
            WHERE submission_id = #{submissionId}
            ORDER BY created_at DESC
            LIMIT 1
            """)
    CheckTask findLatestBySubmission(@Param("submissionId") UUID submissionId);
}


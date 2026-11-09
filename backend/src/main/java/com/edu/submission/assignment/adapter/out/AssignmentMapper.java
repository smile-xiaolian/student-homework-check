package com.edu.submission.assignment.adapter.out;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edu.submission.assignment.domain.Assignment;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssignmentMapper extends BaseMapper<Assignment> {

    @Select("""
            SELECT * FROM app.assignment
            WHERE course_id = #{courseId}
              AND (#{includeDrafts} = true OR status <> 'DRAFT')
            ORDER BY created_at DESC
            """)
    List<Assignment> findByCourse(@Param("courseId") UUID courseId,
                                  @Param("includeDrafts") boolean includeDrafts);
}


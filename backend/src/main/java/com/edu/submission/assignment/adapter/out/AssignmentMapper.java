package com.edu.submission.assignment.adapter.out;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edu.submission.assignment.domain.Assignment;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
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

    @Select("SELECT count(*) FROM app.submission WHERE assignment_id = #{assignmentId}")
    long countSubmissions(@Param("assignmentId") UUID assignmentId);

    @Delete("DELETE FROM app.assignment_attachment WHERE assignment_id = #{assignmentId}")
    int deleteAttachments(@Param("assignmentId") UUID assignmentId);
}


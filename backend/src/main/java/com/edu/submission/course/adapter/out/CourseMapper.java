package com.edu.submission.course.adapter.out;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edu.submission.course.domain.Course;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CourseMapper extends BaseMapper<Course> {

    @Select("""
            SELECT DISTINCT c.*
            FROM app.course c
            LEFT JOIN app.course_member cm
                ON cm.course_id = c.id AND cm.user_id = #{userId} AND cm.status = 'ACTIVE'
            WHERE #{role} = 'ADMIN'
               OR c.owner_id = #{userId}
               OR cm.id IS NOT NULL
            ORDER BY c.created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<Course> findVisible(@Param("userId") UUID userId,
                             @Param("role") String role,
                             @Param("limit") int limit,
                             @Param("offset") int offset);

    @Select("""
            SELECT COUNT(DISTINCT c.id)
            FROM app.course c
            LEFT JOIN app.course_member cm
                ON cm.course_id = c.id AND cm.user_id = #{userId} AND cm.status = 'ACTIVE'
            WHERE #{role} = 'ADMIN'
               OR c.owner_id = #{userId}
               OR cm.id IS NOT NULL
            """)
    long countVisible(@Param("userId") UUID userId, @Param("role") String role);

    @Select("SELECT * FROM app.course WHERE invite_code = #{inviteCode} LIMIT 1")
    Course findByInviteCode(@Param("inviteCode") String inviteCode);
}


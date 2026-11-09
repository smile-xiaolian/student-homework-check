package com.edu.submission.course.adapter.out;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edu.submission.course.domain.CourseMember;
import com.edu.submission.course.adapter.in.dto.CourseMemberView;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CourseMemberMapper extends BaseMapper<CourseMember> {

    @Select("""
            SELECT cm.id, cm.user_id, u.user_no, u.display_name, cm.member_role,
                   cm.status, cm.joined_at
            FROM app.course_member cm
            JOIN app.user_account u ON u.id = cm.user_id
            WHERE cm.course_id = #{courseId} AND cm.status = 'ACTIVE'
            ORDER BY CASE cm.member_role WHEN 'INSTRUCTOR' THEN 0 WHEN 'ASSISTANT' THEN 1 ELSE 2 END,
                     u.user_no
            """)
    List<CourseMemberView> findMembers(@Param("courseId") UUID courseId);

    @Select("""
            SELECT user_id FROM app.course_member
            WHERE course_id = #{courseId} AND status = 'ACTIVE' AND member_role = 'STUDENT'
            """)
    List<UUID> findActiveStudentIds(@Param("courseId") UUID courseId);
}


package com.edu.submission.course.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edu.submission.audit.AuditService;
import com.edu.submission.course.adapter.in.dto.AddMemberRequest;
import com.edu.submission.course.adapter.in.dto.CourseCreateRequest;
import com.edu.submission.course.adapter.in.dto.CourseMemberView;
import com.edu.submission.course.adapter.in.dto.CourseUpdateRequest;
import com.edu.submission.course.adapter.in.dto.CourseView;
import com.edu.submission.course.adapter.out.CourseMapper;
import com.edu.submission.course.adapter.out.CourseMemberMapper;
import com.edu.submission.course.domain.Course;
import com.edu.submission.course.domain.CourseMember;
import com.edu.submission.identity.application.IdentityQueryService;
import com.edu.submission.identity.domain.UserAccount;
import com.edu.submission.identity.domain.UserRole;
import com.edu.submission.shared.api.ApiException;
import com.edu.submission.shared.api.PageQuery;
import com.edu.submission.shared.api.PageResult;
import com.edu.submission.shared.api.Pagination;
import com.edu.submission.shared.ids.Ids;
import com.edu.submission.shared.security.UserPrincipal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseService {

    private final CourseMapper courseMapper;
    private final CourseMemberMapper courseMemberMapper;
    private final IdentityQueryService identityQueryService;
    private final AuditService auditService;

    public CourseService(CourseMapper courseMapper,
                         CourseMemberMapper courseMemberMapper,
                         IdentityQueryService identityQueryService,
                         AuditService auditService) {
        this.courseMapper = courseMapper;
        this.courseMemberMapper = courseMemberMapper;
        this.identityQueryService = identityQueryService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResult<CourseView> list(UserPrincipal principal, Integer page, Integer size) {
        PageQuery query = PageQuery.of(page, size);
        List<Course> courses = courseMapper.findVisible(
                principal.id(), principal.role().name(), query.size(), query.offset());
        long total = courseMapper.countVisible(principal.id(), principal.role().name());
        List<CourseView> views = courses.stream()
                .map(course -> CourseView.from(course, canManage(course, principal)))
                .toList();
        return new PageResult<>(views, Pagination.of(query.page(), query.size(), total));
    }

    @Transactional
    public Course create(UserPrincipal principal, CourseCreateRequest request) {
        if (principal.role() != UserRole.TEACHER && principal.role() != UserRole.ADMIN) {
            throw ApiException.forbidden("COURSE_CREATE_FORBIDDEN", "无权创建课程",
                    "只有教师或管理员可以创建课程。");
        }
        long duplicates = courseMapper.selectCount(new LambdaQueryWrapper<Course>()
                .eq(Course::getCourseCode, request.courseCode().trim())
                .eq(Course::getSemester, request.semester().trim()));
        if (duplicates > 0) {
            throw ApiException.conflict("COURSE_CODE_DUPLICATED", "课程代码已存在",
                    "同一学期内课程代码必须唯一。");
        }

        Course course = new Course();
        course.setId(Ids.newUuidV7());
        course.setCourseCode(request.courseCode().trim());
        course.setName(request.name().trim());
        course.setSemester(request.semester().trim());
        course.setOwnerId(principal.id());
        course.setDescription(blankToNull(request.description()));
        course.setStatus("ACTIVE");
        course.setInviteCode(nextInviteCode());
        course.setRowVersion(0L);
        try {
            courseMapper.insert(course);
        } catch (DuplicateKeyException exception) {
            throw ApiException.conflict("COURSE_CODE_DUPLICATED", "课程代码已存在",
                    "同一学期内课程代码必须唯一。");
        }

        CourseMember instructor = new CourseMember();
        instructor.setId(Ids.newUuidV7());
        instructor.setCourseId(course.getId());
        instructor.setUserId(principal.id());
        instructor.setMemberRole("INSTRUCTOR");
        instructor.setStatus("ACTIVE");
        courseMemberMapper.insert(instructor);
        auditService.recordUser(principal.id(), "course.create", "COURSE", course.getId(), "SUCCESS",
                Map.of("courseCode", course.getCourseCode(), "semester", course.getSemester()), null);
        return course;
    }

    @Transactional(readOnly = true)
    public CourseView get(UserPrincipal principal, UUID courseId) {
        Course course = requireCourse(courseId);
        requireReadable(course, principal);
        return CourseView.from(course, canManage(course, principal));
    }

    @Transactional
    public CourseView update(UserPrincipal principal, UUID courseId, CourseUpdateRequest request,
                             OptionalLong expectedRowVersion) {
        Course course = requireCourse(courseId);
        requireManage(course, principal);
        assertNotArchived(course);
        assertRowVersion(course, expectedRowVersion);
        if (request.name() != null && !request.name().isBlank()) {
            course.setName(request.name().trim());
        }
        if (request.description() != null) {
            course.setDescription(blankToNull(request.description()));
        }
        course.setRowVersion((course.getRowVersion() == null ? 0L : course.getRowVersion()) + 1);
        courseMapper.updateById(course);
        auditService.recordUser(principal.id(), "course.update", "COURSE", course.getId(), "SUCCESS",
                Map.of("fields", "name,description"), null);
        return CourseView.from(course, true);
    }

    @Transactional
    public CourseView archive(UserPrincipal principal, UUID courseId, OptionalLong expectedRowVersion) {
        Course course = requireCourse(courseId);
        requireManage(course, principal);
        if ("ARCHIVED".equals(course.getStatus())) {
            return CourseView.from(course, true);
        }
        assertRowVersion(course, expectedRowVersion);
        course.setStatus("ARCHIVED");
        course.setArchivedAt(OffsetDateTime.now(ZoneOffset.UTC));
        course.setRowVersion((course.getRowVersion() == null ? 0L : course.getRowVersion()) + 1);
        courseMapper.updateById(course);
        auditService.recordUser(principal.id(), "course.archive", "COURSE", course.getId(), "SUCCESS",
                Map.of(), null);
        return CourseView.from(course, true);
    }

    @Transactional
    public CourseView join(UserPrincipal principal, String inviteCode) {
        if (principal.role() != UserRole.STUDENT) {
            throw ApiException.forbidden("COURSE_JOIN_FORBIDDEN", "无法加入课程",
                    "仅学生可以通过邀请码加入课程。");
        }
        Course course = courseMapper.findByInviteCode(inviteCode == null ? "" : inviteCode.trim());
        if (course == null) {
            throw ApiException.notFound("COURSE_INVITE_INVALID", "邀请码无效", "请确认邀请码是否正确。");
        }
        if (!"ACTIVE".equals(course.getStatus())) {
            throw ApiException.conflict("COURSE_NOT_ACTIVE", "课程不可加入", "课程当前不在开放状态。");
        }
        CourseMember member = findMembership(course.getId(), principal.id());
        if (member != null && "ACTIVE".equals(member.getStatus())) {
            return CourseView.from(course, false);
        }
        if (member == null) {
            member = new CourseMember();
            member.setId(Ids.newUuidV7());
            member.setCourseId(course.getId());
            member.setUserId(principal.id());
            member.setMemberRole("STUDENT");
            member.setStatus("ACTIVE");
            courseMemberMapper.insert(member);
        } else {
            member.setStatus("ACTIVE");
            member.setRemovedAt(null);
            member.setJoinedAt(OffsetDateTime.now(ZoneOffset.UTC));
            courseMemberMapper.updateById(member);
        }
        auditService.recordUser(principal.id(), "roster.add", "COURSE", course.getId(), "SUCCESS",
                Map.of("source", "INVITE"), null);
        return CourseView.from(course, false);
    }

    @Transactional(readOnly = true)
    public List<CourseMemberView> members(UserPrincipal principal, UUID courseId) {
        Course course = requireCourse(courseId);
        requireReadable(course, principal);
        return courseMemberMapper.findMembers(courseId);
    }

    @Transactional
    public CourseMemberView addMember(UserPrincipal principal, UUID courseId, AddMemberRequest request) {
        Course course = requireCourse(courseId);
        requireManage(course, principal);
        assertNotArchived(course);
        UserAccount user = identityQueryService.findByLogin(request.userNo().trim())
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "用户不存在",
                        "请确认学工号是否正确。"));
        String memberRole = request.memberRole() == null || request.memberRole().isBlank()
                ? "STUDENT" : request.memberRole();
        if ("ASSISTANT".equals(memberRole) && user.getRole() != UserRole.ASSISTANT) {
            throw ApiException.unprocessable("MEMBER_ROLE_MISMATCH", "成员角色不匹配",
                    "该用户的系统角色不是助教。");
        }
        if ("STUDENT".equals(memberRole) && user.getRole() != UserRole.STUDENT) {
            throw ApiException.unprocessable("MEMBER_ROLE_MISMATCH", "成员角色不匹配",
                    "该用户的系统角色不是学生。");
        }

        CourseMember member = findMembership(courseId, user.getId());
        if (member == null) {
            member = new CourseMember();
            member.setId(Ids.newUuidV7());
            member.setCourseId(courseId);
            member.setUserId(user.getId());
            member.setMemberRole(memberRole);
            member.setStatus("ACTIVE");
            courseMemberMapper.insert(member);
        } else if (!"ACTIVE".equals(member.getStatus())) {
            member.setStatus("ACTIVE");
            member.setMemberRole(memberRole);
            member.setRemovedAt(null);
            member.setJoinedAt(OffsetDateTime.now(ZoneOffset.UTC));
            courseMemberMapper.updateById(member);
        }
        auditService.recordUser(principal.id(), "roster.add", "COURSE", courseId, "SUCCESS",
                Map.of("targetUserId", user.getId().toString(), "source", "MANUAL"), null);
        return courseMemberMapper.findMembers(courseId).stream()
                .filter(view -> view.userId().equals(user.getId()))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("MEMBER_NOT_FOUND", "成员不存在",
                        "花名册更新后未找到该成员。"));
    }

    @Transactional
    public CourseView rotateInviteCode(UserPrincipal principal, UUID courseId) {
        Course course = requireCourse(courseId);
        if (principal.role() != UserRole.ADMIN && !course.getOwnerId().equals(principal.id())) {
            throw ApiException.forbidden("COURSE_MANAGE_FORBIDDEN", "无权修改课程",
                    "只有课程负责人或管理员可以重新生成邀请码。");
        }
        assertNotArchived(course);
        course.setInviteCode(nextInviteCode());
        course.setRowVersion((course.getRowVersion() == null ? 0L : course.getRowVersion()) + 1);
        courseMapper.updateById(course);
        auditService.recordUser(principal.id(), "course.invite.rotate", "COURSE", courseId, "SUCCESS",
                Map.of(), null);
        return CourseView.from(course, true);
    }

    public Course requireCourse(UUID courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw ApiException.notFound("COURSE_NOT_FOUND", "课程不存在", "课程可能已被删除或无权访问。");
        }
        return course;
    }

    public void requireReadable(Course course, UserPrincipal principal) {
        if (principal.role() == UserRole.ADMIN || course.getOwnerId().equals(principal.id())) {
            return;
        }
        CourseMember member = findMembership(course.getId(), principal.id());
        if (member == null || !"ACTIVE".equals(member.getStatus())) {
            throw ApiException.forbidden("COURSE_ACCESS_DENIED", "无权访问课程",
                    "当前账号不是该课程的成员。");
        }
    }

    public void requireManage(Course course, UserPrincipal principal) {
        if (principal.role() == UserRole.ADMIN || course.getOwnerId().equals(principal.id())) {
            return;
        }
        CourseMember member = findMembership(course.getId(), principal.id());
        if (member == null || !"ACTIVE".equals(member.getStatus())
                || !"INSTRUCTOR".equals(member.getMemberRole())) {
            throw ApiException.forbidden("COURSE_MANAGE_FORBIDDEN", "无权管理课程",
                    "只有课程负责人或授课教师可以执行此操作。");
        }
    }

    public boolean canManage(Course course, UserPrincipal principal) {
        if (principal.role() == UserRole.ADMIN || course.getOwnerId().equals(principal.id())) {
            return true;
        }
        CourseMember member = findMembership(course.getId(), principal.id());
        return member != null && "ACTIVE".equals(member.getStatus())
                && "INSTRUCTOR".equals(member.getMemberRole());
    }

    public CourseMember findMembership(UUID courseId, UUID userId) {
        return courseMemberMapper.selectOne(new LambdaQueryWrapper<CourseMember>()
                .eq(CourseMember::getCourseId, courseId)
                .eq(CourseMember::getUserId, userId)
                .last("LIMIT 1"));
    }

    private void assertNotArchived(Course course) {
        if ("ARCHIVED".equals(course.getStatus())) {
            throw ApiException.conflict("COURSE_ARCHIVED", "课程已归档", "归档课程为只读状态。");
        }
    }

    private void assertRowVersion(Course course, OptionalLong expectedRowVersion) {
        if (expectedRowVersion.isPresent()
                && expectedRowVersion.getAsLong() != (course.getRowVersion() == null ? 0L : course.getRowVersion())) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED",
                    "数据已被其他用户修改", "请刷新后重试。");
        }
    }

    private String nextInviteCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            if (courseMapper.selectCount(new LambdaQueryWrapper<Course>()
                    .eq(Course::getInviteCode, candidate)) == 0) {
                return candidate;
            }
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INVITE_CODE_GENERATION_FAILED",
                "邀请码生成失败", "请稍后重试。");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}


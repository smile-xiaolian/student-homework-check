package com.edu.submission.identity;

import com.edu.submission.assignment.adapter.out.AssignmentMapper;
import com.edu.submission.assignment.domain.Assignment;
import com.edu.submission.course.adapter.out.CourseMapper;
import com.edu.submission.course.adapter.out.CourseMemberMapper;
import com.edu.submission.course.domain.Course;
import com.edu.submission.course.domain.CourseMember;
import com.edu.submission.identity.adapter.out.UserAccountMapper;
import com.edu.submission.identity.domain.UserAccount;
import com.edu.submission.identity.domain.UserRole;
import com.edu.submission.identity.domain.UserStatus;
import com.edu.submission.notification.application.NotificationService;
import com.edu.submission.shared.ids.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final UserAccountMapper userAccountMapper;
    private final CourseMapper courseMapper;
    private final CourseMemberMapper courseMemberMapper;
    private final AssignmentMapper assignmentMapper;
    private final NotificationService notificationService;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;

    public DemoDataInitializer(UserAccountMapper userAccountMapper,
                               CourseMapper courseMapper,
                               CourseMemberMapper courseMemberMapper,
                               AssignmentMapper assignmentMapper,
                               NotificationService notificationService,
                               PasswordEncoder passwordEncoder,
                               @Value("${app.demo-data:true}") boolean enabled) {
        this.userAccountMapper = userAccountMapper;
        this.courseMapper = courseMapper;
        this.courseMemberMapper = courseMemberMapper;
        this.assignmentMapper = assignmentMapper;
        this.notificationService = notificationService;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        List<DemoUser> users = List.of(
                new DemoUser("admin", "admin", "系统管理员", UserRole.ADMIN, "admin@example.edu", "Admin@123"),
                new DemoUser("T2026001", "teacher01", "王老师", UserRole.TEACHER, "teacher01@example.edu", "Teacher@123"),
                new DemoUser("A2026001", "assistant01", "李助教", UserRole.ASSISTANT, "assistant01@example.edu", "Assistant@123"),
                new DemoUser("2023001001", "2023001001", "张三", UserRole.STUDENT, "zhangsan@example.edu", "Student@123")
        );
        int created = 0;
        for (DemoUser demo : users) {
            if (userAccountMapper.findByLogin(demo.loginName()) != null) {
                continue;
            }
            UserAccount user = new UserAccount();
            user.setId(Ids.newUuidV7());
            user.setUserNo(demo.userNo());
            user.setLoginName(demo.loginName());
            user.setDisplayName(demo.displayName());
            user.setEmail(demo.email());
            user.setRole(demo.role());
            user.setPasswordHash(passwordEncoder.encode(demo.password()));
            user.setStatus(UserStatus.ACTIVE);
            user.setTokenVersion(1);
            user.setRowVersion(0L);
            userAccountMapper.insert(user);
            created++;
        }
        if (created > 0) {
            log.info("Created {} demo accounts. Change their passwords before any shared deployment.", created);
        }
        seedCourseAndAssignment();
    }

    private void seedCourseAndAssignment() {
        if (courseMapper.selectCount(null) > 0) {
            return;
        }
        UserAccount teacher = userAccountMapper.findByLogin("teacher01");
        UserAccount assistant = userAccountMapper.findByLogin("assistant01");
        UserAccount student = userAccountMapper.findByLogin("2023001001");
        if (teacher == null || student == null) {
            return;
        }
        Course course = new Course();
        course.setId(Ids.newUuidV7());
        course.setCourseCode("CS-2026-01");
        course.setName("数据库系统原理");
        course.setSemester("2026-FALL");
        course.setOwnerId(teacher.getId());
        course.setDescription("第一轮迭代演示课程：作业发布、在线提交、查重与评审主流程。");
        course.setStatus("ACTIVE");
        course.setInviteCode("DB2026FALL");
        course.setRowVersion(0L);
        courseMapper.insert(course);

        insertMember(course.getId(), teacher.getId(), "INSTRUCTOR");
        insertMember(course.getId(), student.getId(), "STUDENT");
        if (assistant != null) {
            insertMember(course.getId(), assistant.getId(), "ASSISTANT");
        }

        Assignment assignment = new Assignment();
        assignment.setId(Ids.newUuidV7());
        assignment.setCourseId(course.getId());
        assignment.setTitle("数据库课程设计（第一轮演示）");
        assignment.setDescription("提交一份数据库课程设计说明，系统将自动生成文本查重任务。");
        assignment.setStatus("PUBLISHED");
        assignment.setPublishAt(OffsetDateTime.now(ZoneOffset.UTC));
        assignment.setOpenAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        assignment.setDeadlineAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(14));
        assignment.setAllowLate(true);
        assignment.setLateDeadlineAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(17));
        assignment.setAllowMultiVersion(true);
        assignment.setEffectiveRule("LAST_SUBMISSION");
        assignment.setCheckEnabled(true);
        assignment.setCheckConfig("""
                {"mode":"TEXT","text_thresholds":{"warning":20,"severe":50},
                 "exclude":{"references":true,"template":true},"classLibraryRequired":true}
                """);
        assignment.setRubricDefinition("""
                {"title":"课程设计评分量规","totalScore":100,"dimensions":[
                  {"key":"requirement","name":"需求与功能","maxScore":40,
                   "levels":[{"label":"优秀","score":40},{"label":"合格","score":24}]},
                  {"key":"quality","name":"文档与表达","maxScore":60,
                   "levels":[{"label":"优秀","score":60},{"label":"合格","score":36}]}]}
                """);
        assignment.setWeight(new BigDecimal("30"));
        assignment.setCreatedBy(teacher.getId());
        assignment.setRowVersion(0L);
        assignmentMapper.insert(assignment);

        notificationService.notifyUsers(List.of(student.getId()), "ASSIGNMENT_PUBLISHED",
                "新作业已发布", "课程《数据库系统原理》发布了作业：" + assignment.getTitle(),
                "ASSIGNMENT", assignment.getId());
    }

    private void insertMember(java.util.UUID courseId, java.util.UUID userId, String role) {
        CourseMember member = new CourseMember();
        member.setId(Ids.newUuidV7());
        member.setCourseId(courseId);
        member.setUserId(userId);
        member.setMemberRole(role);
        member.setStatus("ACTIVE");
        courseMemberMapper.insert(member);
    }

    private record DemoUser(String userNo, String loginName, String displayName,
                            UserRole role, String email, String password) {
    }
}


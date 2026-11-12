package com.edu.submission.assignment.application;

import com.edu.submission.assignment.adapter.in.dto.AssignmentCreateRequest;
import com.edu.submission.assignment.adapter.in.dto.AssignmentUpdateRequest;
import com.edu.submission.assignment.adapter.in.dto.AssignmentView;
import com.edu.submission.assignment.adapter.out.AssignmentMapper;
import com.edu.submission.assignment.domain.Assignment;
import com.edu.submission.audit.AuditService;
import com.edu.submission.course.application.CourseService;
import com.edu.submission.course.domain.Course;
import com.edu.submission.notification.application.NotificationService;
import com.edu.submission.shared.api.ApiException;
import com.edu.submission.shared.api.Etags;
import com.edu.submission.shared.ids.Ids;
import com.edu.submission.shared.security.UserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssignmentService {

    private final AssignmentMapper assignmentMapper;
    private final CourseService courseService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AssignmentService(AssignmentMapper assignmentMapper,
                             CourseService courseService,
                             NotificationService notificationService,
                             AuditService auditService,
                             ObjectMapper objectMapper) {
        this.assignmentMapper = assignmentMapper;
        this.courseService = courseService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<AssignmentView> list(UserPrincipal principal, UUID courseId) {
        Course course = courseService.requireCourse(courseId);
        courseService.requireReadable(course, principal);
        boolean manage = courseService.canManage(course, principal);
        return assignmentMapper.findByCourse(courseId, manage).stream()
                .map(assignment -> toView(assignment, manage))
                .toList();
    }

    @Transactional(readOnly = true)
    public AssignmentView get(UserPrincipal principal, UUID assignmentId) {
        Assignment assignment = requireAssignment(assignmentId);
        Course course = courseService.requireCourse(assignment.getCourseId());
        courseService.requireReadable(course, principal);
        return toView(assignment, courseService.canManage(course, principal));
    }

    @Transactional
    public AssignmentView create(UserPrincipal principal, UUID courseId, AssignmentCreateRequest request) {
        Course course = courseService.requireCourse(courseId);
        courseService.requireManage(course, principal);
        assertCourseActive(course);

        boolean allowLate = Boolean.TRUE.equals(request.allowLate());
        boolean allowMultiVersion = request.allowMultiVersion() == null || request.allowMultiVersion();
        String effectiveRule = request.effectiveRule() == null ? "LAST_SUBMISSION" : request.effectiveRule();
        validateSchedule(request.openAt(), request.deadlineAt(), request.lateDeadlineAt(),
                allowLate, allowMultiVersion, effectiveRule);
        JsonNode checkConfig = validateCheckConfig(request.checkConfig());
        JsonNode rubric = validateRubric(request.rubricDefinition());
        List<String> allowedFileTypes = normalizeAllowedFileTypes(request.allowedFileTypes());
        validateScheduledPublishAt(request.scheduledPublishAt());

        Assignment assignment = new Assignment();
        assignment.setId(Ids.newUuidV7());
        assignment.setCourseId(courseId);
        assignment.setTitle(request.title().trim());
        assignment.setDescription(blankToNull(request.description()));
        assignment.setStatus("DRAFT");
        assignment.setOpenAt(request.openAt());
        assignment.setDeadlineAt(request.deadlineAt());
        assignment.setLateDeadlineAt(allowLate ? request.lateDeadlineAt() : null);
        assignment.setAllowLate(allowLate);
        assignment.setAllowMultiVersion(allowMultiVersion);
        assignment.setEffectiveRule(effectiveRule);
        assignment.setCheckEnabled(Boolean.TRUE.equals(request.checkEnabled()));
        assignment.setCheckConfig(writeJson(checkConfig, "{}"));
        assignment.setRubricDefinition(writeJson(rubric, "{}"));
        assignment.setAllowedFileTypes(allowedFileTypesJson(allowedFileTypes));
        assignment.setScheduledPublishAt(request.scheduledPublishAt());
        assignment.setWeight(request.weight() == null ? BigDecimal.ZERO : request.weight());
        assignment.setCreatedBy(principal.id());
        assignment.setRowVersion(0L);
        assignmentMapper.insert(assignment);
        auditService.recordUser(principal.id(), "assignment.create", "ASSIGNMENT", assignment.getId(),
                "SUCCESS", Map.of("courseId", courseId.toString()), null);
        return toView(assignment, true);
    }

    @Transactional
    public AssignmentView update(UserPrincipal principal, UUID assignmentId,
                                 AssignmentUpdateRequest request, OptionalLong expectedRowVersion) {
        Assignment assignment = requireAssignment(assignmentId);
        Course course = courseService.requireCourse(assignment.getCourseId());
        courseService.requireManage(course, principal);
        assertCourseActive(course);
        assertRowVersion(assignment, expectedRowVersion);
        requireImpactConfirmationIfNeeded(assignment, request.confirmImpact());

        String effectiveRule = request.effectiveRule() == null
                ? assignment.getEffectiveRule() : request.effectiveRule();
        boolean allowLate = request.allowLate() == null
                ? Boolean.TRUE.equals(assignment.getAllowLate()) : request.allowLate();
        boolean allowMultiVersion = request.allowMultiVersion() == null
                ? Boolean.TRUE.equals(assignment.getAllowMultiVersion()) : request.allowMultiVersion();
        OffsetDateTime openAt = request.openAt() == null ? assignment.getOpenAt() : request.openAt();
        OffsetDateTime deadlineAt = request.deadlineAt() == null
                ? assignment.getDeadlineAt() : request.deadlineAt();
        OffsetDateTime lateDeadlineAt = request.lateDeadlineAt() == null
                ? assignment.getLateDeadlineAt() : request.lateDeadlineAt();
        validateSchedule(openAt, deadlineAt, lateDeadlineAt, allowLate, allowMultiVersion, effectiveRule);

        if (request.title() != null && !request.title().isBlank()) {
            assignment.setTitle(request.title().trim());
        }
        if (request.description() != null) {
            assignment.setDescription(blankToNull(request.description()));
        }
        assignment.setOpenAt(openAt);
        assignment.setDeadlineAt(deadlineAt);
        assignment.setLateDeadlineAt(allowLate ? lateDeadlineAt : null);
        assignment.setAllowLate(allowLate);
        assignment.setAllowMultiVersion(allowMultiVersion);
        assignment.setEffectiveRule(effectiveRule);
        if (request.checkEnabled() != null) {
            assignment.setCheckEnabled(request.checkEnabled());
        }
        if (request.checkConfig() != null) {
            assignment.setCheckConfig(writeJson(validateCheckConfig(request.checkConfig()), "{}"));
        }
        if (request.rubricDefinition() != null) {
            assignment.setRubricDefinition(writeJson(validateRubric(request.rubricDefinition()), "{}"));
        }
        if (request.allowedFileTypes() != null) {
            assignment.setAllowedFileTypes(allowedFileTypesJson(normalizeAllowedFileTypes(request.allowedFileTypes())));
        }
        if (request.scheduledPublishAt() != null) {
            validateScheduledPublishAt(request.scheduledPublishAt());
            assignment.setScheduledPublishAt(request.scheduledPublishAt());
        }
        if (request.weight() != null) {
            assignment.setWeight(request.weight());
        }
        assignment.setRowVersion((assignment.getRowVersion() == null ? 0L : assignment.getRowVersion()) + 1);
        assignmentMapper.updateById(assignment);
        auditService.recordUser(principal.id(), "assignment.update", "ASSIGNMENT", assignmentId,
                "SUCCESS", Map.of("courseId", assignment.getCourseId().toString()), null);
        return toView(assignment, true);
    }

    @Transactional
    public void delete(UserPrincipal principal, UUID assignmentId) {
        Assignment assignment = requireAssignment(assignmentId);
        Course course = courseService.requireCourse(assignment.getCourseId());
        courseService.requireManage(course, principal);
        assertCourseActive(course);
        if (assignmentMapper.countSubmissions(assignmentId) > 0) {
            throw ApiException.conflict("ASSIGNMENT_HAS_SUBMISSIONS", "作业已有提交，无法删除",
                    "该作业已有学生提交，不能删除。如需停止提交，可关闭作业或归档课程。");
        }
        try {
            assignmentMapper.deleteAttachments(assignmentId);
            assignmentMapper.deleteById(assignmentId);
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw ApiException.conflict("ASSIGNMENT_DELETE_BLOCKED", "作业无法删除",
                    "该作业存在关联数据，无法删除，请刷新后重试。");
        }
        auditService.recordUser(principal.id(), "assignment.delete", "ASSIGNMENT", assignmentId,
                "SUCCESS", Map.of("courseId", assignment.getCourseId().toString()), null);
    }

    @Transactional
    public AssignmentView publish(UserPrincipal principal, UUID assignmentId) {
        Assignment assignment = requireAssignment(assignmentId);
        Course course = courseService.requireCourse(assignment.getCourseId());
        courseService.requireManage(course, principal);
        assertCourseActive(course);
        if ("PUBLISHED".equals(assignment.getStatus())) {
            return toView(assignment, true);
        }
        if ("ARCHIVED".equals(assignment.getStatus())) {
            throw ApiException.conflict("ASSIGNMENT_ARCHIVED", "作业已归档", "归档作业不能再次发布。");
        }
        assignment.setStatus("PUBLISHED");
        assignment.setPublishAt(OffsetDateTime.now(ZoneOffset.UTC));
        assignment.setScheduledPublishAt(null);
        assignment.setRowVersion((assignment.getRowVersion() == null ? 0L : assignment.getRowVersion()) + 1);
        assignmentMapper.updateById(assignment);
        notificationService.notifyCourseStudents(course.getId(), "ASSIGNMENT_PUBLISHED",
                "新作业已发布", "课程《" + course.getName() + "》发布了作业：" + assignment.getTitle(),
                "ASSIGNMENT", assignment.getId());
        auditService.recordUser(principal.id(), "assignment.publish", "ASSIGNMENT", assignmentId,
                "SUCCESS", Map.of(), null);
        return toView(assignment, true);
    }

    @Transactional(readOnly = true)
    public JsonNode getCheckConfig(UserPrincipal principal, UUID assignmentId) {
        Assignment assignment = requireAssignment(assignmentId);
        Course course = courseService.requireCourse(assignment.getCourseId());
        courseService.requireManage(course, principal);
        return readJson(assignment.getCheckConfig());
    }

    @Transactional
    public JsonNode putCheckConfig(UserPrincipal principal, UUID assignmentId, JsonNode config,
                                   OptionalLong expectedRowVersion) {
        Assignment assignment = requireAssignment(assignmentId);
        Course course = courseService.requireCourse(assignment.getCourseId());
        courseService.requireManage(course, principal);
        assertCourseActive(course);
        assertRowVersion(assignment, expectedRowVersion);
        JsonNode validated = validateCheckConfig(config);
        assignment.setCheckConfig(writeJson(validated, "{}"));
        assignment.setRowVersion((assignment.getRowVersion() == null ? 0L : assignment.getRowVersion()) + 1);
        assignmentMapper.updateById(assignment);
        auditService.recordUser(principal.id(), "assignment.check_config.update", "ASSIGNMENT",
                assignmentId, "SUCCESS", Map.of(), null);
        return validated;
    }

    @Transactional(readOnly = true)
    public JsonNode getRubric(UserPrincipal principal, UUID assignmentId) {
        Assignment assignment = requireAssignment(assignmentId);
        Course course = courseService.requireCourse(assignment.getCourseId());
        courseService.requireReadable(course, principal);
        return readJson(assignment.getRubricDefinition());
    }

    @Transactional
    public JsonNode putRubric(UserPrincipal principal, UUID assignmentId, JsonNode rubric,
                              OptionalLong expectedRowVersion) {
        Assignment assignment = requireAssignment(assignmentId);
        Course course = courseService.requireCourse(assignment.getCourseId());
        courseService.requireManage(course, principal);
        assertCourseActive(course);
        assertRowVersion(assignment, expectedRowVersion);
        JsonNode validated = validateRubric(rubric);
        assignment.setRubricDefinition(writeJson(validated, "{}"));
        assignment.setRowVersion((assignment.getRowVersion() == null ? 0L : assignment.getRowVersion()) + 1);
        assignmentMapper.updateById(assignment);
        auditService.recordUser(principal.id(), "assignment.rubric.update", "ASSIGNMENT",
                assignmentId, "SUCCESS", Map.of(), null);
        return validated;
    }

    public Assignment requireAssignment(UUID assignmentId) {
        Assignment assignment = assignmentMapper.selectById(assignmentId);
        if (assignment == null) {
            throw ApiException.notFound("ASSIGNMENT_NOT_FOUND", "作业不存在", "作业可能已删除或无权访问。");
        }
        return assignment;
    }

    public AssignmentView toView(Assignment assignment, boolean includeCheckConfig) {
        return AssignmentView.from(assignment, readJson(assignment.getCheckConfig()),
                readJson(assignment.getRubricDefinition()),
                parseAllowedFileTypes(assignment.getAllowedFileTypes()), includeCheckConfig);
    }

    private JsonNode validateCheckConfig(JsonNode input) {
        if (input == null || input.isNull()) {
            return objectMapper.createObjectNode();
        }
        if (!input.isObject()) {
            throw ApiException.unprocessable("CHECK_CONFIG_INVALID", "查重配置不合法",
                    "查重配置必须是 JSON 对象。");
        }
        ObjectNode normalized = input.deepCopy();
        String mode = normalized.path("mode").asText("TEXT").toUpperCase();
        if (!"TEXT".equals(mode) && !"CODE".equals(mode)) {
            throw ApiException.unprocessable("CHECK_CONFIG_INVALID", "查重配置不合法",
                    "第一轮仅支持 TEXT 或 CODE 查重模式。");
        }
        normalized.put("mode", mode);
        JsonNode textThresholds = normalized.path("text_thresholds");
        int textWarning = textThresholds.path("warning")
                .asInt(normalized.path("textWarningThreshold").asInt(20));
        int textSevere = textThresholds.path("severe")
                .asInt(normalized.path("textSevereThreshold").asInt(50));
        if (textWarning < 0 || textSevere > 100 || textWarning >= textSevere) {
            throw ApiException.unprocessable("CHECK_CONFIG_INVALID", "查重配置不合法",
                    "相似度阈值必须满足 0 <= warning < severe <= 100。");
        }
        ObjectNode textNormalized = objectMapper.createObjectNode();
        textNormalized.put("warning", textWarning);
        textNormalized.put("severe", textSevere);
        normalized.set("text_thresholds", textNormalized);

        JsonNode structThresholds = normalized.path("struct_thresholds");
        int structWarning = structThresholds.path("warning")
                .asInt(normalized.path("structureWarningThreshold").asInt(30));
        int structSevere = structThresholds.path("severe")
                .asInt(normalized.path("structureSevereThreshold").asInt(60));
        if (structWarning < 0 || structSevere > 100 || structWarning >= structSevere) {
            throw ApiException.unprocessable("CHECK_CONFIG_INVALID", "查重配置不合法",
                    "代码结构阈值必须满足 0 <= warning < severe <= 100。");
        }
        ObjectNode structNormalized = objectMapper.createObjectNode();
        structNormalized.put("warning", structWarning);
        structNormalized.put("severe", structSevere);
        normalized.set("struct_thresholds", structNormalized);
        normalized.put("classLibraryRequired", true);
        return normalized;
    }

    private JsonNode validateRubric(JsonNode input) {
        if (input == null || input.isNull() || input.isEmpty()) {
            return objectMapper.createObjectNode();
        }
        if (!input.isObject()) {
            throw ApiException.unprocessable("RUBRIC_INVALID", "评分量规不合法",
                    "量规必须是 JSON 对象。");
        }
        JsonNode dimensions = input.path("dimensions");
        if (!dimensions.isArray() || dimensions.isEmpty()) {
            throw ApiException.unprocessable("RUBRIC_INVALID", "评分量规不合法",
                    "量规至少需要一个评分维度。");
        }
        if (dimensions.size() > 50) {
            throw ApiException.unprocessable("RUBRIC_INVALID", "评分量规不合法",
                    "评分维度不能超过 50 个。");
        }
        double total = input.path("totalScore").asDouble(0);
        double sum = 0;
        Set<String> keys = new HashSet<>();
        for (JsonNode dimension : dimensions) {
            String key = dimension.path("key").asText();
            if (key.isBlank() || !keys.add(key)) {
                throw ApiException.unprocessable("RUBRIC_INVALID", "评分量规不合法",
                        "评分维度 key 必须存在且唯一。");
            }
            double maxScore = dimension.path("maxScore").asDouble(-1);
            if (maxScore < 0 || maxScore > 100) {
                throw ApiException.unprocessable("RUBRIC_INVALID", "评分量规不合法",
                        "评分维度满分必须在 0-100 之间。");
            }
            sum += maxScore;
        }
        if (total <= 0 || Math.abs(sum - total) > 0.001) {
            throw ApiException.unprocessable("RUBRIC_INVALID", "评分量规不合法",
                    "各维度满分之和必须等于量规总分。");
        }
        return input.deepCopy();
    }

    private List<String> normalizeAllowedFileTypes(List<String> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : input) {
            if (raw == null) {
                continue;
            }
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) {
                continue;
            }
            if (!value.matches("[a-z0-9]{1,16}")) {
                throw ApiException.unprocessable("ASSIGNMENT_FILE_TYPES_INVALID", "文件类型限制不合法",
                        "文件类型只能是 1-16 位字母或数字，例如 pdf、java。");
            }
            normalized.add(value);
        }
        if (normalized.size() > 30) {
            throw ApiException.unprocessable("ASSIGNMENT_FILE_TYPES_INVALID", "文件类型限制不合法",
                    "可限制的文件类型不能超过 30 种。");
        }
        return List.copyOf(normalized);
    }

    private String allowedFileTypesJson(List<String> types) {
        var array = objectMapper.createArrayNode();
        types.forEach(array::add);
        return writeJson(array, "[]");
    }

    private List<String> parseAllowedFileTypes(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                String value = item.asText("");
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
            return values;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private void validateScheduledPublishAt(OffsetDateTime scheduledPublishAt) {
        if (scheduledPublishAt != null && !scheduledPublishAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw ApiException.unprocessable("ASSIGNMENT_SCHEDULED_PUBLISH_INVALID", "定时发布时间不合法",
                    "定时发布时间必须晚于当前时间。");
        }
    }

    private void validateSchedule(OffsetDateTime openAt, OffsetDateTime deadlineAt,
                                  OffsetDateTime lateDeadlineAt, boolean allowLate,
                                  boolean allowMultiVersion, String effectiveRule) {
        if (deadlineAt == null) {
            throw ApiException.unprocessable("ASSIGNMENT_SCHEDULE_INVALID", "作业时间不合法",
                    "必须设置截止时间。");
        }
        if (openAt != null && !openAt.isBefore(deadlineAt)) {
            throw ApiException.unprocessable("ASSIGNMENT_SCHEDULE_INVALID", "作业时间不合法",
                    "开放时间必须早于截止时间。");
        }
        if (allowLate && (lateDeadlineAt == null || !lateDeadlineAt.isAfter(deadlineAt))) {
            throw ApiException.unprocessable("ASSIGNMENT_SCHEDULE_INVALID", "作业时间不合法",
                    "允许补交时必须设置晚于正常截止时间的补交截止时间。");
        }
        if (!allowLate && lateDeadlineAt != null) {
            throw ApiException.unprocessable("ASSIGNMENT_SCHEDULE_INVALID", "作业时间不合法",
                    "不允许补交时不能设置补交截止时间。");
        }
        if ("MANUAL".equals(effectiveRule) && !allowMultiVersion) {
            throw ApiException.unprocessable("ASSIGNMENT_EFFECTIVE_RULE_INVALID", "生效版本规则不合法",
                    "手动选择生效版本时必须允许多版本提交。");
        }
    }

    private void requireImpactConfirmationIfNeeded(Assignment assignment, Boolean confirmImpact) {
        if ("PUBLISHED".equals(assignment.getStatus())
                && assignment.getDeadlineAt() != null
                && assignment.getDeadlineAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))
                && !Boolean.TRUE.equals(confirmImpact)) {
            throw ApiException.conflict("ASSIGNMENT_IMPACT_CONFIRM_REQUIRED", "请确认修改影响",
                    "作业已截止，修改配置会影响迟交与查重结果，请确认后重试。");
        }
    }

    private void assertCourseActive(Course course) {
        if (!"ACTIVE".equals(course.getStatus())) {
            throw ApiException.conflict("COURSE_NOT_ACTIVE", "课程不可编辑", "课程已归档或未激活。");
        }
    }

    private void assertRowVersion(Assignment assignment, OptionalLong expectedRowVersion) {
        if (expectedRowVersion.isPresent()
                && expectedRowVersion.getAsLong()
                != (assignment.getRowVersion() == null ? 0L : assignment.getRowVersion())) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED",
                    "数据已被其他用户修改", "请刷新后重试。");
        }
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private String writeJson(JsonNode node, String fallback) {
        try {
            return node == null ? fallback : objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw ApiException.unprocessable("JSON_INVALID", "配置格式不合法", "无法保存配置。");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}


package com.edu.submission.admin.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edu.submission.admin.adapter.in.dto.AdminUserCreateRequest;
import com.edu.submission.admin.adapter.in.dto.AdminUserUpdateRequest;
import com.edu.submission.audit.AuditService;
import com.edu.submission.identity.adapter.in.dto.UserAccountView;
import com.edu.submission.identity.adapter.out.UserAccountMapper;
import com.edu.submission.identity.domain.UserAccount;
import com.edu.submission.identity.domain.UserRole;
import com.edu.submission.identity.domain.UserStatus;
import com.edu.submission.shared.api.ApiException;
import com.edu.submission.shared.api.PageQuery;
import com.edu.submission.shared.api.PageResult;
import com.edu.submission.shared.api.Pagination;
import com.edu.submission.shared.ids.Ids;
import com.edu.submission.shared.security.UserPrincipal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AdminUserService {

    private static final String DEFAULT_IMPORT_PASSWORD = "Admin@123";

    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    public AdminUserService(UserAccountMapper userAccountMapper, PasswordEncoder passwordEncoder,
                            AuditService auditService, JdbcTemplate jdbcTemplate) {
        this.userAccountMapper = userAccountMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public PageResult<UserAccountView> list(String q, String role, String status,
                                            Integer page, Integer size) {
        PageQuery query = PageQuery.of(page, size);
        List<UserAccount> users = userAccountMapper.selectList(
                buildFilter(q, role, status)
                        .orderByDesc(UserAccount::getCreatedAt)
                        .last("LIMIT " + query.size() + " OFFSET " + query.offset()));
        long total = userAccountMapper.selectCount(buildFilter(q, role, status));
        return new PageResult<>(users.stream().map(UserAccountView::masked).toList(),
                Pagination.of(query.page(), query.size(), total));
    }

    @Transactional(readOnly = true)
    public UserAccountView get(UUID id) {
        return UserAccountView.from(requireUser(id));
    }

    @Transactional
    public UserAccountView create(UserPrincipal principal, AdminUserCreateRequest request) {
        if (userAccountMapper.selectCount(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getUserNo, request.userNo().trim())) > 0) {
            throw ApiException.conflict("USER_NO_DUPLICATED", "学工号已存在", "学工号必须全局唯一。");
        }
        String loginName = request.loginName() == null || request.loginName().isBlank()
                ? request.userNo().trim() : request.loginName().trim();
        if (userAccountMapper.findByLogin(loginName) != null) {
            throw ApiException.conflict("LOGIN_NAME_DUPLICATED", "登录名已存在", "登录名必须唯一。");
        }
        UserAccount user = new UserAccount();
        user.setId(Ids.newUuidV7());
        user.setUserNo(request.userNo().trim());
        user.setLoginName(loginName);
        user.setDisplayName(request.displayName().trim());
        user.setEmail(blankToNull(request.email()));
        user.setPhone(blankToNull(request.phone()));
        user.setRole(UserRole.valueOf(request.role()));
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(1);
        user.setRowVersion(0L);
        userAccountMapper.insert(user);
        auditService.recordUser(principal.id(), "user.create", "USER", user.getId(), "SUCCESS",
                Map.of("role", user.getRole().name()), null);
        return UserAccountView.from(user);
    }

    /**
     * 用户 Excel 批量导入（FR-U-04）：表头（学号|姓名|登录名|邮箱|角色|初始密码）可省略，缺省按固定列；
     * 逐行校验并跳过失败行（记 errors），成功行 Argon2 哈希后插入；重复学号/登录名按跳过 + 记录。
     * 逐行处理（DataFormatter + 行迭代），不整体载入内存；非事务，单行失败不影响其它行。
     */
    public Map<String, Object> importUsers(UserPrincipal principal, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.unprocessable("IMPORT_FILE_REQUIRED", "请选择要导入的文件",
                    "文件内容不能为空。");
        }
        int succeeded = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        Set<String> userNosSeen = new HashSet<>();
        Set<String> loginNamesSeen = new HashSet<>();
        Set<String> dbUserNos = new HashSet<>(existingUserNos());
        Set<String> dbLoginNames = new HashSet<>(existingLoginNames());
        try (InputStream in = file.getInputStream()) {
            Workbook workbook;
            try {
                workbook = WorkbookFactory.create(in);
            } catch (Exception exception) {
                throw ApiException.unprocessable("IMPORT_FILE_INVALID", "文件格式不正确",
                        "请上传有效的 .xlsx 文件。");
            }
            try (workbook) {
                Sheet sheet = workbook.getSheetAt(0);
                DataFormatter formatter = new DataFormatter();
                int firstRow = sheet.getFirstRowNum();
                int lastRow = sheet.getLastRowNum();
                boolean hasHeader = hasHeaderRow(sheet.getRow(firstRow), formatter);
                int[] mapping = hasHeader
                        ? resolveHeader(sheet.getRow(firstRow), formatter)
                        : new int[]{0, 1, 2, 3, 4, 5};
                for (int r = firstRow + (hasHeader ? 1 : 0); r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) {
                        continue;
                    }
                    int rowNum = r + 1;
                    String userNo = cell(row, mapping[0], formatter);
                    String displayName = cell(row, mapping[1], formatter);
                    String loginName = cell(row, mapping[2], formatter);
                    String email = cell(row, mapping[3], formatter);
                    String role = cell(row, mapping[4], formatter).toUpperCase(Locale.ROOT);
                    String password = cell(row, mapping[5], formatter);
                    if (userNo.isEmpty() && displayName.isEmpty() && loginName.isEmpty() && role.isEmpty()) {
                        continue;
                    }
                    String error = validateImportRow(userNo, displayName, loginName, role, password,
                            userNosSeen, loginNamesSeen, dbUserNos, dbLoginNames);
                    if (error != null) {
                        errors.add(Map.of("row", rowNum, "message", error));
                        continue;
                    }
                    String finalLogin = loginName.isEmpty() ? userNo : loginName;
                    String finalPassword = password.isEmpty() ? DEFAULT_IMPORT_PASSWORD : password;
                    UserAccount user = new UserAccount();
                    user.setId(Ids.newUuidV7());
                    user.setUserNo(userNo);
                    user.setLoginName(finalLogin);
                    user.setDisplayName(displayName);
                    user.setEmail(email.isEmpty() ? null : email);
                    user.setRole(UserRole.valueOf(role));
                    user.setPasswordHash(passwordEncoder.encode(finalPassword));
                    user.setStatus(UserStatus.ACTIVE);
                    user.setTokenVersion(1);
                    user.setRowVersion(0L);
                    try {
                        userAccountMapper.insert(user);
                    } catch (DuplicateKeyException exception) {
                        errors.add(Map.of("row", rowNum, "message", "学号或登录名已存在，跳过"));
                        continue;
                    }
                    userNosSeen.add(userNo);
                    loginNamesSeen.add(finalLogin.toLowerCase(Locale.ROOT));
                    dbUserNos.add(userNo);
                    dbLoginNames.add(finalLogin.toLowerCase(Locale.ROOT));
                    succeeded++;
                }
            }
        } catch (IOException exception) {
            throw ApiException.unprocessable("IMPORT_FILE_READ_FAILED", "文件读取失败",
                    "请检查文件后重试。");
        }
        auditService.recordUser(principal.id(), "user.batch_import", "USER", null, "SUCCESS",
                Map.of("succeeded", succeeded, "skipped", errors.size()), null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("succeeded", succeeded);
        result.put("skipped", errors.size());
        result.put("errors", errors);
        return result;
    }

    /** 用户导入模板：表头 + 一行示例，xlsx。 */
    public byte[] importTemplate() {
        String[] headers = {"学号", "姓名", "登录名", "邮箱", "角色", "初始密码"};
        String[] example = {"20240001", "张三", "20240001", "zhangsan@example.com",
                "STUDENT", DEFAULT_IMPORT_PASSWORD};
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("用户导入");
            Row header = sheet.createRow(0);
            Row sample = sheet.createRow(1);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
                sample.createCell(i).setCellValue(example[i]);
                sheet.setColumnWidth(i, 18 * 256);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw ApiException.unprocessable("IMPORT_TEMPLATE_FAILED", "模板生成失败",
                    "请稍后重试。");
        }
    }

    private String validateImportRow(String userNo, String displayName, String loginName,
                                     String role, String password, Set<String> userNosSeen,
                                     Set<String> loginNamesSeen, Set<String> dbUserNos,
                                     Set<String> dbLoginNames) {
        if (userNo.isEmpty()) {
            return "学号不能为空";
        }
        if (userNo.length() > 32) {
            return "学号不能超过 32 个字符";
        }
        if (displayName.isEmpty()) {
            return "姓名不能为空";
        }
        if (displayName.length() > 64) {
            return "姓名不能超过 64 个字符";
        }
        if (role.isEmpty()) {
            return "角色不能为空";
        }
        if (!Set.of("STUDENT", "TEACHER", "ASSISTANT", "ADMIN").contains(role)) {
            return "角色不合法：" + role;
        }
        String finalLogin = loginName.isEmpty() ? userNo : loginName;
        if (finalLogin.length() > 64) {
            return "登录名不能超过 64 个字符";
        }
        String finalPassword = password.isEmpty() ? DEFAULT_IMPORT_PASSWORD : password;
        if (finalPassword.length() < 8 || finalPassword.length() > 128) {
            return "初始密码长度应为 8-128 个字符";
        }
        if (dbUserNos.contains(userNo) || userNosSeen.contains(userNo)) {
            return "学号已存在，跳过";
        }
        String normalizedLogin = finalLogin.toLowerCase(Locale.ROOT);
        if (dbLoginNames.contains(normalizedLogin) || loginNamesSeen.contains(normalizedLogin)) {
            return "登录名已存在，跳过";
        }
        return null;
    }

    private boolean hasHeaderRow(Row row, DataFormatter formatter) {
        if (row == null) {
            return false;
        }
        String first = cell(row, 0, formatter);
        return first.contains("学号") || first.equalsIgnoreCase("userNo")
                || first.equalsIgnoreCase("user_no");
    }

    private int[] resolveHeader(Row row, DataFormatter formatter) {
        int[] mapping = new int[]{-1, -1, -1, -1, -1, -1};
        int last = row.getLastCellNum();
        for (int c = 0; c < last; c++) {
            String header = cell(row, c, formatter);
            if (header.contains("学号")) {
                mapping[0] = c;
            } else if (header.contains("姓名")) {
                mapping[1] = c;
            } else if (header.contains("登录名")) {
                mapping[2] = c;
            } else if (header.contains("邮箱")) {
                mapping[3] = c;
            } else if (header.contains("角色")) {
                mapping[4] = c;
            } else if (header.contains("密码")) {
                mapping[5] = c;
            }
        }
        if (mapping[0] < 0 || mapping[1] < 0 || mapping[4] < 0) {
            throw ApiException.unprocessable("IMPORT_HEADER_INVALID", "表头不完整",
                    "表头至少需要包含：学号、姓名、角色。");
        }
        if (mapping[2] < 0) {
            mapping[2] = mapping[0];
        }
        return mapping;
    }

    private String cell(Row row, int index, DataFormatter formatter) {
        if (row == null || index < 0) {
            return "";
        }
        org.apache.poi.ss.usermodel.Cell cell = row.getCell(index);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private Set<String> existingUserNos() {
        return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT user_no FROM app.user_account", String.class));
    }

    private Set<String> existingLoginNames() {
        List<String> names = jdbcTemplate.queryForList(
                "SELECT login_name::text FROM app.user_account", String.class);
        Set<String> normalized = new HashSet<>(names.size());
        for (String name : names) {
            normalized.add(name.toLowerCase(Locale.ROOT));
        }
        return normalized;
    }

    @Transactional
    public UserAccountView update(UserPrincipal principal, UUID id, AdminUserUpdateRequest request) {
        UserAccount user = requireUser(id);
        if (request.displayName() != null && !request.displayName().isBlank()) {
            user.setDisplayName(request.displayName().trim());
        }
        if (request.email() != null) {
            user.setEmail(blankToNull(request.email()));
        }
        if (request.phone() != null) {
            user.setPhone(blankToNull(request.phone()));
        }
        if (request.role() != null) {
            user.setRole(UserRole.valueOf(request.role()));
            user.setTokenVersion(user.getTokenVersion() + 1);
        }
        user.setRowVersion((user.getRowVersion() == null ? 0L : user.getRowVersion()) + 1);
        userAccountMapper.updateById(user);
        auditService.recordUser(principal.id(), "user.update", "USER", user.getId(), "SUCCESS",
                Map.of(), null);
        return UserAccountView.from(user);
    }

    @Transactional
    public void deactivate(UserPrincipal principal, UUID id) {
        if (principal.id().equals(id)) {
            throw ApiException.conflict("USER_SELF_DISABLE_FORBIDDEN", "不能停用当前账号",
                    "请使用其他管理员账号执行此操作。");
        }
        UserAccount user = requireUser(id);
        user.setStatus(UserStatus.DISABLED);
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setRowVersion((user.getRowVersion() == null ? 0L : user.getRowVersion()) + 1);
        userAccountMapper.updateById(user);
        auditService.recordUser(principal.id(), "user.disable", "USER", user.getId(), "SUCCESS",
                Map.of(), null);
    }

    @Transactional
    public void activate(UserPrincipal principal, UUID id) {
        UserAccount user = requireUser(id);
        user.setStatus(UserStatus.ACTIVE);
        user.setRowVersion((user.getRowVersion() == null ? 0L : user.getRowVersion()) + 1);
        userAccountMapper.updateById(user);
        auditService.recordUser(principal.id(), "user.enable", "USER", user.getId(), "SUCCESS",
                Map.of(), null);
    }

    @Transactional
    public void unlock(UserPrincipal principal, UUID id) {
        UserAccount user = requireUser(id);
        if (user.getStatus() == UserStatus.LOCKED) {
            user.setStatus(UserStatus.ACTIVE);
            user.setRowVersion((user.getRowVersion() == null ? 0L : user.getRowVersion()) + 1);
            userAccountMapper.updateById(user);
        }
        auditService.recordUser(principal.id(), "user.unlock", "USER", user.getId(), "SUCCESS",
                Map.of(), null);
    }

    @Transactional
    public void resetPassword(UserPrincipal principal, UUID id, String newPassword) {
        UserAccount user = requireUser(id);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setRowVersion((user.getRowVersion() == null ? 0L : user.getRowVersion()) + 1);
        userAccountMapper.updateById(user);
        auditService.recordUser(principal.id(), "user.password.reset", "USER", user.getId(), "SUCCESS",
                Map.of(), null);
    }

    /**
     * 物理删除用户（FR-U-04 补齐"删"）。
     *
     * <p>保护规则：仅 ADMIN（由控制器 {@code @PreAuthorize} 保证）；不能删除自己；若用户存在提交、
     * 拥有课程、或为任何课程 ACTIVE 成员，返回 409 USER_IN_USE，提示先处理关联数据。
     *
     * <p>删除方式说明：确认 {@code audit_log.actor_id} 未建立外键（仅 uuid），因此选择物理删除
     * {@code app.user_account}；若仍有其它外键引用（如历史通知、评审记录、已移除的花名册等），
     * 数据库 {@code ON DELETE RESTRICT} 会拒绝删除，此时同样转换为 409 USER_IN_USE，避免 500。
     */
    @Transactional
    public void deleteUser(UserPrincipal principal, UUID id) {
        if (principal.id().equals(id)) {
            throw ApiException.conflict("USER_SELF_DELETE_FORBIDDEN", "不能删除当前账号",
                    "请使用其他管理员账号执行此操作。");
        }
        UserAccount user = requireUser(id);
        if (hasSubmissions(id) || ownsCourses(id) || isActiveMember(id)) {
            throw ApiException.conflict("USER_IN_USE", "用户存在关联数据，无法删除",
                    "该用户存在提交、拥有的课程或为课程成员，请先处理关联数据后再删除。");
        }
        try {
            userAccountMapper.deleteById(id);
        } catch (DataIntegrityViolationException exception) {
            // 其它残留外键引用（通知、评审、已移除花名册等）同样按占用处理
            throw ApiException.conflict("USER_IN_USE", "用户存在关联数据，无法删除",
                    "该用户仍被其它数据引用，请先处理关联数据后再删除。");
        }
        auditService.recordUser(principal.id(), "user.delete", "USER", id, "SUCCESS",
                Map.of("userNo", user.getUserNo()), null);
    }

    private boolean hasSubmissions(UUID id) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app.submission WHERE submitter_id = ?", Long.class, id);
        return count != null && count > 0;
    }

    private boolean ownsCourses(UUID id) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app.course WHERE owner_id = ?", Long.class, id);
        return count != null && count > 0;
    }

    private boolean isActiveMember(UUID id) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app.course_member WHERE user_id = ? AND status = 'ACTIVE'",
                Long.class, id);
        return count != null && count > 0;
    }

    private LambdaQueryWrapper<UserAccount> buildFilter(String q, String role, String status) {
        LambdaQueryWrapper<UserAccount> wrapper = new LambdaQueryWrapper<>();
        if (q != null && !q.isBlank()) {
            String keyword = q.trim();
            wrapper.and(inner -> inner.like(UserAccount::getDisplayName, keyword)
                    .or().like(UserAccount::getUserNo, keyword)
                    .or().like(UserAccount::getLoginName, keyword));
        }
        if (role != null && !role.isBlank()) {
            wrapper.eq(UserAccount::getRole, UserRole.valueOf(role));
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(UserAccount::getStatus, UserStatus.valueOf(status));
        }
        return wrapper;
    }

    private UserAccount requireUser(UUID id) {
        UserAccount user = userAccountMapper.selectById(id);
        if (user == null) {
            throw ApiException.notFound("USER_NOT_FOUND", "用户不存在", "用户可能已被删除。");
        }
        return user;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}


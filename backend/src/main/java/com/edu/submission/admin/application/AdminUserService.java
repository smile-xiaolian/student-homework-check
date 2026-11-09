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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserService {

    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AdminUserService(UserAccountMapper userAccountMapper, PasswordEncoder passwordEncoder,
                            AuditService auditService) {
        this.userAccountMapper = userAccountMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
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


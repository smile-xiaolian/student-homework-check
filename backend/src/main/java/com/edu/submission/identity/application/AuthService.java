package com.edu.submission.identity.application;

import com.edu.submission.audit.AuditService;
import com.edu.submission.identity.adapter.in.dto.LoginResponse.SessionUser;
import com.edu.submission.identity.adapter.in.dto.MeView;
import com.edu.submission.identity.adapter.out.UserAccountMapper;
import com.edu.submission.identity.domain.UserAccount;
import com.edu.submission.identity.domain.UserStatus;
import com.edu.submission.shared.api.ApiException;
import com.edu.submission.shared.security.JwtService;
import com.edu.submission.shared.security.UserPrincipal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final IdentityQueryService identityQueryService;
    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptGuard loginAttemptGuard;
    private final JwtService jwtService;
    private final AuditService auditService;

    public AuthService(IdentityQueryService identityQueryService,
                       UserAccountMapper userAccountMapper,
                       PasswordEncoder passwordEncoder,
                       LoginAttemptGuard loginAttemptGuard,
                       JwtService jwtService,
                       AuditService auditService) {
        this.identityQueryService = identityQueryService;
        this.userAccountMapper = userAccountMapper;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptGuard = loginAttemptGuard;
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    @Transactional
    public AuthResult login(String loginName, String password, String ip) {
        String normalizedLogin = loginName == null ? "" : loginName.trim();
        loginAttemptGuard.assertCanAttempt(normalizedLogin);
        Optional<UserAccount> candidate = identityQueryService.findByLogin(normalizedLogin);
        if (candidate.isEmpty()) {
            loginAttemptGuard.recordFailure(normalizedLogin);
            auditService.record(null, "SYSTEM", "auth.login", "USER", null, "FAILED",
                    Map.of("reason", "bad_credentials"), ip, null);
            throw ApiException.unauthorized("AUTH_BAD_CREDENTIALS", "登录失败", "账号或密码不正确。");
        }

        UserAccount user = candidate.get();
        if (user.getStatus() == UserStatus.DISABLED) {
            auditService.recordUser(user.getId(), "auth.login", "USER", user.getId(), "DENIED",
                    Map.of("reason", "disabled"), ip);
            throw ApiException.forbidden("AUTH_ACCOUNT_DISABLED", "账号已停用", "请联系管理员启用账号。");
        }
        if (user.getStatus() == UserStatus.LOCKED) {
            throw new ApiException(HttpStatus.LOCKED, "AUTH_ACCOUNT_LOCKED", "账号暂时不可用",
                    "账号已被锁定，请联系管理员或稍后重试。");
        }
        if (user.getStatus() != UserStatus.ACTIVE || user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            loginAttemptGuard.recordFailure(normalizedLogin);
            auditService.recordUser(user.getId(), "auth.login", "USER", user.getId(), "FAILED",
                    Map.of("reason", "bad_credentials"), ip);
            throw ApiException.unauthorized("AUTH_BAD_CREDENTIALS", "登录失败", "账号或密码不正确。");
        }

        loginAttemptGuard.reset(normalizedLogin);
        user.setLastLoginAt(OffsetDateTime.now(ZoneOffset.UTC));
        user.setRowVersion((user.getRowVersion() == null ? 0L : user.getRowVersion()) + 1);
        userAccountMapper.updateById(user);
        UserPrincipal principal = UserPrincipal.from(user);
        auditService.recordUser(user.getId(), "auth.login", "USER", user.getId(), "SUCCESS",
                Map.of(), ip);
        return new AuthResult(
                jwtService.createAccessToken(principal),
                jwtService.createRefreshToken(principal),
                jwtService.accessTokenSeconds(),
                sessionUser(user));
    }

    @Transactional(readOnly = true)
    public AuthResult refresh(String refreshToken, String ip) {
        JwtService.ParsedToken parsed = jwtService.parse(refreshToken, JwtService.TYPE_REFRESH)
                .orElseThrow(() -> ApiException.unauthorized(
                        "AUTH_REFRESH_INVALID", "会话已失效", "请重新登录。"));
        UserAccount user = identityQueryService.findById(parsed.userId())
                .filter(candidate -> candidate.getStatus() == UserStatus.ACTIVE)
                .filter(candidate -> candidate.getTokenVersion() != null
                        && candidate.getTokenVersion() == parsed.tokenVersion())
                .orElseThrow(() -> ApiException.unauthorized(
                        "AUTH_REFRESH_INVALID", "会话已失效", "请重新登录。"));
        UserPrincipal principal = UserPrincipal.from(user);
        auditService.recordUser(user.getId(), "auth.refresh", "USER", user.getId(), "SUCCESS",
                Map.of(), ip);
        return new AuthResult(
                jwtService.createAccessToken(principal),
                jwtService.createRefreshToken(principal),
                jwtService.accessTokenSeconds(),
                sessionUser(user));
    }

    @Transactional
    public void logout(UserPrincipal principal, String ip) {
        if (principal == null) {
            return;
        }
        UserAccount user = identityQueryService.findById(principal.id()).orElse(null);
        if (user == null) {
            return;
        }
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setRowVersion((user.getRowVersion() == null ? 0L : user.getRowVersion()) + 1);
        userAccountMapper.updateById(user);
        auditService.recordUser(user.getId(), "auth.logout", "USER", user.getId(), "SUCCESS",
                Map.of(), ip);
    }

    @Transactional(readOnly = true)
    public MeView me(UserPrincipal principal) {
        UserAccount user = identityQueryService.findById(principal.id())
                .orElseThrow(() -> ApiException.unauthorized(
                        "AUTH_REQUIRED", "会话已失效", "请重新登录。"));
        return MeView.from(user);
    }

    @Transactional
    public MeView updateProfile(UserPrincipal principal, String displayName, String email, String phone) {
        UserAccount user = identityQueryService.findById(principal.id())
                .orElseThrow(() -> ApiException.unauthorized(
                        "AUTH_REQUIRED", "会话已失效", "请重新登录。"));
        if (displayName != null && !displayName.isBlank()) {
            user.setDisplayName(displayName.trim());
        }
        user.setEmail(blankToNull(email));
        user.setPhone(blankToNull(phone));
        user.setRowVersion((user.getRowVersion() == null ? 0L : user.getRowVersion()) + 1);
        userAccountMapper.updateById(user);
        auditService.recordUser(user.getId(), "profile.update", "USER", user.getId(), "SUCCESS",
                Map.of("fields", "displayName,email,phone"), null);
        return MeView.from(user);
    }

    @Transactional
    public void changePassword(UserPrincipal principal, String currentPassword, String newPassword) {
        UserAccount user = identityQueryService.findById(principal.id())
                .orElseThrow(() -> ApiException.unauthorized(
                        "AUTH_REQUIRED", "会话已失效", "请重新登录。"));
        if (user.getPasswordHash() == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("AUTH_CURRENT_PASSWORD_INVALID", "原密码不正确",
                    "请检查原密码后重试。");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setRowVersion((user.getRowVersion() == null ? 0L : user.getRowVersion()) + 1);
        userAccountMapper.updateById(user);
        auditService.recordUser(user.getId(), "auth.password.change", "USER", user.getId(), "SUCCESS",
                Map.of(), null);
    }

    private SessionUser sessionUser(UserAccount user) {
        return new SessionUser(user.getId(), user.getUserNo(), user.getDisplayName(),
                java.util.List.of(user.getRole().name()), user.getRole().permissions(), false);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record AuthResult(String accessToken, String refreshToken, long expiresIn, SessionUser user) {
    }
}


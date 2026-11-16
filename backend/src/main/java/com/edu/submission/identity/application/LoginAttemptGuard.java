package com.edu.submission.identity.application;

import com.edu.submission.shared.api.ApiException;
import com.edu.submission.shared.ids.Ids;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录限速守卫（R13）：进程内 {@code ConcurrentHashMap} 已改为 {@code app.login_attempt} 表读写，
 * 使多实例部署下登录失败计数与锁定状态跨实例共享。
 *
 * <p>对外方法签名保持不变（assertCanAttempt / recordFailure / reset），避免改动 AuthService 调用点；
 * 语义上 recordFailure 对应 onFailure、reset 对应 onSuccess。阈值与锁定时长读取
 * {@code security.login_max_failures}、{@code security.login_lock_minutes}，读不到用默认 5 次 / 30 分钟。
 *
 * <p>recordFailure / reset 使用 REQUIRES_NEW：登录事务最终回滚时（失败）仍保留失败计数，
 * 成功登录提交时同步清理锁定记录。
 */
@Component
public class LoginAttemptGuard {

    private static final int DEFAULT_MAX_FAILURES = 5;
    private static final int DEFAULT_LOCK_MINUTES = 30;

    private final JdbcTemplate jdbcTemplate;

    public LoginAttemptGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 若账号处于锁定窗口内，抛 423 LOCKED。 */
    public void assertCanAttempt(String loginName) {
        String key = key(loginName);
        if (key.isEmpty()) {
            return;
        }
        OffsetDateTime lockedUntil = jdbcTemplate.query(
                "SELECT locked_until FROM app.login_attempt WHERE login_name = ?",
                rs -> rs.next() ? rs.getObject("locked_until", OffsetDateTime.class) : null, key);
        if (lockedUntil != null && lockedUntil.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new ApiException(HttpStatus.LOCKED, "AUTH_ACCOUNT_LOCKED", "账号暂时不可用",
                    "登录失败次数过多，请稍后再试。");
        }
    }

    /** 登录失败：计数 +1（封顶 maxFailures），达到阈值时锁定 lockMinutes。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String loginName) {
        String key = key(loginName);
        if (key.isEmpty()) {
            return;
        }
        int maxFailures = intConfig("security.login_max_failures", DEFAULT_MAX_FAILURES);
        int lockMinutes = intConfig("security.login_lock_minutes", DEFAULT_LOCK_MINUTES);
        // 注：用别名 la 引用 ON CONFLICT 的现有行，避免 schema 限定名歧义；间隔用 double precision * interval
        jdbcTemplate.update("""
                INSERT INTO app.login_attempt AS la (id, login_name, failed_count, locked_until, last_attempt_at, updated_at)
                VALUES (?, ?, 1, CASE WHEN ? <= 1 THEN now() + (?::double precision * interval '1 minute') ELSE NULL END, now(), now())
                ON CONFLICT (login_name) DO UPDATE SET
                    failed_count = CASE WHEN la.failed_count >= ?
                                        THEN la.failed_count
                                        ELSE la.failed_count + 1 END,
                    last_attempt_at = now(),
                    locked_until = CASE WHEN la.failed_count + 1 >= ?
                                        THEN now() + (?::double precision * interval '1 minute')
                                        ELSE la.locked_until END,
                    updated_at = now()
                """,
                Ids.newUuidV7(), key, maxFailures, lockMinutes, maxFailures, maxFailures, lockMinutes);
    }

    /** 登录成功：清除该登录名的失败与锁定记录。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reset(String loginName) {
        String key = key(loginName);
        if (key.isEmpty()) {
            return;
        }
        jdbcTemplate.update("DELETE FROM app.login_attempt WHERE login_name = ?", key);
    }

    private String key(String loginName) {
        return loginName == null ? "" : loginName.trim().toLowerCase(Locale.ROOT);
    }

    private int intConfig(String configKey, int fallback) {
        try {
            Integer value = jdbcTemplate.query(
                    "SELECT value::text FROM app.system_config WHERE config_key = ?",
                    rs -> rs.next() ? Integer.parseInt(rs.getString(1)) : null, configKey);
            return value == null || value <= 0 ? fallback : value;
        } catch (Exception exception) {
            return fallback;
        }
    }
}

package com.edu.submission.identity.application;

import com.edu.submission.shared.api.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LoginAttemptGuard {

    private static final int MAX_FAILURES = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(30);

    private final ConcurrentMap<String, AttemptState> attempts = new ConcurrentHashMap<>();

    public void assertCanAttempt(String loginName) {
        AttemptState state = attempts.get(key(loginName));
        if (state == null) {
            return;
        }
        if (state.lockedUntil() != null && state.lockedUntil().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.LOCKED, "AUTH_ACCOUNT_LOCKED", "账号暂时不可用",
                    "登录失败次数过多，请稍后再试。");
        }
    }

    public void recordFailure(String loginName) {
        attempts.compute(key(loginName), (key, current) -> {
            Instant now = Instant.now();
            AttemptState state = current;
            if (state == null || (state.lockedUntil() != null && state.lockedUntil().isBefore(now))) {
                state = new AttemptState(0, null);
            }
            int failures = state.failures() + 1;
            Instant lockUntil = failures >= MAX_FAILURES ? now.plus(LOCK_DURATION) : null;
            return new AttemptState(failures, lockUntil);
        });
    }

    public void reset(String loginName) {
        attempts.remove(key(loginName));
    }

    private String key(String loginName) {
        return loginName == null ? "" : loginName.trim().toLowerCase(Locale.ROOT);
    }

    private record AttemptState(int failures, Instant lockedUntil) {
    }
}


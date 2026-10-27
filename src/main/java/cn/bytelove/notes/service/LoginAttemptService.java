package cn.bytelove.notes.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败计数。单实例部署，放内存即可；多实例要换成 Redis。
 */
@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(5);

    private record Attempt(int count, Instant lockedUntil) {
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public boolean isBlocked(String username) {
        Attempt a = attempts.get(key(username));
        if (a == null || a.lockedUntil() == null) {
            return false;
        }
        if (Instant.now().isAfter(a.lockedUntil())) {
            attempts.remove(key(username));
            return false;
        }
        return true;
    }

    public long remainingLockSeconds(String username) {
        Attempt a = attempts.get(key(username));
        if (a == null || a.lockedUntil() == null) {
            return 0;
        }
        long seconds = Duration.between(Instant.now(), a.lockedUntil()).toSeconds();
        return Math.max(seconds, 0);
    }

    public void loginFailed(String username) {
        attempts.compute(key(username), (k, old) -> {
            int count = (old == null ? 0 : old.count()) + 1;
            if (count >= MAX_ATTEMPTS) {
                return new Attempt(count, Instant.now().plus(LOCK_DURATION));
            }
            return new Attempt(count, null);
        });
    }

    public void loginSucceeded(String username) {
        attempts.remove(key(username));
    }

    private String key(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }
}

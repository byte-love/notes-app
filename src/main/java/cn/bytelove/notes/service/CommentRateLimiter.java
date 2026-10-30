package cn.bytelove.notes.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发评论的频率限制：同一个人 10 秒内只能发一条。
 * 和注册限流一样放内存（单实例部署），只记最后一次的时间，重启即清空。
 */
@Service
public class CommentRateLimiter {

    private static final Duration WINDOW = Duration.ofSeconds(10);

    private final Map<Long, Instant> lastAt = new ConcurrentHashMap<>();

    /** 可以发返回 true（并记下这次时间），10 秒内第二次返回 false。 */
    public boolean tryAcquire(Long userId) {
        if (userId == null) {
            return false;
        }
        Instant now = Instant.now();
        boolean[] ok = {false};
        lastAt.compute(userId, (id, last) -> {
            if (last == null || !last.plus(WINDOW).isAfter(now)) {
                ok[0] = true;
                return now;
            }
            return last;
        });
        return ok[0];
    }
}

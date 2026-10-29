package cn.bytelove.notes.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 注册频率限制。单实例部署，放内存即可；多实例要换成 Redis。
 * 只按来源 IP 计数，不落库，重启即清空。
 */
@Service
public class RegisterRateLimiter {

    private static final int LIMIT = 3;
    private static final Duration WINDOW = Duration.ofHours(1);

    private final Map<String, ConcurrentLinkedDeque<Instant>> hits = new ConcurrentHashMap<>();

    /** 拿到名额返回 true，超限返回 false。 */
    public boolean tryAcquire(String ip) {
        String key = ip == null || ip.isBlank() ? "unknown" : ip;
        Instant now = Instant.now();
        ConcurrentLinkedDeque<Instant> deque = hits.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        Instant edge = now.minus(WINDOW);
        while (true) {
            Instant head = deque.peekFirst();
            if (head == null || !head.isBefore(edge)) {
                break;
            }
            deque.pollFirst();
        }
        if (deque.size() >= LIMIT) {
            return false;
        }
        deque.addLast(now);
        return true;
    }
}

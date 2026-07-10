package ec.tarius.forseti.shared.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Rate limiter en memoria (bucket4j).
 * Sprint 3: migrar a Redis-backed si el sistema crece.
 *
 * Buckets por (key, scope):
 * - login:<ip>     → 5 intentos / 5 min
 * - login:<email>  → 10 intentos / 5 min
 * - register:<ip>  → 3 registros / 1h
 * - recovery:<ip>  → 3 intentos / 1h
 * - recovery:<email>→ 3 intentos / 1h
 */
@Component
public class RateLimiter {

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public boolean intentar(String key, int capacidad, Duration refill) {
        Bucket b = buckets.computeIfAbsent(key, k -> Bucket.builder()
            .addLimit(Bandwidth.classic(capacidad, Refill.intervally(capacidad, refill)))
            .build());
        return b.tryConsume(1);
    }

    public boolean loginPorIp(String ip) {
        return intentar("login:ip:" + ip, 5, Duration.ofMinutes(5));
    }

    public boolean loginPorEmail(String email) {
        return intentar("login:email:" + email.toLowerCase(), 10, Duration.ofMinutes(5));
    }

    public boolean registerPorIp(String ip) {
        return intentar("register:ip:" + ip, 3, Duration.ofHours(1));
    }

    public boolean recoveryPorIp(String ip) {
        return intentar("recovery:ip:" + ip, 3, Duration.ofHours(1));
    }

    public boolean recoveryPorEmail(String email) {
        return intentar("recovery:email:" + email.toLowerCase(), 3, Duration.ofHours(1));
    }
}

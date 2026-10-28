package com.distributed.jobplatform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis-based rate limiting using a sliding-window counter.
 * Uses an atomic Lua script for thread-safe increments.
 *
 * Configuration:
 *   app.rate-limit.requests-per-minute: 100
 *   app.rate-limit.fail-open: true (if Redis unavailable, allow requests)
 *
 * Key design: rate_limit:{userId}:{windowIndex}
 * Two adjacent minute buckets (current + previous) are combined with a
 * proportional weight so bursts that cross a minute boundary still respect
 * the configured per-minute limit.
 * TTL: 2 * window size (to handle edge cases)
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final StringRedisTemplate redisTemplate;
    private final int maxRequestsPerMinute;
    private final boolean failOpen;

    /**
     * Atomic sliding-window-counter script.
     *
     * Uses two fixed buckets (current and previous minute) and weights the
     * previous bucket by how much of it still falls inside the rolling 60s
     * window. This prevents the classic fixed-window flaw where a burst at a
     * minute boundary can exceed the limit up to 2x, while staying a single
     * atomic Redis operation (no check-then-act race).
     *
     * Returns {allowed, used} where allowed is 1 or 0.
     */
    private static final String RATE_LIMIT_SCRIPT =
        "local currentKey = KEYS[1]\n" +
        "local previousKey = KEYS[2]\n" +
        "local limit = tonumber(ARGV[1])\n" +
        "local windowSeconds = tonumber(ARGV[2])\n" +
        "local nowMs = tonumber(ARGV[3])\n" +
        "local windowStartMs = tonumber(ARGV[4])\n" +
        "\n" +
        "local current = tonumber(redis.call('GET', currentKey) or '0')\n" +
        "local previous = tonumber(redis.call('GET', previousKey) or '0')\n" +
        "\n" +
        "local elapsed = (nowMs - windowStartMs) / 1000\n" +
        "local weight = 1 - math.max(0, math.min(1, elapsed / windowSeconds))\n" +
        "local used = current + previous * weight\n" +
        "\n" +
        "if used >= limit then\n" +
        "    return {0, used}\n" +
        "end\n" +
        "\n" +
        "current = redis.call('INCR', currentKey)\n" +
        "if current == 1 then\n" +
        "    redis.call('EXPIRE', currentKey, windowSeconds * 2)\n" +
        "end\n" +
        "return {1, used}\n";

    private static final long WINDOW_SECONDS = 60;

    private final DefaultRedisScript<List> redisScript;

    public RateLimitService(StringRedisTemplate redisTemplate,
                           @Value("${app.rate-limit.requests-per-minute:100}") int maxRequestsPerMinute,
                           @Value("${app.rate-limit.fail-open:true}") boolean failOpen) {
        this.redisTemplate = redisTemplate;
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.failOpen = failOpen;
        this.redisScript = new DefaultRedisScript<>(RATE_LIMIT_SCRIPT, List.class);
    }

    /**
     * Result of a rate limit check, carrying the data needed for standard
     * rate-limit response headers (X-RateLimit-Limit, X-RateLimit-Remaining,
     * Retry-After).
     */
    public static class RateLimitResult {
        private final boolean allowed;
        private final long limit;
        private final long remaining;
        private final long retryAfterSeconds;

        public RateLimitResult(boolean allowed, long limit, long remaining, long retryAfterSeconds) {
            this.allowed = allowed;
            this.limit = limit;
            this.remaining = remaining;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public boolean isAllowed() { return allowed; }
        public long getLimit() { return limit; }
        public long getRemaining() { return remaining; }
        public long getRetryAfterSeconds() { return retryAfterSeconds; }
    }

    /**
     * Atomic sliding-window check. The Lua script performs GET + compare + INCR
     * + EXPIRE in one atomic Redis operation, so concurrent requests can never
     * exceed the limit (no check-then-act race).
     *
     * @param userId the user identifier (authenticated username)
     * @return RateLimitResult with allowed flag and header values
     */
    public RateLimitResult checkRateLimit(String userId) {
        try {
            long nowMs = System.currentTimeMillis();
            long windowIndex = nowMs / (WINDOW_SECONDS * 1000);
            long windowStartMs = windowIndex * WINDOW_SECONDS * 1000;
            String currentKey = "rate_limit:" + userId + ":" + windowIndex;
            String previousKey = "rate_limit:" + userId + ":" + (windowIndex - 1);

            List<Long> result = redisTemplate.execute(redisScript,
                Arrays.asList(currentKey, previousKey),
                String.valueOf(maxRequestsPerMinute),
                String.valueOf(WINDOW_SECONDS),
                String.valueOf(nowMs),
                String.valueOf(windowStartMs));

            if (result == null || result.size() != 2) {
                return new RateLimitResult(failOpen, maxRequestsPerMinute,
                    failOpen ? maxRequestsPerMinute : 0, WINDOW_SECONDS);
            }

            long allowed = result.get(0);
            long used = Math.max(0, result.get(1));

            if (allowed == 1L) {
                long remaining = Math.max(0, maxRequestsPerMinute - used - 1);
                return new RateLimitResult(true, maxRequestsPerMinute, remaining, 0);
            }
            return new RateLimitResult(false, maxRequestsPerMinute, 0,
                Math.max(1, WINDOW_SECONDS - (nowMs - windowStartMs) / 1000));
        } catch (Exception e) {
            if (failOpen) {
                log.warn("Rate limit check failed, failing open: {}", e.getMessage());
                return new RateLimitResult(true, maxRequestsPerMinute, maxRequestsPerMinute, 0);
            } else {
                log.error("Rate limit check failed, failing closed: {}", e.getMessage());
                return new RateLimitResult(false, maxRequestsPerMinute, 0, WINDOW_SECONDS);
            }
        }
    }

    /**
     * Check if the request is allowed for the given user.
     * @param userId the user identifier
     * @return true if allowed, false if rate limited
     */
    public boolean isAllowed(String userId) {
        return checkRateLimit(userId).isAllowed();
    }

    /**
     * Get remaining requests for the user in the current window.
     */
    public long getRemainingRequests(String userId) {
        try {
            long nowMs = System.currentTimeMillis();
            long windowIndex = nowMs / (WINDOW_SECONDS * 1000);
            long windowStartMs = windowIndex * WINDOW_SECONDS * 1000;
            String currentValue = redisTemplate.opsForValue()
                .get("rate_limit:" + userId + ":" + windowIndex);
            String prevValue = redisTemplate.opsForValue()
                .get("rate_limit:" + userId + ":" + (windowIndex - 1));
            long current = currentValue != null ? Long.parseLong(currentValue) : 0;
            long previous = prevValue != null ? Long.parseLong(prevValue) : 0;
            double weight = 1 - (double) (nowMs - windowStartMs) / (WINDOW_SECONDS * 1000);
            long used = (long) Math.floor(current + previous * Math.max(0, Math.min(1, weight)));
            return Math.max(0, maxRequestsPerMinute - used);
        } catch (Exception e) {
            return failOpen ? maxRequestsPerMinute : 0;
        }
    }

    public int getMaxRequestsPerMinute() {
        return maxRequestsPerMinute;
    }
}

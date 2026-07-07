package com.example.agentdemo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.LongSupplier;

final class RedisApiRateLimiter implements ApiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisApiRateLimiter.class);

    private final StringRedisTemplate redisTemplate;
    private final int requestsPerMinute;
    private final Duration window;
    private final String keyPrefix;
    private final LongSupplier currentTimeMillis;

    RedisApiRateLimiter(StringRedisTemplate redisTemplate, int requestsPerMinute) {
        this(redisTemplate, requestsPerMinute, Duration.ofMillis(InMemoryApiRateLimiter.WINDOW_MS),
                "agent-demo:rate-limit:", System::currentTimeMillis);
    }

    RedisApiRateLimiter(StringRedisTemplate redisTemplate, int requestsPerMinute, Duration window,
            String keyPrefix, LongSupplier currentTimeMillis) {
        this.redisTemplate = redisTemplate;
        this.requestsPerMinute = requestsPerMinute;
        this.window = window;
        this.keyPrefix = keyPrefix;
        this.currentTimeMillis = currentTimeMillis;
    }

    @Override
    public boolean allow(String key) {
        if (requestsPerMinute <= 0) {
            return true;
        }
        String redisKey = redisKey(key);
        try {
            Long count = redisTemplate.opsForValue().increment(redisKey);
            if (count == null) {
                log.warn("Redis rate-limit increment returned null for key {}", redisKey);
                return false;
            }
            if (count == 1L) {
                redisTemplate.expire(redisKey, window.multipliedBy(2));
            }
            return count <= requestsPerMinute;
        }
        catch (RuntimeException ex) {
            log.warn("Redis rate-limit check failed for key {}; denying request", redisKey, ex);
            return false;
        }
    }

    private String redisKey(String key) {
        long windowId = currentTimeMillis.getAsLong() / window.toMillis();
        return keyPrefix + windowId + ":" + sha256(key);
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

}

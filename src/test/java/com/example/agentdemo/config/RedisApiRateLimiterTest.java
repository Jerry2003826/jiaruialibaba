package com.example.agentdemo.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisApiRateLimiterTest {

    @Test
    @SuppressWarnings("unchecked")
    void incrementsSharedRedisWindowKeyAndExpiresTheFirstHit() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L, 2L);
        RedisApiRateLimiter limiter = new RedisApiRateLimiter(redisTemplate, 1, Duration.ofSeconds(60),
                "test:rate-limit:", () -> 0L);

        assertThat(limiter.allow("principal:user|POST|/api/chat")).isTrue();
        assertThat(limiter.allow("principal:user|POST|/api/chat")).isFalse();

        verify(values, times(2)).increment("test:rate-limit:0:de576498d8efe434b09dd115b4d506524dedb0dd685009a728b7d7cc68be4a7f");
        verify(redisTemplate).expire(
                "test:rate-limit:0:de576498d8efe434b09dd115b4d506524dedb0dd685009a728b7d7cc68be4a7f",
                Duration.ofSeconds(120));
    }

}

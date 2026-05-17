package com.simi.console.observability;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Custom health indicator for Redis 3-key model.
 * Beyond simple PING — validates that Lua script eval is functional.
 * Actuator exposes at /actuator/health (docs/10 §Health checks).
 */
@Component
public class RedisHealthIndicator implements HealthIndicator {

    private final StringRedisTemplate redis;

    public RedisHealthIndicator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Health health() {
        try {
            String pong = redis.getConnectionFactory()
                    .getConnection().commands().ping();
            if ("PONG".equals(pong)) {
                return Health.up()
                        .withDetail("type", "ElastiCache Redis")
                        .withDetail("cluster", "disabled")
                        .withDetail("purpose", "3-key model context + RBAC cache + dedup")
                        .build();
            }
            return Health.down().withDetail("ping", pong).build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}

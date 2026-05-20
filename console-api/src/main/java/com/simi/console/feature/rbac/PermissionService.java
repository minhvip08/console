package com.simi.console.feature.rbac;

import com.simi.common.domain.entity.ScenarioExecution;
import com.simi.console.feature.context.ScenarioExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Set;

/**
 * Permission check with Redis caching (docs/09 §Caching Strategy).
 * <pre>
 * Redis key: sc:user:{userId}:exec
 * Value:     Set of allowed scenario_definition IDs (stored as strings)
 * TTL:       configurable (default 5 min)
 *
 * Check flow:
 *   1. SMEMBERS sc:user:{userId}:exec
 *      → HIT: check scenario_id in set → allow/deny
 *      → MISS: query RoleStore → cache result → check
 * </pre>
 */
@Service
public class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);
    private static final String KEY_PREFIX = "sc:user:";
    private static final String KEY_SUFFIX = ":exec";

    private final RoleStoreClient roleStore;
    private final StringRedisTemplate redis;
    private final ScenarioExecutionRepository executions;
    private final Duration cacheTtl;

    public PermissionService(RoleStoreClient roleStore,
                             StringRedisTemplate redis,
                             ScenarioExecutionRepository executions,
                             @Value("${rolestore.cache-ttl-seconds:300}") int cacheTtlSeconds) {
        this.roleStore = roleStore;
        this.redis = redis;
        this.executions = executions;
        this.cacheTtl = Duration.ofSeconds(cacheTtlSeconds);
    }

    /**
     * Get allowed scenario IDs for a user (cache-first).
     */
    public Set<String> getAllowedScenarioIds(String userId) {
        if (userId == null || userId.isBlank()) {
            return Set.of(); // anonymous → no access
        }

        String key = cacheKey(userId);

        // Try Redis cache first
        Set<String> cached = redis.opsForSet().members(key);
        if (cached != null && !cached.isEmpty()) {
            log.debug("RBAC cache HIT for user={}, {} scenarios", userId, cached.size());
            return cached;
        }

        // Cache miss → query RoleStore
        log.debug("RBAC cache MISS for user={} → querying RoleStore", userId);
        Set<String> allowed = roleStore.getAllowedScenarioIds(userId);

        // Cache result
        if (!allowed.isEmpty()) {
            redis.opsForSet().add(key, allowed.toArray(String[]::new));
            redis.expire(key, cacheTtl);
        }

        return allowed;
    }

    /**
     * Check if user has permission for a specific scenario.
     * @throws ResponseStatusException 403 if denied
     */
    public void checkScenarioPermission(String userId, Long scenarioDefinitionId) {
        Set<String> allowed = getAllowedScenarioIds(userId);
        if (!allowed.contains(String.valueOf(scenarioDefinitionId))) {
            log.warn("RBAC DENIED: user={} → scenario={}", userId, scenarioDefinitionId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "User does not have permission for scenario: " + scenarioDefinitionId);
        }
    }

    /**
     * Check execution ownership — user must have started the execution OR have scenario permission.
     * @throws ResponseStatusException 403 if denied
     */
    public void checkExecutionPermission(String userId, Long executionId) {
        ScenarioExecution exec = executions.findById(executionId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "execution not found: " + executionId));

        // Check 1: execution ownership (created_by matches)
        if (exec.getCreatedBy() != null && exec.getCreatedBy().equals(userId)) {
            return; // owner — allowed
        }

        // Check 2: scenario permission (user has access to the scenario)
        checkScenarioPermission(userId, exec.getScenarioDefinitionId());
    }

    /**
     * Invalidate cache for a user (webhook / manual).
     */
    public void invalidateCache(String userId) {
        String key = cacheKey(userId);
        Boolean deleted = redis.delete(key);
        log.info("RBAC cache invalidated for user={}, deleted={}", userId, deleted);
    }

    private static String cacheKey(String userId) {
        return KEY_PREFIX + userId + KEY_SUFFIX;
    }
}

package com.simi.console.feature.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simi.common.domain.context.ContextDataItem;
import com.simi.common.domain.entity.ScenarioExecution;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Two-Phase Commit DB First (docs/04 §Two-Phase Commit):
 * <pre>
 *   BEGIN TX
 *     UPDATE scenario_execution.context (append item) ─► OK
 *     SAVE Redis 3-key (Lua atomic)                   ─► OK → COMMIT
 *                                                     ─► FAIL → ROLLBACK DB
 * </pre>
 * Đảm bảo DB luôn là source of truth; nếu Redis fail thì DB tự rollback.
 * <p>
 * Read path: {@link #readPreviousSteps} ưu tiên Redis; fallback PostgreSQL nếu Redis miss
 * và re-hydrate Redis (Cache-Aside — docs/04 §Cache-Aside Fallback).
 * <p>
 * Note: executionId is kept as String in all signatures since it's used as Redis key.
 * DB lookups convert String → Long via {@link Long#parseLong(String)}.
 */
@Component
public class ContextStore {

    private final ScenarioExecutionRepository executions;
    private final RedisContextWriter redisWriter;
    private final RedisContextReader redisReader;
    private final ObjectMapper json;

    public ContextStore(ScenarioExecutionRepository executions,
                        RedisContextWriter redisWriter,
                        RedisContextReader redisReader,
                        ObjectMapper json) {
        this.executions = executions;
        this.redisWriter = redisWriter;
        this.redisReader = redisReader;
        this.json = json;
    }

    /**
     * Append 1 item:
     *  1. Update scenario_execution.context JSONB (append)
     *  2. Write Redis 3-key qua Lua
     * Cả 2 trong cùng @Transactional. Nếu (2) throw → DB rollback.
     */
    @Transactional
    public void appendItem(String executionId, ContextDataItem item) {
        ScenarioExecution exec = executions.findById(parseLong(executionId)).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "execution not found: " + executionId));

        Map<String, ContextDataItem> snapshot = deserializeMap(exec.getContext());
        snapshot.put(item.key(), item);
        exec.setContext(json.valueToTree(snapshot));
        executions.save(exec);

        try {
            boolean ok = redisWriter.save(executionId, item);
            if (!ok) {
                throw new IllegalStateException("Lua script returned non-1 for save");
            }
        } catch (DataAccessException | IllegalStateException e) {
            // @Transactional propagates RuntimeException → rollback
            throw new IllegalStateException("Redis write failed; rolling back DB", e);
        }
    }

    /**
     * Read tất cả items từ step 1 → currentStepOrder. Redis trước, fallback DB nếu miss.
     */
    public List<ContextDataItem> readPreviousSteps(String executionId, int currentStepOrder) {
        List<ContextDataItem> fromRedis = redisReader.previousStepsData(executionId, currentStepOrder);
        if (!fromRedis.isEmpty()) {
            return fromRedis;
        }
        // Cache miss → restore từ DB và re-seed Redis
        ContextSnapshot snapshot = loadSnapshotFromDb(executionId);
        if (snapshot.items().isEmpty()) return List.of();
        restoreToRedis(executionId, snapshot);
        return snapshot.items().stream()
                .filter(i -> i.origin().stepOrder() <= currentStepOrder)
                .toList();
    }

    /** Load snapshot từ scenario_execution.context (JSONB). */
    public ContextSnapshot loadSnapshotFromDb(String executionId) {
        ScenarioExecution exec = executions.findById(parseLong(executionId)).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "execution not found: " + executionId));
        Map<String, ContextDataItem> map = deserializeMap(exec.getContext());
        return new ContextSnapshot(new ArrayList<>(map.values()));
    }

    /** Seed Redis 3-key model từ snapshot (resume / cache-aside fallback). */
    public void restoreToRedis(String executionId, ContextSnapshot snapshot) {
        for (ContextDataItem item : snapshot.items()) {
            redisWriter.save(executionId, item);
        }
    }

    private Map<String, ContextDataItem> deserializeMap(JsonNode node) {
        if (node == null || node.isNull() || node.isEmpty()) return new HashMap<>();
        try {
            return json.convertValue(node, new TypeReference<>() {});
        } catch (IllegalArgumentException e) {
            // context JSONB không phải shape Map<String, ContextDataItem> — log + treat empty
            return new HashMap<>();
        }
    }

    private static Long parseLong(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid execution ID format: " + id);
        }
    }
}

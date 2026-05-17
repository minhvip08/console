package com.simi.console.feature.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simi.common.domain.context.ContextDataItem;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Đọc context theo các pattern docs/04:
 * <ul>
 *   <li>previousStepsData: ZRANGEBYSCORE index:step:{exec} 0 currentStepOrder → C keys → MGET</li>
 *   <li>byVarId: SMEMBERS group:{exec}:var:{varId} → C keys → MGET</li>
 *   <li>byKey: GET 1 C key cụ thể</li>
 * </ul>
 */
@Component
public class RedisContextReader {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RedisContextReader(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    /** Tất cả ContextDataItem từ step 1 → currentStepOrder (inclusive). */
    public List<ContextDataItem> previousStepsData(String executionId, int currentStepOrder) {
        Set<String> dataKeys = redis.opsForZSet().rangeByScore(
                ContextDataItem.stepIndexKey(executionId), 0, currentStepOrder);
        return loadAll(dataKeys);
    }

    /** Mọi ContextDataItem có varId này — thường chỉ 1, hoặc nhiều nếu cùng VAR được set nhiều lần. */
    public List<ContextDataItem> byVarId(String executionId, String varId) {
        Set<String> dataKeys = redis.opsForSet().members(
                ContextDataItem.varGroupKey(executionId, varId));
        return loadAll(dataKeys);
    }

    public Optional<ContextDataItem> byKey(String dataKey) {
        String value = redis.opsForValue().get(dataKey);
        return Optional.ofNullable(value).map(this::deserialize);
    }

    private List<ContextDataItem> loadAll(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return List.of();
        List<String> ordered = new ArrayList<>(keys);
        List<String> values = redis.opsForValue().multiGet(ordered);
        if (values == null) return List.of();
        List<ContextDataItem> out = new ArrayList<>(values.size());
        for (String v : values) {
            if (v != null) out.add(deserialize(v));
        }
        return out;
    }

    private ContextDataItem deserialize(String value) {
        try {
            return json.readValue(value, ContextDataItem.class);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot deserialize ContextDataItem: " + value, e);
        }
    }
}

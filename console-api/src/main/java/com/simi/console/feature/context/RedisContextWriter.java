package com.simi.console.feature.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simi.common.domain.context.ContextDataItem;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Atomic 3-key write qua Lua script (docs/04 §Lua Script).
 * Ghi 1 ContextDataItem → SET C key + ZADD A key + SADD B key trong 1 round trip,
 * không có khả năng partial state.
 */
@Component
public class RedisContextWriter {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final RedisScript<Long> saveScript;

    public RedisContextWriter(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
        this.saveScript = loadScript();
    }

    private static RedisScript<Long> loadScript() {
        try {
            String body = new String(
                    new ClassPathResource("scripts/save_context_item.lua").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            return new DefaultRedisScript<>(body, Long.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load save_context_item.lua", e);
        }
    }

    /**
     * Lưu 1 ContextDataItem qua Lua atomic. Trả về true nếu Redis OK.
     * @param executionId scenario_execution.id
     * @param item ContextDataItem (origin.stepOrder dùng làm ZSET score)
     */
    public boolean save(String executionId, ContextDataItem item) {
        String dataKey = item.key();
        String stepKey = ContextDataItem.stepIndexKey(executionId);
        String varKey = ContextDataItem.varGroupKey(executionId, item.varId());

        String value = serialize(item);
        Long stepOrder = (long) item.origin().stepOrder();

        Long result = redis.execute(
                saveScript,
                List.of(dataKey, stepKey, varKey),
                value,
                stepOrder.toString());

        return result != null && result == 1L;
    }

    /**
     * Eval Lua script trực tiếp (bypass RedisScript) — dùng cho test khi cần script SHA.
     * Production code dùng {@link #save} thay vì hàm này.
     */
    public Long evalRaw(String script, List<String> keys, List<String> args) {
        return redis.execute((connection) -> connection.scriptingCommands().eval(
                script.getBytes(StandardCharsets.UTF_8),
                ReturnType.INTEGER,
                keys.size(),
                toBytesArray(keys, args)
        ), true);
    }

    private static byte[][] toBytesArray(List<String> keys, List<String> args) {
        byte[][] all = new byte[keys.size() + args.size()][];
        int i = 0;
        for (String k : keys) all[i++] = k.getBytes(StandardCharsets.UTF_8);
        for (String a : args) all[i++] = a.getBytes(StandardCharsets.UTF_8);
        return all;
    }

    private String serialize(ContextDataItem item) {
        try {
            return json.writeValueAsString(item);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize ContextDataItem", e);
        }
    }
}

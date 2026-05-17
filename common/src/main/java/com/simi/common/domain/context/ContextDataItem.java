package com.simi.common.domain.context;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

/**
 * Đơn vị data lưu trong Execution Context (docs/04 §ContextDataItem Structure).
 * Mỗi item được lưu qua 3 Redis keys (C/A/B), atomic bằng Lua script.
 * <ul>
 *   <li>{@code C key}: {@code exec:{executionId}:{sourceType}:{sourceId}:{varId}} → JSON này</li>
 *   <li>{@code A key}: {@code index:step:{executionId}} ZSET score=stepOrder, member=C key</li>
 *   <li>{@code B key}: {@code group:{executionId}:var:{varId}} Set members=C keys</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContextDataItem(
        String key,
        String varId,
        Object value,
        String valueType,    // string | number | boolean | object | array | null
        String version,
        Origin origin,
        OffsetDateTime createdAt
) {
    public enum SourceType {
        UI_INPUT,
        ACTION_OUTPUT
    }

    public record Origin(
            SourceType sourceType,
            String scenarioId,
            String stepId,
            int stepOrder,
            String sourceId,       // widgetId hoặc actionExecutionId
            String sourceName,
            String label
    ) {}

    /** Build C key theo convention: exec:{executionId}:{sourceType}:{sourceId}:{varId} */
    public static String buildKey(String executionId, SourceType sourceType, String sourceId, String varId) {
        return "exec:" + executionId + ":" + sourceType.name() + ":" + sourceId + ":" + varId;
    }

    public static String stepIndexKey(String executionId) {
        return "index:step:" + executionId;
    }

    public static String varGroupKey(String executionId, String varId) {
        return "group:" + executionId + ":var:" + varId;
    }

    public static String execKeyPrefix(String executionId) {
        return "exec:" + executionId + ":";
    }
}

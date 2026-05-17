package com.simi.console.feature.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.simi.common.domain.ActionExecutionStatus;

/**
 * Kết quả 1 lần adapter execute:
 * <ul>
 *   <li>{@code COMPLETED}: nhận response 2xx, body parsed</li>
 *   <li>{@code FAILED}: nhận response 4xx/5xx (known outcome) → Saga ngay</li>
 *   <li>{@code TIMEOUT}: không nhận response (network drop, socket timeout) → Reconciler verify</li>
 * </ul>
 * KHÔNG bao giờ collapse FAILED và TIMEOUT — đó là quyết định kiến trúc cốt lõi của hệ thống.
 */
public record AdapterResult(
        ActionExecutionStatus status,
        Integer httpStatus,        // null nếu TIMEOUT
        JsonNode responseBody,     // null nếu TIMEOUT hoặc FAILED không body
        String errorMessage,       // null nếu COMPLETED
        long durationMs
) {
    public static AdapterResult completed(int httpStatus, JsonNode body, long durationMs) {
        return new AdapterResult(ActionExecutionStatus.COMPLETED, httpStatus, body, null, durationMs);
    }

    public static AdapterResult failed(int httpStatus, JsonNode body, String errorMessage, long durationMs) {
        return new AdapterResult(ActionExecutionStatus.FAILED, httpStatus, body, errorMessage, durationMs);
    }

    public static AdapterResult timeout(String errorMessage, long durationMs) {
        return new AdapterResult(ActionExecutionStatus.TIMEOUT, null, null, errorMessage, durationMs);
    }
}

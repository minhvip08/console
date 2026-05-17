package com.simi.console.feature.context;

import com.simi.common.domain.context.ContextDataItem;

import java.util.List;

/**
 * Toàn bộ context của 1 execution tại 1 thời điểm — lưu JSONB trong scenario_execution.context.
 * Restore từ DB → re-seed Redis 3-key model (Cache-Aside).
 */
public record ContextSnapshot(List<ContextDataItem> items) {
    public static ContextSnapshot empty() {
        return new ContextSnapshot(List.of());
    }
}

package com.simi.console.feature.shopee;

import com.simi.common.domain.context.ContextDataItem;
import com.simi.common.shopee.ShopeeException;
import com.simi.console.feature.context.ContextStore;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/shopee/executions")
class ShopeePreviewController {
    private final ShopeeExecutionGuard guard;
    private final ShopeeRunRepository runs;
    private final ContextStore contexts;

    ShopeePreviewController(ShopeeExecutionGuard guard, ShopeeRunRepository runs, ContextStore contexts) {
        this.guard = guard;
        this.runs = runs;
        this.contexts = contexts;
    }

    @GetMapping("/{id}/preview")
    Preview preview(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable Long id) {
        if (!runs.existsById(id)) throw new ShopeeException(404, "Mock run not found");
        guard.checkExecution(user, id);
        List<ContextDataItem> outputs = contexts.loadSnapshotFromDb(String.valueOf(id)).items().stream()
                .filter(item -> item.origin().sourceType() == ContextDataItem.SourceType.ACTION_OUTPUT)
                .filter(item -> "preview".equals(item.origin().label()))
                .toList();
        return new Preview(id, "mock-only", outputs);
    }

    record Preview(Long executionId, String mode, List<ContextDataItem> outputs) {}
}

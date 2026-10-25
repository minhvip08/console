package com.simi.console.feature.executor;

import com.simi.common.domain.ActionExecutionStatus;
import com.simi.common.domain.entity.ActionExecution;
import com.simi.console.feature.rbac.PermissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * POST /api/executions/{executionId}/trigger — main hot path.
 * <p>
 * Toàn bộ 4 tầng idempotency thực thi trong StepExecutor; controller chỉ map status code:
 * <ul>
 *   <li>{@code cached} → 200 với cached response (idempotent return — docs/05)</li>
 *   <li>{@code alreadyProcessing} → 202 Accepted (Tầng 2/3 detect inflight)</li>
 *   <li>any action {@code FAILED} → 200 với detail status (Saga đã trigger ngay trong action_executor)</li>
 *   <li>any action {@code TIMEOUT} → 200 với status TIMEOUT (Reconciler sẽ verify async)</li>
 *   <li>all COMPLETED → 200 OK</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/executions/{executionId}/trigger")
class TriggerController {

    private final StepExecutor stepExecutor;
    private final PermissionService permissionService;
    private final com.simi.console.feature.shopee.ShopeeExecutionGuard shopee;

    TriggerController(StepExecutor stepExecutor,
                      PermissionService permissionService, com.simi.console.feature.shopee.ShopeeExecutionGuard shopee) {
        this.stepExecutor = stepExecutor;
        this.permissionService = permissionService;
        this.shopee = shopee;
    }

    @PostMapping
    public ResponseEntity<TriggerResponse> trigger(
            @PathVariable Long executionId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestIdHeader,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Valid @RequestBody TriggerRequest req) {

        // RBAC pre-check (docs/09 §Permission Check Points)
        if (userId != null && !userId.isBlank()) {
            permissionService.checkExecutionPermission(userId, executionId);
        }

        String requestId = requestIdHeader != null ? requestIdHeader : req.requestId();
        if (requestId == null || requestId.isBlank()) {
            return ResponseEntity.badRequest().body(TriggerResponse.error(
                    "X-Request-Id header or body.requestId required (Tầng 2 idempotency)"));
        }

        shopee.checkTrigger(userId, executionId, req.triggerId(), req.inputs());

        StepExecutor.TriggerResult result = stepExecutor.execute(new StepExecutor.TriggerInput(
                executionId, req.triggerId(), requestId, req.inputs(), userId));

        if (result.alreadyProcessing()) {
            return ResponseEntity.accepted().body(TriggerResponse.processing());
        }
        if (result.cached()) {
            return ResponseEntity.ok(TriggerResponse.cached(result.summary(), result.status().name()));
        }

        HttpStatus status = anyTimeout(result.actionResults())
                ? HttpStatus.ACCEPTED   // TIMEOUT — Reconciler đang verify
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(TriggerResponse.completed(
                result.status().name(),
                result.actionResults().stream().map(ActionSummary::from).toList(), result.summary()));
    }

    private static boolean anyTimeout(List<ActionExecution> rows) {
        return rows.stream().anyMatch(r -> r.getStatus() == ActionExecutionStatus.TIMEOUT);
    }

    public record TriggerRequest(
            @NotNull Long triggerId,
            String requestId,            // fallback if X-Request-Id header missing
            Map<String, Object> inputs   // {varId: value}
    ) {}

    public record TriggerResponse(
            String type,                  // "completed" | "cached" | "processing" | "error"
            String triggerStatus,
            List<ActionSummary> actions,
            String summary,
            String message
    ) {
        static TriggerResponse completed(String triggerStatus, List<ActionSummary> actions, String summary) {
            return new TriggerResponse("completed", triggerStatus, actions, summary, null);
        }
        static TriggerResponse cached(String summary, String triggerStatus) {
            return new TriggerResponse("cached", triggerStatus, List.of(), summary, null);
        }
        static TriggerResponse processing() {
            return new TriggerResponse("processing", "RUNNING", List.of(), null,
                    "request đang chạy — gọi GET /executions/{id} để kiểm tra");
        }
        static TriggerResponse error(String message) {
            return new TriggerResponse("error", null, List.of(), null, message);
        }
    }

    public record ActionSummary(
            Long actionExecutionId,
            Long actionDefinitionId,
            ActionExecutionStatus status,
            String errorMessage
    ) {
        static ActionSummary from(ActionExecution row) {
            return new ActionSummary(
                    row.getId(), row.getActionDefinitionId(),
                    row.getStatus(), row.getErrorMessage());
        }
    }
}

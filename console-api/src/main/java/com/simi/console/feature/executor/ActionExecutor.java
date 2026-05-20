package com.simi.console.feature.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simi.common.domain.ActionExecutionStatus;
import com.simi.common.domain.SagaCompensationStatus;
import com.simi.common.domain.context.ContextDataItem;
import com.simi.common.domain.entity.*;
import com.simi.common.idempotency.IdempotencyKey;
import com.simi.console.feature.adapter.AdapterRegistry;
import com.simi.console.feature.adapter.AdapterRequest;
import com.simi.console.feature.adapter.AdapterResult;
import com.simi.console.feature.compensation.SagaLogRepository;
import com.simi.console.feature.context.ContextStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Execute 1 action với Tầng 4 idempotency (docs/05 §Tầng 4):
 * <pre>
 *   1. Compute idempotency_key = SHA256(execId + actDefId + sorted_params)
 *   2. INSERT action_execution ON CONFLICT DO NOTHING
 *      - Conflict → SELECT existing → return cached status/response
 *      - First insert → execute adapter → UPDATE status
 *   3. COMPLETED → save output VARs vào context + INSERT saga_compensation_log
 * </pre>
 */
@Component
public class ActionExecutor {

    private final ActionDefinitionRepository actionDefs;
    private final ServiceRepository services;
    private final RetryPolicyRepository retryPolicies;
    private final CompensationDefinitionRepository compensationDefs;
    private final ActionExecutionRepository actionExecutions;
    private final SagaLogRepository sagaLogs;
    private final ParameterResolver resolver;
    private final AdapterRegistry adapters;
    private final ContextStore contextStore;
    private final ObjectMapper json;

    @PersistenceContext
    private EntityManager em;

    public ActionExecutor(ActionDefinitionRepository actionDefs,
                          ServiceRepository services,
                          RetryPolicyRepository retryPolicies,
                          CompensationDefinitionRepository compensationDefs,
                          ActionExecutionRepository actionExecutions,
                          SagaLogRepository sagaLogs,
                          ParameterResolver resolver,
                          AdapterRegistry adapters,
                          ContextStore contextStore,
                          ObjectMapper json) {
        this.actionDefs = actionDefs;
        this.services = services;
        this.retryPolicies = retryPolicies;
        this.compensationDefs = compensationDefs;
        this.actionExecutions = actionExecutions;
        this.sagaLogs = sagaLogs;
        this.resolver = resolver;
        this.adapters = adapters;
        this.contextStore = contextStore;
        this.json = json;
    }

    @Transactional
    public ActionExecution execute(ExecuteInput in) {
        ActionDefinition actionDef = actionDefs.findById(in.actionDefinitionId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "action_definition not found: " + in.actionDefinitionId()));

        // 1. Resolve params + compute idempotency key
        ParameterResolver.Resolved resolved = resolver.resolve(actionDef, in.contextItems());
        String idempotencyKey = IdempotencyKey.compute(
                in.executionId(), actionDef.getId(), resolved.flatForIdempotency());

        // 2. Tầng 4: tìm row tồn tại trước (nhanh path)
        Optional<ActionExecution> existing = actionExecutions.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();   // cached: caller decide return cached response hoặc 202
        }

        // 3. Insert PROCESSING row. UNIQUE(idempotency_key) → nếu 2 thread race, 1 thua → conflict.
        ActionExecution row = new ActionExecution();
        row.setScenarioExecutionId(in.executionId());
        row.setTriggerExecutionId(in.triggerExecutionId());
        row.setActionDefinitionId(actionDef.getId());
        row.setStatus(ActionExecutionStatus.PROCESSING);
        row.setIdempotencyKey(idempotencyKey);
        row.setStartedAt(OffsetDateTime.now());

        try {
            actionExecutions.saveAndFlush(row);
        } catch (DataIntegrityViolationException race) {
            // Thread khác đã insert giữa lúc check + insert → quay lại fetch cached.
            return actionExecutions.findByIdempotencyKey(idempotencyKey).orElseThrow();
        }

        // 4. Build adapter request + call
        Service service = services.findById(actionDef.getServiceId()).orElseThrow();
        RetryPolicy retry = actionDef.getRetryPolicyId() == null
                ? null
                : retryPolicies.findById(actionDef.getRetryPolicyId()).orElse(null);

        AdapterRequest adapterReq = new AdapterRequest(
                actionDef, service, retry,
                resolved.path(), resolved.query(), resolved.headers(), resolved.body(),
                idempotencyKey);

        AdapterResult result = adapters.forType(actionDef.getAdapterType()).execute(adapterReq);

        // 5. Apply result → UPDATE row + downstream effects
        row.setStatus(result.status());
        row.setResponseSnapshot(result.responseBody());
        row.setErrorMessage(result.errorMessage());
        row.setCompletedAt(OffsetDateTime.now());
        actionExecutions.save(row);

        if (result.status() == ActionExecutionStatus.COMPLETED) {
            saveOutputVars(in, actionDef, row, result.responseBody());
            // Chỉ log saga khi action có compensation. Action read-only / no-compensation không cần.
            compensationDefs.findByActionDefinitionId(actionDef.getId())
                    .ifPresent(c -> writeSagaLog(in, actionDef, row, c));
        }
        return row;
    }

    private void saveOutputVars(ExecuteInput in, ActionDefinition def, ActionExecution exec, JsonNode body) {
        if (body == null) return;
        JsonNode outputs = def.getOutput();
        if (outputs == null || !outputs.isArray()) return;

        String executionIdStr = String.valueOf(in.executionId());
        String execIdStr = String.valueOf(exec.getId());

        for (JsonNode out : outputs) {
            String field = out.get("field").asText();
            String varId = out.get("varId").asText();
            if (!body.has(field)) continue;

            Object value = json.convertValue(body.get(field), Object.class);
            ContextDataItem.Origin origin = new ContextDataItem.Origin(
                    ContextDataItem.SourceType.ACTION_OUTPUT,
                    String.valueOf(in.scenarioDefinitionId()),
                    in.stepDefinitionId(),
                    in.stepOrder(),
                    execIdStr,
                    def.getName(),
                    field
            );
            ContextDataItem item = new ContextDataItem(
                    ContextDataItem.buildKey(executionIdStr,
                            ContextDataItem.SourceType.ACTION_OUTPUT, execIdStr, varId),
                    varId,
                    value,
                    valueType(out),
                    "1.0",
                    origin,
                    OffsetDateTime.now()
            );
            contextStore.appendItem(executionIdStr, item);
        }
    }

    private void writeSagaLog(ExecuteInput in, ActionDefinition def, ActionExecution exec, ActionCompensation comp) {
        // Snapshot context tại thời điểm action chạy — Saga Manager dùng đúng data này.
        String executionIdStr = String.valueOf(in.executionId());
        List<ContextDataItem> snapshot = contextStore.readPreviousSteps(executionIdStr, in.stepOrder());
        SagaCompensationLog log = new SagaCompensationLog();
        log.setScenarioExecutionId(in.executionId());
        log.setActionExecutionId(exec.getId());
        log.setActionDefinitionId(def.getId());
        log.setStepOrder(in.stepOrder());
        log.setStatus(SagaCompensationStatus.PENDING);
        log.setContextSnapshot(json.valueToTree(snapshot));
        sagaLogs.save(log);
    }

    private static String valueType(JsonNode outputSchema) {
        return outputSchema.has("type") ? outputSchema.get("type").asText() : "string";
    }

    /** Đầu vào: scope của step đang chạy + context đã merge. */
    public record ExecuteInput(
            Long executionId,
            Long scenarioDefinitionId,
            String stepDefinitionId,
            int stepOrder,
            Long triggerExecutionId,   // nullable for compensation actions
            Long actionDefinitionId,
            List<ContextDataItem> contextItems
    ) {}
}

package com.simi.console.feature.executor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simi.common.domain.ActionExecutionStatus;
import com.simi.common.domain.ScenarioExecutionStatus;
import com.simi.common.domain.TriggerExecutionStatus;
import com.simi.common.domain.context.ContextDataItem;
import com.simi.common.domain.entity.*;
import com.simi.console.feature.compensation.CompensationManager;
import com.simi.console.feature.context.ContextStore;
import com.simi.console.feature.context.RedisContextWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrate 1 trigger click (docs/03 §Flow 2 + docs/05 full flow):
 * <pre>
 *   Tầng 2: SETNX dedup:{requestId} EX 60                       — Redis
 *   Tầng 3: UPSERT trigger_execution (UNIQUE exec+trigger)      — PostgreSQL
 *           ├─ RUNNING   → 202 already running
 *           └─ COMPLETED → 200 cached result
 *   ★ Save UI inputs vào context (current step)
 *   ★ Load context (previous steps + current inputs)
 *   ★ For each action ordered by action_order:
 *        Tầng 4: ActionExecutor — idempotency_key, INSERT ON CONFLICT, adapter call
 *   ★ UPDATE trigger_execution → COMPLETED + cache result:{requestId} EX 300
 * </pre>
 */
@Component
public class StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);
    private static final Duration DEDUP_TTL = Duration.ofSeconds(60);
    private static final Duration RESULT_TTL = Duration.ofSeconds(300);

    private final StringRedisTemplate redis;
    private final TriggerExecutionRepository triggerExecutions;
    private final TriggerRepository triggers;
    private final TriggerActionRepository triggerActions;
    private final com.simi.console.feature.context.ScenarioExecutionRepository scenarioExecutions;
    private final StepDefinitionRepository stepDefs;
    private final ContextStore contextStore;
    private final RedisContextWriter contextWriter;
    private final ActionExecutor actionExecutor;
    private final CompensationManager compensationManager;
    private final ObjectMapper json;

    @PersistenceContext
    private EntityManager em;

    public StepExecutor(StringRedisTemplate redis,
                        TriggerExecutionRepository triggerExecutions,
                        TriggerRepository triggers,
                        TriggerActionRepository triggerActions,
                        com.simi.console.feature.context.ScenarioExecutionRepository scenarioExecutions,
                        StepDefinitionRepository stepDefs,
                        ContextStore contextStore,
                        RedisContextWriter contextWriter,
                        ActionExecutor actionExecutor,
                        CompensationManager compensationManager,
                        ObjectMapper json) {
        this.redis = redis;
        this.triggerExecutions = triggerExecutions;
        this.triggers = triggers;
        this.triggerActions = triggerActions;
        this.scenarioExecutions = scenarioExecutions;
        this.stepDefs = stepDefs;
        this.contextStore = contextStore;
        this.contextWriter = contextWriter;
        this.actionExecutor = actionExecutor;
        this.compensationManager = compensationManager;
        this.json = json;
    }

    /**
     * Main entrypoint. Lưu ý: hàm này KHÔNG đánh {@code @Transactional} ở top — Tầng 2 (Redis)
     * và Tầng 3 (DB) phải lookup trước khi mở transaction để return 409/202 nhanh không lock DB.
     */
    public TriggerResult execute(TriggerInput in) {
        // ─── Tầng 2 — Request Dedup ───
        Boolean firstReq = redis.opsForValue()
                .setIfAbsent("dedup:" + in.requestId(), "1", DEDUP_TTL);
        if (Boolean.FALSE.equals(firstReq)) {
            // Same requestId trong 60s — check có cached result không
            String cached = redis.opsForValue().get("result:" + in.requestId());
            if (cached != null) {
                return TriggerResult.fromCache(cached);
            }
            return TriggerResult.inFlight();
        }

        TriggerResult result = runInsideTransaction(in);

        // ─── Phase 5: Saga Compensation trigger (OUTSIDE transaction) ───
        // Must be outside @Transactional so compensation failure doesn't rollback trigger status.
        // FAILED → Saga ngay (docs/06: Known outcome — 4xx/5xx response received)
        // TIMEOUT → Reconciler async verify (docs/07: Unknown outcome — no response)
        if (result.failedActionStatus() != null) {
            if (result.failedActionStatus() == ActionExecutionStatus.FAILED) {
                log.info("Action FAILED in execution {} — triggering Saga Compensation", in.executionId());
                try {
                    compensationManager.compensate(in.executionId());
                } catch (Exception e) {
                    log.error("Compensation error for execution {}: {}",
                            in.executionId(), e.getMessage(), e);
                    // Compensation failure is handled internally (dead letter)
                }
            } else if (result.failedActionStatus() == ActionExecutionStatus.TIMEOUT) {
                log.info("Action TIMEOUT in execution {} — Reconciler will verify async (docs/07)",
                        in.executionId());
            }
        }

        return result;
    }

    @Transactional
    protected TriggerResult runInsideTransaction(TriggerInput in) {
        // ─── Tầng 3 — Trigger Lock ───
        var existing = triggerExecutions
                .findByScenarioExecutionIdAndStepTriggerId(in.executionId(), in.triggerId());
        if (existing.isPresent()) {
            TriggerExecution te = existing.get();
            return switch (te.getStatus()) {
                case RUNNING -> TriggerResult.inFlight();
                case COMPLETED -> TriggerResult.fromCache("trigger already completed: " + te.getId());
                case FAILED -> {
                    te.setStatus(TriggerExecutionStatus.RUNNING);
                    te.setRequestId(in.requestId());
                    te.setStartedAt(OffsetDateTime.now());
                    triggerExecutions.save(te);
                    yield runActions(in, te);
                }
                case PENDING -> {
                    te.setStatus(TriggerExecutionStatus.RUNNING);
                    te.setRequestId(in.requestId());
                    te.setStartedAt(OffsetDateTime.now());
                    triggerExecutions.save(te);
                    yield runActions(in, te);
                }
            };
        }

        // INSERT new trigger_execution. UNIQUE constraint (Tầng 3) bảo vệ ở DB level
        // dù 2 request cùng INSERT — chỉ 1 thắng.
        TriggerExecution te = new TriggerExecution();
        te.setScenarioExecutionId(in.executionId());
        te.setStepTriggerId(in.triggerId());
        te.setStatus(TriggerExecutionStatus.RUNNING);
        te.setRequestId(in.requestId());
        te.setStartedAt(OffsetDateTime.now());

        try {
            triggerExecutions.saveAndFlush(te);
        } catch (DataIntegrityViolationException race) {
            // Race lost — đối thủ đã INSERT trước → fetch lại
            triggerExecutions
                    .findByScenarioExecutionIdAndStepTriggerId(in.executionId(), in.triggerId())
                    .orElseThrow();
            return TriggerResult.inFlight();
        }

        return runActions(in, te);
    }

    private TriggerResult runActions(TriggerInput in, TriggerExecution te) {
        StepTrigger trigger = triggers.findById(in.triggerId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "step_trigger not found: " + in.triggerId()));
        StepDefinition step = stepDefs.findById(trigger.getStepDefinitionId())
                .orElseThrow();
        ScenarioExecution exec = scenarioExecutions.findById(in.executionId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "execution not found: " + in.executionId()));

        // Save UI inputs từ user vào context BEFORE actions chạy — actions sẽ resolve từ đây
        saveUiInputs(in, exec, step);

        String executionIdStr = String.valueOf(in.executionId());

        // Load merged context (previous steps + current step inputs vừa save)
        List<ContextDataItem> context = contextStore.readPreviousSteps(executionIdStr, step.getStepOrder());

        // Sequential action loop theo action_order
        var triggerActionRows = triggerActions.findByStepTriggerIdOrderByActionOrderAsc(in.triggerId());
        List<ActionExecution> results = new ArrayList<>();
        boolean anyFailure = false;
        ActionExecutionStatus failedStatus = null;
        for (StepTriggerAction sta : triggerActionRows) {
            ActionExecutor.ExecuteInput input = new ActionExecutor.ExecuteInput(
                    in.executionId(),
                    exec.getScenarioDefinitionId(),
                    String.valueOf(step.getId()),
                    step.getStepOrder(),
                    te.getId(),
                    sta.getActionDefinitionId(),
                    context
            );
            ActionExecution executed = actionExecutor.execute(input);
            results.add(executed);

            if (executed.getStatus() != ActionExecutionStatus.COMPLETED) {
                anyFailure = true;
                failedStatus = executed.getStatus();
                break; // FAILED/TIMEOUT — break loop, không chạy action tiếp theo
            }
            // Refresh context — action vừa COMPLETED có thể đã append output VARs
            context = contextStore.readPreviousSteps(executionIdStr, step.getStepOrder());
        }

        te.setStatus(anyFailure ? TriggerExecutionStatus.FAILED : TriggerExecutionStatus.COMPLETED);
        te.setCompletedAt(OffsetDateTime.now());
        triggerExecutions.save(te);

        // Mark scenario FAILED inside the transaction (so it persists)
        if (anyFailure && failedStatus == ActionExecutionStatus.FAILED) {
            exec.setStatus(ScenarioExecutionStatus.FAILED);
            scenarioExecutions.save(exec);
        }

        // Cache trigger result for Tầng 2 idempotent return
        String cacheValue = summarize(results);
        redis.opsForValue().set("result:" + in.requestId(), cacheValue, RESULT_TTL);

        return new TriggerResult(te.getStatus(), results, false, false, cacheValue,
                anyFailure ? failedStatus : null);
    }

    private void saveUiInputs(TriggerInput in, ScenarioExecution exec, StepDefinition step) {
        if (in.inputs() == null || in.inputs().isEmpty()) return;
        String executionIdStr = String.valueOf(in.executionId());
        String stepIdStr = String.valueOf(step.getId());
        for (Map.Entry<String, Object> e : in.inputs().entrySet()) {
            String varId = e.getKey();
            Object value = e.getValue();
            String sourceId = "widget_input_" + stepIdStr;   // logical source — không có widget id thật từ UI tier 4 này
            ContextDataItem.Origin origin = new ContextDataItem.Origin(
                    ContextDataItem.SourceType.UI_INPUT,
                    String.valueOf(exec.getScenarioDefinitionId()),
                    stepIdStr,
                    step.getStepOrder(),
                    sourceId,
                    "user_input",
                    null
            );
            ContextDataItem item = new ContextDataItem(
                    ContextDataItem.buildKey(executionIdStr,
                            ContextDataItem.SourceType.UI_INPUT, sourceId, varId),
                    varId,
                    value,
                    inferType(value),
                    "1.0",
                    origin,
                    OffsetDateTime.now()
            );
            contextStore.appendItem(executionIdStr, item);
        }
    }

    private String summarize(List<ActionExecution> rows) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (ActionExecution row : rows) {
            map.put(String.valueOf(row.getActionDefinitionId()),
                    Map.of(
                            "status", row.getStatus().name(),
                            "actionExecutionId", row.getId(),
                            "response", row.getResponseSnapshot() == null
                                    ? null
                                    : row.getResponseSnapshot()
                    ));
        }
        try {
            return json.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String inferType(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number) return "number";
        if (value instanceof String) return "string";
        if (value instanceof Map) return "object";
        if (value instanceof Iterable) return "array";
        return "string";
    }

    public record TriggerInput(
            Long executionId,
            Long triggerId,
            String requestId,
            Map<String, Object> inputs  // {varId: value} từ UI widgets
    ) {}

    public record TriggerResult(
            TriggerExecutionStatus status,
            List<ActionExecution> actionResults,
            boolean cached,
            boolean alreadyProcessing,
            String summary,
            ActionExecutionStatus failedActionStatus   // non-null when action loop broke
    ) {
        static TriggerResult fromCache(String summary) {
            return new TriggerResult(TriggerExecutionStatus.COMPLETED, List.of(), true, false, summary, null);
        }
        static TriggerResult inFlight() {
            return new TriggerResult(TriggerExecutionStatus.RUNNING, List.of(), false, true, null, null);
        }
    }
}

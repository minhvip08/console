package com.simi.console.feature.compensation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simi.common.domain.ActionExecutionStatus;
import com.simi.common.domain.SagaCompensationStatus;
import com.simi.common.domain.ScenarioExecutionStatus;
import com.simi.common.domain.context.ContextDataItem;
import com.simi.common.domain.entity.*;
import com.simi.console.feature.context.ScenarioExecutionRepository;
import com.simi.console.feature.executor.ActionDefinitionRepository;
import com.simi.console.feature.executor.ActionExecutor;
import com.simi.console.feature.executor.CompensationDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Orchestration Saga — Compensation Manager (docs/06 §Compensation Execution).
 * <pre>
 * Trigger khi action FAILED:
 *   1. UPDATE scenario_execution: FAILED → COMPENSATING
 *   2. SELECT saga_compensation_log WHERE exec = X ORDER BY step_order DESC
 *   3. For each log entry:
 *        - FAILED status → skip (chưa thành công, không cần compensate)
 *        - trigger = 'none' → skip (read-only action, e.g. validate_user)
 *        - trigger = 'manual' → skip (admin chủ động trigger riêng)
 *        - trigger = 'auto' + COMPLETED → RUN compensation action
 *   4. Compensation action chạy qua ActionExecutor (cùng pipeline + idempotency Tầng 4)
 *   5. Tất cả OK → scenario_execution.status = COMPENSATED
 *   6. Compensation fail → dead letter + COMPENSATION_FAILED
 * </pre>
 * <p>
 * Key rules (docs/06 §Key Design Rules):
 * - context_snapshot là bắt buộc — dùng data tại thời điểm action gốc chạy
 * - Compensation phải idempotent — cùng idempotency key mechanism
 * - Compensation không trigger compensation khác — fail → dead letter, không chain
 */
@Component
public class CompensationManager {

    private static final Logger log = LoggerFactory.getLogger(CompensationManager.class);

    private final SagaLogRepository sagaLogs;
    private final DeadLetterRepository deadLetters;
    private final ScenarioExecutionRepository scenarioExecutions;
    private final CompensationDefinitionRepository compensationDefs;
    private final ActionDefinitionRepository actionDefs;
    private final ActionExecutor actionExecutor;
    private final ObjectMapper json;

    public CompensationManager(SagaLogRepository sagaLogs,
                               DeadLetterRepository deadLetters,
                               ScenarioExecutionRepository scenarioExecutions,
                               CompensationDefinitionRepository compensationDefs,
                               ActionDefinitionRepository actionDefs,
                               ActionExecutor actionExecutor,
                               ObjectMapper json) {
        this.sagaLogs = sagaLogs;
        this.deadLetters = deadLetters;
        this.scenarioExecutions = scenarioExecutions;
        this.compensationDefs = compensationDefs;
        this.actionDefs = actionDefs;
        this.actionExecutor = actionExecutor;
        this.json = json;
    }

    /**
     * Trigger compensation flow cho 1 execution (docs/06 §Full Example).
     * Chỉ gọi khi action FAILED — TIMEOUT route qua Reconciler, KHÔNG vào đây.
     *
     * @return kết quả compensation: COMPENSATED hoặc COMPENSATION_FAILED
     */
    @Transactional
    public CompensationResult compensate(Long scenarioExecutionId) {
        ScenarioExecution exec = scenarioExecutions.findById(scenarioExecutionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "execution not found: " + scenarioExecutionId));

        // Guard: chỉ FAILED / COMPENSATION_FAILED cho phép retry
        if (exec.getStatus() != ScenarioExecutionStatus.FAILED
                && exec.getStatus() != ScenarioExecutionStatus.COMPENSATION_FAILED) {
            log.warn("compensate called on execution {} with status {} — skipping",
                    scenarioExecutionId, exec.getStatus());
            return new CompensationResult(exec.getStatus(), List.of(),
                    "execution is " + exec.getStatus() + ", not eligible for compensation");
        }

        // Step 1: Transition to COMPENSATING
        exec.setStatus(ScenarioExecutionStatus.COMPENSATING);
        scenarioExecutions.save(exec);

        // Step 2: Load saga log, reversed by step_order
        List<SagaCompensationLog> sagaEntries =
                sagaLogs.findByScenarioExecutionIdOrderByStepOrderDesc(scenarioExecutionId);

        if (sagaEntries.isEmpty()) {
            log.info("No saga entries for execution {} — mark COMPENSATED (nothing to undo)",
                    scenarioExecutionId);
            exec.setStatus(ScenarioExecutionStatus.COMPENSATED);
            scenarioExecutions.save(exec);
            return new CompensationResult(ScenarioExecutionStatus.COMPENSATED, List.of(), null);
        }

        // Step 3: Iterate reverse order — run compensation for eligible entries
        boolean anyFailure = false;
        for (SagaCompensationLog entry : sagaEntries) {
            // Skip nếu đã compensated
            if (entry.getStatus() == SagaCompensationStatus.COMPENSATED) {
                log.debug("saga entry {} already COMPENSATED — skip", entry.getId());
                continue;
            }

            // Lookup compensation definition
            var compDefOpt = compensationDefs.findByActionDefinitionId(entry.getActionDefinitionId());
            if (compDefOpt.isEmpty()) {
                log.debug("No compensation defined for action {} — skip", entry.getActionDefinitionId());
                continue;
            }

            ActionCompensation compDef = compDefOpt.get();

            // Skip theo trigger type (docs/06 §compensation.trigger values)
            if ("none".equals(compDef.getTriggerType())) {
                log.debug("Compensation trigger=none for action {} — skip", entry.getActionDefinitionId());
                entry.setStatus(SagaCompensationStatus.COMPENSATED); // mark as done — nothing to do
                sagaLogs.save(entry);
                continue;
            }
            if ("manual".equals(compDef.getTriggerType())) {
                log.debug("Compensation trigger=manual for action {} — skip (admin trigger riêng)",
                        entry.getActionDefinitionId());
                continue;
            }

            // trigger = 'auto' → RUN compensation action
            entry.setStatus(SagaCompensationStatus.COMPENSATING);
            sagaLogs.save(entry);

            try {
                ActionExecution compExec = executeCompensationAction(
                        exec, entry, compDef);

                if (compExec.getStatus() == ActionExecutionStatus.COMPLETED) {
                    entry.setStatus(SagaCompensationStatus.COMPENSATED);
                    entry.setCompensationActionExecutionId(compExec.getId());
                    sagaLogs.save(entry);
                    log.info("Compensation COMPLETED for saga entry {} (step_order={})",
                            entry.getId(), entry.getStepOrder());
                } else {
                    // FAILED or TIMEOUT — dead letter
                    anyFailure = true;
                    handleCompensationFailure(entry, compDef, compExec.getErrorMessage());
                    // Docs/06: compensation fail → dead letter, KHÔNG chain compensation-of-compensation
                    break; // stop further compensations
                }
            } catch (Exception e) {
                anyFailure = true;
                handleCompensationFailure(entry, compDef, e.getMessage());
                break;
            }
        }

        // Step 4: Final status
        if (anyFailure) {
            exec.setStatus(ScenarioExecutionStatus.COMPENSATION_FAILED);
            scenarioExecutions.save(exec);
            log.warn("Compensation FAILED for execution {} — check dead letters", scenarioExecutionId);
        } else {
            exec.setStatus(ScenarioExecutionStatus.COMPENSATED);
            scenarioExecutions.save(exec);
            log.info("Compensation COMPLETED for execution {} — all steps compensated", scenarioExecutionId);
        }

        return new CompensationResult(exec.getStatus(), sagaEntries, null);
    }

    /**
     * Retry compensation cho 1 execution có COMPENSATION_FAILED entries.
     * Re-runs only non-COMPENSATED entries in reverse step_order.
     */
    @Transactional
    public CompensationResult retryCompensation(Long scenarioExecutionId) {
        // Reset any COMPENSATION_FAILED saga entries back to PENDING
        List<SagaCompensationLog> entries =
                sagaLogs.findByScenarioExecutionIdOrderByStepOrderDesc(scenarioExecutionId);
        for (SagaCompensationLog e : entries) {
            if (e.getStatus() == SagaCompensationStatus.COMPENSATION_FAILED) {
                e.setStatus(SagaCompensationStatus.PENDING);
                sagaLogs.save(e);
            }
        }

        // Reset execution status to FAILED so compensate() accepts it
        ScenarioExecution exec = scenarioExecutions.findById(scenarioExecutionId).orElseThrow();
        if (exec.getStatus() == ScenarioExecutionStatus.COMPENSATION_FAILED) {
            exec.setStatus(ScenarioExecutionStatus.FAILED);
            scenarioExecutions.save(exec);
        }

        // Re-resolve dead letters
        List<CompensationDeadLetter> unresolved =
                deadLetters.findByScenarioExecutionIdAndResolvedFalse(scenarioExecutionId);
        for (CompensationDeadLetter dl : unresolved) {
            dl.setRetryCount(dl.getRetryCount() + 1);
            deadLetters.save(dl);
        }

        return compensate(scenarioExecutionId);
    }

    /**
     * Execute compensation action using context_snapshot from the saga log entry.
     * Compensation action chạy qua cùng ActionExecutor pipeline (Tầng 4 idempotency).
     */
    private ActionExecution executeCompensationAction(
            ScenarioExecution exec,
            SagaCompensationLog sagaEntry,
            ActionCompensation compDef) {

        // Load context từ snapshot — KHÔNG phải live context (docs/06 §Key Design Rules #1)
        List<ContextDataItem> snapshotContext = json.convertValue(
                sagaEntry.getContextSnapshot(),
                new TypeReference<>() {});

        // Compensation action definition
        Long compActionDefId = compDef.getCompensationActionDefinitionId();
        ActionDefinition compActionDef = actionDefs.findById(compActionDefId)
                .orElseThrow(() -> new IllegalStateException(
                        "Compensation action_definition not found: " + compActionDefId));

        ActionExecutor.ExecuteInput input = new ActionExecutor.ExecuteInput(
                exec.getId(),
                exec.getScenarioDefinitionId(),
                "compensation_step",   // virtual step — compensation không thuộc step nào cụ thể
                sagaEntry.getStepOrder(),
                null,                  // no trigger execution for compensation actions
                compActionDefId,
                snapshotContext
        );

        return actionExecutor.execute(input);
    }

    private void handleCompensationFailure(
            SagaCompensationLog entry,
            ActionCompensation compDef,
            String reason) {
        entry.setStatus(SagaCompensationStatus.COMPENSATION_FAILED);
        sagaLogs.save(entry);

        CompensationDeadLetter dl = new CompensationDeadLetter();
        dl.setSagaCompensationLogId(entry.getId());
        dl.setScenarioExecutionId(entry.getScenarioExecutionId());
        dl.setActionDefinitionId(compDef.getCompensationActionDefinitionId());
        dl.setContextSnapshot(entry.getContextSnapshot());
        dl.setFailureReason(reason);
        dl.setCreatedAt(OffsetDateTime.now());
        deadLetters.save(dl);

        // TODO: Slack/email alert webhook (stub — docs/06 §Dead Letter)
        log.error("COMPENSATION DEAD LETTER — saga_log={}, execution={}, reason={}",
                entry.getId(), entry.getScenarioExecutionId(), reason);
    }

    /**
     * Get current compensation status for an execution.
     */
    public CompensationStatusResult getCompensationStatus(Long scenarioExecutionId) {
        ScenarioExecution exec = scenarioExecutions.findById(scenarioExecutionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "execution not found: " + scenarioExecutionId));

        List<SagaCompensationLog> entries =
                sagaLogs.findByScenarioExecutionIdOrderByStepOrderDesc(scenarioExecutionId);

        List<CompensationDeadLetter> unresolvedDeadLetters =
                deadLetters.findByScenarioExecutionIdAndResolvedFalse(scenarioExecutionId);

        return new CompensationStatusResult(exec.getStatus(), entries, unresolvedDeadLetters);
    }

    // ─── Result records ───

    public record CompensationResult(
            ScenarioExecutionStatus executionStatus,
            List<SagaCompensationLog> sagaEntries,
            String message
    ) {}

    public record CompensationStatusResult(
            ScenarioExecutionStatus executionStatus,
            List<SagaCompensationLog> sagaEntries,
            List<CompensationDeadLetter> deadLetters
    ) {}
}

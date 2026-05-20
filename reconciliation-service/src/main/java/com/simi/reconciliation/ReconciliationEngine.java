package com.simi.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.simi.common.domain.ActionExecutionStatus;
import com.simi.common.domain.ScenarioExecutionStatus;
import com.simi.common.domain.entity.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Reconciliation Engine — State Matrix decision-maker (docs/07 §State Matrix).
 * <pre>
 * DB State  │ 3rd Party State │ Drift Type        │ Action
 * ──────────┼─────────────────┼───────────────────┼──────────
 * TIMEOUT   │ PROCESSED       │ Ghost Transaction │ auto-complete or auto-compensate
 * TIMEOUT   │ NOT_FOUND       │ Clean timeout     │ mark FAILED → Saga trigger
 * TIMEOUT   │ PENDING         │ Still running     │ poll again later
 * FAILED    │ PROCESSED       │ Ghost Transaction │ same as TIMEOUT+PROCESSED
 * COMPLETED │ NOT_FOUND       │ Orphan record     │ manual queue
 * </pre>
 */
@Component
public class ReconciliationEngine {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationEngine.class);

    private final ActionExecutionRepository actionExecutions;
    private final ScenarioExecutionRepository scenarioExecutions;
    private final ActionDefinitionRepository actionDefs;
    private final ActionVerificationRepository actionVerifications;
    private final ServiceRepository services;
    private final ReconciliationEventRepository reconciliationEvents;
    private final VerificationClient verificationClient;

    public ReconciliationEngine(ActionExecutionRepository actionExecutions,
                                ScenarioExecutionRepository scenarioExecutions,
                                ActionDefinitionRepository actionDefs,
                                ActionVerificationRepository actionVerifications,
                                ServiceRepository services,
                                ReconciliationEventRepository reconciliationEvents,
                                VerificationClient verificationClient) {
        this.actionExecutions = actionExecutions;
        this.scenarioExecutions = scenarioExecutions;
        this.actionDefs = actionDefs;
        this.actionVerifications = actionVerifications;
        this.services = services;
        this.reconciliationEvents = reconciliationEvents;
        this.verificationClient = verificationClient;
    }

    /**
     * Process a TIMEOUT action_execution — verify with 3rd party and resolve.
     */
    @Transactional
    public ReconcileResult reconcileTimeout(Long actionExecutionId) {
        ActionExecution actionExec = actionExecutions.findById(actionExecutionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "action_execution not found: " + actionExecutionId));

        // Idempotent: skip if no longer TIMEOUT (already reconciled)
        if (actionExec.getStatus() != ActionExecutionStatus.TIMEOUT) {
            log.debug("action_execution {} is {} (not TIMEOUT) — skipping",
                    actionExecutionId, actionExec.getStatus());
            return new ReconcileResult("SKIPPED", "Status is " + actionExec.getStatus());
        }

        ActionDefinition actionDef = actionDefs.findById(actionExec.getActionDefinitionId())
                .orElseThrow();

        // Check if verification block exists
        var verificationOpt = actionVerifications.findByActionDefinitionId(actionDef.getId());
        if (verificationOpt.isEmpty()) {
            // No verification endpoint → manual queue (docs/07 §Gating Mechanism)
            log.warn("No verification config for action {} — queuing for manual review",
                    actionDef.getId());
            insertReconciliationEvent(actionExec, "TIMEOUT", "UNKNOWN",
                    "LOST_RESPONSE", "MANUAL_QUEUED", null,
                    "No verification endpoint configured — manual intervention required");
            return new ReconcileResult("MANUAL_QUEUED",
                    "action_definition has no verification block");
        }

        ActionVerification verification = verificationOpt.get();
        Service service = services.findById(actionDef.getServiceId()).orElseThrow();

        // Call 3rd party verify endpoint
        VerificationClient.VerificationResult verifyResult =
                verificationClient.verify(service, actionDef, verification,
                        actionExec.getIdempotencyKey());

        // Apply State Matrix
        return applyStateMatrix(actionExec, verifyResult);
    }

    /**
     * Apply the State Matrix decision (docs/07 §State Matrix).
     */
    private ReconcileResult applyStateMatrix(ActionExecution actionExec,
                                              VerificationClient.VerificationResult verifyResult) {
        String dbState = actionExec.getStatus().name();
        String tpState = verifyResult.thirdPartyState();

        return switch (tpState) {
            case "PROCESSED" -> handleGhostTransaction(actionExec, verifyResult);
            case "NOT_FOUND" -> handleCleanTimeout(actionExec, verifyResult);
            case "PENDING" -> handleStillProcessing(actionExec, verifyResult);
            case "ERROR" -> handleVerificationError(actionExec, verifyResult);
            default -> {
                log.warn("Unknown 3rd party state: {} for action_execution {}",
                        tpState, actionExec.getId());
                insertReconciliationEvent(actionExec, dbState, tpState,
                        "LOST_RESPONSE", "MANUAL_QUEUED", verifyResult.responseBody(),
                        "Unknown 3rd party state — manual review needed");
                yield new ReconcileResult("MANUAL_QUEUED", "Unknown state: " + tpState);
            }
        };
    }

    /**
     * Ghost Transaction — DB=TIMEOUT, 3rd party=PROCESSED (charged/created/etc.)
     * Action was actually processed but Console didn't get the response.
     */
    private ReconcileResult handleGhostTransaction(ActionExecution actionExec,
                                                    VerificationClient.VerificationResult verifyResult) {
        ScenarioExecution scenarioExec = scenarioExecutions
                .findById(actionExec.getScenarioExecutionId()).orElseThrow();

        if (scenarioExec.getStatus() == ScenarioExecutionStatus.EXECUTING) {
            // Scenario still in progress — mark action COMPLETED, continue flow
            actionExec.setStatus(ActionExecutionStatus.COMPLETED);
            actionExec.setResponseSnapshot(verifyResult.responseBody());
            actionExec.setCompletedAt(OffsetDateTime.now());
            actionExecutions.save(actionExec);

            insertReconciliationEvent(actionExec,
                    "TIMEOUT", "PROCESSED", "GHOST_TRANSACTION", "AUTO_COMPLETED",
                    verifyResult.responseBody(),
                    "Ghost transaction detected — action actually succeeded, marked COMPLETED");

            log.info("GHOST TRANSACTION auto-completed: action_execution={}, scenario still EXECUTING",
                    actionExec.getId());
            return new ReconcileResult("AUTO_COMPLETED",
                    "Ghost transaction — action was processed, marked COMPLETED");

        } else {
            // Scenario is COMPENSATING or already handled — need to compensate this ghost
            insertReconciliationEvent(actionExec,
                    "TIMEOUT", "PROCESSED", "GHOST_TRANSACTION", "AUTO_COMPENSATED",
                    verifyResult.responseBody(),
                    "Ghost transaction detected — scenario is " + scenarioExec.getStatus()
                            + ", needs compensation");

            // Mark action as COMPLETED first (it was actually processed)
            actionExec.setStatus(ActionExecutionStatus.COMPLETED);
            actionExec.setResponseSnapshot(verifyResult.responseBody());
            actionExec.setCompletedAt(OffsetDateTime.now());
            actionExecutions.save(actionExec);

            // Compensation will be handled by the Saga Compensation Manager
            // (Reconciler marks, then triggers Saga for the execution)
            if (scenarioExec.getStatus() != ScenarioExecutionStatus.COMPENSATING
                    && scenarioExec.getStatus() != ScenarioExecutionStatus.COMPENSATED) {
                scenarioExec.setStatus(ScenarioExecutionStatus.FAILED);
                scenarioExecutions.save(scenarioExec);
            }

            log.info("GHOST TRANSACTION — action_execution={} was processed, "
                            + "scenario {} is {} — compensation candidate",
                    actionExec.getId(), scenarioExec.getId(), scenarioExec.getStatus());
            return new ReconcileResult("AUTO_COMPENSATED",
                    "Ghost transaction — action processed but scenario needs compensation");
        }
    }

    /**
     * Clean Timeout — DB=TIMEOUT, 3rd party=NOT_FOUND (never processed).
     * Safe to mark FAILED → Saga can compensate previous steps.
     */
    private ReconcileResult handleCleanTimeout(ActionExecution actionExec,
                                               VerificationClient.VerificationResult verifyResult) {
        actionExec.setStatus(ActionExecutionStatus.FAILED);
        actionExec.setErrorMessage("Reconciler verified: 3rd party never received request (404)");
        actionExec.setCompletedAt(OffsetDateTime.now());
        actionExecutions.save(actionExec);

        // Mark scenario FAILED so Saga can trigger
        ScenarioExecution scenarioExec = scenarioExecutions
                .findById(actionExec.getScenarioExecutionId()).orElseThrow();
        if (scenarioExec.getStatus() == ScenarioExecutionStatus.EXECUTING) {
            scenarioExec.setStatus(ScenarioExecutionStatus.FAILED);
            scenarioExecutions.save(scenarioExec);
        }

        insertReconciliationEvent(actionExec,
                "TIMEOUT", "NOT_FOUND", "LOST_RESPONSE", "AUTO_COMPLETED",
                verifyResult.responseBody(),
                "Clean timeout — 3rd party never processed, marked FAILED for Saga");

        log.info("CLEAN TIMEOUT: action_execution={} — 3rd party 404, marked FAILED",
                actionExec.getId());
        return new ReconcileResult("MARKED_FAILED",
                "Clean timeout — 3rd party confirmed not processed, Saga should compensate");
    }

    /**
     * Still Processing — DB=TIMEOUT, 3rd party=PENDING.
     * Need to poll again after interval.
     */
    private ReconcileResult handleStillProcessing(ActionExecution actionExec,
                                                   VerificationClient.VerificationResult verifyResult) {
        log.info("STILL PROCESSING: action_execution={} — 3rd party says PENDING, will retry",
                actionExec.getId());
        // Don't change DB state — consumer will retry on next CDC event or scheduled poll
        return new ReconcileResult("RETRY_LATER",
                "3rd party still processing — will check again");
    }

    /**
     * Verification call itself failed — network error, 5xx from verify endpoint, etc.
     */
    private ReconcileResult handleVerificationError(ActionExecution actionExec,
                                                     VerificationClient.VerificationResult verifyResult) {
        log.error("VERIFICATION ERROR for action_execution={}: {}",
                actionExec.getId(), verifyResult.errorMessage());
        insertReconciliationEvent(actionExec,
                "TIMEOUT", "ERROR", "LOST_RESPONSE", "MANUAL_QUEUED",
                null,
                "Verification call failed: " + verifyResult.errorMessage());
        return new ReconcileResult("MANUAL_QUEUED",
                "Verification error — " + verifyResult.errorMessage());
    }

    private void insertReconciliationEvent(ActionExecution actionExec,
                                            String dbState, String thirdPartyState,
                                            String driftType, String resolution,
                                            JsonNode thirdPartyResponse, String notes) {
        ReconciliationEvent event = new ReconciliationEvent();
        event.setScenarioExecutionId(actionExec.getScenarioExecutionId());
        event.setActionExecutionId(actionExec.getId());
        event.setDriftType(driftType);
        event.setDbState(dbState);
        event.setThirdPartyState(thirdPartyState);
        event.setThirdPartyResponse(thirdPartyResponse);
        event.setResolution(resolution);
        event.setDetectedAt(OffsetDateTime.now());
        event.setResolvedAt(OffsetDateTime.now());
        event.setNotes(notes);
        reconciliationEvents.save(event);
    }

    public record ReconcileResult(String resolution, String message) {}
}

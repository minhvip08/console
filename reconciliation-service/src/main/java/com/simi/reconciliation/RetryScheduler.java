package com.simi.reconciliation;

import com.simi.common.domain.ActionExecutionStatus;
import com.simi.common.domain.entity.ActionExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Polls TIMEOUT action_executions whose 3rd party is still PENDING and re-runs reconciliation.
 *
 * <p>Compensation retry is handled separately via Kafka retry topics
 * ({@link SagaCompensationCdcConsumer} + {@code @RetryableTopic}).
 */
@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    private final ActionExecutionRepository actionExecutions;
    private final ReconciliationEngine engine;
    private final int timeoutThresholdMinutes;

    public RetryScheduler(ActionExecutionRepository actionExecutions,
                          ReconciliationEngine engine,
                          @Value("${reconciliation.polling.timeout-threshold-minutes:5}") int timeoutThresholdMinutes) {
        this.actionExecutions = actionExecutions;
        this.engine = engine;
        this.timeoutThresholdMinutes = timeoutThresholdMinutes;
    }

    /**
     * Re-checks TIMEOUT action_executions whose 3rd party was still PENDING.
     * ReconciliationEngine tracks attempt count via reconciliation_event rows
     * and escalates to MANUAL_QUEUED when max_poll_attempts is reached.
     */
    @Scheduled(fixedDelayString = "${reconciliation.polling.scheduler-interval-ms:30000}")
    public void retryPendingTimeouts() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(timeoutThresholdMinutes);
        List<ActionExecution> candidates =
                actionExecutions.findByStatusAndStartedAtBefore(ActionExecutionStatus.TIMEOUT, cutoff);

        if (candidates.isEmpty()) return;

        log.info("RetryScheduler: {} TIMEOUT action_execution(s) to re-check", candidates.size());
        for (ActionExecution ae : candidates) {
            try {
                ReconciliationEngine.ReconcileResult result = engine.reconcileTimeout(ae.getId());
                log.info("RetryScheduler: action_execution={} → {}: {}",
                        ae.getId(), result.resolution(), result.message());
            } catch (Exception e) {
                log.error("RetryScheduler: error re-checking action_execution={}: {}",
                        ae.getId(), e.getMessage());
            }
        }
    }
}

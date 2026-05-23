package com.simi.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simi.common.domain.SagaCompensationStatus;
import com.simi.common.domain.entity.CompensationDeadLetter;
import com.simi.common.domain.entity.SagaCompensationLog;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;


/**
 * Kafka consumer for Debezium CDC events from saga_compensation_log (docs/07).
 *
 * <p>Retry strategy — 3 retry topics + DLT:
 * <pre>
 * console.saga_compensation_log.cdc          (original)
 *   → fail → console.saga_compensation_log.cdc-retry-0   (~60s delay)
 *   → fail → console.saga_compensation_log.cdc-retry-1   (~120s delay)
 *   → fail → console.saga_compensation_log.cdc-retry-2   (~240s delay)
 *   → fail → console.saga_compensation_log.cdc-dlt       → CompensationDeadLetter (manual review)
 * </pre>
 *
 * <p>Retry triggers on any uncaught exception (typically DB unavailable).
 * Non-COMPENSATION_FAILED events are filtered silently — no retry.
 */
@Component
public class SagaCompensationCdcConsumer {

    private static final Logger log = LoggerFactory.getLogger(SagaCompensationCdcConsumer.class);

    private final SagaCompensationLogRepository sagaLogs;
    private final CompensationDeadLetterRepository deadLetters;
    private final ObjectMapper json;

    public SagaCompensationCdcConsumer(SagaCompensationLogRepository sagaLogs,
                                       CompensationDeadLetterRepository deadLetters,
                                       ObjectMapper json) {
        this.sagaLogs = sagaLogs;
        this.deadLetters = deadLetters;
        this.json = json;
    }

    @RetryableTopic(
            attempts = "4",   // 1 original + 3 retries
            backOff = @BackOff(delay = 60_000, multiplier = 2, jitter = 10_000),
            // attempt 1 → retry-0 after ~60s ± jitter
            // attempt 2 → retry-1 after ~120s ± jitter
            // attempt 3 → retry-2 after ~240s ± jitter
            // attempt 4 fail → DLT
            autoCreateTopics = "false",   // tạo thủ công trên MSK production
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE
    )
    @KafkaListener(
            topics = "${reconciliation.topics.saga-compensation:console.saga_compensation_log.cdc}",
            groupId = "reconciliation-saga-compensation"
    )
    public void onSagaCompensationChange(ConsumerRecord<String, String> record,
                                          Acknowledgment ack) {
        JsonNode envelope;
        try {
            envelope = json.readTree(record.value());
        } catch (Exception e) {
            // Malformed message — ack to skip, don't retry (retrying won't fix bad JSON)
            log.error("Malformed CDC event, skipping: {}", e.getMessage());
            ack.acknowledge();
            return;
        }

        String op = envelope.has("op") ? envelope.get("op").asText() : null;
        JsonNode after = envelope.get("after");

        if (!"u".equals(op) || after == null) {
            ack.acknowledge();
            return;
        }

        String status = after.has("status") ? after.get("status").asText() : null;
        if (!"COMPENSATION_FAILED".equals(status)) {
            ack.acknowledge();
            return;
        }

        Long sagaLogId = after.has("id") ? after.get("id").asLong() : null;
        if (sagaLogId == null) {
            ack.acknowledge();
            return;
        }

        // Any exception below (DB down, entity not found, etc.) triggers retry via topic chain
        resetCompensationToPending(sagaLogId);
        ack.acknowledge();
    }

    /**
     * Handles messages that exhausted all 3 retry attempts.
     * Writes to CompensationDeadLetter for admin review — no further automatic retry.
     */
    @DltHandler
    public void handleDlt(ConsumerRecord<String, String> record, Exception exception) {
        log.error("Compensation exhausted all retries, routing to dead letter: {}", exception.getMessage());
        try {
            JsonNode envelope = json.readTree(record.value());
            JsonNode after = envelope.get("after");
            if (after == null) return;

            Long sagaLogId = after.has("id") ? after.get("id").asLong() : null;
            if (sagaLogId == null) return;

            SagaCompensationLog sagaLog = sagaLogs.findById(sagaLogId).orElse(null);
            if (sagaLog == null) {
                log.warn("DLT: saga_compensation_log={} not found", sagaLogId);
                return;
            }

            CompensationDeadLetter dl = deadLetters
                    .findBySagaCompensationLogId(sagaLogId)
                    .orElseGet(() -> {
                        CompensationDeadLetter newDl = new CompensationDeadLetter();
                        newDl.setSagaCompensationLogId(sagaLogId);
                        newDl.setScenarioExecutionId(sagaLog.getScenarioExecutionId());
                        newDl.setActionDefinitionId(sagaLog.getActionDefinitionId());
                        newDl.setContextSnapshot(sagaLog.getContextSnapshot());
                        return newDl;
                    });

            dl.setRetryCount(3);
            dl.setFailureReason("Exhausted all retry attempts — " + exception.getMessage());
            deadLetters.save(dl);

            log.error("COMPENSATION PERMANENTLY FAILED — saga_compensation_log={}, scenario={} "
                            + "written to compensation_dead_letter for manual review",
                    sagaLogId, sagaLog.getScenarioExecutionId());

        } catch (Exception e) {
            log.error("Failed to write DLT record to compensation_dead_letter: {}", e.getMessage());
        }
    }

    private void resetCompensationToPending(Long sagaLogId) {
        SagaCompensationLog sagaLog = sagaLogs.findById(sagaLogId)
                .orElseThrow(() -> new IllegalStateException(
                        "saga_compensation_log not found: " + sagaLogId));

        if (sagaLog.getStatus() != SagaCompensationStatus.COMPENSATION_FAILED) {
            log.info("saga_compensation_log={} is {} — already resolved, skipping",
                    sagaLogId, sagaLog.getStatus());
            return;
        }

        sagaLog.setStatus(SagaCompensationStatus.PENDING);
        sagaLogs.save(sagaLog);
        log.info("Reset saga_compensation_log={} → PENDING for SagaManager re-execution", sagaLogId);
    }
}

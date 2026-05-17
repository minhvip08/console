package com.simi.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simi.common.domain.SagaCompensationStatus;
import com.simi.common.domain.entity.SagaCompensationLog;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for Debezium CDC events from saga_compensation_log table (docs/07 §Kafka Topics).
 * <pre>
 * Filter: op="u" AND after.status="COMPENSATION_FAILED"
 * Action: retry compensation with backoff
 * </pre>
 * <p>
 * Automatic retry of failed compensations — complement to manual retry via CompensationController.
 */
@Component
public class SagaCompensationCdcConsumer {

    private static final Logger log = LoggerFactory.getLogger(SagaCompensationCdcConsumer.class);

    private final SagaCompensationLogRepository sagaLogs;
    private final ObjectMapper json;

    public SagaCompensationCdcConsumer(SagaCompensationLogRepository sagaLogs,
                                       ObjectMapper json) {
        this.sagaLogs = sagaLogs;
        this.json = json;
    }

    @KafkaListener(
            topics = "${reconciliation.topics.saga-compensation:console.saga_compensation_log.cdc}",
            groupId = "reconciliation-saga-compensation"
    )
    public void onSagaCompensationChange(ConsumerRecord<String, String> record,
                                          Acknowledgment ack) {
        try {
            JsonNode envelope = json.readTree(record.value());

            String op = envelope.has("op") ? envelope.get("op").asText() : null;
            JsonNode after = envelope.get("after");

            if (!"u".equals(op) || after == null) {
                ack.acknowledge();
                return;
            }

            String status = after.has("status") ? after.get("status").asText() : null;
            String sagaLogId = after.has("id") ? after.get("id").asText() : null;
            String scenarioExecId = after.has("scenario_execution_id")
                    ? after.get("scenario_execution_id").asText() : null;

            if (!"COMPENSATION_FAILED".equals(status)) {
                ack.acknowledge();
                return;
            }

            log.warn("CDC event: saga_compensation_log {} → COMPENSATION_FAILED — "
                            + "queuing for retry (scenario_execution={})",
                    sagaLogId, scenarioExecId);

            // For now, just log — automatic retry will be implemented with backoff scheduling.
            // Manual retry is available via POST /executions/{id}/retry-compensation.
            // In production, this consumer would schedule a delayed retry via a scheduler
            // or push to a retry topic with increasing delay.

            // TODO: Implement automatic retry with exponential backoff
            //   1. Read retry_count from dead_letter
            //   2. If retry_count < max_retries → schedule delayed retry
            //   3. If retry_count >= max_retries → permanent dead letter + alert

            ack.acknowledge();

        } catch (Exception e) {
            log.error("Error processing CDC event for saga_compensation_log: {}", e.getMessage(), e);
        }
    }
}

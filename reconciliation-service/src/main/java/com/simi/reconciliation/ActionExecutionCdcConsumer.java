package com.simi.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for Debezium CDC events from action_execution table (docs/07 §Kafka Topics).
 * <pre>
 * Filter: op="u" AND after.status="TIMEOUT"
 * Action: verify 3rd party via ReconciliationEngine
 * </pre>
 * <p>
 * Consumer uses manual ack (at-least-once delivery) — ReconciliationEngine is idempotent
 * (checks current DB state before acting, replaying same event is safe).
 */
@Component
public class ActionExecutionCdcConsumer {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutionCdcConsumer.class);

    private final ReconciliationEngine engine;
    private final ObjectMapper json;

    public ActionExecutionCdcConsumer(ReconciliationEngine engine, ObjectMapper json) {
        this.engine = engine;
        this.json = json;
    }

    @KafkaListener(
            topics = "${reconciliation.topics.action-execution:console.action_execution.cdc}",
            groupId = "reconciliation-action-execution"
    )
    public void onActionExecutionChange(ConsumerRecord<String, String> record,
                                         Acknowledgment ack) {
        try {
            JsonNode envelope = json.readTree(record.value());

            // Debezium envelope: { "before": {...}, "after": {...}, "op": "u"/"c"/"d" }
            String op = envelope.has("op") ? envelope.get("op").asText() : null;
            JsonNode after = envelope.get("after");

            if (!"u".equals(op) || after == null) {
                // Only interested in updates (op=u). Inserts/deletes irrelevant.
                ack.acknowledge();
                return;
            }

            String status = after.has("status") ? after.get("status").asText() : null;
            Long actionExecutionId = after.has("id") ? after.get("id").asLong() : null;

            if (!"TIMEOUT".equals(status)) {
                // Only TIMEOUT status triggers reconciliation (docs/07)
                ack.acknowledge();
                return;
            }

            log.info("CDC event: action_execution {} → TIMEOUT — starting reconciliation",
                    actionExecutionId);

            ReconciliationEngine.ReconcileResult result = engine.reconcileTimeout(actionExecutionId);
            log.info("Reconciliation result for {}: {} — {}",
                    actionExecutionId, result.resolution(), result.message());

            ack.acknowledge();

        } catch (Exception e) {
            log.error("Error processing CDC event for action_execution: {}", e.getMessage(), e);
            // Don't ack — Kafka will redeliver. ReconciliationEngine is idempotent.
            // In production, implement DLT (dead letter topic) after N retries.
        }
    }
}

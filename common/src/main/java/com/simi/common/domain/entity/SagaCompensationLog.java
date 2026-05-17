package com.simi.common.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.simi.common.domain.SagaCompensationStatus;
import com.simi.common.domain.Timestamped;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Mỗi action COMPLETED → INSERT row này với context_snapshot. Compensation Manager dùng
 * snapshot này (KHÔNG phải live context) để chạy compensation ngược.
 * <p>
 * Denorm có chủ đích: action_definition_id (action_execution_id có thể null nếu chưa chạy),
 * step_order (tránh JOIN cho ORDER BY step_order DESC trong hot path — docs/08 §3).
 */
@Entity
@Table(name = "saga_compensation_log")
public class SagaCompensationLog extends Timestamped {

    @Id
    private String id;

    @Column(name = "scenario_execution_id", nullable = false)
    private String scenarioExecutionId;

    @Column(name = "action_execution_id")
    private String actionExecutionId;

    @Column(name = "action_definition_id", nullable = false)
    private String actionDefinitionId;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaCompensationStatus status = SagaCompensationStatus.PENDING;

    @Column(name = "compensation_action_execution_id")
    private String compensationActionExecutionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_snapshot", nullable = false, columnDefinition = "jsonb")
    private JsonNode contextSnapshot;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getScenarioExecutionId() { return scenarioExecutionId; }
    public void setScenarioExecutionId(String v) { this.scenarioExecutionId = v; }
    public String getActionExecutionId() { return actionExecutionId; }
    public void setActionExecutionId(String v) { this.actionExecutionId = v; }
    public String getActionDefinitionId() { return actionDefinitionId; }
    public void setActionDefinitionId(String v) { this.actionDefinitionId = v; }
    public int getStepOrder() { return stepOrder; }
    public void setStepOrder(int v) { this.stepOrder = v; }
    public SagaCompensationStatus getStatus() { return status; }
    public void setStatus(SagaCompensationStatus v) { this.status = v; }
    public String getCompensationActionExecutionId() { return compensationActionExecutionId; }
    public void setCompensationActionExecutionId(String v) { this.compensationActionExecutionId = v; }
    public JsonNode getContextSnapshot() { return contextSnapshot; }
    public void setContextSnapshot(JsonNode v) { this.contextSnapshot = v; }
}

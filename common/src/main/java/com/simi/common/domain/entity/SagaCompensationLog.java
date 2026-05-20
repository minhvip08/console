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
 * step_order (tránh JOIN chain 5 bảng cho ORDER BY step_order DESC trong hot path — docs/08 §3).
 */
@Entity
@Table(name = "saga_compensation_log")
public class SagaCompensationLog extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scenario_execution_id", nullable = false)
    private Long scenarioExecutionId;

    @Column(name = "action_execution_id")
    private Long actionExecutionId;

    @Column(name = "action_definition_id", nullable = false)
    private Long actionDefinitionId;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaCompensationStatus status = SagaCompensationStatus.PENDING;

    @Column(name = "compensation_action_execution_id")
    private Long compensationActionExecutionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_snapshot", nullable = false, columnDefinition = "jsonb")
    private JsonNode contextSnapshot;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getScenarioExecutionId() { return scenarioExecutionId; }
    public void setScenarioExecutionId(Long v) { this.scenarioExecutionId = v; }
    public Long getActionExecutionId() { return actionExecutionId; }
    public void setActionExecutionId(Long v) { this.actionExecutionId = v; }
    public Long getActionDefinitionId() { return actionDefinitionId; }
    public void setActionDefinitionId(Long v) { this.actionDefinitionId = v; }
    public int getStepOrder() { return stepOrder; }
    public void setStepOrder(int v) { this.stepOrder = v; }
    public SagaCompensationStatus getStatus() { return status; }
    public void setStatus(SagaCompensationStatus v) { this.status = v; }
    public Long getCompensationActionExecutionId() { return compensationActionExecutionId; }
    public void setCompensationActionExecutionId(Long v) { this.compensationActionExecutionId = v; }
    public JsonNode getContextSnapshot() { return contextSnapshot; }
    public void setContextSnapshot(JsonNode v) { this.contextSnapshot = v; }
}

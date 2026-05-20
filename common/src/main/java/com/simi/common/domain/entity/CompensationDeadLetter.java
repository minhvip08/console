package com.simi.common.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * Dead letter cho compensation action thất bại (docs/06 §Compensation Failure — Dead Letter).
 * Admin review + manual retry qua API.
 */
@Entity
@Table(name = "compensation_dead_letter")
public class CompensationDeadLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "saga_compensation_log_id", nullable = false)
    private Long sagaCompensationLogId;

    @Column(name = "scenario_execution_id", nullable = false)
    private Long scenarioExecutionId;

    @Column(name = "action_definition_id", nullable = false)
    private Long actionDefinitionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_snapshot", nullable = false, columnDefinition = "jsonb")
    private JsonNode contextSnapshot;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(nullable = false)
    private boolean resolved = false;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSagaCompensationLogId() { return sagaCompensationLogId; }
    public void setSagaCompensationLogId(Long v) { this.sagaCompensationLogId = v; }
    public Long getScenarioExecutionId() { return scenarioExecutionId; }
    public void setScenarioExecutionId(Long v) { this.scenarioExecutionId = v; }
    public Long getActionDefinitionId() { return actionDefinitionId; }
    public void setActionDefinitionId(Long v) { this.actionDefinitionId = v; }
    public JsonNode getContextSnapshot() { return contextSnapshot; }
    public void setContextSnapshot(JsonNode v) { this.contextSnapshot = v; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String v) { this.failureReason = v; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int v) { this.retryCount = v; }
    public boolean isResolved() { return resolved; }
    public void setResolved(boolean v) { this.resolved = v; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime v) { this.resolvedAt = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}

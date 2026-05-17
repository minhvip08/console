package com.simi.common.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.simi.common.domain.ActionExecutionStatus;
import com.simi.common.domain.Timestamped;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * Tầng 4 idempotency — UNIQUE idempotency_key = SHA256(execId + actDefId + sorted_params).
 * response_snapshot lớn (> ~100KB) lưu S3 qua s3_ref (claim check — docs/10).
 */
@Entity
@Table(name = "action_execution")
public class ActionExecution extends Timestamped {

    @Id
    private String id;

    @Column(name = "scenario_execution_id", nullable = false)
    private String scenarioExecutionId;

    @Column(name = "trigger_execution_id", nullable = false)
    private String triggerExecutionId;

    @Column(name = "action_definition_id", nullable = false)
    private String actionDefinitionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActionExecutionStatus status = ActionExecutionStatus.PENDING;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_snapshot", columnDefinition = "jsonb")
    private JsonNode responseSnapshot;

    @Column(name = "s3_ref")
    private String s3Ref;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getScenarioExecutionId() { return scenarioExecutionId; }
    public void setScenarioExecutionId(String v) { this.scenarioExecutionId = v; }
    public String getTriggerExecutionId() { return triggerExecutionId; }
    public void setTriggerExecutionId(String v) { this.triggerExecutionId = v; }
    public String getActionDefinitionId() { return actionDefinitionId; }
    public void setActionDefinitionId(String v) { this.actionDefinitionId = v; }
    public ActionExecutionStatus getStatus() { return status; }
    public void setStatus(ActionExecutionStatus s) { this.status = s; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String v) { this.idempotencyKey = v; }
    public JsonNode getResponseSnapshot() { return responseSnapshot; }
    public void setResponseSnapshot(JsonNode v) { this.responseSnapshot = v; }
    public String getS3Ref() { return s3Ref; }
    public void setS3Ref(String v) { this.s3Ref = v; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime v) { this.startedAt = v; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime v) { this.completedAt = v; }
}

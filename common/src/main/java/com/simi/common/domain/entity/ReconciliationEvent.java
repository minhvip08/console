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
 * Audit log cho mỗi reconciliation event — Reconciler INSERT khi phát hiện drift
 * và resolve (hoặc queue manual). docs/07 §reconciliation_event table.
 */
@Entity
@Table(name = "reconciliation_event")
public class ReconciliationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scenario_execution_id", nullable = false)
    private Long scenarioExecutionId;

    @Column(name = "action_execution_id", nullable = false)
    private Long actionExecutionId;

    @Column(name = "drift_type", nullable = false)
    private String driftType; // GHOST_TRANSACTION, ORPHAN_RECORD, LOST_RESPONSE, STALE_COMPENSATION

    @Column(name = "db_state", nullable = false)
    private String dbState;

    @Column(name = "third_party_state", nullable = false)
    private String thirdPartyState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "third_party_response", columnDefinition = "jsonb")
    private JsonNode thirdPartyResponse;

    @Column
    private String resolution; // AUTO_COMPENSATED, AUTO_COMPLETED, MANUAL_QUEUED, NO_ACTION

    @Column(name = "detected_at", nullable = false)
    private OffsetDateTime detectedAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getScenarioExecutionId() { return scenarioExecutionId; }
    public void setScenarioExecutionId(Long v) { this.scenarioExecutionId = v; }
    public Long getActionExecutionId() { return actionExecutionId; }
    public void setActionExecutionId(Long v) { this.actionExecutionId = v; }
    public String getDriftType() { return driftType; }
    public void setDriftType(String v) { this.driftType = v; }
    public String getDbState() { return dbState; }
    public void setDbState(String v) { this.dbState = v; }
    public String getThirdPartyState() { return thirdPartyState; }
    public void setThirdPartyState(String v) { this.thirdPartyState = v; }
    public JsonNode getThirdPartyResponse() { return thirdPartyResponse; }
    public void setThirdPartyResponse(JsonNode v) { this.thirdPartyResponse = v; }
    public String getResolution() { return resolution; }
    public void setResolution(String v) { this.resolution = v; }
    public OffsetDateTime getDetectedAt() { return detectedAt; }
    public void setDetectedAt(OffsetDateTime v) { this.detectedAt = v; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime v) { this.resolvedAt = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}

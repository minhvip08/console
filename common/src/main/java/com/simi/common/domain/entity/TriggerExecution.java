package com.simi.common.domain.entity;

import com.simi.common.domain.Timestamped;
import com.simi.common.domain.TriggerExecutionStatus;
import jakarta.persistence.*;

import java.time.OffsetDateTime;

/** Tầng 3 idempotency — UNIQUE (scenario_execution_id, step_trigger_id). */
@Entity
@Table(name = "trigger_execution")
public class TriggerExecution extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scenario_execution_id", nullable = false)
    private Long scenarioExecutionId;

    @Column(name = "step_trigger_id", nullable = false)
    private Long stepTriggerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TriggerExecutionStatus status = TriggerExecutionStatus.PENDING;

    @Column(name = "request_id")
    private String requestId;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getScenarioExecutionId() { return scenarioExecutionId; }
    public void setScenarioExecutionId(Long v) { this.scenarioExecutionId = v; }
    public Long getStepTriggerId() { return stepTriggerId; }
    public void setStepTriggerId(Long v) { this.stepTriggerId = v; }
    public TriggerExecutionStatus getStatus() { return status; }
    public void setStatus(TriggerExecutionStatus s) { this.status = s; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { this.requestId = v; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime v) { this.startedAt = v; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime v) { this.completedAt = v; }
}

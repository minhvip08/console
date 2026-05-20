package com.simi.common.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Junction N:N giữa trigger và action với action_order (sequential execution).
 * Không kế thừa Timestamped vì chỉ có created_at — không có updated_at.
 */
@Entity
@Table(name = "step_trigger_action")
public class StepTriggerAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "step_trigger_id", nullable = false)
    private Long stepTriggerId;

    @Column(name = "action_definition_id", nullable = false)
    private Long actionDefinitionId;

    @Column(name = "action_order", nullable = false)
    private int actionOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStepTriggerId() { return stepTriggerId; }
    public void setStepTriggerId(Long v) { this.stepTriggerId = v; }
    public Long getActionDefinitionId() { return actionDefinitionId; }
    public void setActionDefinitionId(Long v) { this.actionDefinitionId = v; }
    public int getActionOrder() { return actionOrder; }
    public void setActionOrder(int v) { this.actionOrder = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}

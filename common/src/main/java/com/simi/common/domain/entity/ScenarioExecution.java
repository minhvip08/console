package com.simi.common.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.simi.common.domain.ScenarioExecutionStatus;
import com.simi.common.domain.Timestamped;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Runtime instance của 1 scenario_definition. `context` JSONB là snapshot tại checkpoint
 * (step transition / complete) — Redis 3-key giữ live state trong suốt execution
 * (docs/04 §Tại sao Redis).
 */
@Entity
@Table(name = "scenario_execution")
public class ScenarioExecution extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scenario_definition_id", nullable = false)
    private Long scenarioDefinitionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScenarioExecutionStatus status = ScenarioExecutionStatus.PENDING;

    @Column(name = "current_step_order")
    private Integer currentStepOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode context;

    @Column(name = "created_by")
    private String createdBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getScenarioDefinitionId() { return scenarioDefinitionId; }
    public void setScenarioDefinitionId(Long v) { this.scenarioDefinitionId = v; }
    public ScenarioExecutionStatus getStatus() { return status; }
    public void setStatus(ScenarioExecutionStatus s) { this.status = s; }
    public Integer getCurrentStepOrder() { return currentStepOrder; }
    public void setCurrentStepOrder(Integer v) { this.currentStepOrder = v; }
    public JsonNode getContext() { return context; }
    public void setContext(JsonNode context) { this.context = context; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}

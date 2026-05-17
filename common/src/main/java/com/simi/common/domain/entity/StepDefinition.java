package com.simi.common.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.simi.common.domain.Timestamped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Một step trong scenario. UI JSONB chứa widget layout (mỗi widget có outputVarId: VAR_ULID).
 * Trigger config được normalize ra step_trigger + step_trigger_action.
 */
@Entity
@Table(name = "step_definition")
public class StepDefinition extends Timestamped {

    @Id
    private String id;

    @Column(name = "scenario_definition_id", nullable = false)
    private String scenarioDefinitionId;

    @Column(nullable = false)
    private String name;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ui", nullable = false, columnDefinition = "jsonb")
    private JsonNode ui;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getScenarioDefinitionId() { return scenarioDefinitionId; }
    public void setScenarioDefinitionId(String v) { this.scenarioDefinitionId = v; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getStepOrder() { return stepOrder; }
    public void setStepOrder(int stepOrder) { this.stepOrder = stepOrder; }
    public JsonNode getUi() { return ui; }
    public void setUi(JsonNode ui) { this.ui = ui; }
}

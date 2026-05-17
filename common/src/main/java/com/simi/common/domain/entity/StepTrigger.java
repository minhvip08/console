package com.simi.common.domain.entity;

import com.simi.common.domain.Timestamped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "step_trigger")
public class StepTrigger extends Timestamped {

    @Id
    private String id;

    @Column(name = "step_definition_id", nullable = false)
    private String stepDefinitionId;

    @Column(nullable = false)
    private String name;

    @Column(name = "trigger_type", nullable = false)
    private String triggerType; // button_click | on_load | on_change

    @Column(name = "widget_id")
    private String widgetId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStepDefinitionId() { return stepDefinitionId; }
    public void setStepDefinitionId(String v) { this.stepDefinitionId = v; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getWidgetId() { return widgetId; }
    public void setWidgetId(String widgetId) { this.widgetId = widgetId; }
}

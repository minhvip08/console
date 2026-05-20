package com.simi.common.domain.entity;

import com.simi.common.domain.Timestamped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "step_trigger")
public class StepTrigger extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "step_definition_id", nullable = false)
    private Long stepDefinitionId;

    @Column(nullable = false)
    private String name;

    @Column(name = "trigger_type", nullable = false)
    private String triggerType; // button_click | on_load | on_change

    @Column(name = "widget_id")
    private String widgetId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStepDefinitionId() { return stepDefinitionId; }
    public void setStepDefinitionId(Long v) { this.stepDefinitionId = v; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getWidgetId() { return widgetId; }
    public void setWidgetId(String widgetId) { this.widgetId = widgetId; }
}

package com.simi.common.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Local mock configuration; not an Elton or Shopee API schema. */
@Entity
@Table(name = "shopee_connection")
public class ShopeeConnection {
    @Version
    private long version;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_types", columnDefinition = "jsonb")
    private java.util.List<String> dataTypes = java.util.List.of();

    @Column(name = "first_load_date")
    private java.time.LocalDate firstLoadDate;

    @Column(name = "lookback_days")
    private int lookbackDays;

    @Column(name = "frequency_minutes")
    private Integer frequencyMinutes;

    @Column(name = "deleted")
    private boolean deleted;

    @Column(name = "scenario_definition_id")
    private Long scenarioDefinitionId;

    @Column(name = "action_definition_id")
    private Long actionDefinitionId;

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long value) { this.sourceId = value; }
    public String getName() { return name; }
    public void setName(String value) { this.name = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { this.description = value; }
    public java.util.List<String> getDataTypes() { return dataTypes; }
    public void setDataTypes(java.util.List<String> value) { this.dataTypes = value; }
    public java.time.LocalDate getFirstLoadDate() { return firstLoadDate; }
    public void setFirstLoadDate(java.time.LocalDate value) { this.firstLoadDate = value; }
    public int getLookbackDays() { return lookbackDays; }
    public void setLookbackDays(int value) { this.lookbackDays = value; }
    public Integer getFrequencyMinutes() { return frequencyMinutes; }
    public void setFrequencyMinutes(Integer value) { this.frequencyMinutes = value; }
    public boolean getDeleted() { return deleted; }
    public void setDeleted(boolean value) { this.deleted = value; }
    public Long getScenarioDefinitionId() { return scenarioDefinitionId; }
    public void setScenarioDefinitionId(Long value) { this.scenarioDefinitionId = value; }
    public Long getActionDefinitionId() { return actionDefinitionId; }
    public void setActionDefinitionId(Long value) { this.actionDefinitionId = value; }
}

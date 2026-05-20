package com.simi.common.domain.entity;

import com.simi.common.domain.Timestamped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 1:1 với action_definition (optional). required_var_ids: VAR_ULID[] cần load từ
 * Execution Context để chạy compensation action. KHÔNG dùng alias string (đã migrate).
 */
@Entity
@Table(name = "action_compensation")
public class ActionCompensation extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action_definition_id", nullable = false, unique = true)
    private Long actionDefinitionId;

    @Column(name = "compensation_action_definition_id", nullable = false)
    private Long compensationActionDefinitionId;

    @Column(name = "trigger_type", nullable = false)
    private String triggerType; // auto | manual | none

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "required_var_ids", nullable = false, columnDefinition = "TEXT[]")
    private String[] requiredVarIds = new String[0];

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActionDefinitionId() { return actionDefinitionId; }
    public void setActionDefinitionId(Long v) { this.actionDefinitionId = v; }
    public Long getCompensationActionDefinitionId() { return compensationActionDefinitionId; }
    public void setCompensationActionDefinitionId(Long v) { this.compensationActionDefinitionId = v; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String[] getRequiredVarIds() { return requiredVarIds; }
    public void setRequiredVarIds(String[] v) { this.requiredVarIds = v; }
}

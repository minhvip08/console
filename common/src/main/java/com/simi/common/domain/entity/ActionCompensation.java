package com.simi.common.domain.entity;

import com.simi.common.domain.Timestamped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
    private String id;

    @Column(name = "action_definition_id", nullable = false, unique = true)
    private String actionDefinitionId;

    @Column(name = "compensation_action_definition_id", nullable = false)
    private String compensationActionDefinitionId;

    @Column(name = "trigger_type", nullable = false)
    private String triggerType; // auto | manual | none

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "required_var_ids", nullable = false, columnDefinition = "TEXT[]")
    private String[] requiredVarIds = new String[0];

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getActionDefinitionId() { return actionDefinitionId; }
    public void setActionDefinitionId(String v) { this.actionDefinitionId = v; }
    public String getCompensationActionDefinitionId() { return compensationActionDefinitionId; }
    public void setCompensationActionDefinitionId(String v) { this.compensationActionDefinitionId = v; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String[] getRequiredVarIds() { return requiredVarIds; }
    public void setRequiredVarIds(String[] v) { this.requiredVarIds = v; }
}

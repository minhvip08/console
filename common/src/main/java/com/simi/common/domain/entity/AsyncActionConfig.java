package com.simi.common.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Config chỉ tồn tại khi action_definition.adapter_type = 'async'.
 */
@Entity
@Table(name = "async_action_config")
public class AsyncActionConfig {

    @Id
    private String id;

    @Column(name = "action_definition_id", nullable = false, unique = true)
    private String actionDefinitionId;

    @Column(name = "publish_to", nullable = false)
    private String publishTo;

    @Column(name = "status_check_endpoint")
    private String statusCheckEndpoint;

    @Column(name = "status_check_interval_ms")
    private Integer statusCheckIntervalMs;

    @Column(name = "status_check_timeout_ms")
    private Integer statusCheckTimeoutMs;

    @Column(name = "done_condition")
    private String doneCondition;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getActionDefinitionId() { return actionDefinitionId; }
    public void setActionDefinitionId(String v) { this.actionDefinitionId = v; }
    public String getPublishTo() { return publishTo; }
    public void setPublishTo(String v) { this.publishTo = v; }
    public String getStatusCheckEndpoint() { return statusCheckEndpoint; }
    public void setStatusCheckEndpoint(String v) { this.statusCheckEndpoint = v; }
    public Integer getStatusCheckIntervalMs() { return statusCheckIntervalMs; }
    public void setStatusCheckIntervalMs(Integer v) { this.statusCheckIntervalMs = v; }
    public Integer getStatusCheckTimeoutMs() { return statusCheckTimeoutMs; }
    public void setStatusCheckTimeoutMs(Integer v) { this.statusCheckTimeoutMs = v; }
    public String getDoneCondition() { return doneCondition; }
    public void setDoneCondition(String v) { this.doneCondition = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}

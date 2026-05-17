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
 * Config cho Reconciliation Service verify 3rd party state khi action TIMEOUT.
 * Null khi 3rd party không expose verify endpoint → manual queue.
 */
@Entity
@Table(name = "action_verification")
public class ActionVerification extends Timestamped {

    @Id
    private String id;

    @Column(name = "action_definition_id", nullable = false, unique = true)
    private String actionDefinitionId;

    @Column(nullable = false)
    private String endpoint;

    @Column(name = "http_method", nullable = false)
    private String httpMethod;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "query_params", columnDefinition = "jsonb")
    private JsonNode queryParams;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode conditions;

    @Column(name = "poll_interval_ms", nullable = false)
    private int pollIntervalMs = 5000;

    @Column(name = "max_poll_attempts", nullable = false)
    private int maxPollAttempts = 12;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getActionDefinitionId() { return actionDefinitionId; }
    public void setActionDefinitionId(String v) { this.actionDefinitionId = v; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String v) { this.endpoint = v; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String v) { this.httpMethod = v; }
    public JsonNode getQueryParams() { return queryParams; }
    public void setQueryParams(JsonNode v) { this.queryParams = v; }
    public JsonNode getConditions() { return conditions; }
    public void setConditions(JsonNode v) { this.conditions = v; }
    public int getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(int v) { this.pollIntervalMs = v; }
    public int getMaxPollAttempts() { return maxPollAttempts; }
    public void setMaxPollAttempts(int v) { this.maxPollAttempts = v; }
}

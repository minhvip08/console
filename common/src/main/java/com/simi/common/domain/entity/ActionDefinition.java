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
 * Action định nghĩa endpoint + parameters + output. Build từ Swagger YAML lúc import
 * (Backoffice flow: pick service từ Platform API → fetch swagger → gen action_definitions).
 * <p>
 * parameters/output là JSONB array — runtime bind theo varId trong Execution Context.
 */
@Entity
@Table(name = "action_definition")
public class ActionDefinition extends Timestamped {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "service_id", nullable = false)
    private String serviceId;

    @Column(nullable = false)
    private String endpoint;

    @Column(name = "http_method", nullable = false)
    private String httpMethod;

    @Column(name = "adapter_type", nullable = false)
    private String adapterType = "http";

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs = 10000;

    @Column(name = "retry_policy_id")
    private String retryPolicyId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode parameters;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode output;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String v) { this.serviceId = v; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String v) { this.endpoint = v; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String v) { this.httpMethod = v; }
    public String getAdapterType() { return adapterType; }
    public void setAdapterType(String v) { this.adapterType = v; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int v) { this.timeoutMs = v; }
    public String getRetryPolicyId() { return retryPolicyId; }
    public void setRetryPolicyId(String v) { this.retryPolicyId = v; }
    public JsonNode getParameters() { return parameters; }
    public void setParameters(JsonNode v) { this.parameters = v; }
    public JsonNode getOutput() { return output; }
    public void setOutput(JsonNode v) { this.output = v; }
}

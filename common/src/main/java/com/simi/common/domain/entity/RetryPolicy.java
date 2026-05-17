package com.simi.common.domain.entity;

import com.simi.common.domain.Timestamped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Retry policy reusable cross actions (3NF — loại transitive trên action_definition).
 */
@Entity
@Table(name = "retry_policy")
public class RetryPolicy extends Timestamped {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "backoff_type", nullable = false)
    private String backoffType; // exponential | linear | fixed

    @Column(name = "initial_interval_ms", nullable = false)
    private int initialIntervalMs = 1000;

    @Column(name = "max_interval_ms")
    private Integer maxIntervalMs;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "retryable_status_codes", nullable = false, columnDefinition = "INT[]")
    private int[] retryableStatusCodes = new int[]{502, 503, 504};

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public String getBackoffType() { return backoffType; }
    public void setBackoffType(String backoffType) { this.backoffType = backoffType; }
    public int getInitialIntervalMs() { return initialIntervalMs; }
    public void setInitialIntervalMs(int v) { this.initialIntervalMs = v; }
    public Integer getMaxIntervalMs() { return maxIntervalMs; }
    public void setMaxIntervalMs(Integer v) { this.maxIntervalMs = v; }
    public int[] getRetryableStatusCodes() { return retryableStatusCodes; }
    public void setRetryableStatusCodes(int[] v) { this.retryableStatusCodes = v; }
}

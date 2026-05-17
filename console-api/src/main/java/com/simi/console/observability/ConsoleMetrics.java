package com.simi.console.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Centralized metrics registry cho Console API (docs/10 §CloudWatch).
 * <p>
 * All metrics flow: Micrometer → CloudWatch Metrics (via aws-cloudwatch exporter).
 * LocalStack: metrics pushed to http://localhost:4566 via CW endpoint.
 * <p>
 * Key metrics:
 * <ul>
 *   <li>action.execution.duration — action execution latency histogram</li>
 *   <li>action.execution.total — action execution counter by status</li>
 *   <li>action.execution.timeout — TIMEOUT action count</li>
 *   <li>saga.compensation.total — compensation runs counter</li>
 *   <li>saga.compensation.failed — failed compensations (alarm trigger)</li>
 *   <li>compensation.dead_letter.count — dead letter inserts (alarm trigger)</li>
 *   <li>trigger.execution.total — trigger execution counter</li>
 *   <li>rbac.cache.hit / rbac.cache.miss — RBAC cache hit rate</li>
 * </ul>
 */
@Component
public class ConsoleMetrics {

    // ─── Action Execution ───
    private final Counter actionTotal;
    private final Counter actionCompleted;
    private final Counter actionFailed;
    private final Counter actionTimeout;
    private final Timer actionDuration;

    // ─── Saga Compensation ───
    private final Counter compensationTotal;
    private final Counter compensationCompleted;
    private final Counter compensationFailed;
    private final Counter deadLetterCount;

    // ─── Trigger Execution ───
    private final Counter triggerTotal;
    private final Counter triggerDedupHit;

    // ─── RBAC ───
    private final Counter rbacCacheHit;
    private final Counter rbacCacheMiss;

    public ConsoleMetrics(MeterRegistry registry) {
        // Action
        this.actionTotal = Counter.builder("action.execution.total")
                .description("Total action executions")
                .register(registry);
        this.actionCompleted = Counter.builder("action.execution.completed")
                .description("Completed action executions")
                .register(registry);
        this.actionFailed = Counter.builder("action.execution.failed")
                .description("Failed action executions (4xx/5xx)")
                .register(registry);
        this.actionTimeout = Counter.builder("action.execution.timeout")
                .description("Timeout action executions (no response)")
                .register(registry);
        this.actionDuration = Timer.builder("action.execution.duration")
                .description("Action execution latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        // Saga
        this.compensationTotal = Counter.builder("saga.compensation.total")
                .description("Total compensation runs")
                .register(registry);
        this.compensationCompleted = Counter.builder("saga.compensation.completed")
                .description("Successful compensations")
                .register(registry);
        this.compensationFailed = Counter.builder("saga.compensation.failed")
                .description("Failed compensations (alarm threshold > 0)")
                .register(registry);
        this.deadLetterCount = Counter.builder("compensation.dead_letter.count")
                .description("Dead letter inserts (alarm threshold > 0)")
                .register(registry);

        // Trigger
        this.triggerTotal = Counter.builder("trigger.execution.total")
                .description("Total trigger executions")
                .register(registry);
        this.triggerDedupHit = Counter.builder("trigger.dedup.hit")
                .description("Request dedup hits (Tầng 2)")
                .register(registry);

        // RBAC
        this.rbacCacheHit = Counter.builder("rbac.cache.hit")
                .description("RBAC Redis cache hits")
                .register(registry);
        this.rbacCacheMiss = Counter.builder("rbac.cache.miss")
                .description("RBAC Redis cache misses")
                .register(registry);
    }

    // ─── Action Execution ───
    public void recordActionExecution(String status) {
        actionTotal.increment();
        switch (status) {
            case "COMPLETED" -> actionCompleted.increment();
            case "FAILED" -> actionFailed.increment();
            case "TIMEOUT" -> actionTimeout.increment();
        }
    }

    public Timer.Sample startActionTimer() {
        return Timer.start();
    }

    public void stopActionTimer(Timer.Sample sample) {
        sample.stop(actionDuration);
    }

    public void recordActionDuration(Duration duration) {
        actionDuration.record(duration);
    }

    // ─── Saga Compensation ───
    public void recordCompensation(boolean success) {
        compensationTotal.increment();
        if (success) {
            compensationCompleted.increment();
        } else {
            compensationFailed.increment();
        }
    }

    public void recordDeadLetter() {
        deadLetterCount.increment();
    }

    // ─── Trigger Execution ───
    public void recordTriggerExecution() {
        triggerTotal.increment();
    }

    public void recordDedupHit() {
        triggerDedupHit.increment();
    }

    // ─── RBAC ───
    public void recordRbacCacheHit() {
        rbacCacheHit.increment();
    }

    public void recordRbacCacheMiss() {
        rbacCacheMiss.increment();
    }
}

package com.simi.reconciliation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Reconciliation Service — Kafka consumer, NOT a regular HTTP service (docs/07).
 * Consumes Debezium CDC events from MSK, verifies 3rd party state, resolves drift.
 * <p>
 * Only exposes /actuator/health for liveness/readiness probes.
 */
@SpringBootApplication
@EnableScheduling
@EntityScan(basePackages = {"com.simi.common.domain", "com.simi.reconciliation"})
@EnableJpaRepositories(basePackages = {"com.simi.reconciliation"})
public class ReconciliationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReconciliationApplication.class, args);
    }
}

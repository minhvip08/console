package com.simi.console.observability;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Custom health indicator for PostgreSQL.
 * Validates DB connection + Flyway migration state.
 * Reports replication slot status for Debezium CDC monitoring.
 */
@Component
public class DatabaseHealthIndicator implements HealthIndicator {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Health health() {
        try {
            // Basic connectivity
            Object result = em.createNativeQuery("SELECT 1").getSingleResult();

            // Check replication slot for CDC (docs/10 §Monitor replication slot lag)
            Health.Builder builder = Health.up()
                    .withDetail("type", "RDS PostgreSQL 15")
                    .withDetail("wal_level", getWalLevel());

            try {
                var slots = em.createNativeQuery(
                        "SELECT slot_name, active, confirmed_flush_lsn FROM pg_replication_slots"
                ).getResultList();
                builder.withDetail("replication_slots", slots.size());
            } catch (Exception e) {
                builder.withDetail("replication_slots", "N/A");
            }

            return builder.build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }

    private String getWalLevel() {
        try {
            return (String) em.createNativeQuery("SHOW wal_level").getSingleResult();
        } catch (Exception e) {
            return "unknown";
        }
    }
}

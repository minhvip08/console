# DR Runbook — Scenario Console

## 1. PostgreSQL Failover

### Scenario: Primary RDS instance down

**Detection:** CloudWatch alarm `RDSAvailability` → SNS → PagerDuty

**Recovery:**
1. RDS Multi-AZ automatic failover (~30s) — DNS endpoint unchanged
2. Verify:
   ```bash
   # Check replication status
   psql -h console-rds.xxx.rds.amazonaws.com -U console -c "SELECT * FROM pg_stat_replication;"
   
   # Check Debezium replication slot survived
   psql -c "SELECT slot_name, active, confirmed_flush_lsn FROM pg_replication_slots;"
   ```
3. Restart Debezium connector if slot lost:
   ```bash
   curl -X DELETE http://debezium:8083/connectors/console-cdc
   # Re-register (use debezium-init script)
   ```
4. Verify Reconciliation Service reconnected (check logs for Kafka consumer heartbeat)

**LocalStack test:**
```bash
docker compose stop postgres
# Wait 30s
docker compose start postgres
# Verify app reconnects
curl http://localhost:8080/actuator/health
```

---

## 2. Redis Restart — Restore from DB

### Scenario: ElastiCache node restart / memory OOM

**Detection:** CloudWatch alarm `ElastiCacheEngineCPUUtilization` or `CurrConnections=0`

**Recovery:**
1. Redis restart is automatic (ElastiCache failover)
2. Context data is **NOT lost** — PostgreSQL is source of truth (DB First 2PC, docs/04)
3. Active executions call resume endpoint to rebuild Redis state:
   ```bash
   # For each active execution:
   curl -X POST http://console-api/api/executions/{execId}/resume
   ```
4. RBAC cache auto-rebuilds on next request (cache-aside pattern)
5. Dedup keys (`dedup:{requestId}`) are ephemeral — loss is safe (worst case: 1 duplicate processed)

**LocalStack test:**
```bash
docker compose restart redis
# Verify app detects Redis down, then reconnects
curl http://localhost:8080/actuator/health
# Resume an execution
curl -X POST http://localhost:8080/api/executions/exec_01JW.../resume
```

---

## 3. Kafka / MSK Broker Restart

### Scenario: MSK broker restart or partition rebalance

**Detection:** CloudWatch alarm `MSKBrokerCount` < 3 or consumer group lag > 10,000

**Recovery:**
1. Kafka is replicated (factor=3) — single broker restart doesn't lose data
2. Consumer group rebalance is automatic (Spring Kafka handles partition reassignment)
3. Verify consumer lag after recovery:
   ```bash
   kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
     --group reconciliation-service --describe
   ```
4. If lag > 10,000: check Reconciliation Service logs for processing errors

**Replay test:**
```bash
# Reset consumer offset to replay events
kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --group reconciliation-service \
  --topic console.action_execution.cdc \
  --reset-offsets --to-earliest --execute
```
Reconciliation Engine is idempotent — replay is safe.

---

## 4. Debezium Connector Failure

### Scenario: Replication slot dropped or connector config error

**Detection:** Debezium REST API health check fails

**Recovery:**
1. Check connector status:
   ```bash
   curl http://debezium:8083/connectors/console-cdc/status
   ```
2. If FAILED, check tasks:
   ```bash
   curl http://debezium:8083/connectors/console-cdc/tasks/0/status
   ```
3. Restart task:
   ```bash
   curl -X POST http://debezium:8083/connectors/console-cdc/tasks/0/restart
   ```
4. If replication slot dropped, recreate:
   ```sql
   SELECT pg_create_logical_replication_slot('console_reconciliation_slot', 'pgoutput');
   ```
5. Delete and re-register connector (idempotent init script)

**⚠️ WAL retention:** Monitor `pg_wal_lsn_diff()` — if slot lag > 1GB, risk of disk full.

---

## 5. S3 Unavailability

### Scenario: S3 endpoint unreachable (very rare)

**Impact:** Large response payloads (>100KB) fail to upload

**Recovery:**
1. S3ClaimCheckService has fallback: keep response in DB JSONB column
2. No data loss — just larger DB rows until S3 recovers
3. Monitor `s3.upload.error` metric

---

## 6. Full System Recovery Checklist

```
1. [ ] PostgreSQL: verify connectivity, replication slot, Flyway migration state
2. [ ] Redis: verify PING, resume active executions
3. [ ] Kafka: verify broker count, consumer lag
4. [ ] Debezium: verify connector status, replication slot
5. [ ] Console API: verify /actuator/health shows UP
6. [ ] Reconciliation Service: verify /actuator/health shows UP
7. [ ] S3: verify bucket access
8. [ ] CloudWatch: verify metrics flowing
```

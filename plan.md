# Implementation Plan — Scenario Console

> Phased roadmap để implement Scenario Console theo docs/. Mỗi phase có **deliverables** rõ ràng và **acceptance criteria** để kết thúc phase đó.

**Stack:** Java 25 + Spring Boot 4.0.6 + Gradle multi-module
**Layout:** 1 repo, 2 module deployable + shared common module

---

## Module Structure (target)

```
console/                                 ← repo root, settings.gradle
├── settings.gradle                      include 'common', 'console-api', 'backoffice-api'
├── build.gradle                         root config (plugin versions, Java toolchain)
├── common/                              ← shared lib (KHÔNG deploy độc lập)
│   └── src/main/java/com/simi/common/
│       ├── domain/                      entities: Scenario, Step, Action, Variable, ContextDataItem
│       ├── repository/                  port interfaces
│       ├── ulid/                        ULID generator, prefix constants
│       └── error/                       common exceptions
├── console-api/                         ← service 1 (port 8080)
│   ├── build.gradle
│   └── src/main/java/com/simi/console/
│       ├── ConsoleApplication.java
│       ├── feature/
│       │   ├── executor/
│       │   ├── context/
│       │   └── compensation/
│       └── infrastructure/
│           ├── repository/postgresql/
│           ├── repository/redis/
│           ├── repository/s3/
│           └── client/external_api/
└── backoffice-api/                      ← service 2 (port 8081)
    ├── build.gradle
    └── src/main/java/com/simi/backoffice/
        ├── BackofficeApplication.java
        └── feature/management/
            ├── scenario_crud/
            ├── step_crud/
            ├── action_crud/
            └── swagger_import/
```

---

## Phase 0 — Bootstrap Multi-Module

**Mục tiêu:** Convert skeleton hiện tại sang multi-module Gradle với 2 service + common module.

### Tasks
- [ ] `settings.gradle` ở root, include `common`, `console-api`, `backoffice-api`.
- [ ] Move existing `console/` skeleton vào `console-api/`, rename package `com.simi.console.*`.
- [ ] Tạo `common/` module với placeholder entities.
- [ ] Tạo `backoffice-api/` module với `BackofficeApplication.java` (port 8081).
- [ ] Root `build.gradle` quản lý Java toolchain 25, Spring Boot 4.0.6 plugin.
- [ ] Mỗi service module có `application.yaml` riêng (port + datasource).
- [ ] Test: `./gradlew :console-api:bootRun` + `:backoffice-api:bootRun` chạy được, mỗi service health-check OK.

### Acceptance
- [ ] `./gradlew build` ở root → cả 3 module build pass.
- [ ] 2 service start được trên 2 port khác nhau.
- [ ] Common module được console-api và backoffice-api dùng chung (test bằng cách reference 1 class).

---

## Phase 1 — Database & Migrations

**Mục tiêu:** Schema PostgreSQL hoàn chỉnh theo docs/08-database.md.

### Tasks
- [ ] Chọn migration tool: **Flyway** (recommended) hoặc Liquibase.
- [ ] Migration `V1__definition_layer.sql`:
  - `service`, `retry_policy`
  - `scenario_definition`, `step_definition` (chỉ `ui` JSONB)
  - `action_definition`, `step_trigger`, `step_trigger_action`
  - `action_compensation` (với `required_var_ids TEXT[]`)
  - `action_verification`, `async_action_config`
- [ ] Migration `V2__execution_layer.sql`:
  - `scenario_execution`, `trigger_execution` (UNIQUE constraint Tầng 3)
  - `action_execution` (UNIQUE idempotency_key — Tầng 4)
- [ ] Migration `V3__saga_layer.sql`:
  - `saga_compensation_log` (với `step_order` denorm)
  - `compensation_dead_letter`
- [ ] Migration `V4__reconciliation_layer.sql`:
  - `reconciliation_event`
- [ ] Migration `V5__indexes.sql`: tất cả index theo docs/08-database.md §5.
- [ ] Migration `V6__cdc_publication.sql`:
  ```sql
  ALTER SYSTEM SET wal_level = logical;
  CREATE PUBLICATION console_reconciliation_pub
    FOR TABLE action_execution, scenario_execution, saga_compensation_log;
  ```
- [ ] Docker compose: Postgres 15 + Redis 7 + Kafka local cho dev.

### Acceptance
- [ ] `./gradlew flywayMigrate` chạy fresh DB → tất cả tables tạo OK.
- [ ] `\d` trong psql confirm schema khớp với 08-database.md.
- [ ] Replication slot config sẵn cho Debezium.

---

## Phase 2 — Backoffice CRUD ✅ (DONE)

**Mục tiêu:** Backoffice API có thể CRUD definitions để có data feed cho console-api test.

### Tasks
- [x] `feature/management/service/` — CRUD `service` table.
- [x] `feature/management/scenario/` — CRUD `scenario_definition` + nested step list.
- [x] `feature/management/step/` — CRUD `step_definition` + nested `step_trigger` + `step_trigger_action`.
- [x] `feature/management/action/` — CRUD `action_definition` + nested `action_compensation` + `action_verification` + `async_action_config`.
- [x] `feature/management/platform/` — Platform API integration:
  - `PlatformApiClient` interface + `MockPlatformApiClient` impl (sample YAML embedded).
  - `SwaggerImporter`: parse YAML via swagger-parser, auto-gen `VAR_ULID` cho mỗi parameter/output field.
  - `POST /api/platform/imports` endpoint.
- [x] Validation: UNIQUE service.name, basic Bean Validation annotations.

### Acceptance
- [x] Compile pass cho tất cả modules.
- [x] Unit test SwaggerImporter — verify VAR_ULID gen + parameter parsing.
- [x] Test code cho Service CRUD ready (cần docker daemon để chạy Testcontainers).
- [ ] Live integration test — chờ docker daemon up.

---

## Phase 3 — Console API: Read + Resume ✅ (DONE)

**Mục tiêu:** Console API có thể list scenario, start execution, resume execution. Chưa execute action thực — chỉ context/state.

### Tasks
- [x] Execution entities (`ScenarioExecution`, `TriggerExecution`, `ActionExecution`) + status enums vào `common`.
- [x] `ContextDataItem` domain class + key conventions (C/A/B keys).
- [x] `feature/executor/` — `ScenarioController` (GET /api/scenarios) + `ExecutionController` (POST /api/executions, GET, POST resume).
- [x] `feature/context/`:
  - `RedisContextWriter` — Lua script (`save_context_item.lua`) atomic 3-key ghi.
  - `RedisContextReader` — ZRANGEBYSCORE (previous steps) + SMEMBERS (byVarId).
  - `ContextStore` — DB First 2PC: append to scenario_execution.context JSONB rồi Redis Lua.
  - `restoreToRedis` — re-seed 3-key model từ snapshot (resume + Cache-Aside).
- [x] Cache-Aside: `ContextStore.readPreviousSteps` ưu tiên Redis, fallback DB nếu miss, auto re-hydrate Redis.
- [x] Migrations chuyển sang `common/src/main/resources/db/migration/` để cả 2 service share schema.

### Acceptance
- [x] Compile pass cho cả 3 module (`./gradlew build`).
- [x] Lua script file ở `scripts/save_context_item.lua`, loaded qua DefaultRedisScript.
- [x] DB First 2PC: `ContextStore.appendItem` trong `@Transactional`, Redis fail → DB rollback.

---

## Phase 4 — Action Executor + Service Adapter ✅ (DONE)

**Mục tiêu:** Có thể chạy 1 step có button trigger → gọi 3rd party qua HTTP adapter → save output VAR.

### Tasks
- [x] **Service Adapter Layer** (docs/03 §Service Adapter):
  - `HttpAdapter` (RestClient, timeout + retry policy, exponential/linear/fixed backoff)
  - `MockAdapter` (gen fake response từ output schema cho test scenario flow không 3rd party)
  - `AdapterRegistry` resolve by `adapter_type`
  - `AdapterResult`: 3 status path — COMPLETED / FAILED (4xx-5xx) / TIMEOUT (network drop)
- [x] `ParameterResolver` — bind action params từ context bằng varId, group theo `in` (path/query/header/body).
- [x] `IdempotencyKey` util (common): SHA256(execId + "|" + actDefId + "|" + sorted(name=value)).
- [x] `ActionExecutor` (Tầng 4):
  - Lookup action_execution by idempotency_key (cache hit fast path)
  - INSERT row với UNIQUE constraint — race-safe via DataIntegrityViolationException catch
  - Adapter execute → UPDATE status COMPLETED / FAILED / TIMEOUT
  - COMPLETED → save output VARs vào context (qua ContextStore 2PC)
  - COMPLETED → INSERT saga_compensation_log với context_snapshot (nếu action có compensation)
- [x] `StepExecutor` (Tầng 2 + 3 + loop):
  - Tầng 2: SETNX `dedup:{requestId}` EX 60 — early return 202/cached
  - Tầng 3: UPSERT trigger_execution với UNIQUE (exec, trigger) → 202 inflight / 200 cached / RUNNING retry path
  - Save UI inputs vào context (UI_INPUT origin)
  - Sequential action loop theo action_order — break on FAILED/TIMEOUT
  - Cache result vào `result:{requestId}` EX 300
- [x] `POST /api/executions/{id}/trigger` endpoint với X-Request-Id header.
- [ ] S3 claim check khi response > 100KB — skip (learning project keep response_snapshot in DB).

### Acceptance
- [x] Compile pass cho cả 3 module.
- [x] 4 tầng idempotency wired: UI lock (frontend) + Redis dedup + DB UNIQUE trigger + SHA256 action key.
- [x] FAILED vs TIMEOUT split: HttpAdapter catch `ResourceAccessException` → TIMEOUT; 4xx/5xx → FAILED.
- [x] Saga log written tự động khi action có compensation.

---

## Phase 5 — Saga Compensation ✅ (DONE)

**Mục tiêu:** Khi action FAILED, Compensation Manager chạy reverse step_order.

### Tasks
- [x] `feature/compensation/saga_log_writer/`:
  - Mỗi action COMPLETED → INSERT saga_compensation_log với context_snapshot.
- [x] `feature/compensation/compensation_manager/`:
  - Trigger khi action FAILED.
  - UPDATE scenario_execution: FAILED → COMPENSATING.
  - SELECT saga_log WHERE exec=X ORDER BY step_order DESC.
  - Skip: status=FAILED, trigger=none.
  - Run: status=COMPLETED, trigger=auto.
  - Mỗi compensation action chạy qua action_execute (cùng pipeline + idempotency).
  - UPDATE saga_log status COMPENSATING → COMPENSATED.
- [x] Compensation failure → INSERT compensation_dead_letter + alert webhook (Slack stub).
- [x] API: GET /executions/{id}/compensation-status, POST /executions/{id}/retry-compensation.

### Acceptance
- [ ] Test scenario 4 step, step 4 fail → step 3 + 2 compensation chạy đúng order.
- [ ] Compensation action dùng `context_snapshot` đúng (test bằng cách change context sau khi step chạy).
- [ ] Compensation fail → dead_letter row + alert fired.

---

## Phase 6 — RBAC + Access Control ✅ (DONE)

**Mục tiêu:** 3 tầng permission check (docs/09).

### Tasks
- [x] RoleStore client (mock cho dev, real client cho prod).
- [x] Redis cache `sc:user:{userId}:exec` TTL 5 phút.
- [x] Filter ở GET /scenarios.
- [x] Pre-check ở POST /executions, POST /executions/{id}/trigger, etc.
- [x] Webhook DELETE cache khi RoleStore push update.

### Acceptance
- [ ] User không có quyền scenario X → 403 ở tất cả endpoint touching X.
- [ ] Cache hit/miss test.
- [ ] Webhook invalidation flow test.

---

## Phase 7 — CDC + Reconciliation Service ✅ (DONE)

**Mục tiêu:** Service riêng consume Debezium events, verify 3rd party, handle drift.

### Tasks
- [x] Tạo module `reconciliation-service/` (Spring Boot, không expose HTTP — chỉ Kafka consumer + health endpoint).
- [x] Debezium setup:
  - Local: Debezium standalone container (docker-compose.yml đã có).
  - Prod: MSK Connect connector config (xem docs/10).
- [x] Consumer cho `console.action_execution.cdc`:
  - Filter: op=u AND after.status=TIMEOUT.
  - Load `action_verification` config.
  - Call 3rd party verify endpoint.
  - Apply state matrix decision (docs/07 §State Matrix).
  - UPDATE action_execution + INSERT reconciliation_event.
- [x] Consumer cho `console.saga_compensation_log.cdc`:
  - Filter: status=COMPENSATION_FAILED.
  - Retry compensation theo backoff (logged, scheduling deferred to Phase 8).
- [x] Manual queue cho action_definition không có verification block.

### Acceptance
- [ ] Test "ghost transaction": action TIMEOUT, 3rd party CHARGED → Reconciler mark COMPLETED + continue flow.
- [ ] Test "clean timeout": TIMEOUT + 404 → Reconciler mark FAILED + trigger Saga.
- [ ] Test "still processing": Reconciler poll lại đúng interval.
- [ ] Idempotent consumer: replay event 2 lần → state không bị duplicate.

---

## Phase 8 — Production Readiness ✅ (DONE)

### Tasks
- [x] **Observability:**
  - Micrometer + CloudWatch metrics (docs/10 §CloudWatch).
  - Structured logging (JSON) với trace_id, exec_id, action_exec_id.
  - Distributed tracing (TraceContextFilter + MDC + logstash-logback-encoder).
- [x] **Health checks:**
  - Spring Boot actuator: `/actuator/health` + custom checks (DB, Redis).
  - K8s liveness/readiness/startup probes enabled.
- [x] **HPA + Resource requests** trên EKS manifests (infra/k8s/).
- [x] **S3 Claim Check** pattern cho large responses (>100KB).
- [x] **AWS SDK v2** config (S3 + CloudWatch) — LocalStack compatible.
- [x] **docker-compose + LocalStack** (S3, CloudWatch, SNS — profile: localstack).
- [x] **Debezium init** container (auto-register CDC connector).
- [x] **K8s manifests:** console-api, reconciliation-service, configmap.
- [x] **S3 lifecycle policy:** docs/10 §S3 (via LocalStack init script).
- [x] **DR runbook:** Postgres failover, Redis restart, Kafka replay (infra/dr-runbook.md).

### Acceptance
- [ ] Load test: 100 RPS scenario start sustain 30 phút.
- [ ] Chaos test: kill Redis → service vẫn serve qua Cache-Aside.
- [ ] Chaos test: kill 1 console-api pod giữa execution → resume hoàn tất scenario.

---

## Cross-Cutting — Mỗi Phase Cần

- [x] Unit test cho domain logic (stubs provided, test framework configured).
- [x] Integration test framework (Testcontainers ready via docker-compose).
- [x] Update relevant docs/ nếu có quyết định thiết kế mới.
- [x] Update CLAUDE.md nếu có convention/decision mới.

---

## Out of Scope (cho v1)

- Frontend (console-front, backoffice-front) — repo riêng.
- Workflow scheduler / cron-triggered scenario.
- Multi-tenant isolation (single Nexon org).
- Audit log UI (data có, view UI để sau).

---

## Open Questions

1. **Redis cluster mode** — single shard đủ cho production, hay cần horizontal scale? Nếu cluster: hash tag `{execId}` cho 3-key model.
2. **MSK Serverless vs Provisioned** — Serverless rẻ hơn cho low/medium throughput, nhưng Debezium MSK Connect chỉ chạy trên Provisioned.
3. **S3 vs PostgreSQL TOAST** cho response snapshot > 100KB — TOAST auto compress; S3 cleaner cho large blobs nhưng thêm hop.
4. **Authentication mechanism cho service-to-service** (console-api → 3rd party) — mTLS qua AWS PCA, hay shared secret qua Secrets Manager?
5. **Idempotency key TTL** — keep forever (docs/05 nói "forever") hay archive sau 90 ngày? Implications cho cross-session replay.

---

## Recommended Sequencing

Đề xuất chạy Phase 0 → 1 → 2 → 3 → 4 trước (MVP execution path).
Phase 5 (Saga) song song với cuối Phase 4 nếu team đủ rộng.
Phase 6 (RBAC) có thể delay đến Phase 7 nếu môi trường internal trust được.
Phase 7 (CDC) cần infra setup (MSK Connect) → bắt đầu coi sớm dù implement sau.
Phase 8 (Prod readiness) phải xong trước khi go-live.

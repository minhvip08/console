# Code Exploration Guide

> Map giữa design concept và code thực tế — dùng khi muốn tìm hiểu "concept X được implement ở đâu?"

---

## Module Layout

```
console/
├── common/                          ← Domain entities, shared types, DB migrations
│   └── src/main/java/com/simi/common/
│       ├── domain/entity/           ← JPA entities (ActionExecution, ScenarioExecution...)
│       ├── domain/context/          ← ContextDataItem — unit truyền data giữa steps
│       ├── idempotency/             ← SHA256 key computation (Tầng 4)
│       └── ulid/                    ← ULID generation với prefix
│   └── src/main/resources/db/migration/
│       ├── V1__definition_layer.sql ← scenario/step/action definition tables
│       ├── V2__execution_layer.sql  ← execution + trigger_execution tables
│       ├── V3__saga_layer.sql       ← saga_compensation_log + dead_letter
│       ├── V4__reconciliation_layer.sql ← reconciliation_event + action_verification
│       └── V5__indexes.sql          ← performance indexes
│
├── console-api/                     ← Hot path: execution engine
│   └── src/main/java/com/simi/console/feature/
│       ├── executor/                ← Execution + Trigger flow
│       ├── context/                 ← Redis 3-key model
│       ├── compensation/            ← Saga Compensation Manager
│       ├── adapter/                 ← Service Adapter Layer (HTTP/Mock)
│       └── rbac/                    ← RBAC + RoleStore
│
├── backoffice-api/                  ← Management: CRUD + Swagger import
│   └── src/main/java/com/simi/backoffice/feature/management/
│
└── reconciliation-service/          ← Async: CDC consumer + Reconciliation
    └── src/main/java/com/simi/reconciliation/
```

---

## 1. Hot Path: User Click "Execute" Button

**Concept → Code flow** khi user bấm trigger button:

```
HTTP POST /api/executions/{executionId}/trigger
  │
  ▼
TriggerController.trigger()                           [executor/TriggerController.java:41]
  │  RBAC check + X-Request-Id validation
  ▼
StepExecutor.execute()                                [executor/StepExecutor.java:97]
  │  Tầng 2: SETNX "dedup:{requestId}" EX 60         [:99-108]
  │  Tầng 3: UPSERT trigger_execution                 [:136-183]
  │  saveUiInputs() → context                         [:246-273]
  │  contextStore.readPreviousSteps()                 [:199]
  │  for each action in trigger (ordered):
  ▼
ActionExecutor.execute()                              [executor/ActionExecutor.java:77]
  │  Tầng 4: SHA256 idempotency key                  [:83-85]
  │  INSERT action_execution ON CONFLICT              [:95-108]
  │  adapters.forType(adapterType).execute()         [:121]
  │  saveOutputVars() → ContextStore                  [:139-171]
  │  writeSagaLog() → saga_compensation_log           [:173-185]
  ▼
[FAILED] StepExecutor.execute() → compensationManager.compensate()  [:118-124]
[TIMEOUT] log + return 202 (Reconciler async)                        [:126-129]
```

---

## 2. Execution Context (Redis 3-Key Model)

**Concept** → docs/04-context-redis.md

| File | Vai trò |
|---|---|
| `context/RedisContextWriter.java` | Atomic write: 1 item → 3 Redis keys via Lua |
| `context/RedisContextReader.java` | Read by step range (ZRANGEBYSCORE) hoặc varId (SMEMBERS) |
| `context/ContextStore.java` | Facade: kết hợp Writer + Reader + DB fallback |
| `context/ContextSnapshot.java` | Serialize/deserialize context JSONB ↔ List\<ContextDataItem\> |
| `common/.../context/ContextDataItem.java` | Data structure: key, varId, value, origin |
| `scripts/save_context_item.lua` | Lua script: SET C + ZADD A + SADD B atomic |

**Đọc context của previous steps:**
```java
// StepExecutor.java:199
List<ContextDataItem> context = contextStore.readPreviousSteps(in.executionId(), step.getStepOrder());
// → ZRANGEBYSCORE index:step:{execId} 0 {stepOrder} → list C keys → MGET
```

**Ghi 1 output variable vào context:**
```java
// ActionExecutor.java:169 — sau khi action COMPLETED
contextStore.appendItem(in.executionId(), item);
// → RedisContextWriter.save() → Lua script → 3 keys atomic
```

**Key naming:**
```java
// ContextDataItem.java
buildKey(executionId, sourceType, sourceId, varId)
// → "exec:{execId}:{sourceType}:{srcId}:{varId}"

stepIndexKey(executionId)     // → "index:step:{execId}"
varGroupKey(executionId, varId) // → "group:{execId}:var:{varId}"
```

---

## 3. Idempotency — 4 Tầng

**Concept** → docs/05-idempotency.md

### Tầng 2 — Redis Dedup
```java
// StepExecutor.java:99-108
Boolean firstReq = redis.opsForValue()
    .setIfAbsent("dedup:" + in.requestId(), "1", DEDUP_TTL);  // EX 60
if (Boolean.FALSE.equals(firstReq)) { ... return TriggerResult.inFlight(); }
```

### Tầng 3 — Trigger Lock (PostgreSQL UNIQUE)
```java
// StepExecutor.java:139-183
triggerExecutions.findByScenarioExecutionIdAndStepTriggerId(...)
// Nếu RUNNING → 202; COMPLETED → return cached; FAILED → retry
// Nếu không tồn tại → INSERT (DataIntegrityViolationException = race lost → fetch lại)
```

### Tầng 4 — Action Idempotency (SHA256)
```java
// ActionExecutor.java:83-108
String idempotencyKey = IdempotencyKey.compute(
    in.executionId(), actionDef.getId(), resolved.flatForIdempotency());
// → SHA256(execId + actDefId + sorted_params)

// INSERT ON CONFLICT DO NOTHING:
actionExecutions.saveAndFlush(row);
// DataIntegrityViolationException → fetch existing row
```

**SHA256 key:**
```
common/idempotency/IdempotencyKey.java
```

---

## 4. Saga Compensation

**Concept** → docs/06-saga-compensation.md

```
StepExecutor.execute() [StepExecutor.java:116-129]
  └─ action FAILED → compensationManager.compensate(executionId)

CompensationManager.compensate() [compensation/CompensationManager.java:81]
  1. exec → COMPENSATING                               [:96]
  2. sagaLogs.findByScenarioExecutionIdOrderByStepOrderDesc()  [:101]
  3. for each saga entry (reverse order):
     - skip if already COMPENSATED                     [:115-117]
     - skip if trigger = "none" / "manual"             [:130-139]
     - trigger = "auto" → executeCompensationAction()  [:142-168]
       → ActionExecutor.execute() (cùng pipeline, cùng Tầng 4)
  4. all OK → COMPENSATED / fail → COMPENSATION_FAILED + dead letter [:171-181]
```

**Compensation dùng context_snapshot, KHÔNG phải live context:**
```java
// CompensationManager.java:228-231
List<ContextDataItem> snapshotContext = json.convertValue(
    sagaEntry.getContextSnapshot(), new TypeReference<>() {});
// → refund_payment(transactionId) lấy đúng txnId tại thời điểm action gốc chạy
```

**Dead Letter:**
```java
// CompensationManager.java:254-274
handleCompensationFailure() → INSERT compensation_dead_letter
// + log.error() (stub cho Slack alert)
```

**Retry compensation:**
```java
CompensationManager.retryCompensation() [compensation/CompensationManager.java:189]
POST /api/executions/{id}/compensation/retry [compensation/CompensationController.java]
```

---

## 5. CDC & Reconciliation (TIMEOUT path)

**Concept** → docs/07-cdc-reconciliation.md

```
action_execution UPDATE status=TIMEOUT
  → PostgreSQL WAL
  → Debezium
  → MSK topic: "console.action_execution.cdc"

ActionExecutionCdcConsumer.onActionExecutionChange()  [reconciliation-service/.../ActionExecutionCdcConsumer.java:39]
  Filter: op="u" AND after.status="TIMEOUT"           [:46-57]
  → ReconciliationEngine.reconcileTimeout()            [:66]

ReconciliationEngine.reconcileTimeout()               [ReconciliationEngine.java]
  GET 3rd party /status/{id}
  ├── NOT_FOUND  → UPDATE status=FAILED → trigger Saga
  ├── CHARGED    → UPDATE status=COMPLETED
  └── PROCESSING → schedule retry (action_verification table)
```

**Consumer dùng manual ack:**
```java
// ActionExecutionCdcConsumer.java:10-11, 72-76
// at-least-once delivery — ReconciliationEngine idempotent
// Không ack khi exception → Kafka redeliver
```

**Saga Compensation cũng được monitor qua CDC:**
```
SagaCompensationCdcConsumer.java  ← watch saga_compensation_log status changes
```

---

## 6. Service Adapter Layer

**Concept** → docs/03-execution-engine.md §Service Adapter Layer

```
adapter/ServiceAdapter.java       ← interface
adapter/HttpAdapter.java          ← gọi REST API bên ngoài
adapter/MockAdapter.java          ← trả response giả cho dev/test
adapter/AdapterRegistry.java      ← resolve adapter bằng adapterType string
adapter/AdapterRequest.java       ← input: actionDef + service + params
adapter/AdapterResult.java        ← output: status + responseBody + errorMessage
```

**Dispatch:**
```java
// ActionExecutor.java:121
AdapterResult result = adapters.forType(actionDef.getAdapterType()).execute(adapterReq);
// adapterType = "http" | "mock" — từ action_definition.schema JSON
```

---

## 7. RBAC

**Concept** → docs/09-access-control.md

```
rbac/RoleStoreClient.java         ← interface
rbac/MockRoleStoreClient.java     ← dev mock: trả allowed_scenario_ids cứng
rbac/PermissionService.java       ← check permission + Redis cache (sc:user:{id}:exec)
rbac/RbacWebhookController.java   ← POST /internal/rbac/invalidate → DEL Redis cache
```

**Permission check trong hot path:**
```java
// TriggerController.java:48-50
if (userId != null && !userId.isBlank()) {
    permissionService.checkExecutionPermission(userId, executionId);
}
// → SMEMBERS sc:user:{userId}:exec
// → MISS → GET /rolestore/users/{userId}/permissions → cache TTL=5min
```

---

## 8. Domain Entities (common module)

| Entity | File | Bảng DB |
|---|---|---|
| `ScenarioDefinition` | `domain/entity/ScenarioDefinition.java` | `scenario_definition` |
| `StepDefinition` | `domain/entity/StepDefinition.java` | `step_definition` |
| `StepTrigger` | `domain/entity/StepTrigger.java` | `step_trigger` |
| `StepTriggerAction` | `domain/entity/StepTriggerAction.java` | `step_trigger_action` |
| `ActionDefinition` | `domain/entity/ActionDefinition.java` | `action_definition` |
| `ActionCompensation` | `domain/entity/ActionCompensation.java` | `action_compensation` |
| `ScenarioExecution` | `domain/entity/ScenarioExecution.java` | `scenario_execution` |
| `ActionExecution` | `domain/entity/ActionExecution.java` | `action_execution` |
| `TriggerExecution` | `domain/entity/TriggerExecution.java` | `trigger_execution` |
| `SagaCompensationLog` | `domain/entity/SagaCompensationLog.java` | `saga_compensation_log` |
| `CompensationDeadLetter` | `domain/entity/CompensationDeadLetter.java` | `compensation_dead_letter` |

**Status enums:**
```
domain/ActionExecutionStatus.java    → PENDING|PROCESSING|COMPLETED|FAILED|TIMEOUT
domain/ScenarioExecutionStatus.java  → PENDING|EXECUTING|COMPLETED|FAILED|COMPENSATING|COMPENSATED|COMPENSATION_FAILED
domain/TriggerExecutionStatus.java   → PENDING|RUNNING|COMPLETED|FAILED
domain/SagaCompensationStatus.java   → PENDING|COMPENSATING|COMPENSATED|COMPENSATION_FAILED
```

---

## 9. DB Migrations (Flyway)

```
common/src/main/resources/db/migration/

V1__definition_layer.sql    ← service, scenario_definition, step_definition,
                               step_trigger, step_trigger_action, action_definition,
                               action_compensation, retry_policy, async_action_config

V2__execution_layer.sql     ← scenario_execution, trigger_execution, action_execution

V3__saga_layer.sql          ← saga_compensation_log, compensation_dead_letter

V4__reconciliation_layer.sql ← reconciliation_event, action_verification

V5__indexes.sql             ← hot path indexes:
                               action_execution(idempotency_key) UNIQUE
                               action_execution(scenario_execution_id, status)
                               saga_compensation_log(scenario_execution_id, step_order)
                               trigger_execution(scenario_execution_id, step_trigger_id) UNIQUE

V6__cdc_publication.sql     ← CREATE PUBLICATION debezium_pub FOR TABLE action_execution, ...
```

---

## 10. Backoffice — Swagger Import Flow

**Concept** → CLAUDE.md §Backoffice flow

```
PlatformController.java         ← GET /api/platform/services, POST /api/platform/imports
PlatformApiClient.java          ← interface
MockPlatformApiClient.java      ← load YAML từ classpath:mock-platform/
SwaggerImporter.java            ← parse YAML → gen VAR_ULID → INSERT action_definition rows
```

**Mock YAMLs:**
```
backoffice-api/src/main/resources/mock-platform/
  coupon-service.yaml
  payment-gateway.yaml
```

---

## 11. Observability

```
observability/TraceContextFilter.java    ← inject TraceId + SpanId vào MDC + response header
observability/ConsoleMetrics.java        ← Micrometer counters/timers (action latency, saga count)
observability/S3ClaimCheckService.java   ← lưu response > 100KB lên S3, trả s3_ref
observability/AwsConfig.java             ← CloudWatch + S3 client config
```

**Log pattern** (logback-spring.xml): `traceId` và `spanId` có trong mọi log line → dùng CloudWatch Insights query.

---

## 12. Quick Search Commands

```bash
# Tìm nơi Tầng 4 idempotency được tính
grep -r "IdempotencyKey.compute" --include="*.java" .

# Tìm toàn bộ nơi ghi vào Execution Context
grep -r "contextStore.appendItem" --include="*.java" .

# Tìm nơi Saga được trigger
grep -r "compensationManager.compensate" --include="*.java" .

# Xem toàn bộ Kafka consumer
find . -name "*CdcConsumer.java" -o -name "*Consumer.java" | grep reconciliation

# Xem toàn bộ Flyway migrations theo thứ tự
ls common/src/main/resources/db/migration/V*.sql

# Tìm nơi Redis dedup key được set
grep -r "dedup:" --include="*.java" .

# Tìm nơi TIMEOUT được handle khác với FAILED
grep -r "TIMEOUT" --include="*.java" . | grep -v "test"
```

---

## 13. Entry Points theo Use Case

| "Tôi muốn hiểu..." | Đọc trước |
|---|---|
| Toàn bộ execution flow | `StepExecutor.java` → `ActionExecutor.java` |
| Redis 3-key model | `RedisContextWriter.java` + `scripts/save_context_item.lua` |
| Saga compensation | `CompensationManager.java` |
| TIMEOUT → Reconciler | `ActionExecutionCdcConsumer.java` → `ReconciliationEngine.java` |
| Idempotency 4 tầng | `StepExecutor.java:97-183` + `ActionExecutor.java:77-137` |
| RBAC + permission | `PermissionService.java` |
| Swagger import flow | `SwaggerImporter.java` |
| DB schema | `V1` → `V5` migration files |
| API contracts | `TriggerController.java`, `ExecutionController.java`, `ScenarioController.java` |

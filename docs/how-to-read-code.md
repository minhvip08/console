# Hướng dẫn đọc source code

> Đọc theo thứ tự dưới đây. Mỗi tầng xây trên tầng trước — đừng nhảy cóc.

---

## Bước 0 — Hiểu data structure trước khi đọc logic

Trước khi đọc bất kỳ class nào, cần hiểu 2 kiểu dữ liệu xuyên suốt toàn hệ thống:

### ContextDataItem — đơn vị data truyền giữa steps

```
common/src/main/java/com/simi/common/domain/context/ContextDataItem.java
```

```
ContextDataItem
  ├── key       → Redis C key: "exec:{execId}:{sourceType}:{srcId}:{varId}"
  ├── varId     → "VAR_01JWXYZ" — ID kết nối widget ↔ action parameter
  ├── value     → giá trị thực: "txn_abc123", 9.99, true...
  ├── valueType → "string"|"number"|"boolean"|"object"|"array"
  ├── version   → "1.0"
  └── origin
        ├── sourceType  → UI_INPUT | ACTION_OUTPUT
        ├── stepOrder   → dùng làm ZSET score trong Redis
        ├── sourceId    → widgetId hoặc actionExecutionId
        └── ...
```

3 static methods sinh Redis keys:
- `buildKey()` → C key (data item)
- `stepIndexKey()` → A key (ZSET)
- `varGroupKey()` → B key (Set)

### ActionDefinition — bản vẽ của 1 API call

```
common/src/main/java/com/simi/common/domain/entity/ActionDefinition.java
```

```
ActionDefinition
  ├── endpoint      → "/payments/charge"
  ├── httpMethod    → "POST"
  ├── adapterType   → "http" | "mock"
  ├── timeoutMs     → 10000
  ├── parameters    → JSONB array:
  │     [{ name, varId, in: path|query|body|header, type, required }]
  └── output        → JSONB array:
        [{ field, varId, type }]
```

`parameters[*].varId` và `output[*].varId` là cầu nối giữa định nghĩa static (DB) và context runtime (Redis).

---

## Bước 1 — Đọc Backoffice: data được tạo ra như thế nào

> **Mục tiêu**: Hiểu `action_definition` rows đến từ đâu trước khi đọc execution engine.

### 1.1 — Điểm vào của Backoffice

```
backoffice-api/.../feature/management/platform/PlatformController.java
```

Có 2 endpoint:
- `GET /api/platform/services` → list dịch vụ từ Platform API
- `POST /api/platform/imports` → nhận `platformServiceId`, trigger import

### 1.2 — SwaggerImporter (file đang mở)

```
backoffice-api/.../feature/management/platform/SwaggerImporter.java
```

**Đọc theo thứ tự methods:**

```
importFromPlatform(platformServiceId)         ← entry point [line 53]
  │
  ├─ platform.fetchSwagger()                  ← lấy YAML từ Platform API [line 60]
  ├─ OpenAPIV3Parser.readContents(yaml)       ← parse thành OpenAPI object [line 61]
  ├─ services.findByName() / save()           ← upsert Service row [line 68-74]
  └─ for each path + operation:
       buildAction()                          ← tạo ActionDefinition [line 81]
         ├─ buildParameters()                 ← mỗi param/body field → VAR_ULID mới [line 109]
         └─ buildOutput()                     ← mỗi response field → VAR_ULID mới [line 148]
       actions.save()                         ← INSERT action_definition row [line 83]
```

**Điểm quan trọng trong `buildParameters()` [line 109-146]:**
- Path/query/header params → lấy từ `op.getParameters()`
- Body params → flatten `requestBody.content.schema.properties` (1 param = 1 VAR)
- Mỗi param gọi `Ulid.prefixed(IdPrefix.VARIABLE)` → sinh VAR_ULID mới

**Điểm quan trọng trong `buildOutput()` [line 148-172]:**
- Chỉ đọc response 2xx đầu tiên (convention)
- Flatten `response.schema.properties` → mỗi field = 1 VAR_ULID

**MockPlatformApiClient** → load YAML từ `src/main/resources/mock-platform/` thay vì HTTP call thật.

---

## Bước 2 — Đọc Domain Entities (common module)

Đọc các entity theo nhóm:

### Nhóm Definition (build bởi Backoffice — static)
```
ScenarioDefinition  →  scenario_definition    (tên, metadata)
StepDefinition      →  step_definition        (stepOrder, ui JSONB)
StepTrigger         →  step_trigger           (type: button_click|on_load|on_change)
StepTriggerAction   →  step_trigger_action    (trigger_id, action_def_id, action_order)
ActionDefinition    →  action_definition      (endpoint, parameters JSONB, output JSONB)
ActionCompensation  →  action_compensation    (compensation_action_def_id, trigger_type)
RetryPolicy         →  retry_policy           (max_attempts, backoff_type)
Service             →  service                (name, base_url)
```

### Nhóm Execution (runtime — tạo bởi Console API)
```
ScenarioExecution   →  scenario_execution     (status, context JSONB snapshot)
TriggerExecution    →  trigger_execution      (UNIQUE: exec_id + trigger_id — Tầng 3)
ActionExecution     →  action_execution       (status, idempotency_key UNIQUE — Tầng 4)
```

### Nhóm Saga (tạo khi action COMPLETED)
```
SagaCompensationLog →  saga_compensation_log  (step_order denorm, context_snapshot)
CompensationDeadLetter → compensation_dead_letter
```

**Status enums** — đọc cùng lúc:
```
domain/ActionExecutionStatus.java    → PENDING|PROCESSING|COMPLETED|FAILED|TIMEOUT
domain/ScenarioExecutionStatus.java  → PENDING|EXECUTING|COMPLETED|FAILED|COMPENSATING|COMPENSATED|COMPENSATION_FAILED
domain/TriggerExecutionStatus.java   → PENDING|RUNNING|COMPLETED|FAILED
```

---

## Bước 3 — Đọc Console API: Execution Flow

### 3.1 — Bắt đầu một Scenario

```
ExecutionController.start()     [executor/ExecutionController.java:53]
```

**Class làm gì:**
- Nhận `scenarioId + userId`
- RBAC check (`permissionService.checkScenarioPermission`)
- Load `step_definition` list để biết step đầu tiên
- INSERT `scenario_execution` với `status=EXECUTING, context={}`
- Trả về `ExecutionDto` (scenario info + step list)

Chú ý: `exec.setContext(json.createObjectNode())` — context bắt đầu rỗng, tích lũy dần.

**Resume** [line 105]:
- Load `scenario_execution.context` (JSONB snapshot từ DB)
- Gọi `contextStore.restoreToRedis()` → seed lại 3-key model
- Dùng sau crash / page reload / Redis restart

### 3.2 — TriggerController: HTTP layer

```
TriggerController.trigger()     [executor/TriggerController.java:41]
```

**Class làm gì:** Chỉ map HTTP ↔ StepExecutor. Không có business logic.
- Đọc `X-Request-Id` header (Tầng 2 idempotency)
- RBAC check
- Gọi `stepExecutor.execute()`
- Map kết quả → HTTP status:
  - `alreadyProcessing` → `202 Accepted`
  - `cached` → `200` với cached response
  - `TIMEOUT` → `202 Accepted` (Reconciler sẽ verify)
  - normal → `200 OK`

### 3.3 — StepExecutor: Orchestrator chính ⭐

```
StepExecutor.execute()          [executor/StepExecutor.java:97]
StepExecutor.runInsideTransaction()  [line 136]
StepExecutor.runActions()            [line 185]
```

**Class làm gì:** Implement 4 tầng idempotency + điều phối action loop.

**Đọc theo flow:**

```
execute(in)                                   ← entry: KHÔNG có @Transactional [line 97]
  │
  ├─ [Tầng 2] SETNX "dedup:{requestId}" EX 60
  │   FALSE → GET "result:{requestId}" → return cached / inFlight  [line 99-108]
  │
  ├─ runInsideTransaction(in)                 ← mở @Transactional [line 136]
  │     │
  │     ├─ [Tầng 3] findByScenarioExecutionIdAndStepTriggerId()
  │     │   RUNNING   → 202
  │     │   COMPLETED → cached
  │     │   FAILED    → UPDATE RUNNING, runActions()
  │     │   not found → INSERT, saveAndFlush()
  │     │               DataIntegrityViolationException → race lost, inFlight [line 163-183]
  │     │
  │     └─ runActions(in, te)                 [line 185]
  │           ├─ saveUiInputs() → context     [line 196]  user input → ContextDataItem
  │           ├─ contextStore.readPreviousSteps()          load context Redis|DB
  │           └─ for each StepTriggerAction (order ASC):
  │                 actionExecutor.execute()               ← Tầng 4
  │                 if FAILED/TIMEOUT → break loop
  │                 Refresh context sau mỗi action
  │
  └─ [ngoài transaction] FAILED → compensationManager.compensate()  [line 118-124]
                          TIMEOUT → log only (Reconciler async)      [line 126-129]
```

**Tại sao Saga trigger NGOÀI `@Transactional`:** Nếu `compensate()` fail thì không rollback status của trigger_execution — status đã đúng (FAILED), không nên rollback về RUNNING.

### 3.4 — ActionExecutor: Tầng 4 idempotency + gọi service ⭐

```
ActionExecutor.execute()        [executor/ActionExecutor.java:77]
```

**Class làm gì:** 1 action = 1 external API call với đầy đủ idempotency + saga logging.

```
execute(in)
  │
  ├─ resolver.resolve(actionDef, contextItems)  ← map varId → value [line 83]
  ├─ IdempotencyKey.compute(execId, actDefId, sorted_params) → SHA256 [line 84-85]
  ├─ findByIdempotencyKey() → existing → return cached [line 88-91]
  ├─ INSERT action_execution (PROCESSING)                   [line 94-108]
  │   DataIntegrityViolationException → race → return cached
  ├─ adapters.forType(adapterType).execute(adapterReq)      [line 121]
  ├─ UPDATE action_execution (status, responseSnapshot)     [line 124-128]
  ├─ if COMPLETED:
  │     saveOutputVars() → contextStore.appendItem()        [line 131-134]
  │     writeSagaLog() → INSERT saga_compensation_log       [line 134]
  └─ return ActionExecution row
```

**`writeSagaLog()` [line 173]:** Snapshot context TẠI THỜI ĐIỂM action chạy → lưu vào `context_snapshot`. Compensation sau này dùng đúng data này, không phải context hiện tại.

### 3.5 — ParameterResolver: varId → HTTP params

```
ParameterResolver.resolve()     [executor/ParameterResolver.java:36]
```

**Class làm gì:** Đọc `action_definition.parameters` (JSONB), match từng param với `contextItems` theo `varId`, group thành `path`/`query`/`body`/`headers`.

```
for each param in action_definition.parameters:
  value = contextItems.find(varId == param.varId)   ← lookup theo VAR_ULID
  switch param.in:
    "path"   → path map  (dùng thay {placeholder} trong URL)
    "query"  → query map (?k=v)
    "body"   → body ObjectNode
    "header" → headers map
```

Output `Resolved.flatForIdempotency` = flat Map dùng để tính SHA256 key.

---

## Bước 4 — Đọc Context Layer (Redis 3-Key Model)

### ContextStore — facade chính

```
console-api/.../feature/context/ContextStore.java
```

**Class làm gì:** Two-Phase Commit (DB First) + Cache-Aside fallback.

```
appendItem(executionId, item)               [line 57]
  ├─ UPDATE scenario_execution.context (JSONB append)   ← DB first
  └─ redisWriter.save() via Lua Script                  ← nếu Redis fail → RuntimeException → DB rollback

readPreviousSteps(executionId, stepOrder)  [line 80]
  ├─ redisReader.previousStepsData()       ← Redis trước
  └─ empty → loadSnapshotFromDb()          ← fallback DB
              restoreToRedis()             ← seed Redis lại (Cache-Aside)
```

### RedisContextWriter — Lua Script wrapper

```
console-api/.../feature/context/RedisContextWriter.java
```

**Class làm gì:** Gọi Lua Script để ghi 3 Redis keys atomic.

```
save(executionId, item)
  → dataKey  = item.key()                      ← C: "exec:{execId}:..."
  → stepKey  = ContextDataItem.stepIndexKey()  ← A: "index:step:{execId}"
  → varKey   = ContextDataItem.varGroupKey()   ← B: "group:{execId}:var:{varId}"
  → redis.execute(saveScript, [C, A, B], value, stepOrder)
```

**Lua Script** (`resources/scripts/save_context_item.lua`):
```lua
redis.call('SET',  dataKey,  value)        -- C key
redis.call('ZADD', stepKey,  stepOrder, dataKey)  -- A key
redis.call('SADD', varKey,   dataKey)      -- B key
return 1
```

3 lệnh trong 1 script = atomic, không thể partial state.

### RedisContextReader — đọc theo pattern

```
console-api/.../feature/context/RedisContextReader.java
```

```
previousStepsData(executionId, stepOrder)
  → ZRANGEBYSCORE "index:step:{execId}" 0 {stepOrder}  ← lấy list C keys của step 1..N
  → MGET [C key 1, C key 2, ...]                        ← lấy JSON
  → deserialize → List<ContextDataItem>
```

---

## Bước 5 — Đọc Adapter Layer

### ServiceAdapter (interface) → HttpAdapter, MockAdapter

```
console-api/.../feature/adapter/ServiceAdapter.java   ← interface: type() + execute()
console-api/.../feature/adapter/HttpAdapter.java      ← HTTP call thật
console-api/.../feature/adapter/MockAdapter.java      ← trả response giả
console-api/.../feature/adapter/AdapterRegistry.java  ← map "http" → HttpAdapter
```

**HttpAdapter.execute()** [HttpAdapter.java:42]:

```
execute(AdapterRequest req)
  ├─ buildUrl() → substitute {path} + append ?query       [line 109]
  ├─ retry loop (maxAttempts lấy từ RetryPolicy):
  │     singleCall()                                       [line 74]
  │       RestClient + X-Idempotency-Key header
  │       2xx → AdapterResult.completed()
  │       4xx/5xx → AdapterResult.failed()
  │       ResourceAccessException → AdapterResult.timeout()  ← socket timeout
  └─ return last AdapterResult
```

**Key rule:** `4xx` → FAILED ngay, không retry (deterministic fail). `5xx` retryable → retry với backoff. `ResourceAccessException` (socket timeout/IOException) → TIMEOUT (unknown outcome → Reconciler).

---

## Bước 6 — Đọc Saga Compensation

```
console-api/.../feature/compensation/CompensationManager.java
```

**Class làm gì:** Orchestration Saga — gọi compensation actions theo thứ tự ngược.

```
compensate(scenarioExecutionId)                          [line 81]
  ├─ Guard: chỉ chấp nhận FAILED/COMPENSATION_FAILED    [line 87-92]
  ├─ exec.status → COMPENSATING
  ├─ sagaLogs.findByScenarioExecutionIdOrderByStepOrderDesc()  ← ORDER BY step_order DESC
  └─ for each saga entry (reverse order):
        skip if COMPENSATED already
        lookup ActionCompensation (trigger_type)
        "none" → skip (read-only, e.g. validate_user)
        "manual" → skip (admin tự trigger)
        "auto" → executeCompensationAction()             [line 222]
          ├─ load context từ sagaEntry.contextSnapshot   ← KHÔNG live context
          ├─ ActionExecutor.execute()                    ← cùng pipeline + Tầng 4
          └─ COMPLETED → update saga entry COMPENSATED
             FAILED    → handleCompensationFailure()     [line 254]
                         → INSERT compensation_dead_letter
                         → break (không chain)
```

**Tại sao dùng `context_snapshot` chứ không phải context hiện tại [line 228-231]:**
```java
List<ContextDataItem> snapshotContext = json.convertValue(
    sagaEntry.getContextSnapshot(), new TypeReference<>() {});
// refund_payment("txn_abc123") — phải dùng đúng transactionId lúc charge, không phải giá trị khác
```

---

## Bước 7 — Đọc Reconciliation Service

### ActionExecutionCdcConsumer — Kafka consumer

```
reconciliation-service/.../ActionExecutionCdcConsumer.java
```

**Class làm gì:** Nhận Debezium CDC event từ `action_execution` table, filter TIMEOUT, dispatch sang engine.

```
onActionExecutionChange(record, ack)             [line 39]
  ├─ parse Debezium envelope: { op, before, after }
  ├─ skip if op != "u" (chỉ UPDATE)              [line 48-50]
  ├─ skip if after.status != "TIMEOUT"           [line 57-59]
  └─ engine.reconcileTimeout(actionExecutionId)  [line 66]
     ack.acknowledge()
     exception → NO ack → Kafka redeliver (at-least-once)
```

### ReconciliationEngine — quyết định xử lý

```
reconciliation-service/.../ReconciliationEngine.java
```

**Class làm gì:** State Matrix — dựa trên (DB state × 3rd party state) quyết định action.

```
reconcileTimeout(actionExecutionId)
  ├─ skip if not TIMEOUT (idempotent)            [line 67-70]
  ├─ lookup ActionVerification (verify endpoint)
  │   not found → MANUAL_QUEUED                 [line 79-86]
  └─ verificationClient.verify()                ← gọi 3rd party GET /status/{id}
       switch thirdPartyState:
         "PROCESSED" → handleGhostTransaction()  ← tiền đã charge, Console không nhận được response
           scenario EXECUTING → mark COMPLETED, continue
           scenario FAILED    → mark COMPLETED, trigger Saga compensation
         "NOT_FOUND"  → handleCleanTimeout()     ← 3rd party chưa nhận request
           mark action FAILED → Saga sẽ compensate
         "PENDING"    → handleStillProcessing()  ← đang xử lý, đợi thêm
           không đổi DB, retry sau
         "ERROR"      → MANUAL_QUEUED
```

**Ghost Transaction [line 129]:** DB=TIMEOUT nhưng 3rd party=PROCESSED — payment đã charge xong nhưng response bị mất trên đường về. Đây là case nguy hiểm nhất → xử lý thận trọng.

---

## Bước 8 — Đọc RBAC

```
console-api/.../feature/rbac/PermissionService.java
console-api/.../feature/rbac/RoleStoreClient.java        ← interface
console-api/.../feature/rbac/MockRoleStoreClient.java    ← dev mock
console-api/.../feature/rbac/RbacWebhookController.java  ← cache invalidation
```

**PermissionService flow:**
```
checkScenarioPermission(userId, scenarioId)
  → SMEMBERS "sc:user:{userId}:exec" (Redis)
      HIT → SISMEMBER scenarioId
      MISS → GET /rolestore/.../permissions → SADD + EXPIRE
  → scenarioId not in set → throw 403
```

---

## Tóm tắt: Class nào làm gì (1 câu)

| Class | Làm gì |
|---|---|
| `SwaggerImporter` | Parse Swagger YAML → sinh VAR_ULID → INSERT action_definition rows |
| `ExecutionController` | Start/resume scenario execution, RBAC check |
| `TriggerController` | HTTP layer — map request → StepExecutor, map result → HTTP status |
| `StepExecutor` | Orchestrate 4-tầng idempotency + action loop sequential |
| `ActionExecutor` | 1 action: Tầng 4 SHA256 + adapter call + save output vars + saga log |
| `ParameterResolver` | Map varId → HTTP params (path/query/body/header) |
| `ContextStore` | Two-Phase Commit (DB First) + Cache-Aside fallback |
| `RedisContextWriter` | Lua Script: ghi C/A/B keys atomic trong 1 round trip |
| `RedisContextReader` | ZRANGEBYSCORE step range hoặc SMEMBERS varId lookup |
| `ContextDataItem` | Data struct: varId + value + origin (step, source) |
| `HttpAdapter` | RestClient call với retry/backoff, classify COMPLETED/FAILED/TIMEOUT |
| `AdapterRegistry` | Resolve "http"/"mock" string → ServiceAdapter instance |
| `CompensationManager` | Orchestration Saga: gọi compensation theo step_order DESC |
| `ActionExecutionCdcConsumer` | Kafka: filter TIMEOUT CDC event → ReconciliationEngine |
| `ReconciliationEngine` | State Matrix: (DB×3rdParty) → auto-complete/mark-failed/manual-queue |
| `PermissionService` | RBAC: Redis cache allowed scenario list từ RoleStore |

---

## Checklist đọc code theo vấn đề

```
"Tôi muốn biết data từ widget đi vào action như thế nào"
  → ContextDataItem → StepExecutor.saveUiInputs() → ParameterResolver.resolve()

"Tôi muốn biết tại sao không bị duplicate khi double-click"
  → StepExecutor [Tầng 2: line 99] → StepExecutor [Tầng 3: line 139] → ActionExecutor [Tầng 4: line 83]

"Tôi muốn biết khi nào Redis được ghi và khi nào đọc từ DB"
  → ContextStore.appendItem() [DB first write] → ContextStore.readPreviousSteps() [Redis với DB fallback]

"Tôi muốn biết TIMEOUT xử lý thế nào khác FAILED"
  → StepExecutor.execute() [line 116-129] → ActionExecutionCdcConsumer → ReconciliationEngine

"Tôi muốn biết compensation dùng data gì"
  → ActionExecutor.writeSagaLog() [snapshot at time of action] → CompensationManager.executeCompensationAction() [line 228]

"Tôi muốn biết action từ Swagger được import thế nào"
  → SwaggerImporter.importFromPlatform() → buildParameters() [VAR_ULID gen] → buildOutput()
```

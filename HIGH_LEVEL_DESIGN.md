# High-Level Design — Scenario Console Service Communication & Compensation

> Tài liệu này cover 2 lớp bảo vệ data consistency:
> - **Saga Orchestration** (Section 3–8): xử lý failure đã biết, immediate compensation
> - **CDC + Reconciliation** (Section 10–14): xử lý state drift không biết, eventual consistency

---

## 1. Service Communication Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Console Frontend                         │
└───────────────────────────────┬─────────────────────────────────┘
                                │ HTTP/REST
┌───────────────────────────────▼─────────────────────────────────┐
│                      Console API (Orchestrator)                  │
│                                                                  │
│  ┌─────────────────┐   ┌──────────────────┐   ┌──────────────┐ │
│  │ Scenario Engine │   │ Action Executor   │   │ Compensation │ │
│  │                 │──▶│                  │──▶│   Manager    │ │
│  │ - state machine │   │ - bind varId     │   │              │ │
│  │ - step routing  │   │ - idempotency    │   │ - saga log   │ │
│  │ - context mgmt  │   │ - retry policy   │   │ - rollback   │ │
│  └─────────────────┘   └────────┬─────────┘   └──────────────┘ │
│                                 │                                │
│  ┌──────────────────────────────▼──────────────────────────────┐│
│  │                    Service Adapter Layer                     ││
│  │                                                              ││
│  │  ┌───────────────┐  ┌──────────────────┐  ┌─────────────┐  ││
│  │  │  HTTP Adapter │  │  Async Adapter   │  │Mock Adapter │  ││
│  │  │  (sync REST)  │  │ (Kafka/SQS/SNS)  │  │  (testing)  │  ││
│  │  └───────┬───────┘  └────────┬─────────┘  └─────────────┘  ││
│  └──────────┼───────────────────┼────────────────────────────  ││
└─────────────┼───────────────────┼─────────────────────────────┘ │
              │                   │
   ┌──────────┼───────────────────┼──────────────────────┐
   │          │   Service Layer   │                       │
   │  ┌───────▼────────┐  ┌───────▼──────────────┐       │
   │  │Internal Services│  │  External Services   │       │
   │  │                │  │                      │       │
   │  │ - Campaign Svc │  │ - Payment Gateway    │       │
   │  │ - Coupon Svc   │  │   (Stripe / PG / ..) │       │
   │  │ - Segment Svc  │  │ - Notification Svc   │       │
   │  │ - Release Svc  │  │ - KYC / Fraud Check  │       │
   │  │ - Order Svc    │  │ - 3rd party APIs     │       │
   │  └────────────────┘  └──────────────────────┘       │
   └─────────────────────────────────────────────────────┘
```

---

## 2. Service Adapter Layer

Console API không gọi thẳng service — tất cả đi qua **Service Adapter Layer**. Adapter được resolve từ `action_definition.schema` tại runtime.

### action_definition.schema — Adapter config

```json
{
  "service": "payment-gateway",
  "adapter": "http",
  "endpoint": "/payments/charge",
  "method":   "POST",
  "timeout_ms": 10000,
  "retry": {
    "max_attempts": 3,
    "backoff": "exponential",
    "retryable_status": [502, 503, 504]
  },
  "parameters": [...],
  "output": [...]
}
```

### Adapter types

| Adapter   | Use case                                          | Pattern              |
|-----------|---------------------------------------------------|----------------------|
| `http`    | Internal service, external REST API (sync)        | Request → Response   |
| `async`   | Long-running job (batch export, send email blast) | Fire → Poll / Callback |
| `mock`    | Testing, staging environment                      | -                    |

### Async Action Flow

Với action dạng async (ví dụ: gửi 1 triệu email):

```
Console API ──▶ Publish message to Queue ──▶ Worker Service
                                                   │
Console API ◀── Webhook callback ◀─────────────────┘
(hoặc poll status via GET /jobs/{jobId})
```

`action_definition.schema` cho async:

```json
{
  "adapter":      "async",
  "publish_to":   "notification-queue",
  "status_check": {
    "endpoint": "/jobs/{jobId}",
    "interval_ms": 5000,
    "timeout_ms":  60000,
    "done_when":   "status == 'COMPLETED'"
  },
  "output": [
    { "field": "job_id", "alias": "notificationJobId", "type": "string" }
  ]
}
```

---

## 3. Vấn đề Rollback — Payment đã xử lý, Step sau Cancel

### Tại sao đây là vấn đề khó?

```
Step 1: Charge Payment    ✅ → tiền đã bị trừ trên Payment Gateway
Step 2: Create Order      ✅ → order đã tồn tại trong DB
Step 3: Release Content   ❌ FAIL  (hoặc user cancel)
```

- Step 1, 2 đã gọi external service thành công — **không thể undo bằng DB ROLLBACK**
- Payment Gateway, Order Service là **separate systems** không nằm trong transaction của Console
- Cần gọi **compensating actions** theo chiều ngược lại: cancel_order → refund_payment

---

## 4. Compensation Manager — Saga Orchestration

Console API là orchestrator — phù hợp nhất với **Orchestration-based Saga**: Console chủ động gọi compensation actions thay vì dùng event/choreography.

### Compensation State Machine

```
EXECUTING ──▶ COMPLETED
     │
     └──▶ FAILED ──▶ COMPENSATING ──▶ COMPENSATED
                           │
                           └──▶ COMPENSATION_FAILED ──▶ [manual intervention]
```

### Saga Log Table (PostgreSQL)

```sql
CREATE TABLE saga_compensation_log (
    id                  VARCHAR PRIMARY KEY,   -- ulid
    execution_id        VARCHAR NOT NULL,      -- scenario_execution.id
    step_order          INT     NOT NULL,
    action_definition_id VARCHAR NOT NULL,
    action_execution_id VARCHAR,               -- ref action_execution
    status              VARCHAR NOT NULL,      -- PENDING | COMPENSATING | COMPENSATED | FAILED
    compensate_action_id VARCHAR,              -- action_definition_id của compensation action
    compensate_execution_id VARCHAR,           -- ref action_execution của compensation
    context_snapshot    JSONB,                 -- alias values tại thời điểm action chạy
    created_at          TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ
);
```

`context_snapshot` lưu lại alias values **tại thời điểm action chạy** — quan trọng vì compensation cần đúng dữ liệu đó (e.g. `paymentTransactionId` của lần charge cụ thể đó).

---

## 5. action_definition.schema — Mở rộng với Compensation

```json
{
  "service":  "payment-gateway",
  "adapter":  "http",
  "endpoint": "/payments/charge",
  "method":   "POST",
  "parameters": [
    { "name": "amount",   "alias": "paymentAmount",   "in": "body", "type": "number",  "required": true },
    { "name": "currency", "alias": "paymentCurrency", "in": "body", "type": "string",  "required": true },
    { "name": "user_id",  "alias": "userId",          "in": "body", "type": "string",  "required": true }
  ],
  "output": [
    { "field": "transaction_id", "alias": "paymentTransactionId", "type": "string" },
    { "field": "status",         "alias": "paymentStatus",        "type": "string" }
  ],
  "compensation": {
    "actionDefinitionId": "actdef_refund_payment",
    "trigger": "auto",
    "required_var_ids": ["VAR_01JWGHI"]
  }
}
```

```json
{
  "service":  "payment-gateway",
  "adapter":  "http",
  "endpoint": "/payments/{paymentTransactionId}/refund",
  "method":   "POST",
  "parameters": [
    { "name": "paymentTransactionId", "alias": "paymentTransactionId", "in": "path",  "type": "string", "required": true },
    { "name": "reason",               "alias": "compensationReason",   "in": "body",  "type": "string", "required": false }
  ],
  "output": [
    { "field": "refund_id", "alias": "refundId", "type": "string" }
  ]
}
```

**`compensation.trigger` values:**

| Value    | Ý nghĩa                                                      |
|----------|--------------------------------------------------------------|
| `auto`   | Tự động chạy khi scenario fail hoặc cancel                   |
| `manual` | Chỉ chạy khi admin/PM chủ động trigger (sensitive actions)   |
| `none`   | Không có compensation (e.g. read-only, send notification)    |

---

## 6. Payment Scenario — Full Flow với Rollback

### Scenario: In-Game Purchase Flow

```
Step 1 — Validate User & Item
  Action: validate_user_item(userId, itemId) → { eligible: true, price: 9.99 }
  Compensation: none (read-only)

Step 2 — Process Payment
  Action: charge_payment(userId, amount, currency) → { transactionId, status }
  Compensation: auto → refund_payment(transactionId)

Step 3 — Create Order
  Action: create_order(userId, itemId, transactionId) → { orderId }
  Compensation: auto → cancel_order(orderId)

Step 4 — Release Content   ← FAIL ở đây
  Action: release_content(userId, itemId, orderId) → { grantId }
  Compensation: auto → revoke_content(grantId)
```

### Khi Step 4 FAIL — Compensation Execution

```
Trigger: Step 4 action FAILED (hoặc user cancel)
Compensation Manager khởi động:

1. Query saga_compensation_log WHERE execution_id = X ORDER BY step_order DESC
   → Step 4: FAILED    → skip (chưa succeed, không cần compensate)
   → Step 3: COMPLETED → có compensation, trigger = auto → RUN
   → Step 2: COMPLETED → có compensation, trigger = auto → RUN
   → Step 1: COMPLETED → compensation = none → skip

Execution (reverse order):
  [1] cancel_order(orderId)
       input: orderId ← từ context_snapshot của Step 3
       → POST /orders/{orderId}/cancel
       → saga_log Step 3: COMPENSATING → COMPENSATED ✅

  [2] refund_payment(transactionId)
       input: paymentTransactionId ← từ context_snapshot của Step 2
       → POST /payments/{transactionId}/refund
       → saga_log Step 2: COMPENSATING → COMPENSATED ✅

Scenario status: COMPENSATED
```

### Sequence Diagram

```
User/System     Console API      Compensation Mgr    Order Svc    Payment GW
    │               │                   │                │             │
    │  cancel/fail  │                   │                │             │
    │──────────────▶│                   │                │             │
    │               │ trigger(exec_id)  │                │             │
    │               │──────────────────▶│                │             │
    │               │                   │ load saga log  │             │
    │               │                   │ (steps desc)   │             │
    │               │                   │                │             │
    │               │                   │ cancel_order() │             │
    │               │                   │───────────────▶│             │
    │               │                   │     ✅ 200      │             │
    │               │                   │◀───────────────│             │
    │               │                   │                │             │
    │               │                   │  refund()      │             │
    │               │                   │────────────────────────────▶│
    │               │                   │     ✅ 200                    │
    │               │                   │◀────────────────────────────│
    │               │                   │                │             │
    │               │  COMPENSATED ✅    │                │             │
    │               │◀──────────────────│                │             │
    │  notify user  │                   │                │             │
    │◀──────────────│                   │                │             │
```

---

## 7. Compensation Failure — Manual Intervention

Nếu `refund_payment` thất bại (Payment GW down, timeout...):

```
saga_log Step 2: COMPENSATING → COMPENSATION_FAILED

Hệ thống:
  1. Alert → notify admin (Slack/email)
  2. Ghi dead letter vào compensation_dead_letter table
  3. Expose API: GET /executions/{id}/compensation-status
                 POST /executions/{id}/retry-compensation
  4. Audit log: "Compensation failed, manual intervention required"
```

### compensation_dead_letter table

```sql
CREATE TABLE compensation_dead_letter (
    id                  VARCHAR PRIMARY KEY,
    saga_log_id         VARCHAR NOT NULL,
    execution_id        VARCHAR NOT NULL,
    action_definition_id VARCHAR NOT NULL,
    context_snapshot    JSONB NOT NULL,
    failure_reason      TEXT,
    retry_count         INT DEFAULT 0,
    resolved            BOOLEAN DEFAULT FALSE,
    created_at          TIMESTAMPTZ
);
```

---

## 8. Tổng hợp — Decision Matrix cho Compensation

| Scenario                                | Compensation trigger | Handling                                          |
|-----------------------------------------|----------------------|---------------------------------------------------|
| Action fail (network error)             | auto                 | Retry trước, nếu hết retry → compensate ngược     |
| User cancel giữa chừng                  | auto/manual          | Tùy config `trigger` của từng action              |
| Last step fail (payment đã xử lý)       | auto                 | Compensation Manager chạy reverse                 |
| Compensation action fail                | -                    | Dead letter + alert + manual retry                |
| Action không có compensation            | none                 | Log, alert nếu cần manual fix                     |
| External service không hỗ trợ rollback  | manual               | Audit trail, admin xử lý ngoài hệ thống           |

---

## 9. Key Design Rules

1. **Mỗi action có thể optional khai báo `compensation`** trong `action_definition.schema` — không bắt buộc (read-only action không cần).
2. **`context_snapshot` là bắt buộc** khi lưu saga log — compensation phải dùng đúng data tại thời điểm action gốc chạy, không dùng context hiện tại (có thể đã thay đổi).
3. **Compensation cũng phải idempotent** — dùng cùng idempotency key mechanism như action thường.
4. **Compensation không trigger compensation khác** — nếu `refund` cũng fail, không cố refund-của-refund, đi thẳng vào dead letter.
5. **`auto` compensation chỉ chạy cho actions đã `COMPLETED`** — action bị FAILED hoặc NOT_STARTED không cần compensate.
6. **Manual trigger** dành cho sensitive actions (e.g. gửi email thông báo đã xong, không thể "unsend" → để admin quyết định cách handle).

---

---

## 10. CDC + Reconciliation — Tại Sao Saga Chưa Đủ?

### Saga bỏ sót trường hợp nào?

```
Console API ──▶ POST /payments/charge ──▶ Payment Gateway
                                               │
                                         Process OK ✅
                                         transactionId = TXN_123
                                               │
                              ← Network timeout / connection reset
                                               │
Console API nhận: IOException / 504
Console DB ghi: action_execution.status = FAILED
```

**Kết quả:**
- Console DB nghĩ: payment FAILED → không trigger Saga compensation (vì không có gì để compensate)
- Payment Gateway thực tế: TXN_123 đã CHARGED → tiền đã bị trừ
- User bị charge tiền nhưng không nhận được service → **ghost transaction**

Saga không handle được case này vì **Saga chỉ biết failure từ response**, không biết state thực tế ở 3rd party.

### CDC + Reconciliation giải quyết gì?

| Vấn đề | Saga | CDC + Reconciliation |
|--------|------|----------------------|
| Failure rõ ràng (4xx, exception) | ✅ compensate ngay | ✅ cũng detect được |
| Response bị mất (timeout, network drop) | ❌ không biết | ✅ phát hiện ghost transaction |
| 3rd party xử lý async, chưa xong | ❌ | ✅ poll đến khi xong |
| State drift sau khi chạy xong (3rd party rollback riêng) | ❌ | ✅ periodic check |
| Compensation failed, dead letter tồn đọng lâu | partial | ✅ retry tự động |

---

## 11. Architecture — CDC + Reconciliation

```
┌──────────────────────────────────────────────────────────────────────┐
│                         Console API                                   │
│   action_execution (write) ──▶ PostgreSQL                            │
└────────────────────────────────────┬─────────────────────────────────┘
                                     │ WAL (Write-Ahead Log)
                          ┌──────────▼──────────┐
                          │   Debezium (CDC)     │
                          │   Connector          │
                          └──────────┬───────────┘
                                     │ publish CDC events
                    ┌────────────────▼──────────────────────┐
                    │            Kafka                       │
                    │                                        │
                    │  console.action_execution.cdc          │
                    │  console.scenario_execution.cdc        │
                    │  console.saga_compensation_log.cdc     │
                    └────────────────┬──────────────────────┘
                                     │ consume
          ┌──────────────────────────▼──────────────────────────────┐
          │                 Reconciliation Service                    │
          │                                                          │
          │  ┌─────────────────┐   ┌──────────────────────────────┐ │
          │  │ Event Classifier│   │    State Comparator          │ │
          │  │                 │──▶│                              │ │
          │  │ - filter events │   │ - gọi 3rd party GET API      │ │
          │  │ - priority queue│   │ - so sánh vs DB state        │ │
          │  └─────────────────┘   └──────────┬───────────────────┘ │
          │                                   │                      │
          │                    ┌──────────────▼──────────────────┐  │
          │                    │     Drift Detector               │  │
          │                    │                                  │  │
          │                    │  DB=FAILED, 3rd=SUCCESS → ghost  │  │
          │                    │  DB=SUCCESS, 3rd=NOT_FOUND → orphan│ │
          │                    │  DB=PENDING, 3rd=PROCESSING → wait│ │
          │                    └──────────────┬───────────────────┘  │
          │                                   │                      │
          │              ┌────────────────────▼──────────────────┐  │
          │              │       Reconciliation Engine            │  │
          │              │                                        │  │
          │              │  auto-complete  │  auto-compensate     │  │
          │              │  retry-saga     │  manual-queue        │  │
          │              └────────────────────────────────────────┘  │
          └──────────────────────────────────────────────────────────┘
                    │                           │
         ┌──────────▼───────────┐    ┌──────────▼──────────────┐
         │  3rd Party Services  │    │  reconciliation_task DB  │
         │  (verify state)      │    │  (manual intervention)   │
         └──────────────────────┘    └─────────────────────────┘
```

---

## 12. Kafka Topics — CDC Events

Debezium publish events theo format:

```json
{
  "before": { "id": "actexec_01JW", "status": "PROCESSING", ... },
  "after":  { "id": "actexec_01JW", "status": "FAILED",     ... },
  "op": "u",
  "ts_ms": 1747267200000,
  "source": { "table": "action_execution", "db": "console" }
}
```

**Topics:**

| Topic | Trigger | Reconciler cần làm |
|-------|---------|---------------------|
| `console.action_execution.cdc` | Mọi status change của action | Check 3rd party nếu FAILED hoặc timeout |
| `console.scenario_execution.cdc` | Scenario status change | Verify toàn bộ flow nếu COMPENSATING |
| `console.saga_compensation_log.cdc` | Compensation status change | Retry nếu COMPENSATION_FAILED |

**Filter quan trọng — Reconciler chỉ quan tâm:**

```
action_execution:
  op = "u" (update)
  AND (
    after.status = "FAILED"           -- potential ghost
    OR after.status = "PROCESSING"    -- stuck, chưa nhận response
    AND (now - after.updated_at) > timeout_threshold
  )

saga_compensation_log:
  op = "u"
  AND after.status = "COMPENSATION_FAILED"
```

---

## 13. action_definition.schema — Thêm `verification` Block

Để Reconciliation Service biết cách check 3rd party, mở rộng schema:

```json
{
  "service":  "payment-gateway",
  "endpoint": "/payments/charge",
  "method":   "POST",
  "parameters": [...],
  "output": [...],
  "compensation": { ... },

  "verification": {
    "endpoint": "/payments",
    "method":   "GET",
    "query_params": {
      "idempotency_key": "{idempotencyKey}"
    },
    "conditions": {
      "processed":  "response.status == 'CHARGED' && response.transaction_id != null",
      "not_found":  "response_code == 404",
      "pending":    "response.status == 'PROCESSING'"
    },
    "poll_interval_ms": 5000,
    "max_poll_attempts": 12
  }
}
```

`idempotencyKey` được tính từ `SHA256(action_definition_id + sorted_required_params)` — giống hệt key đã gửi trong header lúc gọi action, nên Payment GW lookup được.

---

## 14. Reconciliation Engine — Decision Logic

### State Matrix & Action

```
DB State   │  3rd Party State  │  Drift Type     │  Reconciler Action
───────────┼───────────────────┼─────────────────┼─────────────────────────────────────
FAILED     │  CHARGED          │  Ghost Txn      │  auto-compensate (refund)
           │                   │                 │  hoặc auto-complete nếu đây là intent
FAILED     │  NOT_FOUND        │  Clean          │  No action (failure đúng)
FAILED     │  PROCESSING       │  Race condition │  Poll lại sau interval
PROCESSING │  CHARGED          │  Lost response  │  Update DB → COMPLETED, continue flow
PROCESSING │  NOT_FOUND        │  Timeout clean  │  Update DB → FAILED, trigger saga
PROCESSING │  PROCESSING       │  Still running  │  Poll lại
COMPLETED  │  NOT_FOUND        │  Orphan record  │  Alert admin, manual queue
COMPLETED  │  CHARGED          │  Consistent ✅  │  No action
COMP_FAILED│  CHARGED          │  Stale dead ltr │  Retry compensation
```

### Ghost Transaction Flow (payment đã charge, DB ghi FAILED)

```
1. Debezium captures: action_execution status → FAILED
2. Kafka event published
3. Reconciler consumes event
4. Reconciler reads action_definition.verification config
5. Reconciler calls: GET /payments?idempotency_key=SHA256(...)
6. Payment GW returns: { transaction_id: "TXN_123", status: "CHARGED" }
7. Drift Detector: DB=FAILED, 3rd=CHARGED → Ghost Transaction

Decision:
  a. Nếu scenario đang COMPENSATING → đây là refund candidate
     → trigger Saga compensation: POST /payments/TXN_123/refund
     → update saga_compensation_log

  b. Nếu scenario đang IN_PROGRESS → lost response, payment thực ra OK
     → update action_execution.status = COMPLETED
     → lưu transactionId vào execution context
     → notify Scenario Engine tiếp tục flow

8. Ghi reconciliation_event log để audit
```

### reconciliation_event table

```sql
CREATE TABLE reconciliation_event (
    id                   VARCHAR PRIMARY KEY,
    execution_id         VARCHAR NOT NULL,
    action_execution_id  VARCHAR NOT NULL,
    detected_at          TIMESTAMPTZ NOT NULL,
    drift_type           VARCHAR NOT NULL,
    -- GHOST_TRANSACTION | ORPHAN_RECORD | LOST_RESPONSE | STALE_COMPENSATION
    db_state             VARCHAR NOT NULL,
    third_party_state    VARCHAR NOT NULL,
    third_party_response JSONB,
    resolution           VARCHAR,
    -- AUTO_COMPENSATED | AUTO_COMPLETED | MANUAL_QUEUED | NO_ACTION
    resolved_at          TIMESTAMPTZ,
    notes                TEXT
);
```

---

## 15. Saga vs CDC + Reconciliation — Khi Nào Dùng Cái Nào?

```
Failure xảy ra
      │
      ├─── Console nhận được response lỗi (4xx, 5xx, exception)?
      │         │
      │         YES ──▶ SAGA xử lý ngay
      │                 (compensation theo chiều ngược)
      │
      └─── Console KHÔNG nhận được response (timeout, network drop)?
                │
                YES ──▶ CDC + RECONCILIATION xử lý async
                        Debezium capture PROCESSING timeout
                        Reconciler check 3rd party thực tế
                        Quyết định: complete flow / compensate / alert
```

**Hai lớp bảo vệ — complement nhau:**

```
Layer 1 — Saga (immediate):
  Trigger:  Console nhận failure response
  Latency:  Milliseconds
  Coverage: Known failures

Layer 2 — CDC + Reconciliation (eventual):
  Trigger:  DB state change captured by Debezium
  Latency:  Seconds to minutes (async)
  Coverage: Unknown failures, ghost txn, state drift, stuck PROCESSING
```

---

## 16. Infrastructure Requirements

| Component | Role | Tech |
|-----------|------|------|
| Debezium Connector | CDC từ PostgreSQL WAL | Debezium + Kafka Connect |
| Kafka | Event streaming | Apache Kafka / Amazon MSK |
| Reconciliation Service | Consume events, check 3rd party, resolve drift | Spring Boot (separate service) |
| reconciliation_event table | Audit trail của mọi drift được phát hiện | PostgreSQL |
| Dead letter topic | Events Reconciler không xử lý được | Kafka DLT |

**Debezium config cơ bản:**

```json
{
  "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
  "database.hostname": "rds-console.xxx.rds.amazonaws.com",
  "database.dbname":   "console",
  "table.include.list": "public.action_execution,public.scenario_execution,public.saga_compensation_log",
  "publication.name":  "console_reconciliation_pub",
  "slot.name":         "console_reconciliation_slot",
  "topic.prefix":      "console"
}
```

**Tradeoff cần biết:**
- Debezium dùng PostgreSQL replication slot → giữ WAL, cần monitor slot lag để tránh disk đầy
- Reconciler phải idempotent — Kafka at-least-once delivery, event có thể đến 2 lần
- 3rd party verification calls tốn quota API → cần rate limiting + exponential backoff trong Reconciler

---

---

## 17. Button Trigger Idempotency — End-to-End

### Tại sao button click có thể gây duplicate?

```
User click "Create Coupon"
  → double-click (2 request gửi đi cùng lúc)
  → network chậm → user click lại lần 2
  → request 1 timeout ở client → browser/SDK auto-retry
  → navigate back rồi forward → component remount → on_load trigger lại
  → server trả 500 → frontend retry middleware tự gửi lại
```

Mỗi case trên là một lớp duplicate khác nhau — cần chặn tại **4 tầng**:

```
[Tầng 1] UI Lock          — chặn ngay khi click, không cho click lần 2
[Tầng 2] Request Dedup    — HTTP layer, X-Request-Id, Redis short TTL
[Tầng 3] Trigger Lock     — Execution Context, trigger chỉ chạy 1 lần / state
[Tầng 4] Action Idempotency — SHA256 key, DB UNIQUE constraint
```

---

### Tầng 1 — UI Lock (Frontend)

Khi user click button, **ngay lập tức** trước khi gửi request:

```
state machine của button:
  IDLE ──click──▶ LOADING ──success──▶ DONE (hoặc về IDLE nếu có thể retry)
                         └──error──▶ ERROR (re-enable với message)

Rules:
  - LOADING: disabled=true, show spinner, bỏ qua mọi click tiếp theo
  - DONE:    disabled=true hoặc hide (action đã chạy xong, không chạy lại)
  - ERROR:   re-enable button để user có thể retry SAU KHI đọc lỗi
```

Button state lưu vào **Execution Context** (không phải chỉ component local state) để survive page refresh:

```json
"triggerState": {
  "trigger_01JWB2T1": {
    "status": "LOADING",
    "requestId": "req_01JWXYZ",
    "startedAt": "2026-05-15T10:30:00Z"
  }
}
```

→ User F5 lại → component đọc từ context → thấy LOADING → button vẫn disabled, tiếp tục poll.

---

### Tầng 2 — Request Dedup (HTTP Layer)

Frontend generate **`X-Request-Id`** một lần duy nhất tại thời điểm click:

```
User click
  → requestId = ulid()           ← generate một lần, không thay đổi khi retry
  → lưu requestId vào triggerState trong context
  → gửi POST /executions/{id}/trigger
    Header: X-Request-Id: req_01JWXYZ
```

Backend nhận request:

```
1. Extract X-Request-Id từ header
2. SETNX  "dedup:req_01JWXYZ"  "1"  EX 60   (Redis, TTL 60s)
   → SETNX = 1 (key chưa tồn tại): first request → proceed
   → SETNX = 0 (key đã tồn tại):   duplicate within 60s → return 409

3. Lookup action result đang chờ:
   GET "result:req_01JWXYZ"    (Redis)
   → Có kết quả: return 200 với cached response (request trước đã xong)
   → Không có: đang processing → return 202 { status: "processing" }
```

**Tại sao dùng `ulid()` thay vì UUID?**
- ULID có timestamp prefix → sortable, dễ debug ("request lúc 10:30:00")
- Cùng entropy với UUID v4 → collision probability negligible

**Flow khi network timeout:**

```
Client send req_01JWXYZ ──▶ Server nhận, SET dedup key, bắt đầu xử lý
Client timeout (30s)
Client retry cùng req_01JWXYZ ──▶ Server: dedup key tồn tại
                                   → Check result key
                                   → Nếu chưa xong: 202 { status: "processing" }
                                   → Nếu xong rồi:  200 { result: ... }
```

---

### Tầng 3 — Trigger Lock (Execution Context)

Một trigger trong một execution chỉ được chạy một lần (hoặc chỉ được retry khi FAILED).

**trigger_execution table:**

```sql
CREATE TABLE trigger_execution (
    id                    VARCHAR PRIMARY KEY,  -- ulid
    scenario_execution_id VARCHAR NOT NULL,
    step_trigger_id       VARCHAR NOT NULL,
    status                VARCHAR NOT NULL,     -- PENDING | RUNNING | COMPLETED | FAILED
    request_id            VARCHAR,              -- X-Request-Id của request đầu tiên
    started_at            TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    UNIQUE (scenario_execution_id, step_trigger_id)
);
```

Backend khi nhận trigger request:

```
1. SELECT * FROM trigger_execution
   WHERE scenario_execution_id = ? AND step_trigger_id = ?

   → RUNNING:   return 202 "already running, poll for result"
   → COMPLETED: return 200 với kết quả đã lưu  (idempotent return)
   → FAILED:    cho phép retry → UPDATE status = RUNNING
   → Not found: INSERT status = RUNNING → proceed

2. Execute actions (theo thứ tự trong block_definition.schema)
3. UPDATE trigger_execution status = COMPLETED / FAILED
```

**UNIQUE constraint** trên `(execution_id, trigger_id)` đảm bảo tại DB level — dù có race condition 2 request cùng INSERT, chỉ 1 cái thắng.

---

### Tầng 4 — Action Idempotency (Per-Action)

Đây là lớp sâu nhất, protect individual action call đến 3rd party.

**Key generation:**

```
idempotencyKey = SHA256(
  execution_id          +   ← scope về execution này
  action_definition_id  +   ← action nào
  sorted(required_params)   ← data gì
)
```

Tại sao include `execution_id`?
- Không có nó: user A và user B cùng tạo coupon cùng params → cùng key → user B bị chặn
- Có nó: mỗi execution là độc lập, chỉ idempotent trong scope của execution đó

**action_execution_log check:**

```
INSERT INTO action_execution_log
  (idempotency_key, status = 'PROCESSING', execution_id, ...)
ON CONFLICT (idempotency_key) DO NOTHING
RETURNING id

→ id trả về: INSERT thành công → first call → execute action
→ id null (DO NOTHING): duplicate → query status

SELECT status, response_snapshot FROM action_execution_log
WHERE idempotency_key = ?

  PROCESSING → return 202
  COMPLETED  → return response_snapshot (không gọi lại 3rd party)
  FAILED     → check TTL, nếu đủ cũ → DELETE + retry
```

**`response_snapshot`** lưu toàn bộ response của 3rd party để return lại mà không cần gọi lại.

---

### Full Flow — User Click "Create Coupon"

```
[Browser]
  User click button
  → button: IDLE → LOADING (disabled)
  → requestId = ulid()  →  lưu vào triggerState
  → POST /executions/exec_01JW/trigger
    X-Request-Id: req_01JWXYZ
    Body: { triggerId: "trigger_01JWB2T1", inputs: { couponName: "SUMMER2024", ... } }

[API Gateway / Console API]
  ┌─ Tầng 2: SETNX "dedup:req_01JWXYZ" EX 60
  │   → fail: 409 (double-click, < 60s)
  │   → ok: proceed
  │
  ├─ Tầng 3: SELECT trigger_execution WHERE execution_id + trigger_id
  │   → RUNNING:   202 (poll)
  │   → COMPLETED: 200 (cached)
  │   → else:      INSERT RUNNING, proceed
  │
  └─ Tầng 4: Với mỗi action trong trigger:
      idempotencyKey = SHA256(exec_01JW + actdef_create_coupon + sorted_params)
      INSERT action_execution_log (key, PROCESSING)
      → conflict: return cached response
      → ok: call POST /coupons trên coupon-service

[coupon-service]
  Nhận X-Idempotency-Key: {idempotencyKey} trong header
  → 3rd party tự dedup nếu hỗ trợ

[Console API — response path]
  action trả về { coupon_id: "cpn_01JW..." }
  → UPDATE action_execution_log status=COMPLETED, response_snapshot={...}
  → lưu alias "couponId" vào Execution Context (Redis)
  → UPDATE trigger_execution status=COMPLETED
  → SET "result:req_01JWXYZ" = { coupon_id: "cpn_01JW..." } EX 300
  → return 200

[Browser]
  Nhận 200
  → update context: couponId = "cpn_01JW..."
  → button: LOADING → DONE
  → advance to next step
```

---

### Race Condition — 2 Request Cùng Lúc (Double-Click Nhanh)

```
Request A ──▶ SETNX "dedup:req_A" = OK ──▶ INSERT trigger_execution RUNNING
Request B ──▶ SETNX "dedup:req_B" = OK ──▶ INSERT trigger_execution ...
                                              → UNIQUE constraint violation
                                              → return 409 "trigger already running"

→ Chỉ Request A chạy action
→ Request B bị chặn tại Tầng 3 (trigger_execution UNIQUE constraint)
```

Trường hợp `requestId` giống nhau (retry đúng cách):

```
Request A (attempt 1) ──▶ SETNX "dedup:req_01JWXYZ" = OK → proceed
Request A (attempt 2, retry) ──▶ SETNX "dedup:req_01JWXYZ" = FAIL → 409
                                  Backend check result key
                                  → chưa có result: 202 "processing"
                                  → có result: 200 với cached response
```

---

### Tổng hợp — 4 Tầng Idempotency

```
Tầng  │ Where      │ Key                     │ TTL      │ Chặn case
──────┼────────────┼─────────────────────────┼──────────┼────────────────────────────
1     │ Frontend   │ button state in context  │ session  │ double-click, UI re-render
2     │ Redis      │ "dedup:{requestId}"      │ 60s      │ network retry < 60s
3     │ PostgreSQL │ UNIQUE(execution, trigger)│ forever │ concurrent requests, race
4     │ PostgreSQL │ SHA256(exec+action+params)│ forever │ cross-session, same params
      │ + Redis    │ "dedup:{idempotencyKey}" │ 30s lock │ concurrent action execution
```

**Rule:** Tầng cao chặn sớm → ít tốn resource hơn. Tầng thấp chặn muộn hơn nhưng chắc chắn hơn.

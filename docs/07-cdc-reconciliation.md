# CDC + Reconciliation

## Tại sao Saga chưa đủ?

```
Console API ──▶ POST /payments/charge ──▶ Payment Gateway
                                               │
                                         Process OK ✅
                                         transactionId = TXN_123
                                               │
                              ← Network timeout / connection reset

Console API nhận: IOException / 504
Console DB ghi:   action_execution.status = TIMEOUT
```

**Kết quả:**
- Console không biết: payment có được xử lý không
- Payment Gateway thực tế: TXN_123 đã CHARGED → tiền đã bị trừ
- **Ghost transaction** — user bị charge tiền nhưng không nhận được service

`TIMEOUT` khác `FAILED`: `FAILED` là nhận được response lỗi rõ ràng (4xx/5xx), `TIMEOUT` là không nhận được response nào cả. Saga chỉ xử lý `FAILED` vì biết rõ outcome. `TIMEOUT` là unknown → Reconciler phụ trách.

---

## Saga vs CDC + Reconciliation

| Vấn đề | Saga | CDC + Reconciliation |
|---|---|---|
| Failure rõ ràng (4xx, exception) | ✅ compensate ngay | ✅ cũng detect được |
| Response bị mất (timeout, network drop) | ❌ | ✅ phát hiện ghost transaction |
| 3rd party xử lý async, chưa xong | ❌ | ✅ poll đến khi xong |
| State drift sau khi chạy xong | ❌ | ✅ periodic check |
| Compensation failed, dead letter tồn đọng | partial | ✅ retry tự động |

---

## Architecture

```
Console API
  action_execution (write) ──▶ PostgreSQL
                                    │ WAL (Write-Ahead Log)
                         ┌──────────▼──────────┐
                         │   Debezium (CDC)     │
                         │   Connector          │
                         └──────────┬───────────┘
                                    │ publish CDC events
                   ┌────────────────▼────────────────────┐
                   │            Amazon MSK (Kafka)        │
                   │  console.action_execution.cdc        │
                   │  console.scenario_execution.cdc      │
                   │  console.saga_compensation_log.cdc   │
                   └────────────────┬────────────────────┘
                                    │ consume
         ┌──────────────────────────▼───────────────────────────┐
         │              Reconciliation Service                   │
         │                                                       │
         │  Event Classifier ──▶ State Comparator               │
         │                             │                         │
         │                      Drift Detector                   │
         │                             │                         │
         │                    Reconciliation Engine              │
         │          auto-complete │ auto-compensate              │
         │          retry-saga    │ manual-queue                 │
         └──────────────────────────────────────────────────────┘
                   │                           │
        3rd Party Services          reconciliation_task DB
        (verify state)              (manual intervention)
```

---

## Kafka Topics

Debezium publish events từ PostgreSQL WAL:

```json
{
  "before": { "id": "actexec_01JW", "status": "PROCESSING" },
  "after":  { "id": "actexec_01JW", "status": "TIMEOUT" },
  "op": "u",
  "ts_ms": 1747267200000,
  "source": { "table": "action_execution" }
}
```

| Topic | Trigger | Reconciler làm gì |
|---|---|---|
| `console.action_execution.cdc` | Mọi status change | Check 3rd party nếu `TIMEOUT` |
| `console.scenario_execution.cdc` | Scenario status change | Verify toàn bộ flow nếu COMPENSATING |
| `console.saga_compensation_log.cdc` | Compensation status change | Retry nếu COMPENSATION_FAILED |

**Reconciler chỉ quan tâm:**

```
action_execution:
  op = "u"
  AND after.status = "TIMEOUT"       ← unknown outcome, cần verify 3rd party

saga_compensation_log:
  op = "u" AND after.status = "COMPENSATION_FAILED"   ← retry compensation
```

`FAILED` (nhận được error response rõ ràng) → Saga xử lý ngay, Reconciler không cần check.
`TIMEOUT` (không nhận được response) → Reconciler phải verify vì không biết 3rd party có xử lý không.

---

## action_definition — verification Block là Gating Mechanism

Reconciler không chỉ dành cho payment — apply cho **mọi action_definition có `verification` block**. Block này do Backoffice admin điền khi tạo action_definition, tuỳ thuộc vào 3rd party có hỗ trợ verify không.

```
Reconciler nhận CDC event (action_execution FAILED hoặc PROCESSING timeout)
        │
        ▼
Load action_definition
        │
        ├── verification = null
        │     → 3rd party không hỗ trợ verify (hoặc read-only action)
        │     → INSERT manual_reconciliation_queue
        │     → Alert admin
        │
        └── verification có data
              → build URL: service.base_url + verification.endpoint
              → call 3rd party với idempotency_key
              → compare state → handle drift tự động
```

**Ai điền verification block?** Backoffice admin khi tạo action_definition:

| Action | 3rd party hỗ trợ? | verification |
|---|---|---|
| charge_payment | GET /payments?idempotency_key=... | ✓ điền |
| create_coupon | GET /coupons?idempotency_key=... | ✓ điền |
| create_order | GET /orders?idempotency_key=... | ✓ điền |
| release_content | GET /releases?idempotency_key=... | ✓ điền |
| send_email | không thể check email đã gửi chưa | null → manual |
| publish_feed | không expose verify endpoint | null → manual |
| GET (read-only) | không có side effect | không cần |

Reconciler build URL từ: `service.base_url` + `verification.endpoint` + `query_params`:

```json
{
  "verification": {
    "endpoint": "/payments",
    "method": "GET",
    "query_params": {
      "idempotency_key": "{idempotencyKey}"
    },
    "conditions": {
      "processed": "response.status == 'CHARGED' && response.transaction_id != null",
      "not_found": "response_code == 404",
      "pending":   "response.status == 'PROCESSING'"
    },
    "poll_interval_ms": 5000,
    "max_poll_attempts": 12
  }
}
```

`idempotencyKey` = SHA256(action_definition_id + sorted_required_params) — giống hệt key đã gửi trong header lúc gọi action.

---

## State Matrix — Reconciler Decision

```
DB State  │ 3rd Party State │ Drift Type       │ Reconciler Action
──────────┼─────────────────┼──────────────────┼──────────────────────────────────────
TIMEOUT   │ CHARGED         │ Ghost Txn         │ Scenario COMPENSATING → auto-compensate
          │                 │                   │ Scenario IN_PROGRESS  → mark COMPLETED, continue
TIMEOUT   │ NOT_FOUND       │ Clean timeout     │ Mark FAILED → Saga trigger compensation ngược
TIMEOUT   │ PROCESSING      │ Still running     │ Poll lại sau interval
FAILED    │ CHARGED         │ Ghost Txn (5xx)   │ Như TIMEOUT+CHARGED — auto-compensate
FAILED    │ NOT_FOUND       │ Clean failure     │ No action (failure đúng, Saga đã xử lý)
COMPLETED │ NOT_FOUND       │ Orphan record     │ Alert admin, manual queue
COMPLETED │ CHARGED         │ Consistent ✅     │ No action
COMP_FAIL │ CHARGED         │ Stale dead letter │ Retry compensation
```

> `TIMEOUT` là DB state chính Reconciler xử lý. `FAILED` + `CHARGED` vẫn có thể xảy ra với 5xx (3rd party xử lý xong nhưng trả về lỗi) — Reconciler cũng check case này.

---

## Ghost Transaction Flow

```
1. Debezium: action_execution status → TIMEOUT
2. Kafka event published
3. Reconciler consumes event
4. Reconciler reads action_definition.verification config
5. GET /payments?idempotency_key=SHA256(...)
6. Response: { transaction_id: "TXN_123", status: "CHARGED" }
7. Drift Detector: DB=TIMEOUT, 3rd=CHARGED → Ghost Transaction

Decision:
  Scenario đang COMPENSATING → refund candidate
    → trigger Saga compensation: POST /payments/TXN_123/refund
    → update saga_compensation_log

  Scenario đang IN_PROGRESS → lost response, payment thực ra OK
    → UPDATE action_execution.status = COMPLETED
    → lưu transactionId vào execution context
    → notify Scenario Engine tiếp tục flow

8. INSERT reconciliation_event (audit log)
```

---

## reconciliation_event table

```sql
CREATE TABLE reconciliation_event (
    id                    VARCHAR PRIMARY KEY,
    scenario_execution_id VARCHAR NOT NULL REFERENCES scenario_execution(id),
    action_execution_id   VARCHAR NOT NULL REFERENCES action_execution(id),
    drift_type            VARCHAR NOT NULL CHECK (drift_type IN (
        'GHOST_TRANSACTION','ORPHAN_RECORD','LOST_RESPONSE','STALE_COMPENSATION'
    )),
    db_state              VARCHAR NOT NULL,
    third_party_state     VARCHAR NOT NULL,
    third_party_response  JSONB,
    resolution            VARCHAR CHECK (resolution IN (
        'AUTO_COMPENSATED','AUTO_COMPLETED','MANUAL_QUEUED','NO_ACTION'
    )),
    detected_at           TIMESTAMPTZ NOT NULL,
    resolved_at           TIMESTAMPTZ,
    notes                 TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## Khi nào dùng Saga, khi nào dùng CDC?

```
Failure xảy ra
      │
      ├─ Console nhận response lỗi (4xx, 5xx, exception)?
      │       YES ──▶ SAGA xử lý ngay (milliseconds)
      │
      └─ Console KHÔNG nhận response (timeout, network drop)?
              YES ──▶ CDC + RECONCILIATION xử lý async (seconds to minutes)
```

**Layer 1 — Saga (immediate):** trigger khi Console nhận failure response, latency milliseconds, coverage known failures.

**Layer 2 — CDC + Reconciliation (eventual):** trigger qua Debezium WAL capture, latency seconds to minutes, coverage TIMEOUT (unknown outcome) / ghost txn / state drift / stale compensation.

---

## Infrastructure Notes

| Component | Role | AWS Service |
|---|---|---|
| Debezium Connector | CDC từ PostgreSQL WAL | Self-hosted trên EKS hoặc MSK Connect |
| Kafka | Event streaming | Amazon MSK |
| Reconciliation Service | Consume events, check 3rd party, resolve drift | EKS Pod (separate deployment) |
| Dead letter topic | Events Reconciler không xử lý được | MSK DLT |

**Tradeoffs:**
- Debezium dùng PostgreSQL replication slot → giữ WAL, cần monitor slot lag để tránh disk đầy
- Reconciler phải idempotent — Kafka at-least-once delivery, event có thể đến 2 lần
- 3rd party verification calls tốn quota API → cần rate limiting + exponential backoff

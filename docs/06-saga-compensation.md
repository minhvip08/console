# Saga Compensation

## Tại sao cần Saga?

```
Step 1: Charge Payment    ✅ → tiền đã bị trừ trên Payment Gateway
Step 2: Create Order      ✅ → order đã tồn tại trong DB
Step 3: Release Content   ❌ FAIL
```

Step 1, 2 đã gọi external service thành công — **không thể undo bằng DB ROLLBACK**. Cần gọi **compensating actions** theo chiều ngược lại: cancel_order → refund_payment.

Console API là orchestrator → dùng **Orchestration-based Saga**: Console chủ động gọi compensation actions.

---

## Compensation State Machine

```
EXECUTING ──▶ COMPLETED
     │
     ├──▶ FAILED   ──▶ COMPENSATING ──▶ COMPENSATED
     │    (nhận được         │
     │    response lỗi)      └──▶ COMPENSATION_FAILED ──▶ [manual intervention]
     │
     └──▶ TIMEOUT
          (không nhận response)
          → Reconciler xử lý, không phải Saga
```

**Saga chỉ trigger khi `FAILED`** — Console nhận được response lỗi rõ ràng (4xx/5xx), biết chắc action đã fail.

**`TIMEOUT`** — Console không biết 3rd party có xử lý không → Reconciler verify trước, sau đó quyết định:
- 3rd party chưa xử lý (`NOT_FOUND`) → mark `FAILED` → Saga trigger compensation
- 3rd party đã xử lý (`CHARGED`) → mark `COMPLETED` → continue flow hoặc trigger compensation

---

## action_definition — Khai báo Compensation

```json
{
  "service":  "payment-gateway",
  "endpoint": "/payments/charge",
  "method":   "POST",
  "parameters": [...],
  "output": [
    { "field": "transaction_id", "varId": "VAR_01JWTXN", "type": "string" }
  ],
  "compensation": {
    "actionDefinitionId": "actdef_refund_payment",
    "trigger": "auto",
    "required_var_ids": ["VAR_01JWTXN"]
  }
}
```

Compensation action tương ứng:

```json
{
  "service":  "payment-gateway",
  "endpoint": "/payments/{transactionId}/refund",
  "method":   "POST",
  "parameters": [
    { "name": "transactionId", "varId": "VAR_01JWTXN", "in": "path", "required": true }
  ]
}
```

### compensation.trigger values

| Value | Ý nghĩa |
|---|---|
| `auto` | Tự động chạy khi scenario fail hoặc cancel |
| `manual` | Chỉ chạy khi admin chủ động trigger |
| `none` | Không có compensation (read-only, send notification) |

---

## saga_compensation_log

```sql
CREATE TABLE saga_compensation_log (
    id                               VARCHAR PRIMARY KEY,
    scenario_execution_id            VARCHAR NOT NULL REFERENCES scenario_execution(id),
    action_execution_id              VARCHAR REFERENCES action_execution(id),
    action_definition_id             VARCHAR NOT NULL REFERENCES action_definition(id),
    step_order                       INT NOT NULL,
    status                           VARCHAR NOT NULL CHECK (status IN (
        'PENDING','COMPENSATING','COMPENSATED','COMPENSATION_FAILED'
    )),
    compensation_action_execution_id VARCHAR REFERENCES action_execution(id),
    context_snapshot                 JSONB NOT NULL,
    created_at                       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

`context_snapshot` lưu variable values **tại thời điểm action chạy** — compensation dùng đúng data đó (e.g. `VAR_01JWTXN` = transactionId của lần charge cụ thể đó).

---

## Full Example — In-Game Purchase Flow

```
Step 1: validate_user_item(userId, itemId)
  compensation: none (read-only)

Step 2: charge_payment(userId, amount, currency) → { transactionId }
  compensation: auto → refund_payment(transactionId)

Step 3: create_order(userId, itemId, transactionId) → { orderId }
  compensation: auto → cancel_order(orderId)

Step 4: release_content(userId, itemId, orderId) → { grantId }  ← FAIL
  compensation: auto → revoke_content(grantId)
```

### Khi Step 4 FAIL — Compensation Execution

```
Compensation Manager khởi động:
Query saga_log WHERE execution_id = X ORDER BY step_order DESC

  Step 4: FAILED    → skip (chưa succeed, không cần compensate)
  Step 3: COMPLETED → trigger = auto → RUN
  Step 2: COMPLETED → trigger = auto → RUN
  Step 1: COMPLETED → trigger = none → skip

[1] cancel_order(orderId)
     input: orderId ← từ context_snapshot của Step 3
     → POST /orders/{orderId}/cancel
     → saga_log Step 3: COMPENSATING → COMPENSATED ✅

[2] refund_payment(transactionId)
     input: paymentTransactionId ← từ context_snapshot của Step 2
     → POST /payments/{transactionId}/refund
     → saga_log Step 2: COMPENSATING → COMPENSATED ✅

scenario_execution.status → COMPENSATED
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
    │               │                   │ ORDER BY DESC  │             │
    │               │                   │ cancel_order() │             │
    │               │                   │───────────────▶│             │
    │               │                   │◀───────────────│             │
    │               │                   │ refund()       │             │
    │               │                   │────────────────────────────▶│
    │               │                   │◀────────────────────────────│
    │               │  COMPENSATED ✅    │                │             │
    │◀──────────────│◀──────────────────│                │             │
```

---

## Compensation Failure — Dead Letter

Nếu `refund_payment` thất bại (Payment GW down):

```
saga_log Step 2: COMPENSATING → COMPENSATION_FAILED

Hệ thống:
  1. Alert → notify admin (Slack/email)
  2. INSERT vào compensation_dead_letter
  3. Expose API:
     GET  /executions/{id}/compensation-status
     POST /executions/{id}/retry-compensation
```

```sql
CREATE TABLE compensation_dead_letter (
    id                       VARCHAR PRIMARY KEY,
    saga_compensation_log_id VARCHAR NOT NULL REFERENCES saga_compensation_log(id),
    scenario_execution_id    VARCHAR NOT NULL REFERENCES scenario_execution(id),
    action_definition_id     VARCHAR NOT NULL REFERENCES action_definition(id),
    context_snapshot         JSONB NOT NULL,
    failure_reason           TEXT,
    retry_count              INT NOT NULL DEFAULT 0,
    resolved                 BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## Decision Matrix

| Scenario | Compensation trigger | Handling |
|---|---|---|
| Action fail (network error) | auto | Retry trước, hết retry → compensate ngược |
| User cancel giữa chừng | auto/manual | Tùy config `trigger` của từng action |
| Last step fail (payment đã xử lý) | auto | Compensation Manager chạy reverse |
| Compensation action fail | - | Dead letter + alert + manual retry |
| Action không có compensation | none | Log, alert nếu cần manual fix |
| External service không hỗ trợ rollback | manual | Audit trail, admin xử lý ngoài |

---

## Key Design Rules

1. **`context_snapshot` là bắt buộc** khi lưu saga log — dùng đúng data tại thời điểm action gốc chạy
2. **Compensation phải idempotent** — dùng cùng idempotency key mechanism như action thường
3. **Compensation không trigger compensation khác** — nếu refund fail → dead letter, không refund-của-refund
4. **`auto` chỉ chạy cho actions đã `COMPLETED`** — `FAILED`, `TIMEOUT`, hoặc NOT_STARTED không cần compensate (`TIMEOUT` do Reconciler verify trước, sau đó mới quyết định có trigger Saga không)
5. **`manual`** dành cho irreversible actions (gửi email đã xong, không thể unsend)

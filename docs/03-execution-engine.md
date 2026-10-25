# Execution Engine

## Status Machine

### action_execution

```
PENDING ──▶ PROCESSING ──▶ COMPLETED
                │
                ├──▶ FAILED    → Saga trigger compensation ngay
                │   (4xx/5xx)
                │
                └──▶ TIMEOUT   → Reconciler verify 3rd party
                   (no response)    ├── 3rd party NOT_FOUND → mark FAILED → Saga
                                    ├── 3rd party CHARGED   → mark COMPLETED
                                    └── 3rd party PROCESSING → poll lại
```

### scenario_execution

```
PENDING ──▶ EXECUTING ──▶ COMPLETED
                │
                └──▶ FAILED ──▶ COMPENSATING ──▶ COMPENSATED
                                      │
                                      └──▶ COMPENSATION_FAILED
```

---

## Flow 1 — Bắt đầu Scenario mới

```
1. User chọn scenario từ danh sách
2. Check quyền via RoleStore (cache Redis sc:user:{userId}:exec)
3. INSERT scenario_execution (status = EXECUTING)
4. Load DataSet liên quan
5. Trả về scenario info + step list
6. Auto-select step đầu tiên
```

---

## Flow 2 — Thực thi Step (Button Click)

```
Frontend gửi: triggerId + inputs { varId: value }
                │
                ▼
1. Tầng idempotency check (xem 05-idempotency.md)
                │
                ▼
2. Transform inputs → ContextDataItem[]
                │
                ▼
3. Query previous steps data từ Redis
   ZRANGEBYSCORE index:step:{executionId} 0 currentStepOrder
                │
                ▼
4. Merge: previous context + current inputs
   (current wins on alias conflict)
                │
                ▼
5. Identify actions từ step_trigger_action (N actions, ordered)
                │
                ▼
6. Action Loop (sequential):
   ├── Generate idempotency key: SHA256(exec_id + action_def_id + sorted_params)
   ├── INSERT action_execution (status = PROCESSING)
   ├── Call Target API (via Service Adapter Layer)
   │     ├── Response OK       → UPDATE status = COMPLETED, save output vars
   │     ├── Response 4xx/5xx  → UPDATE status = FAILED → Saga trigger
   │     └── Timeout/no reply  → UPDATE status = TIMEOUT → Reconciler picks up via CDC
   └── Save output varIds → Execution Context (Redis + DB)
                │
                ▼
7. UPDATE trigger_execution status = COMPLETED
8. Return response to frontend
```

### Sequential vs Parallel actions

Actions trong cùng 1 trigger chạy **sequential** theo `action_order` — output của action trước là input của action sau (variable chain).

---

## Flow 3 — Resume (Tiếp tục scenario đang dở)

```
1. Load scenario_execution từ PostgreSQL (có context JSONB snapshot)
2. Deserialize context JSONB → ContextDataItem[]
3. Restore to Redis:
   MSET   (C keys — data items)
   ZADD   (A keys — step index)
   SADD   (B keys — alias index)
4. Load DataSet
5. Return resumable step info + restored context
```

Cache-Aside fallback: Redis miss → load từ PostgreSQL context JSONB → restore 3-key model → trả về kết quả.

---

## Flow 4 — Hoàn thành Scenario

```
1. Verify tất cả required steps đã COMPLETED
2. Final context snapshot → UPDATE scenario_execution.context (JSONB)
3. UPDATE scenario_execution.status = COMPLETED
4. Cleanup Redis: DEL tất cả 3-key model keys của execution này
5. Scenario trở thành read-only (chỉ xem qua snapshot)
```

---

## Service Adapter Layer

Console API không gọi thẳng service — tất cả đi qua **Service Adapter Layer**, được resolve từ `action_definition.schema.adapter` tại runtime.

```
Action Executor
      │
      ▼
Service Adapter Layer
  ├── HTTP Adapter   (adapter: "http")  → sync Request/Response
  ├── Async Adapter  (adapter: "async") → Fire → Poll / Callback
  └── Mock Adapter   (adapter: "mock")  → testing only
```

### Async Action Flow

```
Console API ──▶ Publish message to Queue ──▶ Worker Service
                                                   │
Console API ◀── Webhook callback ◀─────────────────┘
(hoặc poll status via GET /jobs/{jobId})
```

Async action config:
```json
{
  "adapter": "async",
  "publish_to": "notification-queue",
  "status_check": {
    "endpoint": "/jobs/{jobId}",
    "interval_ms": 5000,
    "timeout_ms": 60000,
    "done_when": "status == 'COMPLETED'"
  }
}
```


## Shopee / Shopee Ads Mock Preview

Provisioned connection scenarios use the existing `mock` adapter and frozen `shopee_run` configuration, with current source/scenario authorization checked before trigger cache lookup and action dispatch. No vendor API or scheduled execution is enabled. Trigger responses include the serialized summary on first completion as well as cached repeats; guarded stored preview outputs are available after resume. See [mock integration guide](11-shopee-mock-integration.md).

# Idempotency — 4 Tầng Chống Duplicate

## Tại sao cần 4 tầng?

```
User click "Create Coupon"
  → double-click         (2 request cùng lúc)
  → network chậm         → user click lại lần 2
  → client timeout       → browser auto-retry
  → component remount    → on_load trigger lại
  → server trả 500       → frontend retry middleware
```

Mỗi case là một lớp duplicate khác nhau — cần chặn tại 4 tầng:

```
[Tầng 1] UI Lock          — chặn ngay khi click, không cho click lần 2
[Tầng 2] Request Dedup    — HTTP layer, X-Request-Id, Redis short TTL
[Tầng 3] Trigger Lock     — DB UNIQUE constraint, trigger chỉ chạy 1 lần / execution
[Tầng 4] Action Idempotency — SHA256 key, DB UNIQUE constraint
```

---

## Tầng 1 — UI Lock (Frontend)

```
Button state machine:
  IDLE ──click──▶ LOADING ──success──▶ DONE
                         └──error──▶ ERROR (re-enable)

Rules:
  LOADING: disabled=true, show spinner, bỏ qua mọi click
  DONE:    disabled=true (action đã chạy xong)
  ERROR:   re-enable để user retry sau khi đọc lỗi
```

Button state lưu vào **Execution Context** (không phải local component state) để survive F5:

```json
"triggerState": {
  "trigger_01JWB2T1": {
    "status": "LOADING",
    "requestId": "req_01JWXYZ",
    "startedAt": "2026-05-15T10:30:00Z"
  }
}
```

---

## Tầng 2 — Request Dedup (HTTP Layer / Redis)

Frontend generate `X-Request-Id` một lần duy nhất tại thời điểm click:

```
User click
  → requestId = ulid()        ← generate 1 lần, không đổi khi retry
  → lưu requestId vào triggerState
  → POST /executions/{id}/trigger
    Header: X-Request-Id: req_01JWXYZ
```

Backend:

```
1. SETNX "dedup:{requestId}" "1" EX 60    (Redis, TTL 60s)
   → OK (key chưa tồn tại):  first request → proceed
   → FAIL (key tồn tại):     duplicate < 60s → 409

2. GET "result:{requestId}"               (Redis)
   → có result: 200 với cached response
   → không có:  202 { status: "processing" }
```

**ULID vs UUID:** ULID có timestamp prefix → sortable, dễ debug.

---

## Tầng 3 — Trigger Lock (PostgreSQL)

```sql
CREATE TABLE trigger_execution (
    ...
    UNIQUE (scenario_execution_id, step_trigger_id)
);
```

Backend khi nhận trigger request:

```
SELECT * FROM trigger_execution
WHERE scenario_execution_id = ? AND step_trigger_id = ?

→ RUNNING:   202 "already running"
→ COMPLETED: 200 với kết quả đã lưu (idempotent return)
→ FAILED:    cho phép retry → UPDATE status = RUNNING
→ Not found: INSERT status = RUNNING → proceed
```

UNIQUE constraint đảm bảo tại DB level — dù 2 request cùng INSERT, chỉ 1 thắng.

---

## Tầng 4 — Action Idempotency (Per-Action)

### Key generation

```
idempotencyKey = SHA256(
  execution_id          +   ← scope về execution này
  action_definition_id  +   ← action nào
  sorted(required_params)   ← data gì
)
```

`execution_id` trong key là bắt buộc — không có nó thì 2 user cùng tạo coupon cùng params sẽ bị chặn nhau.

### action_execution_log check

```sql
INSERT INTO action_execution
  (idempotency_key, status = 'PROCESSING', ...)
ON CONFLICT (idempotency_key) DO NOTHING
RETURNING id

→ id trả về:  INSERT thành công → first call → execute action
→ id null:    duplicate → query status
```

```
SELECT status, response_snapshot FROM action_execution
WHERE idempotency_key = ?

  PROCESSING → 202 (đang gửi request)
  COMPLETED  → return response_snapshot (không gọi lại 3rd party)
  FAILED     → Saga đã/đang xử lý, return error
  TIMEOUT    → Reconciler đang verify, return 202 "verifying"
```

---

## Full Flow — User Click "Create Coupon"

```
[Browser]
  click → IDLE → LOADING
  requestId = ulid() → lưu vào triggerState
  POST /executions/exec_01JW/trigger
    X-Request-Id: req_01JWXYZ

[Console API]
  Tầng 2: SETNX "dedup:req_01JWXYZ" EX 60
    → fail: 409 (double-click)
    → ok: proceed

  Tầng 3: SELECT trigger_execution
    → RUNNING: 202
    → else:    INSERT RUNNING, proceed

  Tầng 4: mỗi action trong trigger:
    idempotencyKey = SHA256(exec_01JW + actdef_create_coupon + sorted_params)
    INSERT action_execution (key, PROCESSING)
    → conflict: return cached / 202
    → ok: POST /coupons
         ├── response OK  → UPDATE COMPLETED
         ├── response 4xx/5xx → UPDATE FAILED → Saga trigger
         └── timeout      → UPDATE TIMEOUT → Reconciler trigger

[Response path]
  action OK → response_snapshot saved
  trigger_execution → COMPLETED
  SET "result:req_01JWXYZ" = { coupon_id: "cpn_01JW..." } EX 300
  return 200

[Browser]
  200 → update context: couponId = "cpn_01JW..."
  button: LOADING → DONE
```

---

## Tổng hợp

```
Tầng │ Where      │ Key                       │ TTL      │ Chặn case
─────┼────────────┼───────────────────────────┼──────────┼──────────────────────────
1    │ Frontend   │ button state in context   │ session  │ double-click, UI re-render
2    │ Redis      │ "dedup:{requestId}"       │ 60s      │ network retry < 60s
3    │ PostgreSQL │ UNIQUE(execution, trigger)│ forever  │ concurrent requests, race
4    │ PostgreSQL │ SHA256(exec+action+params)│ forever  │ cross-session, same params
```

Tầng cao chặn sớm → ít tốn resource. Tầng thấp chặn muộn hơn nhưng chắc chắn hơn.

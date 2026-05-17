# Execution Context & Redis

## Tại sao cần Redis?

Trong quá trình thực thi scenario, data phát sinh liên tục (UI input, Action output) cần được **đọc/ghi nhanh** để phục vụ real-time flow. Redis đóng vai trò **high-performance data workspace** — không chỉ là cache đơn thuần.

PostgreSQL lưu **snapshot** tại checkpoint (step transition). Redis lưu **live state** trong suốt execution.

---

## 3-Key Model

Mỗi ContextDataItem được lưu qua 3 Redis keys, atomic bằng Lua Script:

### Key C — Data Item (String)
```
Key:   exec:{executionId}:{sourceType}:{sourceId}:{varId}
Value: ContextDataItem JSON
Role:  Single source of truth cho từng data item
```

### Key A — Step Index (Sorted Set)
```
Key:    index:step:{executionId}
Score:  stepOrder (1, 2, 3…)
Member: C key
Role:   "Tất cả data từ step 1 đến N?"
        → ZRANGEBYSCORE index:step:{executionId} 0 N
```

### Key B — Alias Group Index (Set)
```
Key:    group:{executionId}:var:{varId}
Member: C keys có cùng varId
Role:   "Tất cả data có varId = VAR_01JWXYZ?"
        → SMEMBERS group:{executionId}:var:VAR_01JWXYZ
```

---

## ContextDataItem Structure

```json
{
  "key": "exec:exec_01JW:ACTION_OUTPUT:actexec_01JW:VAR_01JWGHI",
  "varId": "VAR_01JWGHI",
  "value": "cpn_01JWXYZ",
  "valueType": "string",
  "version": "1.1",
  "origin": {
    "sourceType": "ACTION_OUTPUT",
    "scenarioId": "scen_01JWAB3CD5EF6GH7IJ",
    "stepId": "step_01JW02",
    "stepOrder": 2,
    "sourceId": "actexec_01JW",
    "sourceName": "actdef_create_coupon",
    "label": "Coupon ID"
  },
  "createdAt": "2026-05-15T10:30:00Z"
}
```

`sourceType`: `UI_INPUT` | `ACTION_OUTPUT`

---

## Lua Script — Atomic Save

3 key types phải được lưu đồng thời để tránh partial state:

```lua
-- save_context_item.lua
local dataKey   = KEYS[1]   -- C key
local stepKey   = KEYS[2]   -- A key
local varKey    = KEYS[3]   -- B key
local value     = ARGV[1]   -- JSON string
local stepOrder = tonumber(ARGV[2])

redis.call('SET',  dataKey,  value)
redis.call('ZADD', stepKey,  stepOrder, dataKey)
redis.call('SADD', varKey,   dataKey)
return 1
```

---

## Two-Phase Commit — DB First Strategy

Mỗi write phải đồng bộ cả PostgreSQL (persistent) và Redis (runtime):

```
BEGIN DB transaction
   │
   ├── Save to PostgreSQL (context JSONB snapshot) ✅
   │
   ├── Save to Redis (3-key model via Lua Script)
   │     ├── OK  → COMMIT
   │     └── FAIL → ROLLBACK DB (Redis vẫn cũ → consistent)
   │
   └── DB FAIL → không touch Redis → không inconsistency
```

**Tại sao DB First, không phải Redis First?**

- PostgreSQL có ACID transaction, ROLLBACK luôn clean
- Redis không có multi-key transaction thật sự
- DB là source of truth — nếu phải chọn 1 nơi consistent, chọn DB
- Worst case Redis mất data: Cache-Aside auto-restore từ DB
- Worst case DB mất data: không recover được từ Redis (volatile)

---

## Cache-Aside Fallback

```
Read request đến
        │
        ▼
   Check Redis ──▶ HIT → trả về ngay
        │
        MISS
        │
        ▼
   Query PostgreSQL (context JSONB snapshot)
        │
        ▼
   Restore 3-key model vào Redis
        │
        ▼
   Trả về kết quả
```

Đảm bảo không service downtime khi Redis restart hoặc mất data.

---

## Cleanup khi Execution hoàn thành

Khi scenario COMPLETED → DEL tất cả 3 key types của execution:

```
DEL exec:{executionId}:*          (C keys)
DEL index:step:{executionId}      (A key)
DEL group:{executionId}:var:*      (B keys)
```

Completed scenario chỉ đọc qua PostgreSQL context JSONB snapshot.

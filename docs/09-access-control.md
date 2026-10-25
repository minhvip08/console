# Access Control (RBAC)

## 3 Tầng kiểm tra quyền

```
User request
      │
      ▼
[Tầng 1] GameScale Console Gateway
          Kiểm tra quyền truy cập menu console
          → upstream check via RoleStore
          → fail: redirect error page
      │
      ▼
[Tầng 2] Console API — RBAC module
          Kiểm tra quyền thực thi scenario cụ thể
          → check scenario_id có trong allowed list không
          → fail: 403 Forbidden
      │
      ▼
[Tầng 3] Per-action permission check
          Mỗi API endpoint đều check quyền trước khi execute
```

---

## RoleStore Integration

**RoleStore** là hệ thống quản lý quyền trung tâm — trả về danh sách scenario IDs mà user được phép thực thi.

```
Console API
      │ cache miss
      ▼
GET /rolestore/users/{userId}/permissions?resource=scenario
      │
      ▼
Response: { "allowed_scenario_ids": ["scen_01JW", "scen_02JW", ...] }
      │
      ▼
Cache vào Redis (TTL-based)
```

---

## Caching Strategy

```
Redis key: sc:user:{userId}:exec
Value:     ["scen_01JW", "scen_02JW", ...]
TTL:       configurable (default: 5 phút)
```

```
Check permission:
  1. SMEMBERS sc:user:{userId}:exec
     → HIT: check scenario_id in set → allow/deny
     → MISS: query RoleStore → cache result → check
```

---

## Permission Check Points

| Endpoint | Check |
|---|---|
| `GET /scenarios` | Chỉ trả về scenarios user có quyền |
| `POST /executions` | Check scenario_id trước khi tạo execution |
| `GET /executions/{id}/steps` | Check execution thuộc về user |
| `POST /executions/{id}/trigger` | Check quyền + check execution ownership |
| `POST /executions/{id}/resume` | Check quyền + check execution ownership |

---

## Cache Invalidation

Khi RoleStore update quyền của user:
- RoleStore gửi webhook → Console API DEL `sc:user:{userId}:exec`
- Hoặc dùng TTL ngắn để tự expire (eventual consistency)


## Shopee Mock Integration

See [mock integration guide](11-shopee-mock-integration.md). Mock enablement and Admin IDs are explicit configuration. Source user grants supplement current scenario permission, including for execution owners. Trusted gateway identity is mandatory on integration start/trigger/read/resume/result endpoints; a body userId cannot substitute at start. Checks occur before cached response or restored context access. Disabled/deleted/expired sources and deleted connections block subsequent requests. Generic Backoffice definition endpoints retain their upstream Admin protection requirement.

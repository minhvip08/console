# Scenario Console — Project Guide

> Integrated console cho Nexon Game Ops: cho phép Technical PM / Operations chạy multi-step scenario (e.g. release coupon, in-game purchase) gọi 3rd-party services qua một điểm truy cập duy nhất.

---

## Repository Layout

```
console/
├── CLAUDE.md                 ← bạn đang đọc
├── plan.md                   ← implementation roadmap
├── HIGH_LEVEL_DESIGN.md      ← design tổng quan (cũ — tham khảo)
├── SCENARIO_DESIGN.md        ← scenario sample
├── REFERENCE.MD              ← reference materials
├── docs/                     ← canonical design docs (source of truth)
│   ├── README.md             ← index
│   ├── 01-architecture.md    ← Clean Architecture + package structure
│   ├── 02-core-concepts.md   ← Scenario / Step / Action / Variable
│   ├── 03-execution-engine.md← Flows: start / step / resume / complete
│   ├── 04-context-redis.md   ← 3-key model + Lua atomic + 2PC
│   ├── 05-idempotency.md     ← 4 tầng chống duplicate
│   ├── 06-saga-compensation.md
│   ├── 07-cdc-reconciliation.md
│   ├── 08-database.md        ← 3NF schema (CANONICAL)
│   ├── 09-access-control.md  ← RBAC + RoleStore
│   ├── 10-aws-infrastructure.md
│   └── flow.md               ← Mermaid diagrams (toàn bộ flow)
└── console/                  ← Spring Boot skeleton hiện tại (sẽ refactor multi-module)
```

**Khi có conflict giữa docs/ và source code:** docs/ là source of truth cho thiết kế; nếu code lệch thì align code về docs, hoặc update docs nếu có quyết định mới.

---

## Tech Stack (canonical)

| | Version |
|---|---|
| Java | 25 |
| Spring Boot | 4.0.6 |
| Build | Gradle (multi-module, settings.gradle) |
| Database | PostgreSQL 15+ (RDS, Multi-AZ) |
| Cache | Redis 7+ (ElastiCache) — single shard cho Lua script |
| Streaming | Kafka 3.5+ (MSK) + Debezium CDC |
| Object store | S3 (claim check khi response > 100KB) |
| Orchestration | EKS + ArgoCD + ECR |

> Nếu nâng/hạ version, update bảng này **và** docs/10-aws-infrastructure.md cùng lúc.

---

## Three Services

Repo này chứa **3 Spring Boot service** chạy độc lập:

| Service | Port | Mục đích | User chính |
|---|---|---|---|
| `console-api` | 8080 | Execution engine — chạy scenario | Technical PM, Operations |
| `backoffice-api` | 8081 | Definition CRUD + Swagger import | Admin |
| `reconciliation-service` | 8082 | Kafka consumer, CDC drift detection | System (no user-facing) |

Cả 3 share infra: cùng PostgreSQL, cùng Redis, cùng MSK. Tách service vì SLO khác nhau (console hot path latency-sensitive, backoffice management chấp nhận chậm hơn, reconciler chạy async).

### Local dev (docker-compose)

```bash
docker compose up -d postgres redis                               # core dev
docker compose --profile full up -d                               # + Kafka/Debezium
docker compose --profile localstack up -d                         # + LocalStack (S3, CW)
docker compose --profile full --profile localstack up -d          # full stack
```

### Backoffice flow — Platform API integration

Backoffice không upload swagger thủ công — Admin chọn service từ một **Platform API** (service thứ 3, internal Nexon catalog) → Platform API trả Swagger YAML → Backoffice parse và build `action_definition` rows (mỗi parameter/output field gen `VAR_ULID`).

```
Admin → Backoffice                 Platform API (3rd party)
  GET /api/platform/services  ───►   list services
  POST /api/platform/imports  ───►   fetch swagger YAML
                              ◄───   raw YAML
  parse + gen VAR_ULIDs
  INSERT service (if new) + action_definition rows
```

Trong demo này dùng `MockPlatformApiClient` với sample YAML embedded ở `backoffice-api/src/main/resources/mock-platform/`. Real impl sẽ HTTP call qua service-to-service auth.

---

## Architecture Decisions — Quick Reference

| Decision | Rationale | Doc |
|---|---|---|
| **No "Block" layer** | Step trực tiếp chứa UI + Actions, kết nối qua Variable. Đơn giản hóa hierarchy. | 02 |
| **VAR_ULID thay alias string** | Frontend tự gen `VAR_{ULID}` khi tạo widget. Tránh collision/typo/rename pain. | 02 |
| **action_execution.status: PENDING/PROCESSING/COMPLETED/FAILED/TIMEOUT** | Split FAILED (4xx/5xx) vs TIMEOUT (no response) để route đúng handler. | 03, 08 |
| **FAILED → Saga ngay, TIMEOUT → CDC+Reconciler** | Known vs unknown outcome. Saga ms latency; Reconciler async verify 3rd party. | 06, 07 |
| **Redis 3-key model + Lua atomic** | C (data) + A (step ZSET) + B (varId Set) ghi atomic 1 round trip. | 04 |
| **DB First 2PC** | PostgreSQL ACID là source of truth; Redis rebuild được từ DB qua Cache-Aside. | 04 |
| **4-tầng idempotency** | UI lock → Redis SETNX requestId → DB UNIQUE trigger_execution → SHA256 action key. | 05 |
| **Orchestration Saga** | Console là orchestrator, gọi compensation actions theo `step_order DESC`. | 06 |
| **CDC qua Debezium** | PostgreSQL WAL → MSK → Reconciliation service. Catch ghost transaction + state drift. | 07 |
| **Compensation key fields**: `required_var_ids: TEXT[]` (VAR_ULIDs), KHÔNG phải `required_aliases` | Đã migrate alias → varId hoàn toàn. | 06, 08 |

---

## Key Schemas — Cheatsheet

### action_execution (hot table)
```
id, scenario_execution_id, trigger_execution_id, action_definition_id,
status (PENDING|PROCESSING|COMPLETED|FAILED|TIMEOUT),
idempotency_key VARCHAR NOT NULL UNIQUE,   -- SHA256(execId + actDefId + sorted_params)
response_snapshot JSONB, s3_ref VARCHAR,
error_message, started_at, completed_at, ...
```

### trigger_execution (Tầng 3 idempotency)
```
id, scenario_execution_id, step_trigger_id,
status (PENDING|RUNNING|COMPLETED|FAILED),
UNIQUE (scenario_execution_id, step_trigger_id)
```

### saga_compensation_log
```
id, scenario_execution_id, action_execution_id, action_definition_id,
step_order INT,   -- denorm cho ORDER BY DESC hot path
status (PENDING|COMPENSATING|COMPENSATED|COMPENSATION_FAILED),
context_snapshot JSONB,   -- variable values tại thời điểm action chạy
...
```

### Redis keys (toàn bộ namespace)
```
exec:{execId}:{sourceType}:{srcId}:{varId}     ← C, String ContextDataItem JSON
index:step:{execId}                            ← A, ZSET (score=stepOrder)
group:{execId}:var:{varId}                     ← B, Set of C keys
dedup:{requestId}             EX 60            ← Tầng 2
result:{requestId}            EX 300           ← cached response
sc:user:{userId}:exec         EX 300           ← RBAC cache
```

Xem chi tiết: 04-context-redis.md, 08-database.md, flow.md §12.

---

## Coding Conventions

### Package layout — Vertical Slice by Feature
```
src/main/java/com/simi/{service}/
├── feature/
│   ├── executor/                 (console-api)
│   │   ├── scenario_start/
│   │   ├── step_execute/
│   │   ├── action_execute/
│   │   └── scenario_resume/
│   ├── context/                  (console-api)
│   │   ├── context_save/
│   │   ├── context_get/
│   │   └── context_restore/
│   ├── compensation/             (console-api)
│   └── management/               (backoffice-api)
│       ├── scenario_crud/
│       ├── action_crud/
│       └── swagger_import/
└── infrastructure/               ← shared
    ├── repository/postgresql/
    ├── repository/redis/
    ├── repository/s3/
    └── client/external_api/
```

Mỗi feature slice = full stack: Controller → UseCase → DataProvider. Team có thể work parallel trên các feature khác nhau mà không đụng nhau.

### Clean Architecture dependency rule
```
Frameworks ── depends on ──▶ Adapters ── depends on ──▶ Use Cases ── depends on ──▶ Entities
```
Inner layer KHÔNG import outer layer. Use case nhận `port` interface, infrastructure implement port.

### Naming
- Tables/columns: `snake_case`, plural-ish theo Spring Data convention.
- Java class: `PascalCase`, methods `camelCase`.
- ULID prefix: `scen_`, `step_`, `actdef_`, `actexec_`, `exec_`, `trigger_`, `actexec_`, `req_`, `widget_`, `VAR_`.
- Variable ID: **VAR_{26-char ULID}** — không dùng alias string ở DB hay API.

### Error handling
- 4xx/5xx từ 3rd party → `action_execution.status = FAILED` + Saga.
- Timeout / network drop → `TIMEOUT` + Reconciler.
- Đừng catch-and-swallow; throw lên use case layer để pipeline biết.

---

## Working with the Docs

**Khi sửa code động chạm schema/flow:**
1. Check docs/ trước — schema canonical ở 08, flow canonical ở 03/06/07.
2. Update docs nếu có quyết định thiết kế mới — **đừng để code drift khỏi docs**.
3. Update flow.md (Mermaid) song song nếu flow thay đổi.

**Khi nào dùng JSONB vs normalize:**
- JSONB: structure variable theo type (widget, parameters), không bao giờ query theo field bên trong.
- Normalize: cần FK, cần ORDER BY, cần index theo field cụ thể (e.g. `step_trigger`, `step_trigger_action`).

---

## Implementation Plan

Xem [plan.md](plan.md) — phased roadmap, mỗi phase có acceptance criteria.

---

## What NOT to Do

- ❌ Đừng tạo lại tầng `block_definition` — đã loại bỏ có chủ đích (xem 02).
- ❌ Đừng dùng alias string ở DB hoặc API — chỉ dùng `VAR_ULID`.
- ❌ Đừng skip layer Tầng 4 idempotency dù đã có Tầng 3 — Tầng 4 chặn cross-session retry.
- ❌ Đừng gọi 3rd party trực tiếp từ Controller — phải qua Service Adapter Layer.
- ❌ Đừng commit JSONB cho `step_trigger` config — đã normalize sang table.
- ❌ Đừng để `TIMEOUT` rớt vào Saga path — chỉ `FAILED` mới trigger Saga.
- ❌ Đừng dùng Redis cluster mode khi chưa setup hash tag cho 3-key model — Lua script cần cùng slot.

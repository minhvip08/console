# Backend Interview Prep - Scenario Console

# 📋 Tổng quan dự án (Project Overview)

## Scenario Console là gì?

**Scenario Console** là một nền tảng **통합 콘솔 (Integrated Console)** được xây dựng để giải quyết vấn đề phân mảnh công cụ trong hệ sinh thái Nexon. Thay vì phải thao tác trên nhiều console khác nhau cho từng platform service, người dùng (Technical PM, Game Operations) có thể thực hiện mọi thao tác qua **một điểm truy cập duy nhất** dựa trên luồng kịch bản (scenario-based flow).

**Vấn đề giải quyết:**

- Quá nhiều platform console riêng lẻ → người vận hành phải chuyển đổi liên tục
- Technical PM mất thời gian vào công việc cấu hình lặp lại thay vì tập trung vào planning
- Quy trình launching game mới phức tạp, dễ sai sót

**Giải pháp:**

- **Scenario-based Integrated Console**: Mọi tác vụ được tổ chức thành scenario → step → action, UI và action kết nối qua variable
- **Hierarchical Structure**: Scenario (flow) / Step (UI elements + Actions) / Action (atomic operation) / Variable (kết nối UI với Action)
- **Role-based Access Control**: Bắt đầu từ Technical PM → dần chuyển giao cho Operations team

---

# 🏗️ System Architecture

## High-Level Architecture

Hệ thống gồm 4 thành phần chính:

1. **User Domain** (Console Front / Backoffice Front)
2. **Backend** (GameScale Console Gateway → Console API / Backoffice API)
3. **Data Store** (PostgreSQL + Redis)
4. **External Services** (Platform APIs + RoleStore)

## AWS Infrastructure

- **Route53 (inface GW)** → **ALB** → **EKS Ingress** → Services
- **Amazon EKS Cluster**: Console Service + Backoffice Service
- **Amazon RDS (PostgreSQL)**: Persistent data
- **ElastiCache (Redis)**: Execution context (runtime data)
- **Amazon S3**: Static resources & uploads
- **Amazon ECR** → **ArgoCD/Helm**: CI/CD pipeline
- **CloudWatch**: Monitoring & logging

## Application Architecture

Cả Console và Backoffice đều áp dụng **Clean Architecture**:

1. **Entity (Domain Layer)**: Scenario, Step, Action - core business rules
2. **Use Case (Application Layer)**: Execution engine, Context Manager - scenario execution logic
3. **Interface Adapter**: Controller, Presenter, Repository Interface - format conversion
4. **Frameworks & Drivers (Infrastructure)**: Spring Boot, JPA, Redis Client, S3 SDK

**Package Structure** theo mô hình **Vertical Slice by Feature**:

- `feature/executor/{feature_name}/` — Scenario Execution features
- `feature/context/{feature_name}/` — Runtime Context Management
- `feature/management/{feature_name}/` — Component CRUD (Backoffice)
- `infrastructure/` — Shared Repository implementations (PostgreSQL, Redis, S3, External APIs)

---

# 🧩 Core Concepts & Hierarchical Structure

## Scenario → Step → Action (via Variable)

| **Concept** | **Mô tả** | **Ví dụ** |
| --- | --- | --- |
| **Scenario** | Một luồng công việc hoàn chỉnh, gồm nhiều Step tuần tự | “Đăng ký coupon đơn lẻ” |
| **Step** | Một bước trong scenario — chứa UI elements và Actions | “Nhập thông tin cơ bản coupon” |
| **Variable** | Kết nối output của widget với input của action — ID do frontend tự gen | `VAR_01JWXYZ` |
| **Action** | Thao tác nguyên tử gọi External API | POST /events/{eventNo}/coupons |

## Console vs Backoffice

- **Backoffice**: Quản lý (CRUD) scenario definitions, block definitions, action definitions, dataset
- **Console**: Thực thi scenario — user chọn scenario → tiến hành từng step → nhập dữ liệu → trigger action → hoàn thành

## Scenario Engine

- Điều phối toàn bộ luồng thực thi scenario
- Quản lý trạng thái: IN_PROGRESS, COMPLETED
- Ghi audit log cho mọi thao tác
- Kiểm tra quyền thực thi trước mỗi thao tác

---

# 🗄️ Database Design (PostgreSQL)

## Entity Overview

### Definition Tables (Backoffice quản lý)

| **Table** | **Mô tả** |
| --- | --- |
| `scenario_definition` | Metadata + step list |
| `step_definition` | ui JSONB (widget layout với outputVarId) |
| `step_trigger` | Trigger config (button_click, on_load, on_change) |
| `step_trigger_action` | Mapping trigger → actions theo thứ tự |
| `action_definition` | API endpoint, parameters (varId), output (varId) |
| `dataset` / `dataset_item` / `dataset_definition` | Quản lý dữ liệu preset (email, contact info, etc.) |

### Runtime Tables (Console ghi lại)

| **Table** | **Mô tả** |
| --- | --- |
| `scenario_execution` | Mỗi lần thực thi scenario — chứa `progress` (JSONB), `context` (JSONB snapshot), status, started_by, timestamps |
| `scenario_execution_event_log` | Event log cho mỗi step transition (STEP_COMPLETED, etc.) |
| `action_execution` | Chi tiết từng lần gọi action (request, response, elapsed_time, status) |
| `action_execution_log` | Bảng idempotency — ngăn duplicate action execution |
| `audit_log` | Ai / khi nào / làm gì — toàn bộ audit trail |

## Thiết kế JSONB

- `step_definition.ui`: Widget layout — elements với `outputVarId: VAR_ULID`
- `action_definition.parameters`: API endpoint, HTTP method, parameter definitions với `varId: VAR_ULID`
- `action_definition.output`: Output field → `varId` mapping
- `scenario_execution.context`: Full snapshot của execution context (dùng để restore khi resume)

---

# ⚡ Execution Context & Redis (3-Key Model)

## Tại sao cần Redis?

Trong quá trình thực thi scenario, dữ liệu phát sinh liên tục (UI input, Action output) cần được **đọc/ghi nhanh** để phục vụ real-time flow. Redis đóng vai trò **“High-performance Data Workspace”**, không chỉ là cache đơn thuần.

## 3-Key Model

### Key Type C — Data Item (String)

- **Key**: `exec:{executionId}:{sourceType}:{sourceId}:{varId}`
- **Value**: `ContextDataItem` JSON
- **Vai trò**: Lưu trữ từng data item riêng lẻ — Single Source of Truth cho runtime

### Key Type A — Step Index (Sorted Set / ZSET)

- **Key**: `index:step:{executionId}`
- **Score**: `stepOrder` (1, 2, 3…)
- **Member**: Data item key (C key)
- **Vai trò**: Trả lời “tất cả dữ liệu từ step 1 đến step N là gì?” → dùng `ZRANGEBYSCORE`

### Key Type B — Variable Group Index (Set)

- **Key**: `group:{executionId}:var:{varId}`
- **Member**: Data item keys có cùng varId
- **Vai trò**: Trả lời “tất cả dữ liệu có varId = VAR_01JWXYZ là gì?” → dùng `SMEMBERS`

## ContextDataItem Structure

```
{
  key: string,           // Redis key đầy đủ
  varId: string,         // Variable ID (VAR_ULID) để tìm kiếm và bind
  value: any,            // Giá trị thực
  valueType: "string" | "number" | "boolean" | "object" | "array",
  version: "1.1",
  origin: {
    sourceType: "UI_INPUT" | "ACTION_OUTPUT",
    scenarioId, stepId, stepOrder,
    sourceId, sourceName, label
  },
  createdAt: string      // ISO 8601
}
```

## Lua Script — Atomic Save

Để đảm bảo 3 key types được lưu đồng thời (atomic), sử dụng **Lua Script**:

```
-- save_context_item.lua
local dataKey  = KEYS[1]   -- C key
local stepKey  = KEYS[2]   -- A key
local aliasKey = KEYS[3]   -- B key
local value     = ARGV[1]  -- JSON
local stepOrder = tonumber(ARGV[2])

redis.call('SET',  dataKey, value)
redis.call('ZADD', stepKey, stepOrder, dataKey)
redis.call('SADD', aliasKey, dataKey)
return 1
```

## Cache-Aside Fallback

Khi Redis key không tồn tại (do restart, failure) → query từ PostgreSQL (`scenario_execution.context` JSONB) → reload vào Redis → trả về kết quả. Đảm bảo **không service downtime**.

---

# 🔄 Scenario Execution Flows

## Flow 1: Bắt đầu Scenario mới

1. User chọn scenario từ danh sách (đã check quyền via RoleStore)
2. Tạo `scenario_execution` record (status = IN_PROGRESS)
3. Load DataSet liên quan
4. Trả về scenario info + step list
5. Auto-select step đầu tiên

## Flow 2: Tiến hành từng Step

**Khi user nhấn “Action Execute” button:**

1. Frontend gửi `triggerId` + `inputs` data (varId: value)
2. Backend transform `inputs` → `ContextDataItem`
3. Query previous steps data từ Redis (`ZRANGEBYSCORE`)
4. **Merge** previous data + current inputs (varId-based, current wins on conflict)
5. Identify mapped actions từ `step_trigger_action` (N actions, ordered)
6. **Action Loop**: Execute each action sequentially
- Generate idempotency key
- Bind API parameters via varId matching từ Execution Context
- Call Target Platform API
- Record result in `action_execution`
1. Transform successful outputs → `ContextDataItem`
2. Save to Redis + PostgreSQL (2PC pattern)

**Khi user nhấn “Next” button:**

1. Validate required fields
2. Save current step data (UI inputs + successful action outputs)
3. **2-Phase Commit**: DB transaction BEGIN → save to PostgreSQL → save to Redis → COMMIT
4. Move pointer to next step

## Flow 3: Resume (Tiếp tục scenario đang dở)

1. Load `scenario_execution` record từ PostgreSQL
2. Deserialize `context` JSONB → ContextDataItem list
3. **Restore to Redis**: MSET (data items) + SADD (alias index) + ZADD (step index)
4. Load DataSet
5. Return resumable step info + restored context

## Flow 4: Hoàn thành Scenario

1. Verify tất cả required blocks đã được thực thi
2. Final context snapshot → save to PostgreSQL
3. Update status = COMPLETED
4. **Cleanup Redis**: Delete all 3-Key model keys for this execution
5. Terminated scenario chỉ có thể xem qua snapshot (read-only)

---

# 🛡️ Idempotency (Chống trùng lặp Action)

## Tại sao cần?

Action thực hiện CUD operations trên platform APIs. Duplicate call có thể gây hỏng dữ liệu nghiêm trọng (tạo 2 coupon, xóa 2 lần…).

## Idempotency Key Generation

```
SHA256(action_definition_id + sorted_required_parameters_json)
```

- Chỉ dùng **required parameters** — optional parameters khác nhau = call khác nhau
- Sort keys trước khi hash → đảm bảo deterministic

## MVP Approach: DB UNIQUE Constraint

1. Generate idempotency key
2. `INSERT INTO action_execution_log (idempotency_key, status='PROCESSING')`
3. **INSERT thành công** → First call → Execute action → UPDATE status
4. **INSERT thất bại** (UNIQUE violation) → Duplicate detected:
- `COMPLETED` → Return cached response
- `PROCESSING` → Return “currently processing”
- `FAILED` → DELETE old record → Allow retry

## Improved Approach: Redis Distributed Lock

1. `SETNX idempotency_key 'PROCESSING' EX timeout` (atomic lock)
2. **SETNX = 1** → Lock acquired → Execute action → Record to DB → `DEL` key
3. **SETNX = 0** → Lock failed → Check DB for completed result → Return cached or “processing”

**Lợi ích**: Giải quyết race condition khi nhiều request đồng thời.

---

# 💾 Data Consistency — Two-Phase Commit (2PC)

## Vấn đề

Mỗi thao tác cần lưu vào **cả 2 nơi**: PostgreSQL (persistent) + Redis (runtime). Nếu 1 nơi thất bại → data inconsistency.

## Giải pháp: “DB First” Strategy

1. `BEGIN` DB transaction
2. Save to PostgreSQL (progress + context JSONB snapshot)
3. Save to Redis (3-Key model via Lua Script)
4. **Cả 2 thành công** → `COMMIT`
5. **Redis thất bại** → `ROLLBACK` DB
6. **DB thất bại** → Không touch Redis → Không inconsistency

## Fallback khi Redis mất dữ liệu

- PostgreSQL luôn có **full snapshot** trong `scenario_execution.context`
- Resume flow tự động restore Redis từ DB snapshot
- Cache-Aside pattern: read miss → load from DB → populate Redis

---

# 🔐 Access Control (RBAC)

## Cấu trúc phân quyền

1. **GameScale Console Gateway**: Kiểm tra quyền truy cập menu console (upstream check via RoleStore)
2. **Console API (RBAC module)**: Kiểm tra quyền thực thi scenario cụ thể
3. **RoleStore**: Hệ thống quản lý quyền trung tâm — trả về danh sách scenario ID mà user được phép thực thi

## Caching Strategy

- Redis key: `sc:user:{userId}:exec` → Cache danh sách scenario IDs user có quyền
- TTL-based expiry
- Cache miss → Query RoleStore API → Cache result

## Permission Check Points

Mỗi API endpoint đều check quyền:

- List scenarios → chỉ trả về scenarios user có quyền
- Start execution → check quyền
- Get step info → check quyền
- Execute action → check quyền
- Resume scenario → check quyền

---

# 🔀 Variable-Based Data Flow

## Cách hoạt động

1. **Frontend**: Collect user inputs → gửi dưới dạng `{varId: value}` pairs
2. **Console API**: Store varId-based data chunks vào Execution Context
3. **Khi trigger Action**: Merge all previous steps data + current step data (varId-based, current wins)
4. **Action Adapter**: Dùng `varId` matching để bind API parameters từ merged Execution Context
5. **Call Platform API** với bound parameters

## Lợi ích của Variable (VAR_ULID) so với Alias

1. **Không collision**: VAR_01JW ≠ VAR_02JW — không bao giờ trùng dù cùng tên
2. **Không typo**: ID do hệ thống gen, không người nhập
3. **Rename tự do**: Đổi label widget không ảnh hưởng VAR_ID
4. **Explicit connection**: Kéo thả kết nối widget → action là explicit, không implicit qua string matching

→ **Giải pháp tương lai**: **Ruleset** — external configuration-based data transformation layer (“data transformation blueprint”)

---

# 📊 Error Handling

## Error Code Convention

```
{service-code:4}-{feature-code:2}{feature-error-code:3}
```

- `scenario-console-api`: Service code `9800`
- `scenario-console-admin-api`: Service code `9801`

## Xử lý lỗi theo level

- **Permission errors** (401): Redirect → error popup
- **Validation errors** (400): Required fields missing → popup hướng dẫn
- **Action execution errors**: Ghi vào `action_execution_log` status=FAILED, cho phép retry
- **System errors**: DB/Redis failure → 2PC rollback, return error to client

---

# 🔧 Git Branch Strategy

## Pre-Go-Live (MVP đến June 2026)

- `feature/*` → `develop` → Auto-deploy to Dev server
- `release/M1` (from develop) → Deploy to Test server → QA
- QA passed → Merge to `master` → Tag → Deploy to Live

## Post-Go-Live (6-Branch Model)

| **Branch** | **Role** | **Source** | **Target** |
| --- | --- | --- | --- |
| `master` | Final release version (Live deployment source) | release/*, hotfix/* | - |
| `develop` | Continuous Integration, preparing next version | master, release/*, hotfix/* | deploy_dev |
| `deploy_dev` | Dev environment deployment only | develop | - |
| `release/*` | Independent release unit (QA + stabilization) | master | master, develop |
| `feature/*` | Individual feature development | develop | develop |
| `hotfix/*` | Urgent live bug fixes | master | master, develop |

---

# 🏢 Team Structure & Collaboration

- **NK (Korea HQ)**: Core design/spec, architecture, code review, QA environment, CI/CD, deployment
- **NDVN (Vietnam)**: Backend API implementation, Backoffice UI development, Console API implementation, bug fixes
- **Collaboration**: Daily meetings, PR-based code review, shared Notion workspace

---

# 🛣️ Roadmap

| **Phase** | **Timeline** | **Key Features** |
| --- | --- | --- |
| Phase 1 — MVP | ~ June 2026 | Scenario/Step/Action CRUD, Execution Engine, Context Management, RBAC, Audit Log |
| Phase 2 — Expansion | Jul-Dec 2026 | Low-code Builder (Drag&Drop), AI recommendations, Data Preset/Snapshot, Parallel execution |
| Phase 3 — AI Builder | 2027 Q1+ | Natural language scenario creation, Auto UI generation, Failure auto-analysis, Personalized recommendations |

---

# 🎤 Behavioral Interview Questions (STAR Method)

## Dựa trên kinh nghiệm thực tế của bạn với Scenario Console

- **Tell me about a complex technical project you’ve worked on.**
- **Situation**: Tại Nexon, các platform service có console riêng lẻ, gây khó khăn cho game operations team và technical PM.
- **Task**: Thiết kế và phát triển backend cho Scenario Console — một integrated console cho phép thực thi các workflow phức tạp qua scenario-based approach.
- **Action**:
    - Thiết kế hierarchical data model (Scenario → Step → Action) với PostgreSQL JSONB
    - Xây dựng 3-Key Model trên Redis cho execution context (high-performance runtime data)
    - Implement 2-Phase Commit pattern để đảm bảo data consistency giữa DB và Redis
    - Thiết kế idempotency mechanism để ngăn duplicate action execution
    - Áp dụng Clean Architecture với vertical slice pattern
- **Result**: Hệ thống có khả năng xử lý scenario execution với real-time data flow, hỗ trợ resume/restore, và đảm bảo data integrity qua 2PC pattern.
- **Describe a time you made a difficult technical decision.**
- **Situation**: Cần quyết định cách quản lý runtime data — lưu tất cả vào DB (đơn giản) hay dùng Redis + DB hybrid (phức tạp hơn).
- **Task**: Chọn giải pháp cân bằng giữa performance, reliability, và complexity.
- **Action**: Phân tích yêu cầu: execution context cần read/write cực nhanh trong runtime, nhưng cũng cần persistent cho resume. Đề xuất hybrid approach: Redis 3-Key Model cho runtime + PostgreSQL JSONB snapshot cho persistence, kết nối qua 2PC pattern với “DB First” strategy và Cache-Aside fallback.
- **Result**: Hệ thống đạt được cả fast runtime performance lẫn data durability. Khi Redis mất dữ liệu, tự động restore từ DB snapshot mà không ảnh hưởng service.
- **Tell me about a time you had to design a system to prevent data inconsistency.**
- **Situation**: Action execution gọi external platform APIs (create coupon, etc.). Duplicate calls có thể gây data corruption nghiêm trọng.
- **Task**: Thiết kế mechanism đảm bảo mỗi action chỉ execute đúng 1 lần, ngay cả khi có concurrent requests.
- **Action**:
    - MVP: Dùng DB UNIQUE constraint trên idempotency key (SHA256 of action_id + sorted required params)
    - Improved: Thêm Redis SETNX distributed lock để giải quyết race condition
    - Thiết kế 3 trạng thái: PROCESSING → COMPLETED/FAILED, với retry logic cho FAILED cases
- **Result**: Zero duplicate action executions trong production. Khi user double-click hoặc network retry, system tự động trả cached response thay vì execute lại.
- **Describe a time you worked with a cross-functional or distributed team.**
- **Situation**: Dự án Scenario Console có 2 team: NK (Korea HQ) phụ trách core design/spec và NDVN (Vietnam) phụ trách implementation.
- **Task**: Đảm bảo alignment giữa 2 team ở 2 timezone, ngôn ngữ khác nhau.
- **Action**:
    - Tham gia daily meetings để sync progress
    - Sử dụng Notion workspace chung cho tất cả specs, ADR, và documentation
    - PR-based code review process
    - Chủ động propose DB schema changes và API design cải tiến (ví dụ: thêm GID/Name cho scenario_execution, cải tiến block definition schema)
- **Result**: Deliver đúng timeline MVP (June 2026), code quality được maintain qua review process, và team trust được xây dựng qua proactive communication.
- **Tell me about a time you identified and proposed improvements.**
- **Situation**: Trong quá trình phát triển, nhận thấy alias-based 1:1 mapping có nhiều hạn chế (alias collision, no data transform, UI-API coupling).
- **Task**: Phân tích rõ các limitations và đề xuất hướng cải tiến.
- **Action**:
    - Document chi tiết 4 limitations của MVP approach với concrete examples
    - Đề xuất Ruleset concept — external configuration cho data transformation
    - Vạch roadmap: Raw JSON editor → GUI Builder → AI-based automation
    - Present cho team và management để align on future direction
- **Result**: Team đồng ý adopt Ruleset vào Phase 2 roadmap. MVP vẫn ship đúng hạn với workaround hợp lý, đồng thời có clear vision cho evolution.

---

# 🔥 Deep Dive Interview Questions

## PostgreSQL, JSONB, và Fault Tolerance

- **Tại sao chọn PostgreSQL cho project này mà không phải MySQL hay NoSQL?**
    
    **1. JSONB native support — Hybrid Relational + Document model**
    
- Project cần lưu cả **structured data** (scenario metadata, user info, audit log) lẫn **semi-structured data** (scenario definition schema, block UI layout, execution context snapshot)
- PostgreSQL cho phép **dùng 1 database duy nhất** cho cả 2 nhu cầu: relational tables cho structured + JSONB columns cho semi-structured
- Nếu dùng MySQL: không có JSONB (chỉ có JSON text), không index được nested fields hiệu quả
- Nếu dùng MongoDB: mất relational integrity (foreign keys, transactions), phải tự manage consistency
    
    **2. ACID Transactions mạnh mẽ**
    
- Scenario execution cần **strong consistency**: tạo execution record + save progress + log event phải trong cùng 1 transaction
- 2PC pattern (DB First strategy) yêu cầu DB hỗ trợ `BEGIN/COMMIT/ROLLBACK` đáng tin cậy
- PostgreSQL có **MVCC (Multi-Version Concurrency Control)** — read không block write, phù hợp cho concurrent scenario executions
    
    **3. GIN Index cho JSONB**
    
- Hỗ trợ tạo **GIN index** trên JSONB columns để query nested fields nhanh hơn
- Ví dụ: `CREATE INDEX idx_context ON scenario_execution USING GIN (context jsonb_path_ops);`
- Cho phép query kiểu `WHERE context @> '{"alias": "couponName"}'` với index scan thay vì full table scan
    
    **4. Mature ecosystem**
    
- Tích hợp tốt với Spring Boot + JPA/Hibernate
- AWS RDS managed service — auto backup, failover, scaling
- Extension ecosystem: pg_stat_statements (performance), pgcrypto (encryption)
- **JSONB giúp gì trong project? Tại sao không normalize hết thành relational tables?**
    
    **JSONB giải quyết vấn đề Schema Evolution**
    
    **Vấn đề nếu normalize hoàn toàn:**
    
- `scenario_definition.schema` chứa danh sách steps, mỗi step có danh sách blocks, mỗi block có trigger-action mappings
- Nếu normalize: cần 5-6 tables chỉ cho definition (scenario_step, step_block_map, block_trigger, trigger_action_map…)
- Mỗi lần thay đổi structure (thêm field mới vào step, đổi block layout) → cần **DB migration** → downtime risk
- Với JSONB: thay đổi structure chỉ cần update JSON schema validation ở application layer → **zero migration**
    
    **Cụ thể trong project:**
    
1. **`step_definition.ui`** (JSONB)
    - Widget layout definition — JSON tree structure tự nhiên cho UI hierarchy
    - Mỗi widget có `outputVarId: VAR_ULID` — structure có thể evolve mà không cần ALTER TABLE
2. **`action_definition.parameters`** (JSONB)
    - API endpoint, HTTP method, parameter definitions với `varId: VAR_ULID`
    - Mỗi platform API có parameter list khác nhau → JSONB cho phép **flexible parameter definition**
3. **`action_definition.output`** (JSONB)
    - Output field → `varId` mapping cho mỗi action
5. **`scenario_execution.context`** (JSONB)
**Tóm lại**: JSONB dùng cho data có **schema không cố định, nested/hierarchical, hoặc chỉ cần read as-a-whole** (không cần JOIN từng field). Relational tables dùng cho data cần **query, filter, aggregate, foreign key relationship** (scenario metadata, execution records, audit logs).
    - Full snapshot của runtime context — dùng để restore khi resume
    - Structure phụ thuộc vào scenario đang chạy → **dynamic schema per execution**
- **Performance của JSONB trong project — có vấn đề gì không?**
    
    **Ưu điểm performance:**
    
- JSONB lưu dạng **binary** (không phải text) → parse nhanh hơn JSON type
- Hỗ trợ **GIN index** → query nested fields với index scan
- Hỗ trợ **partial update** qua `jsonb_set()` — không cần rewrite toàn bộ document
    
    **Nhược điểm và cách xử lý:**
    
1. **Write amplification**: Mỗi lần update 1 field trong JSONB → PostgreSQL rewrite toàn bộ row (do MVCC)
    - *Trong project*: `scenario_execution.context` snapshot có thể lớn (nhiều steps, nhiều data items)
    - *Giải pháp*: Chỉ write snapshot tại **checkpoint moments** (step transition, save draft) — không write mỗi keystroke. Runtime reads/writes đi qua **Redis** (O(1) per item), DB chỉ nhận periodic snapshots
2. **Query performance trên nested fields kém hơn relational**
    - *Trong project*: Không cần query deep nested fields thường xuyên. JSONB columns chủ yếu được **read as-a-whole** (load definition → parse in application) hoặc query top-level keys
    - *Giải pháp*: Nếu cần query frequently → extract thành relational column. Ví dụ: `scenario_execution.status` là relational column, không nằm trong JSONB
3. **Size limit**: JSONB field tối đa ~1GB, nhưng practical limit thấp hơn nhiều
**Kết luận**: JSONB performance hoàn toàn phù hợp cho use case này vì:
    - *Trong project*: Context snapshot cho 1 execution thường < 1MB → không vấn đề
- Definition data: write ít (Backoffice tạo/update), read nhiều (Console load)
- Context snapshot: write periodic (step transitions), read chỉ khi resume
- Heavy runtime I/O đi qua Redis, không qua JSONB
- **Bug scenario: Action gửi request đến external service thành công nhưng không nhận được response — xử lý thế nào?**
    
    **Scenario cụ thể:**
    
1. Console gọi Action “Tạo coupon” → gửi POST request đến Platform Coupon API
2. Platform API **nhận và xử lý thành công** (coupon đã được tạo)
3. Nhưng **response bị mất** (network timeout, connection reset, service crash trước khi trả response)
4. Console nhận timeout error → đánh dấu action = FAILED
5. User nhấn retry → gửi lại request → **coupon bị tạo lần thứ 2!** 💥
    
    **Đây là classic “at-least-once delivery” problem**
    
    **Cách xử lý trong project:**
    
    **Layer 1 — Idempotency Key (Console side)**
    
- Mỗi action execution có idempotency key: `SHA256(action_definition_id + sorted_required_params)`
- Khi retry, idempotency key **giống hệt** lần trước
- `action_execution_log` kiểm tra: key đã tồn tại?
    - Nếu status = COMPLETED → return cached response, không gọi lại
    - Nếu status = PROCESSING → return “đang xử lý”
    - Nếu status = FAILED → cho phép retry
- **Vấn đề**: Lần đầu status = PROCESSING, nhưng response mất → status vẫn PROCESSING mãi → **stuck state**
    
    **Layer 2 — Timeout + Cleanup (xử lý stuck state)**
    
- PROCESSING records có **TTL/timeout** (ví dụ: 5 phút)
- Background job kiểm tra: PROCESSING quá timeout → chuyển sang FAILED → cho phép retry
- Hoặc: khi user retry, nếu PROCESSING record quá timeout → override + retry
    
    **Layer 3 — Platform API side Idempotency (nếu hỗ trợ)**
    
- Gửi idempotency key trong HTTP header (`X-Idempotency-Key`)
- Platform API tự kiểm tra: key đã xử lý rồi → return cached result
- **Đây là giải pháp tốt nhất** nhưng phụ thuộc vào platform API có hỗ trợ hay không
    
    **Layer 4 — Compensation / Manual Rollback**
    
- Nếu platform API **không hỗ trợ idempotency** và **không có delete/cancel API**:
    - Ghi log chi tiết vào `action_execution` (request params, timestamp)
    - Alert admin để manual check và cleanup
    - Trong audit log, đánh dấu “potential duplicate”
- Nếu platform API có cancel/delete API → implement **compensating transaction**: detect duplicate → gọi cancel API
- **Bug scenario: Gửi N actions tuần tự, action thứ 3 fail — các action trước đã gửi đi rồi, không rollback được**
    
    **Scenario cụ thể:**
    
- Block có 5 actions cần execute tuần tự: A1 → A2 → A3 → A4 → A5
- A1 ✅ (tạo event thành công)
- A2 ✅ (gán coupon thành công)
- A3 ❌ (gửi notification fail)
- A1 và A2 đã execute trên external services → **không thể rollback**
    
    **Tại sao không dùng distributed transaction (2PC/Saga) cho external APIs?**
    
- External platform APIs **không hỗ trợ distributed transaction protocol**
- Không phải tất cả API đều có reverse/cancel operation
- Distributed transaction across multiple services cực kỳ phức tạp và brittle
    
    **Cách xử lý trong project (MVP):**
    
    **1. Fail-Fast + Partial Success Model**
    
- Khi A3 fail → **dừng ngay**, không execute A4, A5
- Record trạng thái: A1=COMPLETED, A2=COMPLETED, A3=FAILED, A4=NOT_STARTED, A5=NOT_STARTED
- Ghi chi tiết vào `action_execution` table + `action_execution_log`
    
    **2. Selective Retry**
    
- User có thể retry **chỉ action bị fail** (A3) thay vì retry toàn bộ
- Idempotency key đảm bảo A1, A2 không bị execute lại
- Khi A3 retry thành công → tiếp tục A4, A5
    
    **3. Compensating Actions (Phase 2 roadmap)**
    
- Định nghĩa **reverse action** cho mỗi action (nếu platform API hỗ trợ)
- Ví dụ: A1 = “Tạo event” → Reverse A1 = “Xóa event”
- Khi fail → auto-execute reverse actions theo thứ tự ngược: Reverse A2 → Reverse A1
- Đây là **Saga Pattern (Compensating Transaction)**
    
    **4. Audit Trail cho Manual Recovery**
    
- Mọi action execution đều có full log: request, response, timestamp, status
- Admin có thể xem chính xác những gì đã xảy ra và manually fix nếu cần
- `scenario_execution_event_log` ghi lại toàn bộ state transitions
    
    **Interview tip**: Thừa nhận đây là trade-off có chủ đích. MVP ưu tiên **simplicity + auditability** (Fail-Fast + Retry + Full Logging). Saga pattern planned cho Phase 2 khi có đủ reverse APIs từ platform team.
    
- **Tại sao dùng “DB First” strategy trong 2PC mà không phải “Redis First”?**
    
    **Phân tích 2 options:**
    
    **Option A: Redis First (❌ Không chọn)**
    
1. Save to Redis → ✅
2. Save to PostgreSQL → ❌ FAIL
3. Kết quả: Redis có data mới, DB không có → **Inconsistency**
4. Cần rollback Redis → nhưng giữa bước 1 và 3, **có thể có request khác đã đọc Redis data mới** → cascade inconsistency
5. Redis không có transaction isolation → không thể rollback cleanly
    
    **Option B: DB First (✅ Chọn)**
    
6. BEGIN DB transaction
7. Save to PostgreSQL → ✅
8. Save to Redis → ❌ FAIL
9. ROLLBACK DB → DB trở về trạng thái cũ
10. Redis vẫn có data cũ (chưa update) → **Consistent (cả 2 đều ở trạng thái cũ)**
11. Retry toàn bộ operation
    
    **Lý do chính:**
    
- PostgreSQL có **ACID transactions** → ROLLBACK luôn clean
- Redis **không có multi-key transaction** (MULTI/EXEC không phải true transaction) → rollback phức tạp
- DB là **Source of Truth** → nếu phải chọn 1 nơi consistent, chọn DB
- Worst case (Redis mất data): Cache-Aside pattern auto-restore từ DB → **self-healing**
- Worst case (DB mất data): **Không recover được** từ Redis vì Redis là volatile → phải chọn DB first

---

# 📚 Technical Concepts Cheat Sheet

## Để trả lời các câu hỏi kỹ thuật phỏng vấn

- **Clean Architecture là gì? Tại sao chọn nó?**
- Kiến trúc phân tầng: Entity → UseCase → Interface Adapter → Framework
- **Dependency Rule**: Dependencies luôn hướng vào trong (Framework → Adapter → UseCase → Entity)
- **Lợi ích**: Domain logic không phụ thuộc vào framework, dễ test, dễ thay đổi infrastructure
- **Trong project**: Console API tách riêng domain entities (Scenario, Step, Action), use cases (execution logic), adapters (controllers, data providers), và infrastructure (JPA, Redis client)
- **Redis data structures nào bạn đã dùng và tại sao?**
- **String**: Lưu ContextDataItem JSON — mỗi data item có unique key, access O(1)
- **Sorted Set (ZSET)**: Index theo step order — `ZRANGEBYSCORE` để query “tất cả data từ step 1 đến N” trong O(log N + M)
- **Set**: Index theo varId — `SMEMBERS` để tìm tất cả data items có cùng varId trong O(N)
- **Lua Script**: Đảm bảo atomic operation khi save 3 keys cùng lúc
- **SETNX**: Distributed lock cho idempotency
- **Two-Phase Commit (2PC) đơn giản hóa**
- **Problem**: Cần write vào cả PostgreSQL và Redis atomically
- **Solution**: “DB First” strategy
    1. BEGIN transaction
    2. Write to PostgreSQL (source of truth)
    3. Write to Redis (runtime cache)
    4. Nếu cả 2 OK → COMMIT
    5. Nếu Redis fail → ROLLBACK DB
    6. Nếu DB fail → không touch Redis
- **Fallback**: Redis data mất → auto-restore từ DB snapshot (Cache-Aside)
- **Idempotency trong distributed system**
- **Key Concept**: Cùng request gọi nhiều lần → kết quả giống nhau, side-effect chỉ xảy ra 1 lần
- **Implementation**: SHA256(action_id + sorted required params) → unique idempotency key
- **MVP**: DB UNIQUE constraint → INSERT thành công = first call, INSERT fail = duplicate
- **Advanced**: Redis SETNX distributed lock + DB permanent log
- **States**: PROCESSING (đang xử lý) → COMPLETED (done, return cached) hoặc FAILED (allow retry)
- **JSONB trong PostgreSQL — khi nào nên dùng?**
- **Khi dùng**: Schema linh hoạt, nested/complex structures, query frequency thấp hơn relational data
- **Trong project**: step_definition.ui (widget layout với outputVarId), action_definition.parameters (varId mapping), scenario_execution.context (runtime snapshot)
- **Lợi ích**: Flexible schema evolution, no migration needed cho nested structure changes
- **Trade-off**: Query performance kém hơn normalized tables, cần GIN index cho complex queries
- **Vertical Slice Architecture**
- **Concept**: Organize code by feature (vertical) thay vì by technical layer (horizontal)
- **Trong project**: Mỗi feature (scenario.start, scenario.end, context.get) có full stack riêng: Controller → UseCase → DataProvider
- **Lợi ích**: Mỗi feature self-contained, dễ develop/test/deploy independently
- **So với traditional layered**: Ít cross-cutting concerns, team members có thể work parallel trên features khác nhau
- **Cache-Aside Pattern**
- **Read**: Check Redis first → Cache hit: return → Cache miss: query DB → populate Redis → return
- **Write**: Write to DB first → then update/invalidate Redis
- **Trong project**: Execution context data dùng Cache-Aside. Redis miss → load từ PostgreSQL context JSONB → restore to Redis 3-Key model
- **Benefit**: Resilient to Redis failures, tự healing khi Redis restart
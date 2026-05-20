# Interview Guide — Scenario Console System Design

> Tài liệu này dành cho việc chuẩn bị phỏng vấn system design dựa trên project thực tế:  
> **Scenario Console** — nền tảng thực thi multi-step operation cho Nexon Game Ops.

---

## Scenario Mẫu — "In-Game Purchase Flow"

Toàn bộ tài liệu này sẽ dùng scenario sau để minh họa cụ thể:

```
Scenario: "In-Game Purchase"
  Step 1: validate_user_item(userId, itemId)        → kết quả: item hợp lệ
  Step 2: charge_payment(userId, amount, currency)  → output: VAR_TXN_ID (transactionId)
  Step 3: create_order(transactionId, itemId)       → output: VAR_ORDER_ID (orderId)
  Step 4: release_content(orderId, userId)          → output: VAR_GRANT_ID (grantId)
```

User (Operations) bấm từng step, nhập data vào form, bấm "Execute" → Console gọi các 3rd-party services theo thứ tự, forward output của step trước vào input step sau.

---

## 1. Câu hỏi Thiết kế Tổng quan

---

**Q: Hãy mô tả kiến trúc tổng quan của hệ thống Scenario Console. Tại sao lại tách thành 3 service riêng biệt?**

> **3 service:**
>
> | Service | Port | Vai trò |
> |---|---|---|
> | `console-api` | 8080 | Hot path — execution engine, latency-sensitive |
> | `backoffice-api` | 8081 | Management — CRUD scenario/action definition, Swagger import |
> | `reconciliation-service` | 8082 | Async — Kafka consumer, CDC drift detection |
>
> **Lý do tách:**
> - **SLO khác nhau**: Console API cần p99 < 500ms (user đang chờ kết quả); Backoffice có thể chậm vài giây; Reconciler chạy async không cần real-time.
> - **Scale độc lập**: Khi load tăng, chỉ scale Console API pod mà không cần scale Backoffice.
> - **Failure isolation**: Backoffice down không ảnh hưởng execution đang chạy.
> - **Cùng chia sẻ infra**: PostgreSQL, Redis, MSK — tiết kiệm resource, đảm bảo consistency.

---

**Q: Clean Architecture trong project này trông như thế nào? Tại sao không dùng layered architecture thông thường?**

> ```
> Controller (Adapter) → UseCase (Application) → Entity (Domain)
>                ↑                    ↑
>         Infrastructure          Port (interface)
>          (DB, Redis, API)
> ```
>
> **Trong project:**
> ```
> feature/
>   executor/scenario_start/
>     ScenarioStartController     ← Adapter: parse HTTP request
>     ScenarioStartUseCase        ← Application: orchestrate flow
>     ScenarioStartDataProvider   ← Port: interface cho DB/Redis
>
> infrastructure/
>   repository/postgresql/
>     ScenarioStartRepositoryImpl ← Infrastructure: implement port
> ```
>
> **Tại sao không layered:**
> - Layered (Controller → Service → Repository) dễ bị coupling ngang (Service A gọi Service B gọi Service C).
> - Vertical slice: mỗi feature = 1 stack đứng độc lập, team parallel work không conflict.
> - Use case layer không biết DB là PostgreSQL hay Redis — dễ test bằng mock port.

---

**Q: Tại sao không có tầng "Block" trong hierarchy Scenario → Step → Action?**

> Thiết kế ban đầu có Scenario → Block → Step → Action. Block bị loại vì:
> - Thêm 1 tầng trung gian không có behavior riêng — chỉ là group.
> - Frontend phải quản lý thêm block state, backend thêm join table.
> - Trong thực tế, Step đã đủ để đại diện cho 1 "màn hình" — có UI riêng, có actions riêng.
> - Hierarchy đơn giản hơn: Scenario → Step → (UI Elements + Actions) thông qua Variable.

---

## 2. Variable System & Data Flow

---

**Q: VAR_ULID là gì? Tại sao không dùng alias string như trước?**

> **VAR_ULID** = `VAR_` prefix + 26-char ULID (Universally Unique Lexicographically Sortable ID), được frontend tự gen khi user kéo thả kết nối widget với action parameter.
>
> **Vấn đề với alias string:**
> ```
> Step 2: action "create_coupon" → output alias: "couponName"
> Step 3: action "release_coupon" → input alias: "couponname"  ← typo! không match
> ```
>
> **VAR_ULID giải quyết:**
>
> | Vấn đề | Alias (cũ) | VAR_ULID (mới) |
> |---|---|---|
> | Collision | "couponName" ở Step 1 và Step 3 trùng | VAR_01JW ≠ VAR_02JW — không bao giờ |
> | Typo | "couponName" vs "CouponName" | ID do hệ thống gen, không ai nhập tay |
> | Rename | Đổi label phải update cả DB lẫn code | Đổi label widget không ảnh hưởng ID |
> | Explicit | Không rõ widget nào nối với param nào | Kết nối explicit qua ID |
>
> **Ví dụ In-Game Purchase:**
> ```
> Step 2 action output:
>   "transactionId" → varId: VAR_TXN_ID
>
> Step 3 action input:
>   "transactionId" ← varId: VAR_TXN_ID   ← cùng ID → runtime bind tự động
> ```

---

**Q: Làm thế nào Console biết data của Step 1 để pass vào Step 3? Trace data flow cụ thể.**

> **Data flow trong In-Game Purchase:**
>
> ```
> Step 2 — charge_payment
>   User nhập: amount = 9.99, currency = "USD"
>   → action_execution response: { "transactionId": "txn_abc123" }
>   → save ContextDataItem:
>       key:   exec:exec_01JW:ACTION_OUTPUT:actexec_02JW:VAR_TXN_ID
>       value: { varId: "VAR_TXN_ID", value: "txn_abc123", origin.stepOrder: 2 }
>   → Redis SET key + ZADD index:step:exec_01JW score=2 + SADD group:exec_01JW:var:VAR_TXN_ID
>
> Step 3 — create_order
>   Runtime cần bind parameter "transactionId" ← VAR_TXN_ID
>   → SMEMBERS group:exec_01JW:var:VAR_TXN_ID
>     → trả về C key của step 2
>   → GET C key → value: "txn_abc123"
>   → POST /orders { transactionId: "txn_abc123", itemId: "item_xyz" }
> ```
>
> **Không cần explicit "pass từ step này sang step kia"** — runtime tự resolve bằng cách lookup VAR_ULID trong Execution Context (Redis), bất kể variable đó từ step nào.

---

## 3. Redis 3-Key Model

---

**Q: Tại sao cần đến 3 Redis keys để lưu 1 ContextDataItem? Không dùng 1 key HSET được không?**

> **3 keys phục vụ 3 query pattern khác nhau:**
>
> | Key | Type | Query pattern |
> |---|---|---|
> | **C** `exec:{execId}:{sourceType}:{srcId}:{varId}` | String | GET single item — O(1) direct lookup |
> | **A** `index:step:{execId}` | Sorted Set (score=stepOrder) | "Tất cả data từ step 1-3?" → ZRANGEBYSCORE |
> | **B** `group:{execId}:var:{varId}` | Set | "varId VAR_TXN_ID có giá trị gì?" → SMEMBERS |
>
> **Ví dụ cụ thể:**
> - Khi load Step 3, cần tất cả context từ Step 1+2: `ZRANGEBYSCORE index:step:exec_01JW 0 2` → trả về list C keys → MGET.
> - Khi bind parameter VAR_TXN_ID: `SMEMBERS group:exec_01JW:var:VAR_TXN_ID` → lấy C key → GET giá trị.
>
> **Nếu dùng 1 HSET:** không thể query range theo step order mà không scan toàn bộ field. O(N) thay vì O(log N).
>
> **3 keys phải ghi atomic** bằng Lua Script — nếu ghi C thành công nhưng A fail → query ZRANGEBYSCORE trả thiếu data → inconsistency. Lua Script đảm bảo all-or-nothing trong 1 round trip.

---

**Q: Tại sao Lua Script? Không dùng Redis MULTI/EXEC transaction được không?**

> MULTI/EXEC là optimistic transaction — nếu key bị modify giữa WATCH và EXEC → transaction abort, phải retry.
>
> Lua Script:
> - Chạy atomic trên server, không thể bị interrupt.
> - Không cần WATCH/retry loop.
> - 1 round trip (thay vì MULTI + 3 commands + EXEC = 5 round trips).
> - Redis đảm bảo script chạy single-threaded → không có race condition.
>
> **Quan trọng**: Lua Script chỉ atomic trên 1 node. Nếu dùng Redis Cluster, 3 keys phải cùng hash slot → dùng hash tag `{exec_01JW}` để force cùng slot. Project hiện tại dùng single shard ElastiCache → không cần lo vấn đề này.

---

**Q: Tại sao chọn DB First (write PostgreSQL trước Redis) thay vì ngược lại?**

> **DB First 2PC logic:**
> ```
> BEGIN DB transaction
>   → INSERT/UPDATE PostgreSQL (ACID)
>   → Write Redis (Lua Script)
>      OK  → COMMIT
>      FAIL → ROLLBACK DB (Redis vẫn như cũ → consistent)
> DB FAIL → không touch Redis → không inconsistency
> ```
>
> **Lý do:**
> - PostgreSQL có ACID: ROLLBACK luôn clean, không để lại partial state.
> - Redis không có multi-key transaction thật sự (Lua atomic nhưng không có ROLLBACK).
> - DB là **source of truth** — mất Redis recover được từ DB (Cache-Aside). Mất DB không recover được từ Redis (volatile, không replicated như production DB).
> - Worst case: Redis mất data → Cache-Aside tự restore → chậm hơn, không mất data.

---

## 4. Idempotency — 4 Tầng

---

**Q: Tại sao cần 4 tầng idempotency? 1 tầng không đủ sao?**

> Mỗi tầng chặn 1 class of duplicate khác nhau:
>
> ```
> Tầng 1 — UI Lock (Frontend):
>   Chặn: double-click, component remount, F5 → on_load trigger lại
>   Nếu thiếu: 2 request bay lên server cùng lúc
>
> Tầng 2 — Request Dedup (Redis, TTL 60s):
>   Chặn: network retry trong 60s (browser auto-retry, frontend retry middleware)
>   Nếu thiếu: cùng X-Request-Id gửi 2 lần → xử lý 2 lần
>
> Tầng 3 — Trigger Lock (PostgreSQL UNIQUE):
>   Chặn: concurrent requests vượt qua Tầng 2 (TTL hết, khác requestId, khác session)
>   Nếu thiếu: user từ browser A và browser B cùng trigger → 2 execution
>
> Tầng 4 — Action Idempotency (SHA256 + DB UNIQUE):
>   Chặn: cross-session retry, server restart giữa chừng, bug replay
>   Nếu thiếu: Tầng 3 lock per-trigger, nhưng từng action trong trigger vẫn chạy lại được
> ```
>
> **Trade-off**: Mỗi tầng tốn resource (Redis SETNX, DB INSERT). Nhưng Tầng cao chặn sớm → ít request xuống tầng thấp. Đây là **Defense in Depth** — không một tầng nào hoàn hảo một mình.

---

**Q: SHA256 idempotency key được tính như thế nào? Tại sao cần `execution_id` trong key?**

> ```
> idempotencyKey = SHA256(
>   execution_id          +   ← scope về execution này
>   action_definition_id  +   ← action nào
>   sorted(required_params)   ← data gì (sorted để tránh thứ tự khác nhau tạo key khác)
> )
> ```
>
> **Ví dụ In-Game Purchase:**
> ```
> SHA256("exec_01JW" + "actdef_charge_payment" + "amount=9.99&currency=USD&userId=u_123")
>   → "a3f8c2..."
> ```
>
> **Nếu không có `execution_id`:**
> - User A: charge_payment(userId=u_1, amount=9.99) → key = SHA256("actdef_charge...amount=9.99&userId=u_1...")
> - User B: charge_payment(userId=u_1, amount=9.99) trong scenario khác → cùng key → User B bị chặn vì Tầng 4 nghĩ là duplicate!
> - `execution_id` đảm bảo key unique per execution → mỗi lần chạy scenario là 1 execution độc lập.

---

**Q: Trace cụ thể luồng khi Operations double-click "Charge Payment" button.**

> ```
> Click lần 1:
>   [Tầng 1] Button → IDLE → LOADING, requestId = "req_abc" lưu vào triggerState
>   [Tầng 2] SETNX "dedup:req_abc" EX 60 → OK (first)
>   [Tầng 3] INSERT trigger_execution (execution_id, trigger_id) → OK
>   [Tầng 4] INSERT action_execution (SHA256_key, PROCESSING) → OK
>   → POST /payments/charge { amount: 9.99, ... }
>
> Click lần 2 (50ms sau, trước khi lần 1 xong):
>   [Tầng 1] Button đang LOADING → disabled=true → click bị bỏ qua, không có request 2
>
> Browser auto-retry (network drop, requestId giữ nguyên = "req_abc"):
>   [Tầng 2] SETNX "dedup:req_abc" EX 60 → FAIL (key tồn tại)
>   → GET "result:req_abc" → có kết quả? → 200 cached / 202 processing
>
> User mở tab mới (khác requestId, cùng execution):
>   [Tầng 3] SELECT trigger_execution → RUNNING → 202 "already running"
>
> Server restart giữa chừng (execution bị resume):
>   [Tầng 4] INSERT action_execution (SHA256_key) → conflict → query status
>   → COMPLETED → return response_snapshot (không gọi lại payment gateway)
> ```

---

## 5. FAILED vs TIMEOUT — Saga vs Reconciler

---

**Q: Tại sao FAILED và TIMEOUT được xử lý bởi 2 cơ chế khác nhau? Không thể gộp vào 1?**

> Sự khác biệt cốt lõi: **Console biết hay không biết 3rd party đã xử lý chưa.**
>
> ```
> FAILED (4xx/5xx): Console nhận được response → biết chắc action đã fail
>   → Saga trigger ngay: cancel_order → refund_payment
>   → Latency: milliseconds
>
> TIMEOUT (no response): Console không nhận response → KHÔNG BIẾT 3rd party đã xử lý chưa
>   → Nếu Saga ngay: charge_payment đã thực sự charge → refund chưa? → double refund risk
>   → Reconciler verify trước:
>       GET /payments/{transactionId}/status
>       ├── NOT_FOUND → chưa xử lý → mark FAILED → Saga
>       ├── CHARGED   → đã xử lý   → mark COMPLETED → continue flow hoặc compensate
>       └── PROCESSING → còn xử lý → poll lại
> ```
>
> **Ghost transaction problem**: Network timeout không có nghĩa là request thất bại — có thể payment gateway đã charge xong nhưng response bị mất trên đường về. Saga ngay lập tức sẽ trigger refund cho 1 transaction đã thành công → double-refund hoặc account negative balance.

---

**Q: Orchestration Saga là gì? Tại sao không dùng Choreography?**

> **Orchestration**: Console API chủ động gọi từng compensation action theo thứ tự ngược.
> ```
> Step 4 FAIL
>   → Console: cancel_order(orderId)    ← Console gọi Order Service
>   → Console: refund_payment(txnId)    ← Console gọi Payment Gateway
> ```
>
> **Choreography**: Mỗi service lắng nghe event, tự biết phải làm gì.
> ```
> Step 4 FAIL → publish event "order.failed"
>   → Order Service nhận event → cancel order → publish "order.cancelled"
>   → Payment Service nhận event → refund → publish "payment.refunded"
> ```
>
> **Tại sao chọn Orchestration:**
> - Console là điểm trung tâm biết toàn bộ flow — phù hợp làm orchestrator.
> - Dễ debug: nhìn `saga_compensation_log` là biết ngay từng step đã làm gì, fail ở đâu.
> - Choreography phức tạp hơn khi cần track overall state — phải aggregate events từ nhiều service.
> - Compensating actions cần `context_snapshot` (transactionId, orderId) — orchestrator giữ data này, không cần event carry.

---

**Q: context_snapshot trong saga_compensation_log để làm gì? Không lấy từ context hiện tại được không?**

> `context_snapshot` lưu **variable values tại đúng thời điểm action đó chạy**.
>
> **Tại sao không dùng context hiện tại:**
> ```
> Step 2 charge_payment: transactionId = "txn_001"
>   → saga_log context_snapshot: { VAR_TXN_ID: "txn_001" }
>
> [Scenario fail ở Step 4]
>
> Lúc compensation chạy, context có thể đã bị modify:
>   - User đã input dữ liệu khác cho Step 3 retry
>   - Một action khác đã overwrite VAR_TXN_ID
>
> Compensation PHẢI dùng snapshot: refund_payment("txn_001") — đúng transaction ID đó
> Không thể dùng context hiện tại: có thể bị "txn_002" từ retry → refund sai transaction
> ```

---

## 6. CDC & Reconciliation

---

**Q: Debezium CDC hoạt động thế nào trong hệ thống này? Reconciler làm gì với data từ Kafka?**

> ```
> [Bình thường]
> action_execution UPDATE status=TIMEOUT
>   → PostgreSQL WAL
>   → Debezium đọc WAL qua logical replication slot
>   → Publish event tới MSK topic: "postgres.public.action_execution"
>     event: { before: {status: "PROCESSING"}, after: {status: "TIMEOUT"}, ... }
>   → Reconciliation Service consumer nhận event
>   → Extract: execution_id, action_definition_id, context_snapshot
>   → Call 3rd party: GET /payments/{transactionId}/status
>   → Kết quả:
>       NOT_FOUND → UPDATE action_execution SET status=FAILED → Saga trigger
>       CHARGED   → UPDATE action_execution SET status=COMPLETED
>       PROCESSING → Reschedule poll (lưu vào pending_verification table)
> ```
>
> **Tại sao CDC thay vì scheduler?**
> - Scheduler polling DB mỗi N giây → latency cao, load DB khi không có gì để làm.
> - CDC event-driven: Reconciler chỉ wake up khi có TIMEOUT thực sự → latency thấp hơn, hiệu quả hơn.
> - CDC là audit trail tự nhiên — mọi thay đổi trạng thái đều có event.
>
> **Lưu ý quan trọng:**
> - Replication slot phải được monitor để tránh WAL disk bloat khi Reconciler lag.
> - Debezium offset lưu trong Kafka topic `__debezium-offsets` — mất offset = replay từ đầu (snapshot mode).

---

**Q: Nếu Kafka down, hệ thống có bị ảnh hưởng không?**

> Console API (hot path) không phụ thuộc Kafka:
> - Execution flow: PostgreSQL + Redis → không cần Kafka.
> - TIMEOUT action → ghi vào PostgreSQL (status=TIMEOUT) và tiếp tục.
>
> Kafka down ảnh hưởng:
> - Reconciler không nhận được TIMEOUT events → TIMEOUT actions không được verify.
> - Khi Kafka recover: Debezium replay từ WAL position đã lưu → Reconciler xử lý backlog.
> - **Không mất data** vì source of truth là PostgreSQL WAL, không phải Kafka.
>
> Điều này chứng minh quyết định thiết kế: **DB First** — PostgreSQL là foundation, Kafka là delivery mechanism.

---

## 7. Access Control

---

**Q: RBAC được implement thế nào? Tại sao cache RoleStore response vào Redis?**

> ```
> User request POST /executions
>   → Console API check: sc:user:{userId}:exec (Redis SET)
>       HIT: O(1) SISMEMBER check
>       MISS: GET /rolestore/users/{userId}/permissions → cache vào Redis TTL=5min
>   → scenario_id không trong SET → 403 Forbidden
> ```
>
> **Tại sao cache:**
> - Mỗi API call check permission = 1 HTTP call tới RoleStore → latency cao (10-50ms per call).
> - Execution flow có thể gọi 4-5 endpoints → 4-5 RoleStore calls = 50-250ms overhead thuần overhead.
> - TTL=5min: eventual consistency chấp nhận được — quyền thay đổi thì tối đa 5 phút mới có hiệu lực.
> - RoleStore push webhook khi thay đổi → Console API DEL cache key ngay → near-realtime invalidation.
>
> **Tại sao dùng Redis SET (không phải string)?**
> - User có thể có quyền trên nhiều scenario → lưu list.
> - SISMEMBER là O(1) — check "scenario này có trong set không?" rất nhanh.

---

## 8. Câu hỏi Trade-off & Design Decision

---

**Q: Nếu scenario có 10 steps và mỗi step có 3 actions, bạn lo ngại điểm gì về performance?**

> **Redis key explosion**: 10 steps × 3 actions × N variables = có thể hàng trăm C keys per execution. Mỗi key thêm vào ZSET (A) và SET (B).
> - `ZRANGEBYSCORE index:step:exec_01JW 0 10` → O(log N + M) — vẫn nhanh với vài trăm keys.
> - Cleanup khi COMPLETED phải DEL pattern `exec:{execId}:*` → pipeline delete, không block.
>
> **Action chain latency**: 3 actions sequential = 3 external HTTP calls. Nếu mỗi call 200ms → 600ms/step. Worst case: 10 steps × 600ms = 6s total waiting time.
> - Giảm: actions không phụ thuộc nhau trong cùng trigger có thể chạy parallel (cần thiết kế trigger schema hỗ trợ `parallel` group).
>
> **Saga backlog**: 10 steps × 3 actions = tối đa 30 compensation entries. ORDER BY step_order DESC với 30 rows — trivial. `step_order` được denorm vào saga_log chính là vì lý do này.

---

**Q: Tại sao lưu `step_order` vào `saga_compensation_log` thay vì JOIN với `step_definition`?**

> Denormalization có chủ đích:
> ```sql
> -- Với denorm:
> SELECT * FROM saga_compensation_log
> WHERE scenario_execution_id = 'exec_01JW'
> ORDER BY step_order DESC;
> -- 1 query, no join
>
> -- Không có denorm:
> SELECT scl.*, sd.step_order
> FROM saga_compensation_log scl
> JOIN action_definition ad ON scl.action_definition_id = ad.id
> JOIN step_trigger_action sta ON ad.id = sta.action_definition_id
> JOIN step_definition sd ON sta.step_id = sd.id
> WHERE scl.scenario_execution_id = 'exec_01JW'
> ORDER BY sd.step_order DESC;
> -- 3 joins trên hot path của compensation
> ```
>
> Compensation là **emergency path** — khi hệ thống đang fail, cần xử lý nhanh nhất có thể. 3 joins = 3 table scans = extra latency = bad. Denorm `step_order` vào saga_log là trade-off space for time hoàn toàn hợp lý.

---

**Q: Tại sao dùng JSONB cho `step_definition.ui` nhưng normalize `step_trigger` thành table riêng?**

> **Nguyên tắc**: JSONB khi không cần query theo field bên trong; normalize khi cần FK, ORDER BY, index.
>
> **UI elements (JSONB):**
> - Không bao giờ query `WHERE ui.elements[*].type = 'button'`.
> - Chỉ load nguyên JSONB ra, render trên frontend.
> - Structure thay đổi theo widget type (button khác text_input) → không có schema cố định.
>
> **step_trigger (normalized table):**
> - Cần `ORDER BY action_order` khi execute actions sequential.
> - Cần FK constraint: `action_definition_id` phải tồn tại trong `action_definition`.
> - Cần GROUP BY trigger để biết trigger nào chứa action nào.
> - Nếu JSONB: phải parse JSON để biết order → không thể dùng DB sorting → application-level sort.

---

**Q: Nếu một Operations engineer vô tình double-trigger "Charge Payment" từ 2 browser tab khác nhau cùng lúc, điều gì xảy ra?**

> ```
> Tab A: POST /executions/exec_01JW/trigger (trigger_id: trig_charge)
>   X-Request-Id: req_AAA
>
> Tab B: POST /executions/exec_01JW/trigger (trigger_id: trig_charge)
>   X-Request-Id: req_BBB   ← khác requestId → vượt Tầng 2
> ```
>
> **Tầng 3 xử lý:**
> ```sql
> -- Tab A INSERT trước:
> INSERT INTO trigger_execution (scenario_execution_id, step_trigger_id)
> VALUES ('exec_01JW', 'trig_charge')
> -- OK → status = RUNNING
>
> -- Tab B INSERT sau (milliseconds):
> INSERT INTO trigger_execution (scenario_execution_id, step_trigger_id)
> VALUES ('exec_01JW', 'trig_charge')
> -- UNIQUE constraint violation → Tab B nhận 202 "already running"
> ```
>
> Tab B nhận 202, polling kết quả. Khi Tab A xong → Tab B nhận kết quả từ `result:{requestId}` cache.  
> Chỉ 1 lần charge xảy ra. **Payment Gateway không bị gọi 2 lần**.

---

**Q: Hệ thống xử lý thế nào khi Operations cancel scenario giữa chừng sau Step 2 đã charge tiền?**

> ```
> User bấm "Cancel" khi scenario đang ở Step 3:
>   → POST /executions/exec_01JW/cancel
>
> Console API:
>   1. UPDATE scenario_execution SET status = FAILED
>   2. Trigger Compensation Manager
>
> Compensation Manager:
>   SELECT * FROM saga_compensation_log
>   WHERE scenario_execution_id = 'exec_01JW'
>   ORDER BY step_order DESC
>
>   Step 3 (create_order): status COMPLETED, trigger = auto
>     → POST /orders/{orderId}/cancel   ← orderId từ context_snapshot
>     → COMPENSATED ✅
>
>   Step 2 (charge_payment): status COMPLETED, trigger = auto
>     → POST /payments/{transactionId}/refund  ← transactionId từ context_snapshot
>     → COMPENSATED ✅
>
>   Step 1 (validate_user_item): trigger = none → skip
>
>   UPDATE scenario_execution SET status = COMPENSATED
> ```
>
> User nhận thông báo: "Scenario đã bị hủy. Refund đã được xử lý."

---

## 9. Câu hỏi Behavioral (dựa trên project)

---

**Q: Kể về một quyết định thiết kế khó nhất trong project này.**

> Gợi ý trả lời:
>
> *"Khó nhất là quyết định split FAILED và TIMEOUT sang 2 handling path riêng biệt. Lúc đầu tôi muốn gộp cả hai vào Saga cho đơn giản. Nhưng khi đi sâu vào ghost transaction problem — network timeout không có nghĩa là payment đã fail, có thể gateway đã charge xong nhưng response bị mất — tôi nhận ra nếu Saga refund ngay thì user bị refund tiền trong khi item đã được release. Đây là inconsistency về tài chính, không thể chấp nhận.*
>
> *Giải pháp là Reconciler verify trước bằng cách call lại 3rd party: nếu NOT_FOUND thì mới trigger Saga, nếu CHARGED thì mark COMPLETED và tiếp tục hoặc compensate. Trade-off là latency cao hơn cho TIMEOUT case (vài giây thay vì milliseconds), nhưng correctness quan trọng hơn speed cho financial transaction."*

---

**Q: Nếu được làm lại từ đầu, bạn sẽ thay đổi gì?**

> Gợi ý trả lời:
>
> *"Tôi sẽ thiết kế trigger schema hỗ trợ parallel action group ngay từ đầu. Hiện tại actions trong 1 trigger chạy sequential — nếu Step 2 có 2 actions độc lập nhau (ví dụ: fetch user profile + fetch payment methods cùng lúc), chúng vẫn phải chờ nhau. Để thêm parallel sau này cần thay đổi schema và execution engine. Nếu thiết kế từ đầu có `action_group` với `execution_mode: sequential|parallel`, sẽ flexible hơn mà không tốn thêm complexity."*

---

## 10. Checklist Trước Phỏng Vấn

```
Core concepts
  ☐ Giải thích Scenario → Step → (UI + Action) hierarchy
  ☐ VAR_ULID tại sao tốt hơn alias string
  ☐ Data flow từ Step 1 tới Step 3 (trace qua Redis)

Redis model
  ☐ 3 key types: C (data), A (step index ZSET), B (varId SET)
  ☐ Tại sao Lua Script thay vì MULTI/EXEC
  ☐ DB First 2PC: tại sao không Redis First

Idempotency
  ☐ 4 tầng: UI Lock / Request Dedup / Trigger Lock / Action SHA256
  ☐ Tại sao execution_id bắt buộc có trong SHA256 key
  ☐ Double-click scenario trace qua 4 tầng

Saga vs Reconciler
  ☐ FAILED = biết outcome → Saga ngay
  ☐ TIMEOUT = không biết outcome → Reconciler verify trước
  ☐ Ghost transaction problem
  ☐ context_snapshot tại sao không dùng context hiện tại

Design decisions
  ☐ Tại sao không có Block layer
  ☐ JSONB vs normalize: ui vs step_trigger
  ☐ step_order denorm trong saga_log
  ☐ Orchestration vs Choreography Saga
```

---

*Tài liệu liên quan: `docs/02-core-concepts.md`, `docs/03-execution-engine.md`, `docs/04-context-redis.md`, `docs/05-idempotency.md`, `docs/06-saga-compensation.md`, `docs/07-cdc-reconciliation.md`*

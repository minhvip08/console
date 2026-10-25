# System Flows — Mermaid Diagrams

---

## 1. Variable Data Flow — UI ↔ Action (Release Coupon Campaign)

Mỗi widget `outputVarId` kết nối với action parameter `varId` qua Variable. Biến truyền xuyên suốt các Step trong Execution Context.

```mermaid
flowchart TD
    classDef uiNode     fill:#b7e1cd,stroke:#2d8a2d,color:#000
    classDef actNode    fill:#fce8b2,stroke:#b8860b,color:#000
    classDef actNodeKey fill:#fce8b2,stroke:#cc5500,stroke-width:3px,color:#000

    subgraph SCEN["Scenario: Release Coupon Campaign"]
        subgraph S1["Step 1 — Select Campaign"]
            direction LR
            UI1["UI: text_input outputVarId: VAR_SEARCH"]:::uiNode
            ACT1["Action: GET /campaigns/search ────────────────────── query        ← VAR_SEARCH campaign_id  → VAR_CAMPAIGNID"]:::actNode
        end

        subgraph S2["Step 2 — Configure Coupon"]
            direction LR
            UI2a["UI: text_display VAR_CAMPAIGNID (readonly)"]:::uiNode
            UI2b["UI: text_input outputVarId: VAR_NAME"]:::uiNode
            UI2c["UI: number_input outputVarId: VAR_DISCOUNT"]:::uiNode
            ACT2["Action: POST /coupons ────────────────────── campaign_id ← VAR_CAMPAIGNID name        ← VAR_NAME discount    ← VAR_DISCOUNT coupon_id   → VAR_COUPONID ────────────────────── 🔄 Comp: DELETE /coupons/{couponId}   trigger=auto | var: VAR_COUPONID 🔍 Verify: GET /coupons?idempotency_key=SHA256(...)"]:::actNodeKey
        end

        subgraph S3["Step 3 — Select Target Users"]
            direction LR
            UI3["UI: multi_select outputVarId: VAR_SEGMENTS optionsVarId: VAR_SEGLIST"]:::uiNode
            ACT3a["Action: GET /segments ────────────────────── segments → VAR_SEGLIST"]:::actNode
            ACT3b["Action: POST /segments/estimate ────────────────────── segment_ids ← VAR_SEGMENTS total_users → VAR_USERCOUNT"]:::actNode
        end

        subgraph S4["Step 4 — Review & Release"]
            direction LR
            UI4["UI: json_viewer VAR_PREVIEW (readonly)"]:::uiNode
            ACT4a["Action: POST /releases/preview ────────────────────── coupon_id   ← VAR_COUPONID segment_ids ← VAR_SEGMENTS total_users ← VAR_USERCOUNT preview     → VAR_PREVIEW"]:::actNode
            ACT4b["Action: POST /releases ────────────────────── coupon_id   ← VAR_COUPONID segment_ids ← VAR_SEGMENTS result      → VAR_RESULT ────────────────────── 🔄 Comp: POST /releases/{releaseId}/revoke   trigger=auto | var: VAR_RESULT 🔍 Verify: GET /releases?idempotency_key=SHA256(...)"]:::actNodeKey
        end
    end

    UI1   -->|"VAR_SEARCH"| ACT1
    ACT1  -->|"VAR_CAMPAIGNID"| UI2a
    ACT1  -.->|"VAR_CAMPAIGNID"| ACT2
    UI2b  -->|"VAR_NAME"| ACT2
    UI2c  -->|"VAR_DISCOUNT"| ACT2
    ACT3a -->|"VAR_SEGLIST (options)"| UI3
    UI3   -->|"VAR_SEGMENTS"| ACT3b
    ACT2  -.->|"VAR_COUPONID"| ACT4a
    ACT2  -.->|"VAR_COUPONID"| ACT4b
    ACT3b -.->|"VAR_SEGMENTS, VAR_USERCOUNT"| ACT4a
    ACT3b -.->|"VAR_SEGMENTS"| ACT4b
    ACT4a -->|"VAR_PREVIEW"| UI4
```

<!-- ```mermaid
flowchart TD
    classDef uiNode     fill:#b7e1cd,stroke:#2d8a2d,color:#000
    classDef actNode    fill:#fce8b2,stroke:#b8860b,color:#000
    classDef actNodeKey fill:#fce8b2,stroke:#cc5500,stroke-width:3px,color:#000

    subgraph SCEN["Scenario: Release Coupon Campaign"]
        subgraph S1["Step 1 — Select Campaign"]
            direction LR
            UI1["UI: text_input\noutputVarId: VAR_SEARCH"]:::uiNode
            ACT1["Action: GET /campaigns/search\n──────────────────────\nquery        ← VAR_SEARCH\ncampaign_id  → VAR_CAMPAIGNID"]:::actNode
        end

        subgraph S2["Step 2 — Configure Coupon"]
            direction LR
            UI2a["UI: text_display\nVAR_CAMPAIGNID (readonly)"]:::uiNode
            UI2b["UI: text_input\noutputVarId: VAR_NAME"]:::uiNode
            UI2c["UI: number_input\noutputVarId: VAR_DISCOUNT"]:::uiNode
            ACT2["Action: POST /coupons\n──────────────────────\ncampaign_id ← VAR_CAMPAIGNID\nname        ← VAR_NAME\ndiscount    ← VAR_DISCOUNT\ncoupon_id   → VAR_COUPONID\n──────────────────────\n🔄 Comp: DELETE /coupons/{couponId}\n   trigger=auto | var: VAR_COUPONID\n🔍 Verify: GET /coupons?idempotency_key=SHA256(...)"]:::actNodeKey
        end

        subgraph S3["Step 3 — Select Target Users"]
            direction LR
            UI3["UI: multi_select\noutputVarId: VAR_SEGMENTS\noptionsVarId: VAR_SEGLIST"]:::uiNode
            ACT3a["Action: GET /segments\n──────────────────────\nsegments → VAR_SEGLIST"]:::actNode
            ACT3b["Action: POST /segments/estimate\n──────────────────────\nsegment_ids ← VAR_SEGMENTS\ntotal_users → VAR_USERCOUNT"]:::actNode
        end

        subgraph S4["Step 4 — Review & Release"]
            direction LR
            UI4["UI: json_viewer\nVAR_PREVIEW (readonly)"]:::uiNode
            ACT4a["Action: POST /releases/preview\n──────────────────────\ncoupon_id   ← VAR_COUPONID\nsegment_ids ← VAR_SEGMENTS\ntotal_users ← VAR_USERCOUNT\npreview     → VAR_PREVIEW"]:::actNode
            ACT4b["Action: POST /releases\n──────────────────────\ncoupon_id   ← VAR_COUPONID\nsegment_ids ← VAR_SEGMENTS\nresult      → VAR_RESULT\n──────────────────────\n🔄 Comp: POST /releases/{releaseId}/revoke\n   trigger=auto | var: VAR_RESULT\n🔍 Verify: GET /releases?idempotency_key=SHA256(...)"]:::actNodeKey
        end
    end

    UI1   -->|"VAR_SEARCH"| ACT1
    ACT1  -->|"VAR_CAMPAIGNID"| UI2a
    ACT1  -.->|"VAR_CAMPAIGNID"| ACT2
    UI2b  -->|"VAR_NAME"| ACT2
    UI2c  -->|"VAR_DISCOUNT"| ACT2
    ACT3a -->|"VAR_SEGLIST (options)"| UI3
    UI3   -->|"VAR_SEGMENTS"| ACT3b
    ACT2  -.->|"VAR_COUPONID"| ACT4a
    ACT2  -.->|"VAR_COUPONID"| ACT4b
    ACT3b -.->|"VAR_SEGMENTS, VAR_USERCOUNT"| ACT4a
    ACT3b -.->|"VAR_SEGMENTS"| ACT4b
    ACT4a -->|"VAR_PREVIEW"| UI4
``` -->

> Mũi tên `-->` = kết nối trong cùng Step. Mũi tên `-.->` = variable truyền qua Execution Context từ Step trước.
> Node viền cam đậm (`actNodeKey`) = action có **Compensation** + **Verification** block — quan trọng với Saga và Reconciler.

---

## 1b. Rollback Flow — Step 4 Fails (Saga Compensation)

Khi `actdef_confirm_release` (Step 4) FAILED, Saga Manager chạy compensation ngược:
- Step 4: FAILED → skip (chưa complete, không cần compensate)
- Step 3: `actdef_estimate_targets` trigger=**none** (read-only) → skip
- Step 2: `actdef_create_coupon` trigger=**auto** → **RUN** — dùng `VAR_COUPONID` từ `context_snapshot`
- Step 1: `actdef_search_campaigns` trigger=**none** (read-only) → skip

```mermaid
sequenceDiagram
    participant CM as Saga Compensation Manager
    participant DB as PostgreSQL
    participant CouponSvc as Coupon Service

    Note over CM: actdef_confirm_release FAILED → Step 4
    CM->>DB: UPDATE scenario_execution → FAILED → COMPENSATING

    CM->>DB: SELECT saga_compensation_log<br/>WHERE scenario_execution_id = X<br/>ORDER BY step_order DESC

    Note over CM: step_order=4 FAILED     → skip
    Note over CM: step_order=3 trigger=none → skip (estimate là read-only)
    Note over CM: step_order=2 trigger=auto → RUN compensation
    Note over CM: step_order=1 trigger=none → skip (search là read-only)

    CM->>DB: UPDATE saga_log step2 → COMPENSATING

    CM->>CouponSvc: DELETE /coupons/{couponId}<br/>couponId = VAR_COUPONID ← context_snapshot của step 2

    alt 200 OK — coupon deleted
        CouponSvc-->>CM: 200 OK ✅
        CM->>DB: UPDATE saga_log step2 → COMPENSATED
        CM->>DB: UPDATE scenario_execution → COMPENSATED ✅
    else FAIL — coupon service down
        CouponSvc-->>CM: 5xx / timeout
        CM->>DB: UPDATE saga_log step2 → COMPENSATION_FAILED
        CM->>DB: INSERT compensation_dead_letter
        Note over CM: Alert admin — manual intervention required
        Note over CM: Expose: POST /executions/{id}/retry-compensation
    end
```

---

## 1c. Reconciliation Flow — POST /coupons Times Out

Khi `actdef_create_coupon` (Step 2) TIMEOUT — Console không biết coupon đã được tạo chưa.
Reconciler dùng `verification` block để check state thực tế trên Coupon Service.

```mermaid
sequenceDiagram
    participant API as Console API
    participant DB as PostgreSQL
    participant DEB as Debezium (CDC)
    participant MSK as Kafka MSK
    participant REC as Reconciliation Service
    participant CouponSvc as Coupon Service

    API->>CouponSvc: POST /coupons (with X-Idempotency-Key: SHA256(...))
    Note over API,CouponSvc: Network timeout — no response received

    API->>DB: UPDATE action_execution → TIMEOUT
    DB->>DEB: WAL event captured
    DEB->>MSK: console.action_execution.cdc<br/>{before: PROCESSING, after: TIMEOUT}

    MSK->>REC: Consume CDC event (op=u, after.status=TIMEOUT)

    REC->>DB: Load action_verification for actdef_create_coupon
    DB-->>REC: endpoint=/coupons, method=GET<br/>queryParams: {idempotency_key: SHA256(...)}<br/>conditions: {processed: status=ACTIVE, not_found: 404}<br/>poll_interval_ms: 5000, max_poll_attempts: 12

    REC->>CouponSvc: GET /coupons?idempotency_key=SHA256(execId+actDefId+params)

    alt 200 — coupon EXISTS (status=ACTIVE)
        CouponSvc-->>REC: {coupon_id: "cpn_01JW", status: "ACTIVE"}
        Note over REC: DB=TIMEOUT, 3rd=ACTIVE → Lost Response
        Note over REC: Console gửi request thành công nhưng không nhận được response
        REC->>DB: UPDATE action_execution → COMPLETED<br/>response_snapshot = {coupon_id: "cpn_01JW"}
        REC->>DB: INSERT reconciliation_event (LOST_RESPONSE, AUTO_COMPLETED)
        Note over REC: Notify Scenario Engine tiếp tục flow<br/>VAR_COUPONID = "cpn_01JW" → restored to context

    else 404 — coupon NOT FOUND
        CouponSvc-->>REC: 404
        Note over REC: DB=TIMEOUT, 3rd=NOT_FOUND → Clean timeout
        Note over REC: Coupon Service không xử lý — safe to fail
        REC->>DB: UPDATE action_execution → FAILED
        REC->>DB: INSERT reconciliation_event (CLEAN_TIMEOUT, NO_ACTION)
        Note over REC: Scenario FAILED<br/>step 2 chưa complete → không có gì để compensate

    else 200 — status=PROCESSING
        CouponSvc-->>REC: {status: "PROCESSING"}
        Note over REC: 3rd party vẫn đang xử lý → poll lại
        Note over REC: Wait poll_interval_ms=5000ms, retry đến max_poll_attempts=12
    end
```

---

## 1d. Scenario End-to-End — Happy Path + Failure Paths

```mermaid
flowchart TD
    classDef uiNode     fill:#b7e1cd,stroke:#2d8a2d,color:#000
    classDef actNode    fill:#fce8b2,stroke:#b8860b,color:#000
    classDef actNodeKey fill:#fce8b2,stroke:#cc5500,stroke-width:3px,color:#000
    classDef sagaNode   fill:#ffe0cc,stroke:#cc3300,color:#000
    classDef recNode    fill:#e8d5ff,stroke:#6a1b9a,color:#000
    classDef okNode     fill:#ccffcc,stroke:#007700,color:#000
    classDef failNode   fill:#ffcccc,stroke:#cc0000,color:#000

    S1["Step 1: search_campaigns\ntrigger=none (read-only)"]:::actNode
    S2["Step 2: create_coupon\n🔄 comp: DELETE /coupons/{couponId}\n🔍 verify: GET /coupons?key=..."]:::actNodeKey
    S3["Step 3: estimate_targets\ntrigger=none (read-only)"]:::actNode
    S4["Step 4: confirm_release\n🔄 comp: POST /releases/{id}/revoke\n🔍 verify: GET /releases?key=..."]:::actNodeKey

    S1 --> S2 --> S3 --> S4

    S4 -->|"✅ 200 OK"| DONE(["scenario_execution\n→ COMPLETED ✅"]):::okNode

    S4 -->|"❌ FAILED (4xx/5xx)"| SAGA["Saga Compensation Manager\n— load saga_log ORDER BY step_order DESC\n— step4 FAILED → skip\n— step3 trigger=none → skip\n— step2 trigger=auto → RUN compensation\n— step1 trigger=none → skip"]:::sagaNode
    SAGA -->|"DELETE /coupons/{couponId}\ncouponId ← context_snapshot step2"| COMP_OK(["saga_log step2 → COMPENSATED\nscenario → COMPENSATED ✅"]):::okNode
    SAGA -->|"compensation fails"| DL(["COMPENSATION_FAILED\n→ dead_letter + alert admin"]):::failNode

    S2 -->|"⏱️ TIMEOUT"| REC["Reconciler\nGET /coupons?idempotency_key=SHA256(...)"]:::recNode
    REC -->|"status=ACTIVE\nLost Response"| AUTO_DONE(["UPDATE action_execution → COMPLETED\nVAR_COUPONID restored\n→ continue flow ▶ Step 3"]):::okNode
    REC -->|"404 Not Found\nClean timeout"| AUTO_FAIL(["UPDATE action_execution → FAILED\nscenario → FAILED"]):::failNode
    REC -->|"PROCESSING\nStill running"| POLL(["Poll lại sau 5000ms\nmax 12 attempts"]):::recNode

    S4 -->|"⏱️ TIMEOUT"| REC2["Reconciler\nGET /releases?idempotency_key=SHA256(...)"]:::recNode
    REC2 -->|"RELEASED → Ghost"| AUTO_COMP(["Trigger Saga compensation\n→ POST /releases/{id}/revoke\n→ DELETE /coupons/{couponId}"]):::sagaNode
    REC2 -->|"404 → Clean"| AUTO_FAIL2(["scenario → FAILED\nstep4 chưa complete"]):::failNode
```

---

## 2. Execution Engine — Button Click Flow

```mermaid
sequenceDiagram
    actor User
    participant FE as Frontend
    participant API as Console API
    participant Redis
    participant DB as PostgreSQL
    participant Svc as External Service

    User->>FE: Click button
    FE->>FE: Button IDLE → LOADING (disabled)
    FE->>FE: requestId = ulid()

    FE->>API: POST /executions/{id}/trigger<br/>X-Request-Id: req_01JW<br/>body: {triggerId, varId: value}

    Note over API: Tầng 2 — Request Dedup
    API->>Redis: SETNX "dedup:req_01JW" EX 60
    alt duplicate (key exists)
        Redis-->>API: FAIL
        API-->>FE: 409 Conflict
    end

    Note over API: Tầng 3 — Trigger Lock
    API->>DB: SELECT trigger_execution WHERE step_trigger_id=?
    alt RUNNING
        DB-->>API: RUNNING
        API-->>FE: 202 Already running
    else COMPLETED
        DB-->>API: result
        API-->>FE: 200 Cached result
    else NOT FOUND
        API->>DB: INSERT trigger_execution (RUNNING)
    end

    Note over API: Load & merge context
    API->>Redis: ZRANGEBYSCORE index:step:{execId} 0 currentStep
    Redis-->>API: previous step variables

    loop For each action (sequential)
        Note over API: Tầng 4 — Action Idempotency
        API->>DB: INSERT action_execution (idempotencyKey, PROCESSING)
        alt conflict
            DB-->>API: duplicate
            API->>DB: SELECT status, response
        end

        API->>Svc: Call API (với varId-bound params)

        alt 200 OK
            Svc-->>API: response
            API->>DB: UPDATE action_execution COMPLETED
            API->>Redis: ZADD / SADD (save output vars, Lua atomic)
        else 4xx/5xx
            Svc-->>API: error
            API->>DB: UPDATE action_execution FAILED
            Note over API: Trigger Saga compensation
        else Timeout / no response
            API->>DB: UPDATE action_execution TIMEOUT
            Note over API: CDC picks up → Reconciler
        end
    end

    API->>DB: UPDATE trigger_execution COMPLETED
    API->>Redis: SET "result:req_01JW" EX 300
    API-->>FE: 200 {result}
    FE->>FE: Button LOADING → DONE
```

---

## 3. State Machines

### 3a. action_execution

```mermaid
stateDiagram-v2
    [*] --> PENDING
    PENDING --> PROCESSING : action bắt đầu gọi API
    PROCESSING --> COMPLETED : nhận response 2xx
    PROCESSING --> FAILED : nhận response 4xx/5xx
    PROCESSING --> TIMEOUT : không nhận response

    FAILED --> [*] : Saga trigger compensation ngay
    TIMEOUT --> [*] : CDC event → Reconciler verify 3rd party
    COMPLETED --> [*]

    note right of TIMEOUT
        Reconciler check 3rd party:
        • NOT_FOUND → mark FAILED → Saga
        • CHARGED   → mark COMPLETED
        • PROCESSING → poll lại
    end note
```

### 3b. scenario_execution

```mermaid
stateDiagram-v2
    [*] --> PENDING
    PENDING --> EXECUTING : scenario bắt đầu
    EXECUTING --> COMPLETED : tất cả steps done

    EXECUTING --> FAILED : action FAILED (4xx/5xx)
    FAILED --> COMPENSATING : Saga Manager trigger
    COMPENSATING --> COMPENSATED : tất cả compensations OK

    COMPENSATING --> COMPENSATION_FAILED : compensation action fail
    COMPENSATION_FAILED --> [*] : dead letter + alert admin

    COMPENSATED --> [*]
    COMPLETED --> [*]
```

---

## 4. Idempotency — 4 Tầng Chống Duplicate

```mermaid
flowchart TD
    classDef blocked fill:#ffcccc,stroke:#cc0000,color:#000
    classDef pass    fill:#ccffcc,stroke:#007700,color:#000
    classDef layer   fill:#e8f4fd,stroke:#1a73e8,color:#000

    START([User click button])

    subgraph L1["Tầng 1 — UI Lock (Frontend)"]
        T1{Button LOADING?}
    end

    subgraph L2["Tầng 2 — Request Dedup (Redis)"]
        T2{"SETNX dedup:{execId}:{triggerId}:{reqId}\nEX 60"}
        T2b{"GET result:{execId}:{triggerId}:{reqId}"}
    end

    subgraph L3["Tầng 3 — Trigger Lock (PostgreSQL)"]
        T3{trigger_execution\nstatus?}
    end

    subgraph L4["Tầng 4 — Action Idempotency (PostgreSQL)"]
        T4{"INSERT action_execution\n(idempotencyKey)\nON CONFLICT DO NOTHING"}
        T4b{status?}
    end

    EXEC([Execute action → call 3rd party])
    DONE([Return result])

    START --> T1
    T1 -->|"LOADING → ignore"| BLOCKED1([🚫 Drop click]):::blocked
    T1 -->|"IDLE → proceed"| T2

    T2 -->|"FAIL = duplicate < 60s"| T2b
    T2b -->|"has result"| CACHED1([✅ 200 Cached]):::pass
    T2b -->|"no result"| PROC1([⏳ 202 Processing]):::pass
    T2 -->|"OK = first request"| T3

    T3 -->|"RUNNING"| PROC2([⏳ 202 Already running]):::pass
    T3 -->|"COMPLETED"| CACHED2([✅ 200 Cached result]):::pass
    T3 -->|"FAILED → allow retry"| T3_insert[INSERT RUNNING]
    T3 -->|"NOT FOUND"| T3_insert
    T3_insert --> T4

    T4 -->|"CONFLICT = duplicate"| T4b
    T4b -->|"PROCESSING"| PROC3([⏳ 202 Processing]):::pass
    T4b -->|"COMPLETED"| CACHED3([✅ 200 Cached response]):::pass
    T4 -->|"OK = first call"| EXEC

    EXEC --> DONE
```

---

## 5. Two-Phase Commit (DB First)

```mermaid
flowchart TD
    classDef ok      fill:#ccffcc,stroke:#007700
    classDef fail    fill:#ffcccc,stroke:#cc0000
    classDef neutral fill:#fff9c4,stroke:#f9a825

    START([Write request])
    T1[BEGIN DB transaction]
    T2[Save to PostgreSQL\ncontext JSONB snapshot]
    T3[Save to Redis\n3-key model via Lua Script]

    T2ok{PostgreSQL OK?}
    T3ok{Redis OK?}
    COMMIT([COMMIT ✅]):::ok
    ROLLBACK([ROLLBACK DB ✅\nRedis vẫn ở state cũ]):::ok
    SKIP([Không touch Redis ✅\nDB cũng chưa commit]):::ok

    START --> T1 --> T2 --> T2ok
    T2ok -->|"FAIL"| SKIP
    T2ok -->|"OK"| T3 --> T3ok
    T3ok -->|"OK"| COMMIT
    T3ok -->|"FAIL"| ROLLBACK
```

---

## 6. Cache-Aside Fallback

```mermaid
flowchart LR
    READ([Read request])
    REDIS{"Redis\nHIT?"}
    RETURN_CACHE([Return data ✅])
    QUERY_DB[Query PostgreSQL\ncontext JSONB snapshot]
    RESTORE["Restore 3-key model\nvào Redis\n(MSET + ZADD + SADD)"]
    RETURN_DB([Return data ✅])

    READ --> REDIS
    REDIS -->|HIT| RETURN_CACHE
    REDIS -->|MISS| QUERY_DB --> RESTORE --> RETURN_DB
```

---

## 7. Saga Compensation — In-Game Purchase Flow

```mermaid
sequenceDiagram
    participant CM as Compensation Manager
    participant OrderSvc as Order Service
    participant PayGW as Payment Gateway
    participant DB as PostgreSQL

    Note over CM: Step 4 FAILED → trigger saga

    CM->>DB: SELECT saga_compensation_log<br/>WHERE execution_id = X<br/>ORDER BY step_order DESC

    Note over CM: Step 4: FAILED → skip (không cần compensate)
    Note over CM: Step 3: COMPLETED + trigger=auto → RUN
    Note over CM: Step 2: COMPLETED + trigger=auto → RUN
    Note over CM: Step 1: COMPLETED + trigger=none → skip

    CM->>DB: UPDATE saga_log step3 → COMPENSATING
    CM->>OrderSvc: POST /orders/{orderId}/cancel<br/>(orderId ← context_snapshot của step 3)
    OrderSvc-->>CM: 200 OK
    CM->>DB: UPDATE saga_log step3 → COMPENSATED ✅

    CM->>DB: UPDATE saga_log step2 → COMPENSATING
    CM->>PayGW: POST /payments/{transactionId}/refund<br/>(transactionId ← context_snapshot của step 2)
    PayGW-->>CM: 200 OK
    CM->>DB: UPDATE saga_log step2 → COMPENSATED ✅

    CM->>DB: UPDATE scenario_execution → COMPENSATED
```

### Compensation Failure — Dead Letter

```mermaid
flowchart TD
    CF([refund_payment FAILS])
    DB1[UPDATE saga_log → COMPENSATION_FAILED]
    DL[INSERT compensation_dead_letter]
    ALERT[Alert admin\nSlack / email]
    API[Expose API:\nGET /executions/{id}/compensation-status\nPOST /executions/{id}/retry-compensation]

    CF --> DB1 --> DL & ALERT & API
```

---

## 8. CDC + Reconciliation — Ghost Transaction Flow

```mermaid
flowchart TD
    classDef decision fill:#e8f4fd,stroke:#1a73e8
    classDef action   fill:#fce8b2,stroke:#b8860b
    classDef alert    fill:#ffcccc,stroke:#cc0000

    CDC([Debezium: action_execution → TIMEOUT])
    KAFKA[Kafka: console.action_execution.cdc]
    CONSUME[Reconciler consumes event]
    LOAD["Load action_definition\nverification config"]

    NO_VER{verification\nblock?}
    MANUAL[INSERT manual_reconciliation_queue\nAlert admin]:::alert

    CALL["GET 3rd party:\nservice.base_url + verification.endpoint\n?idempotency_key=SHA256(...)"]
    CHECK{3rd party\nstate?}

    CHARGED["DB=TIMEOUT, 3rd=CHARGED\n→ Ghost Transaction"]:::action
    NOT_FOUND["DB=TIMEOUT, 3rd=NOT_FOUND\n→ Clean timeout"]:::action
    PROCESSING["DB=TIMEOUT, 3rd=PROCESSING\n→ Still running"]:::action

    SCENARIO{Scenario\nstate?}
    AUTO_COMP["Trigger Saga compensation\n→ POST /refund"]:::action
    AUTO_DONE["UPDATE action_execution → COMPLETED\nSave transactionId to context\nNotify Scenario Engine continue"]:::action
    MARK_FAIL[UPDATE action_execution → FAILED\nSaga trigger compensation]:::action
    POLL[Poll lại sau poll_interval_ms]

    LOG[INSERT reconciliation_event\naudit log]

    CDC --> KAFKA --> CONSUME --> LOAD
    LOAD --> NO_VER
    NO_VER -->|"null"| MANUAL
    NO_VER -->|"has config"| CALL
    CALL --> CHECK

    CHECK -->|"CHARGED"| CHARGED
    CHECK -->|"NOT_FOUND"| NOT_FOUND
    CHECK -->|"PROCESSING"| PROCESSING

    CHARGED --> SCENARIO
    SCENARIO -->|"COMPENSATING"| AUTO_COMP
    SCENARIO -->|"IN_PROGRESS"| AUTO_DONE
    NOT_FOUND --> MARK_FAIL
    PROCESSING --> POLL

    AUTO_COMP & AUTO_DONE & MARK_FAIL & MANUAL --> LOG
```

---

## 9. Reconciliation State Matrix

```mermaid
flowchart LR
    classDef ghost   fill:#ffcccc,stroke:#cc0000
    classDef clean   fill:#ccffcc,stroke:#007700
    classDef pending fill:#fff9c4,stroke:#f9a825
    classDef orphan  fill:#e8d5ff,stroke:#6a1b9a

    T1["DB=TIMEOUT\n3rd=CHARGED"]:::ghost     -->|"Ghost Transaction"| A1["Auto-compensate\nhoặc Auto-complete"]
    T2["DB=TIMEOUT\n3rd=NOT_FOUND"]:::clean   -->|"Clean timeout"| A2["Mark FAILED\n→ Saga"]
    T3["DB=TIMEOUT\n3rd=PROCESSING"]:::pending-->|"Still running"| A3["Poll lại sau interval"]
    T4["DB=FAILED\n3rd=CHARGED"]:::ghost      -->|"Ghost Txn (5xx)"| A4["Auto-compensate"]
    T5["DB=FAILED\n3rd=NOT_FOUND"]:::clean    -->|"Clean failure"| A5["No action\nSaga đã xử lý"]
    T6["DB=COMPLETED\n3rd=NOT_FOUND"]:::orphan-->|"Orphan record"| A6["Alert admin\nManual queue"]
    T7["DB=COMPLETED\n3rd=CHARGED"]:::clean   -->|"Consistent ✅"| A7["No action"]
    T8["DB=COMP_FAIL\n3rd=CHARGED"]:::ghost   -->|"Stale dead letter"| A8["Retry compensation"]
```

---

## 10. Resume Scenario (Cache-Aside Restore)

```mermaid
sequenceDiagram
    actor User
    participant API as Console API
    participant Redis
    participant DB as PostgreSQL

    User->>API: POST /executions/{id}/resume

    API->>DB: SELECT scenario_execution\n(context JSONB snapshot)
    DB-->>API: execution + context JSON

    API->>API: Deserialize context JSON\n→ ContextDataItem[]

    Note over API,Redis: Restore 3-key model (atomic)
    API->>Redis: MSET  exec:{id}:*    (C keys — data items)
    API->>Redis: ZADD  index:step:{id} (A key — step index)
    API->>Redis: SADD  group:{id}:var:* (B keys — var index)

    API-->>User: resumable step info + restored context
```

---

## 11. Access Control — 3 Tầng

```mermaid
flowchart TD
    REQ([User request])

    subgraph GW["Tầng 1 — GameScale Console Gateway"]
        T1{"Menu access\ncheck via RoleStore"}
    end

    subgraph RBAC["Tầng 2 — Console API RBAC"]
        T2{"sc:user:{userId}:exec\nin Redis?"}
        T2b[GET /rolestore/users/{userId}/permissions]
        T2c["Cache SET sc:user:{userId}:exec\nTTL 5 phút"]
        T2d{"scenario_id\nin allowed set?"}
    end

    subgraph ACTION["Tầng 3 — Per-Action Check"]
        T3{"execution\nownership check"}
    end

    DENY1([❌ 403 Redirect error])
    DENY2([❌ 403 Forbidden])
    DENY3([❌ 403 Forbidden])
    ALLOW([✅ Execute])

    REQ --> T1
    T1 -->|"denied"| DENY1
    T1 -->|"allowed"| T2
    T2 -->|"MISS"| T2b --> T2c --> T2d
    T2 -->|"HIT"| T2d
    T2d -->|"not in set"| DENY2
    T2d -->|"in set"| T3
    T3 -->|"not owner"| DENY3
    T3 -->|"owner"| ALLOW
```

---

## 12. Redis Key Namespaces — Toàn bộ System

```mermaid
mindmap
  root((Redis Keys))
    Execution Context
      exec:{execId}:{sourceType}:{srcId}:{varId}
        String - ContextDataItem JSON
        TTL: execution lifetime
      index:step:{execId}
        ZSET - score=stepOrder
        TTL: execution lifetime
      group:{execId}:var:{varId}
        Set - members=C keys
        TTL: execution lifetime
    Idempotency
      dedup:{execId}:{triggerId}:{requestId}
        String - "1"
        TTL: 60s
      result:{execId}:{triggerId}:{requestId}
        String - response JSON
        TTL: 300s
    RBAC
      sc:user:{userId}:exec
        Set - allowed scenario IDs
        TTL: 5 phút
```


## 13. Shopee / Shopee Ads — Mock-only Manual Preview

```mermaid
flowchart TD
    A[Admin creates source and simulates Shop Account grant] --> B[Save manual connection and inactive frequency]
    B --> C[Provision through mock catalog and Swagger import]
    C --> D[Operator starts scenario with source and scenario grants]
    D --> E[Freeze configuration in shopee_run]
    E --> F[Trigger: recheck identity, grants, source and input allowlist]
    F --> G{Cached result?}
    G -->|Yes| H[Return sanitized summary]
    G -->|No| I[ActionExecutor and MockAdapter generate bounded fixtures]
    I --> J[Save output VARs through ContextStore]
    J --> H
    J --> K[Resume and preview read recheck current access]
```

Live provider access and durable scheduled sync remain separately blocked/gated; see [integration guide](11-shopee-mock-integration.md).

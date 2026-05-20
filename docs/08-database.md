# Database Schema (3NF)

> **3NF checklist:**
> - 1NF: atomic values, không có repeating group
> - 2NF: non-key attribute phụ thuộc vào toàn bộ PK
> - 3NF: không có transitive dependency (non-key → non-key)
>
> JSONB dùng khi structure variable và không bao giờ query theo từng field bên trong — đây là pragmatic exception, không phải 3NF violation.

---

## ERD

```
service ◀──── action_definition ────▶ retry_policy
                      │
         ┌────────────┼────────────┐
         ▼            ▼            ▼
action_compensation  action_    async_action_
                     verification  config

scenario_definition
  └── step_definition  (ui JSONB, schema JSONB)
        └── step_trigger ──▶ step_trigger_action ──▶ action_definition

scenario_execution ──▶ scenario_definition
  ├── trigger_execution ──▶ step_trigger
  └── action_execution ──▶ action_definition
        └── saga_compensation_log
              └── compensation_dead_letter

action_execution ──▶ reconciliation_event
```

> Không có tầng `block_definition`. Step trực tiếp chứa `ui` JSONB (widget layout) và `schema` JSONB (trigger config). UI và Action kết nối qua **Variable** (`VAR_ULID`) thay vì alias string.

---

## 1. Definition Layer

### service

```sql
CREATE TABLE service (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR NOT NULL UNIQUE,
    base_url   VARCHAR NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### retry_policy

```sql
CREATE TABLE retry_policy (
    id                     BIGSERIAL PRIMARY KEY,
    name                   VARCHAR NOT NULL UNIQUE,
    max_attempts           INT NOT NULL DEFAULT 3,
    backoff_type           VARCHAR NOT NULL CHECK (backoff_type IN ('exponential','linear','fixed')),
    initial_interval_ms    INT NOT NULL DEFAULT 1000,
    max_interval_ms        INT,
    retryable_status_codes INT[] NOT NULL DEFAULT '{502,503,504}',
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### scenario_definition

```sql
CREATE TABLE scenario_definition (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR NOT NULL,
    description TEXT,
    created_by  VARCHAR,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### step_definition

`ui` JSONB chứa widget layout (các widget có `outputVarId: VAR_ULID` do frontend tự gen) — giữ JSONB vì structure variable theo widget type và không query theo từng field bên trong.

Trigger config (trigger nào chạy action nào, theo thứ tự nào) **không** lưu trong JSONB — đã normalize sang `step_trigger` + `step_trigger_action` để FK thật với `trigger_execution` và đảm bảo ordering.

```sql
CREATE TABLE step_definition (
    id                     BIGSERIAL PRIMARY KEY,
    scenario_definition_id BIGINT NOT NULL REFERENCES scenario_definition(id),
    name                   VARCHAR NOT NULL,
    step_order             INT NOT NULL,
    ui                     JSONB NOT NULL DEFAULT '{}',   -- widget layout với outputVarId
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (scenario_definition_id, step_order)
);
```

### action_definition

`parameters` và `output` là JSONB — config arrays, không query theo field riêng lẻ, chỉ đọc toàn bộ để bind request.
`service_id` FK loại transitive `action_id → service_name → base_url`.
`retry_policy_id` FK loại transitive `action_id → max_attempts → backoff_type`.

```sql
CREATE TABLE action_definition (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR NOT NULL,
    service_id      BIGINT NOT NULL REFERENCES service(id),
    endpoint        VARCHAR NOT NULL,
    http_method     VARCHAR NOT NULL CHECK (http_method IN ('GET','POST','PUT','PATCH','DELETE')),
    adapter_type    VARCHAR NOT NULL CHECK (adapter_type IN ('http','async','mock')) DEFAULT 'http',
    timeout_ms      INT NOT NULL DEFAULT 10000,
    retry_policy_id BIGINT REFERENCES retry_policy(id),
    parameters      JSONB NOT NULL DEFAULT '[]',
    output          JSONB NOT NULL DEFAULT '[]',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### step_trigger

Tách ra khỏi `step_definition.schema` JSONB để `trigger_execution` có FK thật.

```sql
CREATE TABLE step_trigger (
    id                 BIGSERIAL PRIMARY KEY,
    step_definition_id BIGINT NOT NULL REFERENCES step_definition(id),
    name               VARCHAR NOT NULL,
    trigger_type       VARCHAR NOT NULL CHECK (trigger_type IN ('button_click','on_load','on_change')),
    widget_id          VARCHAR,  -- null khi trigger_type = 'on_load'
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### step_trigger_action

Junction table N:N có order giữa trigger và action — thay cho JSONB array để đảm bảo FK và ordering.

```sql
CREATE TABLE step_trigger_action (
    id                   BIGSERIAL PRIMARY KEY,
    step_trigger_id      BIGINT NOT NULL REFERENCES step_trigger(id),
    action_definition_id BIGINT NOT NULL REFERENCES action_definition(id),
    action_order         INT NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (step_trigger_id, action_order)
);
```

### action_compensation

1:1 với `action_definition` (optional). Tách ra để không có null columns trên action không có compensation.
`required_var_ids` thay `required_aliases` — lưu VAR_ULID cần thiết để chạy compensation.

```sql
CREATE TABLE action_compensation (
    id                                BIGSERIAL PRIMARY KEY,
    action_definition_id              BIGINT NOT NULL UNIQUE REFERENCES action_definition(id),
    compensation_action_definition_id BIGINT NOT NULL REFERENCES action_definition(id),
    trigger_type                      VARCHAR NOT NULL CHECK (trigger_type IN ('auto','manual','none')),
    required_var_ids                  TEXT[] NOT NULL DEFAULT '{}',  -- VAR_ULID[] cần cho compensation
    created_at                        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### action_verification

Config cho Reconciliation Service.

```sql
CREATE TABLE action_verification (
    id                   BIGSERIAL PRIMARY KEY,
    action_definition_id BIGINT NOT NULL UNIQUE REFERENCES action_definition(id),
    endpoint             VARCHAR NOT NULL,
    http_method          VARCHAR NOT NULL CHECK (http_method IN ('GET','POST')),
    query_params         JSONB,
    conditions           JSONB NOT NULL,
    poll_interval_ms     INT NOT NULL DEFAULT 5000,
    max_poll_attempts    INT NOT NULL DEFAULT 12,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### async_action_config

Chỉ tồn tại khi `adapter_type = 'async'`.

```sql
CREATE TABLE async_action_config (
    id                       BIGSERIAL PRIMARY KEY,
    action_definition_id     BIGINT NOT NULL UNIQUE REFERENCES action_definition(id),
    publish_to               VARCHAR NOT NULL,
    status_check_endpoint    VARCHAR,
    status_check_interval_ms INT,
    status_check_timeout_ms  INT,
    done_condition           VARCHAR,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## 2. Execution Layer

### scenario_execution

`current_step_order` là column riêng — indexable, không duplicate vào JSONB.

```sql
CREATE TABLE scenario_execution (
    id                     BIGSERIAL PRIMARY KEY,
    scenario_definition_id BIGINT NOT NULL REFERENCES scenario_definition(id),
    status                 VARCHAR NOT NULL CHECK (status IN (
        'PENDING','EXECUTING','COMPLETED','FAILED',
        'COMPENSATING','COMPENSATED','COMPENSATION_FAILED'
    )) DEFAULT 'PENDING',
    current_step_order     INT,
    created_by             VARCHAR,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### trigger_execution

`UNIQUE (scenario_execution_id, step_trigger_id)` — Tầng 3 idempotency.

```sql
CREATE TABLE trigger_execution (
    id                    BIGSERIAL PRIMARY KEY,
    scenario_execution_id BIGINT NOT NULL REFERENCES scenario_execution(id),
    step_trigger_id       BIGINT NOT NULL REFERENCES step_trigger(id),
    status                VARCHAR NOT NULL CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')) DEFAULT 'PENDING',
    request_id            VARCHAR,
    started_at            TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (scenario_execution_id, step_trigger_id)
);
```

### action_execution

`idempotency_key UNIQUE` — Tầng 4 idempotency. `s3_ref` chỉ set khi response payload > ~100KB.
`trigger_execution_id` nullable — compensation action không thuộc trigger cụ thể nào.

```sql
CREATE TABLE action_execution (
    id                    BIGSERIAL PRIMARY KEY,
    scenario_execution_id BIGINT NOT NULL REFERENCES scenario_execution(id),
    trigger_execution_id  BIGINT REFERENCES trigger_execution(id),
    action_definition_id  BIGINT NOT NULL REFERENCES action_definition(id),
    status                VARCHAR NOT NULL CHECK (status IN (
        'PENDING',      -- chưa bắt đầu
        'PROCESSING',   -- request đang được gửi (window ngắn)
        'COMPLETED',    -- nhận được response thành công
        'FAILED',       -- nhận được response lỗi rõ ràng (4xx/5xx)
        'TIMEOUT'       -- gửi request nhưng không nhận được response
    )) DEFAULT 'PENDING',
    idempotency_key       VARCHAR NOT NULL UNIQUE,
    response_snapshot     JSONB,
    s3_ref                VARCHAR,
    error_message         TEXT,
    started_at            TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## 3. Saga + Compensation Layer

### saga_compensation_log

> **Intentional deviations:**
> - `action_definition_id` — denorm vì `action_execution_id` có thể NULL (action chưa chạy)
> - `step_order` — denorm để tránh JOIN chain 5 bảng cho `ORDER BY step_order DESC` trong compensation hot path

```sql
CREATE TABLE saga_compensation_log (
    id                               BIGSERIAL PRIMARY KEY,
    scenario_execution_id            BIGINT NOT NULL REFERENCES scenario_execution(id),
    action_execution_id              BIGINT REFERENCES action_execution(id),
    action_definition_id             BIGINT NOT NULL REFERENCES action_definition(id),
    step_order                       INT NOT NULL,
    status                           VARCHAR NOT NULL CHECK (status IN (
        'PENDING','COMPENSATING','COMPENSATED','COMPENSATION_FAILED'
    )) DEFAULT 'PENDING',
    compensation_action_execution_id BIGINT REFERENCES action_execution(id),
    context_snapshot                 JSONB NOT NULL,
    created_at                       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### compensation_dead_letter

```sql
CREATE TABLE compensation_dead_letter (
    id                       BIGSERIAL PRIMARY KEY,
    saga_compensation_log_id BIGINT NOT NULL REFERENCES saga_compensation_log(id),
    scenario_execution_id    BIGINT NOT NULL REFERENCES scenario_execution(id),
    action_definition_id     BIGINT NOT NULL REFERENCES action_definition(id),
    context_snapshot         JSONB NOT NULL,
    failure_reason           TEXT,
    retry_count              INT NOT NULL DEFAULT 0,
    resolved                 BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## 4. Reconciliation Layer

### reconciliation_event

```sql
CREATE TABLE reconciliation_event (
    id                    BIGSERIAL PRIMARY KEY,
    scenario_execution_id BIGINT NOT NULL REFERENCES scenario_execution(id),
    action_execution_id   BIGINT NOT NULL REFERENCES action_execution(id),
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

## 5. Indexes

```sql
-- Definition layer
CREATE INDEX idx_step_def_scenario    ON step_definition(scenario_definition_id);
CREATE INDEX idx_step_trigger_step    ON step_trigger(step_definition_id);
CREATE INDEX idx_sta_trigger          ON step_trigger_action(step_trigger_id);
CREATE INDEX idx_action_def_service   ON action_definition(service_id);

-- Execution hot path
CREATE INDEX idx_scenario_exec_status ON scenario_execution(status)
    WHERE status IN ('EXECUTING','COMPENSATING');
CREATE INDEX idx_trigger_exec_scenario ON trigger_execution(scenario_execution_id);
CREATE INDEX idx_action_exec_scenario  ON action_execution(scenario_execution_id);
CREATE INDEX idx_action_exec_status    ON action_execution(status, updated_at)
    WHERE status IN ('PROCESSING','TIMEOUT');  -- Reconciler filter chính

-- Saga — compensation query: ORDER BY step_order DESC
CREATE INDEX idx_saga_log_exec_step ON saga_compensation_log(scenario_execution_id, step_order DESC);
CREATE INDEX idx_saga_log_pending   ON saga_compensation_log(status)
    WHERE status IN ('PENDING','COMPENSATING');

-- Dead letter — admin polling
CREATE INDEX idx_dead_letter_unresolved ON compensation_dead_letter(resolved, created_at)
    WHERE resolved = FALSE;

-- Reconciliation
CREATE INDEX idx_reconcile_action ON reconciliation_event(action_execution_id);
CREATE INDEX idx_reconcile_open   ON reconciliation_event(drift_type, detected_at)
    WHERE resolved_at IS NULL;
```

---

## 6. 3NF Summary

### Transitive dependencies đã loại bỏ

| Vi phạm ban đầu | Giải pháp |
|---|---|
| `action_definition → service_name → base_url` | Tách `service` table, FK `service_id` |
| `action_definition → max_attempts, backoff_type` (lặp) | Tách `retry_policy` table |
| `action_definition.schema.compensation` (nested) | Tách `action_compensation` table |
| `action_definition.schema.verification` (nested) | Tách `action_verification` table |
| `step_definition.schema.triggers[].actions[]` | Tách `step_trigger` + `step_trigger_action` |

### JSONB giữ nguyên — lý do

| Column | Lý do |
|---|---|
| `step_definition.ui` | Widget layout — 9+ types, mỗi type có fields khác nhau, chứa `outputVarId: VAR_ULID` |
| `action_definition.parameters` | Array config với `varId: VAR_ULID`, không query theo field riêng lẻ |
| `action_definition.output` | Như trên |
| `saga_compensation_log.context_snapshot` | Historical snapshot toàn bộ execution context tại một thời điểm |
| `action_execution.response_snapshot` | Raw 3rd party response — structure khác nhau theo từng service |

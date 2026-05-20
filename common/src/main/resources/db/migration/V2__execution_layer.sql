-- =====================================================================
-- V2 — Execution Layer
-- Per docs/08-database.md §2
-- Tables: scenario_execution, trigger_execution (Tầng 3 idempotency),
--         action_execution (Tầng 4 idempotency)
-- =====================================================================

CREATE TABLE scenario_execution (
    id                     BIGSERIAL PRIMARY KEY,
    scenario_definition_id BIGINT NOT NULL REFERENCES scenario_definition(id),
    status                 VARCHAR NOT NULL CHECK (status IN (
        'PENDING','EXECUTING','COMPLETED','FAILED',
        'COMPENSATING','COMPENSATED','COMPENSATION_FAILED'
    )) DEFAULT 'PENDING',
    current_step_order     INT,
    context                JSONB NOT NULL DEFAULT '{}',
    created_by             VARCHAR,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- trigger_execution: Tầng 3 idempotency. UNIQUE đảm bảo 1 trigger / 1 execution.
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

-- action_execution: Tầng 4 idempotency. idempotency_key = SHA256(execId + actDefId + sorted_params)
-- s3_ref chỉ set khi response_snapshot > ~100KB (claim check pattern).
-- trigger_execution_id nullable: compensation actions không có trigger execution.
CREATE TABLE action_execution (
    id                    BIGSERIAL PRIMARY KEY,
    scenario_execution_id BIGINT NOT NULL REFERENCES scenario_execution(id),
    trigger_execution_id  BIGINT REFERENCES trigger_execution(id),
    action_definition_id  BIGINT NOT NULL REFERENCES action_definition(id),
    status                VARCHAR NOT NULL CHECK (status IN (
        'PENDING',
        'PROCESSING',
        'COMPLETED',
        'FAILED',
        'TIMEOUT'
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

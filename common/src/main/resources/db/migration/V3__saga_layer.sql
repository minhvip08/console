-- =====================================================================
-- V3 — Saga + Compensation Layer
-- Per docs/08-database.md §3 + docs/06-saga-compensation.md
-- Tables: saga_compensation_log, compensation_dead_letter
-- =====================================================================

-- Intentional denormalization (docs/08-database.md §3):
-- - action_definition_id: action_execution_id có thể NULL (action chưa chạy)
-- - step_order: tránh JOIN chain 5 bảng cho ORDER BY step_order DESC trong hot path
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

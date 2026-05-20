-- =====================================================================
-- V4 — Reconciliation Layer
-- Per docs/08-database.md §4 + docs/07-cdc-reconciliation.md
-- Tables: reconciliation_event
-- =====================================================================

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

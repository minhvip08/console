-- =====================================================================
-- V1 — Definition Layer
-- Per docs/08-database.md §1
-- Tables: service, retry_policy, scenario_definition, step_definition,
--         action_definition, step_trigger, step_trigger_action,
--         action_compensation, action_verification, async_action_config
-- =====================================================================

CREATE TABLE service (
    id         VARCHAR PRIMARY KEY,
    name       VARCHAR NOT NULL UNIQUE,
    base_url   VARCHAR NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE retry_policy (
    id                     VARCHAR PRIMARY KEY,
    name                   VARCHAR NOT NULL UNIQUE,
    max_attempts           INT NOT NULL DEFAULT 3,
    backoff_type           VARCHAR NOT NULL CHECK (backoff_type IN ('exponential','linear','fixed')),
    initial_interval_ms    INT NOT NULL DEFAULT 1000,
    max_interval_ms        INT,
    retryable_status_codes INT[] NOT NULL DEFAULT '{502,503,504}',
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE scenario_definition (
    id          VARCHAR PRIMARY KEY,
    name        VARCHAR NOT NULL,
    description TEXT,
    created_by  VARCHAR,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- step_definition: chỉ ui JSONB (widget layout với outputVarId).
-- Trigger config được normalize sang step_trigger + step_trigger_action.
CREATE TABLE step_definition (
    id                     VARCHAR PRIMARY KEY,
    scenario_definition_id VARCHAR NOT NULL REFERENCES scenario_definition(id),
    name                   VARCHAR NOT NULL,
    step_order             INT NOT NULL,
    ui                     JSONB NOT NULL DEFAULT '{}',
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (scenario_definition_id, step_order)
);

CREATE TABLE action_definition (
    id              VARCHAR PRIMARY KEY,
    name            VARCHAR NOT NULL,
    service_id      VARCHAR NOT NULL REFERENCES service(id),
    endpoint        VARCHAR NOT NULL,
    http_method     VARCHAR NOT NULL CHECK (http_method IN ('GET','POST','PUT','PATCH','DELETE')),
    adapter_type    VARCHAR NOT NULL CHECK (adapter_type IN ('http','async','mock')) DEFAULT 'http',
    timeout_ms      INT NOT NULL DEFAULT 10000,
    retry_policy_id VARCHAR REFERENCES retry_policy(id),
    parameters      JSONB NOT NULL DEFAULT '[]',
    output          JSONB NOT NULL DEFAULT '[]',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE step_trigger (
    id                 VARCHAR PRIMARY KEY,
    step_definition_id VARCHAR NOT NULL REFERENCES step_definition(id),
    name               VARCHAR NOT NULL,
    trigger_type       VARCHAR NOT NULL CHECK (trigger_type IN ('button_click','on_load','on_change')),
    widget_id          VARCHAR,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE step_trigger_action (
    id                   VARCHAR PRIMARY KEY,
    step_trigger_id      VARCHAR NOT NULL REFERENCES step_trigger(id),
    action_definition_id VARCHAR NOT NULL REFERENCES action_definition(id),
    action_order         INT NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (step_trigger_id, action_order)
);

-- action_compensation: 1:1 với action_definition. required_var_ids là VAR_ULID array.
CREATE TABLE action_compensation (
    id                                VARCHAR PRIMARY KEY,
    action_definition_id              VARCHAR NOT NULL UNIQUE REFERENCES action_definition(id),
    compensation_action_definition_id VARCHAR NOT NULL REFERENCES action_definition(id),
    trigger_type                      VARCHAR NOT NULL CHECK (trigger_type IN ('auto','manual','none')),
    required_var_ids                  TEXT[] NOT NULL DEFAULT '{}',
    created_at                        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE action_verification (
    id                   VARCHAR PRIMARY KEY,
    action_definition_id VARCHAR NOT NULL UNIQUE REFERENCES action_definition(id),
    endpoint             VARCHAR NOT NULL,
    http_method          VARCHAR NOT NULL CHECK (http_method IN ('GET','POST')),
    query_params         JSONB,
    conditions           JSONB NOT NULL,
    poll_interval_ms     INT NOT NULL DEFAULT 5000,
    max_poll_attempts    INT NOT NULL DEFAULT 12,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE async_action_config (
    id                       VARCHAR PRIMARY KEY,
    action_definition_id     VARCHAR NOT NULL UNIQUE REFERENCES action_definition(id),
    publish_to               VARCHAR NOT NULL,
    status_check_endpoint    VARCHAR,
    status_check_interval_ms INT,
    status_check_timeout_ms  INT,
    done_condition           VARCHAR,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

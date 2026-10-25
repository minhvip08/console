-- Console-owned mock models. No vendor credentials, endpoints or schemas.
CREATE TABLE shopee_source (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    kind VARCHAR NOT NULL CHECK (kind IN ('SHOPEE', 'SHOPEE_ADS')),
    display_name VARCHAR(120) NOT NULL,
    description VARCHAR(1000),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    shop_account_id VARCHAR(80),
    authorized_until TIMESTAMPTZ,
    allowed_user_ids JSONB NOT NULL DEFAULT '[]'
);
CREATE TABLE shopee_connection (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    source_id BIGINT NOT NULL REFERENCES shopee_source(id),
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000),
    data_types JSONB NOT NULL,
    first_load_date DATE NOT NULL,
    lookback_days INT NOT NULL CHECK (lookback_days BETWEEN 1 AND 3650),
    frequency_minutes INT CHECK (frequency_minutes > 0), -- inactive preference only
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    scenario_definition_id BIGINT UNIQUE REFERENCES scenario_definition(id),
    action_definition_id BIGINT REFERENCES action_definition(id)
);
CREATE INDEX idx_shopee_connection_source ON shopee_connection(source_id);
CREATE TABLE shopee_run (
    execution_id BIGINT PRIMARY KEY REFERENCES scenario_execution(id),
    connection_id BIGINT NOT NULL REFERENCES shopee_connection(id),
    kind VARCHAR NOT NULL,
    shop_account_id VARCHAR(80) NOT NULL,
    data_types JSONB NOT NULL,
    first_load_date DATE NOT NULL,
    lookback_days INT NOT NULL,
    run_at TIMESTAMPTZ NOT NULL
);

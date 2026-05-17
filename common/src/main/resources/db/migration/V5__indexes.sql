-- =====================================================================
-- V5 — Indexes
-- Per docs/08-database.md §5
-- =====================================================================

-- Definition layer
CREATE INDEX idx_step_def_scenario  ON step_definition(scenario_definition_id);
CREATE INDEX idx_step_trigger_step  ON step_trigger(step_definition_id);
CREATE INDEX idx_sta_trigger        ON step_trigger_action(step_trigger_id);
CREATE INDEX idx_action_def_service ON action_definition(service_id);

-- Execution hot path
CREATE INDEX idx_scenario_exec_status ON scenario_execution(status)
    WHERE status IN ('EXECUTING','COMPENSATING');
CREATE INDEX idx_trigger_exec_scenario ON trigger_execution(scenario_execution_id);
CREATE INDEX idx_action_exec_scenario  ON action_execution(scenario_execution_id);

-- Reconciler filter: chỉ quan tâm PROCESSING (timeout candidate) + TIMEOUT
CREATE INDEX idx_action_exec_status ON action_execution(status, updated_at)
    WHERE status IN ('PROCESSING','TIMEOUT');

-- Saga: compensation_manager query ORDER BY step_order DESC
CREATE INDEX idx_saga_log_exec_step ON saga_compensation_log(scenario_execution_id, step_order DESC);
CREATE INDEX idx_saga_log_pending   ON saga_compensation_log(status)
    WHERE status IN ('PENDING','COMPENSATING');

-- Dead letter: admin polling
CREATE INDEX idx_dead_letter_unresolved ON compensation_dead_letter(resolved, created_at)
    WHERE resolved = FALSE;

-- Reconciliation
CREATE INDEX idx_reconcile_action ON reconciliation_event(action_execution_id);
CREATE INDEX idx_reconcile_open   ON reconciliation_event(drift_type, detected_at)
    WHERE resolved_at IS NULL;

-- =====================================================================
-- V6 — CDC Publication for Debezium
-- Per docs/10-aws-infrastructure.md §RDS + docs/07-cdc-reconciliation.md
--
-- Note: wal_level=logical phải set ở server level (ALTER SYSTEM hoặc
-- postgresql.conf) + restart — không phải migration scope.
-- Migration này chỉ tạo publication. Replication slot do Debezium tự tạo
-- khi connector start.
-- =====================================================================

CREATE PUBLICATION console_reconciliation_pub
    FOR TABLE action_execution, scenario_execution, saga_compensation_log;

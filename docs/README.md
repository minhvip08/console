# Console Service — Documentation Index

| File | Nội dung |
|---|---|
| [01-architecture.md](01-architecture.md) | System overview, Clean Architecture, package structure |
| [02-core-concepts.md](02-core-concepts.md) | Scenario/Step/Action, Variable system, JSONB schemas |
| [03-execution-engine.md](03-execution-engine.md) | Execution flows (start, step, resume, complete), adapter layer |
| [04-context-redis.md](04-context-redis.md) | Redis 3-key model, Lua script, 2PC, Cache-Aside |
| [05-idempotency.md](05-idempotency.md) | 4 tầng chống duplicate, button click flow |
| [06-saga-compensation.md](06-saga-compensation.md) | Saga pattern, compensation log, dead letter |
| [07-cdc-reconciliation.md](07-cdc-reconciliation.md) | Debezium, Kafka, Reconciliation engine, ghost transaction |
| [08-database.md](08-database.md) | 3NF schema, CREATE TABLE, indexes |
| [09-access-control.md](09-access-control.md) | RBAC, RoleStore, permission check points |
| [10-aws-infrastructure.md](10-aws-infrastructure.md) | AWS components, deployment, request flow end-to-end |
| [11-shopee-mock-integration.md](11-shopee-mock-integration.md) | Mock-only source/connection lifecycle, manual previews and live/sync gates |
| [debug.md](debug.md) | Production debugging flow: 3 pillars, toolchain (OTel/Jaeger/Datadog/Prometheus), playbook, interview Q&A |
| [debug-traceid-cloudwatch.md](debug-traceid-cloudwatch.md) | TraceId + CloudWatch Logs Insights setup chi tiết |
| [testing.md](testing.md) | Testing pyramid, tool stack (JUnit 5/Mockito/AssertJ/Testcontainers), naming convention, coverage targets, interview Q&A |

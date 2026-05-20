# CV Experience — Nexon Dev Vina

**Software Engineer** | Jan 2025 – Present

---

- Designed and built a **scenario-based unified execution console** from scratch for Nexon's Global Game Platform, enabling Technical PMs and Game Operators to run complex multi-service workflows (e.g., charge payment → create order → release in-game content) through a single access point instead of manually coordinating across separate service consoles — **reduced operational execution time by ~90%** and eliminated human errors from manual cross-system coordination.

- Architected a **3-service Spring Boot system** (execution engine, backoffice management, async reconciliation) sharing PostgreSQL, Redis, and Kafka — separated by SLO: hot-path execution (latency-sensitive), backoffice CRUD (management, latency-tolerant), reconciler (fully async), deployed on EKS with ArgoCD.

- Designed a **hierarchical data model** in PostgreSQL (3NF) covering scenario/step/action/execution layers; made explicit JSONB vs. normalized table decisions per column: normalized trigger configs and compensation/verification configs into proper FK tables, kept JSONB for variable-structure widget layouts and raw 3rd-party response snapshots.

- Built a **VAR\_ULID variable binding system** replacing human-readable alias strings — frontend generates collision-free IDs at connection time, runtime binds UI widget outputs to action parameters by ID lookup, eliminating alias collision bugs and rename-cascade issues.

- Implemented a **4-layer idempotency stack** (UI button state → Redis `SETNX` request dedup → PostgreSQL UNIQUE trigger lock → SHA256 action key) protecting against double-click, network retry, component remount, and cross-session duplicate execution.

- Designed a **Redis execution context** using a 3-key model (data item, step-ordered sorted set, varId group index) written atomically via Lua script; applied DB-first two-phase commit (PostgreSQL ACID as source of truth, Redis rebuilt via Cache-Aside on miss) ensuring zero data loss on Redis restarts.

- Implemented an **Orchestration Saga** for known failures (`FAILED` = received 4xx/5xx): Console API orchestrates reverse compensation actions in `step_order DESC`, using per-action `context_snapshot` to ensure compensation calls carry the exact variable values from the original execution; failed compensations escalate to a dead-letter table with admin alert and retry API.

- Built a **CDC + Reconciliation Service** for unknown outcomes (`TIMEOUT` = no response received): PostgreSQL WAL → Debezium → Kafka MSK → reconciler verifies 3rd-party state and resolves ghost transactions automatically (CHARGED→compensate or COMPLETED→continue flow), preventing users from being charged without receiving service.

- Designed a **step-by-step execution log** recording every action's status, inputs, outputs, and error state; integrated `TraceId` propagation with CloudWatch for direct drill-down from a failed execution to the exact log line in the downstream service.

- Authored full **system design documentation** (architecture, data model, execution flows, idempotency strategy, saga compensation, CDC reconciliation, Redis context model, RBAC) as canonical source of truth for the team.

---

**Tech:** Java 25, Spring Boot 4, PostgreSQL 15, Redis 7, Kafka (Amazon MSK), Debezium CDC, AWS (EKS, RDS Multi-AZ, ElastiCache, S3, CloudWatch), ArgoCD, Gradle multi-module

# AWS Infrastructure

## Full Architecture Diagram

```
Internet
    │
    ▼
┌──────────────────────────────────────────────────────────────────┐
│                         AWS Cloud                                │
│                                                                  │
│  ┌─────────────┐                                                 │
│  │  Route 53   │  DNS routing, health check failover             │
│  └──────┬──────┘                                                 │
│         │                                                        │
│  ┌──────▼──────┐                                                 │
│  │     ALB     │  Application Load Balancer                      │
│  │             │  SSL termination, path-based routing            │
│  └──────┬──────┘                                                 │
│         │                                                        │
│  ┌──────▼────────────────────────────────────────────────┐       │
│  │                  Amazon EKS Cluster                   │       │
│  │                                                       │       │
│  │  ┌──────────────────┐    ┌──────────────────────┐    │       │
│  │  │  Console Service │    │  Backoffice Service   │    │       │
│  │  │  (Spring Boot)   │    │  (Spring Boot)        │    │       │
│  │  │                  │    │                       │    │       │
│  │  │  - Execution Eng │    │  - CRUD Management    │    │       │
│  │  │  - Context Mgr   │    │  - Definition Builder │    │       │
│  │  │  - Saga Mgr      │    │                       │    │       │
│  │  └────────┬─────────┘    └───────────────────────┘    │       │
│  │           │                                            │       │
│  │  ┌────────▼──────────────────────────────────────┐    │       │
│  │  │         Reconciliation Service                 │    │       │
│  │  │         (Kafka consumer, state comparator)     │    │       │
│  │  └────────────────────────────────────────────────┘    │       │
│  └───────────────────────────────────────────────────────┘       │
│         │              │              │              │            │
│         ▼              ▼              ▼              ▼            │
│  ┌─────────────┐ ┌─────────────┐ ┌──────────┐ ┌──────────────┐  │
│  │  Amazon RDS │ │ElastiCache  │ │  Amazon  │ │  Amazon MSK  │  │
│  │ PostgreSQL  │ │   Redis     │ │    S3    │ │   (Kafka)    │  │
│  └─────────────┘ └─────────────┘ └──────────┘ └──────────────┘  │
│                                                       ▲           │
│  ┌──────────────────────────────────────────────┐    │           │
│  │           Debezium (MSK Connect)             │────┘           │
│  │  CDC từ PostgreSQL WAL → MSK Topics          │                │
│  └──────────────────────────────────────────────┘                │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐     │
│  │  CI/CD Pipeline                                         │     │
│  │  Amazon ECR ──▶ GitHub Actions ──▶ ArgoCD ──▶ EKS      │     │
│  └─────────────────────────────────────────────────────────┘     │
│                                                                  │
│  Amazon CloudWatch (Logs, Metrics, Alarms)                       │
└──────────────────────────────────────────────────────────────────┘
```

---

## Từng Component

### Route 53

- DNS routing cho `console.internal` và `backoffice.internal`
- Health check → automatic failover nếu ALB unhealthy
- Weighted routing cho blue/green deployment

```
console.internal     → ALB (Console + Backoffice)
kafka.internal       → MSK bootstrap brokers
```

---

### ALB (Application Load Balancer)

- SSL/TLS termination (cert từ ACM)
- Path-based routing:

```
/api/console/*    → Console Service (EKS target group)
/api/backoffice/* → Backoffice Service (EKS target group)
```

- Sticky sessions không cần thiết (stateless API, state ở Redis)
- Access logs → S3 → CloudWatch Logs Insights

---

### Amazon EKS

EKS cluster chạy 3 services:

| Service | Role | Replicas |
|---|---|---|
| `console-api` | Scenario execution, RBAC, Saga Manager | ≥ 2 (HPA) |
| `backoffice-api` | Definition CRUD, dataset management | ≥ 2 |
| `reconciliation-svc` | Kafka consumer, drift detection | ≥ 2 (consumer group) |

**HPA (Horizontal Pod Autoscaler):** scale theo CPU/memory khi execution load tăng.

**Ingress Controller (NGINX hoặc AWS Load Balancer Controller):** route từ ALB vào đúng service.

---

### Amazon RDS — PostgreSQL

**Role trong system:**

| Table group | Role |
|---|---|
| Definition tables | Source of truth cho scenario/step/action definitions |
| Execution tables | `scenario_execution`, `action_execution`, `trigger_execution` |
| Saga tables | `saga_compensation_log`, `compensation_dead_letter` |
| Reconciliation | `reconciliation_event` |

**Config:**

```
Engine:       PostgreSQL 15+
Instance:     db.r6g.xlarge (production)
Multi-AZ:     enabled (auto failover ~30s)
Storage:      gp3, auto-scaling
Backup:       automated daily, 7-day retention
Read Replica: 1 replica cho reporting / backoffice read queries
```

**Debezium cần:**

```sql
-- Enable logical replication
ALTER SYSTEM SET wal_level = logical;

-- Tạo publication cho CDC
CREATE PUBLICATION console_reconciliation_pub
  FOR TABLE action_execution, scenario_execution, saga_compensation_log;

-- Replication slot (Debezium tự tạo, nhưng cần monitor)
-- SELECT * FROM pg_replication_slots;
```

**Monitor replication slot lag** — Debezium giữ WAL, nếu consumer lag lớn → disk đầy:

```
CloudWatch metric: RDSFreeStorageSpace + custom metric từ pg_replication_slots.confirmed_flush_lsn
```

---

### Amazon ElastiCache — Redis

**Role trong system:**

| Use case | Redis key pattern | TTL |
|---|---|---|
| Execution Context (C key) | `exec:{execId}:{sourceType}:{srcId}:{varId}` | execution lifetime |
| Step Index (A key) | `index:step:{execId}` | execution lifetime |
| Variable Index (B key) | `group:{execId}:var:{varId}` | execution lifetime |
| Request Dedup (Tầng 2) | `dedup:{requestId}` | 60s |
| Result Cache | `result:{requestId}` | 300s |
| RBAC Cache | `sc:user:{userId}:exec` | 5 phút |

**Config:**

```
Engine:       Redis 7+
Mode:         Cluster mode disabled (single shard, simpler Lua script support)
              hoặc Cluster mode enabled nếu cần scale horizontal
Instance:     cache.r6g.large
Multi-AZ:     enabled với automatic failover
Backup:       RDB snapshot daily (context có thể restore từ PostgreSQL nên không critical)
```

**Lua Script atomic operations:** Tất cả 3-key model writes đều dùng Lua Script → cần Redis single shard hoặc hash tags để đảm bảo 3 keys cùng slot trong cluster mode.

---

### Amazon S3

**Role trong system:**

| Use case | S3 path | Trigger |
|---|---|---|
| Large response payload | `s3://console-artifacts/executions/{execId}/{actionExecId}.json` | response size > 100KB |
| Action execution archive | `s3://console-archive/action-logs/{year}/{month}/` | retention policy |
| Static assets | `s3://console-static/` | frontend build artifacts |

**Claim Check Pattern** khi response lớn:

```
Action response 5MB
  → Upload to S3
  → Lưu s3_ref trong action_execution.s3_ref
  → Debezium CDC event nhỏ, không vượt Kafka message limit (1MB)
  → Reconciler / Compensation Manager cần data → fetch từ S3 theo s3_ref
```

**Lifecycle policy:**

```
s3://console-artifacts/executions/*
  → 30 ngày: transition to S3 IA
  → 1 năm:   transition to Glacier
  → 3 năm:   expire (hoặc tuỳ compliance requirement)
```

---

### Amazon MSK — Kafka

**Role trong system:** Transport layer cho CDC events từ Debezium → Reconciliation Service.

```
Topics:
  console.action_execution.cdc
  console.scenario_execution.cdc
  console.saga_compensation_log.cdc
  console.reconciliation.dead-letter    ← events Reconciler không xử lý được
```

**Config:**

```
Version:      Kafka 3.5+
Brokers:      3 (Multi-AZ)
Replication:  3
Retention:    7 ngày (đủ để replay nếu Reconciler down)
Partition:    execution_id là partition key → đảm bảo order per execution
```

**Debezium MSK Connect config:**

```json
{
  "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
  "database.hostname": "console-rds.xxx.rds.amazonaws.com",
  "database.dbname":   "console",
  "database.server.name": "console",
  "table.include.list": "public.action_execution,public.scenario_execution,public.saga_compensation_log",
  "publication.name":  "console_reconciliation_pub",
  "slot.name":         "console_reconciliation_slot",
  "topic.prefix":      "console",
  "transforms":        "unwrap",
  "transforms.unwrap.type": "io.debezium.transforms.ExtractNewRecordState"
}
```

**Reconciliation Service consumer:**

```
Consumer group: reconciliation-service
Auto offset reset: earliest (replay từ đầu khi restart)
Commit: manual (sau khi xử lý xong và ghi reconciliation_event)
```

---

### Amazon ECR + ArgoCD — CI/CD

```
Developer push code
        │
        ▼
GitHub Actions
  ├── Build & Test
  ├── docker build
  └── docker push → Amazon ECR
                         │
                         ▼
                     ArgoCD (GitOps)
                     detect image tag change
                         │
                         ▼
                     Helm upgrade → EKS
                     (rolling update, zero downtime)
```

**Image tagging strategy:**

```
ecr.amazonaws.com/console-api:{git-sha}         ← immutable per commit
ecr.amazonaws.com/console-api:latest             ← không dùng trong production
ecr.amazonaws.com/console-api:v1.2.3             ← release tag
```

---

### Amazon CloudWatch

**Metrics cần monitor:**

| Metric | Ngưỡng alert | Ý nghĩa |
|---|---|---|
| `action_execution` PROCESSING > 5 phút | > 0 | Stuck action, Reconciler cần check |
| `saga_compensation_log` COMPENSATION_FAILED | > 0 | Dead letter cần xử lý |
| RDS replication slot lag | > 1GB | Debezium consumer lag, disk risk |
| ElastiCache memory usage | > 80% | Cần scale up Redis |
| MSK consumer group lag | > 10,000 messages | Reconciler chậm |

**Log groups:**

```
/ecs/console-api              → Application logs
/ecs/reconciliation-service   → Reconciler logs
/rds/postgresql               → DB slow query logs
/msk/console                  → Kafka broker logs
```

---

## Request Flow — End to End

```
User browser
    │ HTTPS
    ▼
Route 53 → ALB (SSL termination)
    │ HTTP/2
    ▼
EKS Ingress → console-api Pod
    │
    ├── Read context → ElastiCache Redis
    │
    ├── Write execution state → RDS PostgreSQL
    │         │ WAL
    │         ▼
    │     Debezium (MSK Connect)
    │         │ CDC event
    │         ▼
    │     Amazon MSK
    │         │ consume
    │         ▼
    │     reconciliation-svc Pod
    │         │ verify 3rd party
    │         ▼
    │     External Service API
    │
    ├── Large payload → Amazon S3
    │
    └── Return response → User browser
```

---

## Deployment Strategy

**Rolling update (default):**

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1
    maxUnavailable: 0
```

**Blue/Green (cho major releases):**

```
Route 53 weighted routing:
  console-blue.internal  → 100% traffic (current)
  console-green.internal → 0% traffic (new version)

After validation:
  console-blue.internal  → 0%
  console-green.internal → 100%
```

**Database migration:**

- Chạy migration trước khi deploy (expand-contract pattern)
- Không có breaking schema changes trong rolling update window
- RDS Multi-AZ failover test trước release

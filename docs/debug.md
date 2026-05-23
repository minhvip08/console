# Production Debugging Guide

> Tài liệu này cover toàn bộ flow debug trên production: từ alert → locate → root cause → fix. Phần **Tool Landscape** phù hợp cho phỏng vấn system design; phần **Console-specific** là quy trình thực tế của project này.

---

## Mục lục

1. [3 Trụ cột Observability](#1-3-trụ-cột-observability)
2. [Tool Landscape — phỏng vấn](#2-tool-landscape)
3. [Alert → Triage Flow](#3-alert--triage-flow)
4. [Hotfix Flow](#4-hotfix-flow)
5. [Console-specific Debug Flow](#5-console-specific-debug-flow)
6. [Debug từng layer](#6-debug-từng-layer)
7. [Common Production Issues & Playbook](#7-common-production-issues--playbook)
8. [Interview Q&A](#8-interview-qa)

---

## 1. 3 Trụ cột Observability

```
┌──────────────┬──────────────────────────────────────────┬────────────────────────────────┐
│  Pillar       │  Trả lời câu hỏi                         │  Tool phổ biến                 │
├──────────────┼──────────────────────────────────────────┼────────────────────────────────┤
│  Logs        │  Chuyện gì xảy ra? (what happened)       │  CloudWatch, Datadog, ELK Stack│
│  Metrics     │  Nghiêm trọng đến đâu? (how bad)         │  Prometheus, Grafana, Datadog  │
│  Traces      │  Xảy ra ở đâu trong hệ thống? (where)   │  Jaeger, Zipkin, AWS X-Ray     │
└──────────────┴──────────────────────────────────────────┴────────────────────────────────┘
```

Ba trụ liên kết với nhau qua **Trace ID** — một ID duy nhất xuyên suốt từ FE request → BE → DB → 3rd party call.

---

## 2. Tool Landscape

### 2.1 Distributed Tracing

#### OpenTelemetry (OTel) — chuẩn ngành hiện tại
- **Vai trò**: vendor-neutral instrumentation SDK, export sang bất kỳ backend nào (Jaeger, Datadog, X-Ray...)
- **Cách hoạt động**: tự động instrument Spring Boot (HTTP, JDBC, Redis, Kafka) qua Java agent
- **Key concepts**: Span (1 operation), Trace (cây các Span), Context Propagation (W3C `traceparent` header)

```bash
# Attach OTel Java agent khi start JVM
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.service.name=console-api \
     -Dotel.exporter.otlp.endpoint=http://otel-collector:4317 \
     -jar app.jar
```

#### Jaeger
- Open-source distributed tracing (CNCF), origin từ Uber
- UI cho phép xem flame graph của 1 request: span nào chậm, span nào failed
- Lưu trace vào Elasticsearch hoặc Cassandra

#### AWS X-Ray
- Managed alternative khi stack AWS (EKS + RDS + ElastiCache)
- Tích hợp native với ALB, Lambda, ECS — tự capture upstream/downstream call
- Service Map: visualize dependency graph theo real traffic

### 2.2 Metrics & Alerting

#### Prometheus + Grafana
- **Prometheus**: pull-based metrics store, scrape `/actuator/prometheus` endpoint
- **Grafana**: dashboard + alerting (AlertManager → PagerDuty/Slack)
- Spring Boot Actuator expose metrics tự động: `http_requests_total`, `jvm_memory_used_bytes`, `hikaricp_connections_active`

```yaml
# application.yml — expose metrics endpoint
management:
  endpoints.web.exposure.include: health, metrics, prometheus
  metrics.export.prometheus.enabled: true
```

#### Datadog (all-in-one)
- APM (traces) + Infrastructure metrics + Log Management + Synthetic Monitoring trong 1 platform
- Agent chạy trên mỗi node, tự correlate logs ↔ traces ↔ metrics theo `dd.trace_id`
- **Dùng khi**: team không muốn self-host nhiều tool; budget cho managed service

### 2.3 Log Aggregation

#### ELK Stack (Elasticsearch + Logstash + Kibana)
- **Logstash/Filebeat**: ship log từ container stdout → Elasticsearch
- **Kibana**: query, visualize, alert
- Thay Logstash bằng **Fluent Bit** (lightweight, viết bằng C) khi chạy trên K8s

#### CloudWatch Logs Insights (AWS)
- Native với EKS/ECS, zero-config khi dùng Container Insights addon
- Query language đơn giản nhưng đủ dùng cho phần lớn case
- Xem chi tiết: [debug-traceid-cloudwatch.md](debug-traceid-cloudwatch.md)

### 2.4 Incident Management

| Tool | Vai trò |
|---|---|
| **PagerDuty** | On-call rotation, alert routing, escalation policy |
| **OpsGenie** | Alternative PagerDuty (Atlassian ecosystem) |
| **Slack** | Incident channel, bot tích hợp alert |
| **Statuspage** | Communicate incident ra ngoài (user-facing) |

---

## 3. Alert → Triage Flow

```
Prometheus / Datadog phát hiện anomaly
         │
         ▼
AlertManager → PagerDuty → On-call engineer nhận page
         │
         ▼
1. Xác định severity
   P1: production down / data loss
   P2: degraded performance (latency spike)
   P3: non-critical error rate tăng

         │
         ▼
2. Mở Grafana → Service Overview dashboard
   - Error rate có spike không?
   - Latency p99 bao nhiêu?
   - Pod count có drop không? (OOMKill? CrashLoop?)

         │
         ▼
3. Mở Jaeger/X-Ray → tìm slow/error trace
   - Span nào bị đỏ?
   - External call (3rd party) hay internal DB?

         │
         ▼
4. Mở CloudWatch Logs Insights / Kibana
   - Filter theo traceId từ bước 3
   - Đọc stack trace, error message

         │
         ▼
5. Root cause → Fix → Monitor recovery
   - Deploy hotfix
   - Watch error rate drop về baseline
   - Close incident, viết postmortem
```

---

## 4. Hotfix Flow

> Rule số 1: **Mitigate trước, fix sau.** User đang bị ảnh hưởng — ưu tiên giảm thiệt hại ngay, điều tra sau.

### 4.1 Tổng quan — 5 giai đoạn

```
DETECT → MITIGATE → ROOT CAUSE → FIX → RECOVER
  │           │           │         │        │
  │           │           │         │        └── monitor + postmortem
  │           │           │         └── hotfix branch → fast review → deploy
  │           │           └── logs / traces / DB query
  │           └── rollback / feature flag / circuit breaker (trong vài phút)
  └── alert tự động (Prometheus) hoặc user report
```

---

### 4.2 Giai đoạn 1 — DETECT: phân loại severity ngay

Việc đầu tiên khi nhận alert: **classify P-level**, vì nó quyết định ai cần wake up và timeline phải fix.

| Level | Định nghĩa | SLA response | Ví dụ |
|---|---|---|---|
| **P1** | Production down hoàn toàn / data loss / security breach | < 15 phút | Tất cả scenario execution fail, DB down |
| **P2** | Feature bị ảnh hưởng nghiêm trọng, workaround có nhưng tệ | < 1 giờ | Saga không trigger → compensation bị miss |
| **P3** | Bug ảnh hưởng một số user, workaround ổn | < 1 ngày | UI hiển thị sai status nhưng data đúng |

**Ai cần notify:**
```
P1: on-call engineer + TL + PO + stakeholder ngay lập tức
P2: on-call engineer + TL
P3: assign ticket, fix trong business hours
```

---

### 4.3 Giai đoạn 2 — MITIGATE: dập lửa trong vài phút

**Không cần biết root cause** để mitigate. Mục tiêu: giảm user bị ảnh hưởng ngay.

#### Option A — Rollback (nhanh nhất nếu bug do deploy mới)

```bash
# Xem deployment history
kubectl rollout history deployment/console-api -n console

# Rollback về revision trước (ArgoCD: sync về commit cũ)
kubectl rollout undo deployment/console-api -n console

# Hoặc qua ArgoCD UI: App → History → Rollback to <commit>

# Verify rollback xong
kubectl rollout status deployment/console-api -n console
```

Rollback phù hợp khi: bug xuất hiện sau deploy mới, không có DB schema migration (migration không rollback được dễ).

#### Option B — Feature Flag (tắt feature bị lỗi, giữ service up)

```bash
# Ví dụ dùng env var làm feature flag đơn giản
kubectl set env deployment/console-api \
  FEATURE_SAGA_ENABLED=false \
  -n console

# Hoặc nếu có feature flag service (LaunchDarkly, Unleash):
# toggle flag "saga-compensation-enabled" → OFF
```

Feature flag phù hợp khi: chỉ 1 feature bị ảnh hưởng, phần còn lại của service hoạt động tốt.

#### Option C — Circuit Breaker thủ công (3rd party bị lỗi)

```bash
# Force open circuit breaker cho 1 service cụ thể
# (nếu dùng Resilience4j với Actuator endpoint)
curl -X POST http://<pod-ip>:8080/actuator/circuitbreakers/coupon-service/force-open
```

Dùng khi: 3rd party service bị lỗi, console cần fail fast thay vì chờ timeout 30s → user experience tệ hơn.

#### Option D — Scale down / Traffic shift (worst case)

```bash
# Nếu chỉ 1 pod bị lỗi (bad pod)
kubectl delete pod <bad-pod-name> -n console
# Deployment sẽ tự spin pod mới

# Nếu cần maintenance mode: return 503 tạm thời
kubectl scale deployment/console-api --replicas=0 -n console
# Nhớ update status page trước khi làm điều này
```

---

### 4.4 Giai đoạn 3 — ROOT CAUSE: tìm nguyên nhân

Sau khi đã mitigate (user bớt bị ảnh hưởng), mới bắt đầu điều tra. Không rush fix mà chưa hiểu rõ.

```
1. Timeline reconstruction
   - Lỗi bắt đầu lúc mấy giờ?
   - Có deploy mới trước đó không? (git log, ArgoCD history)
   - Có config change không? (K8s events, SSM Parameter Store history)
   - Traffic pattern có thay đổi không? (Grafana request rate)

2. Evidence collection (song song)
   - Logs: CloudWatch query theo traceId, executionId
   - Metrics: Grafana — error rate, latency, DB connections, JVM heap
   - Traces: Jaeger/X-Ray — span nào fail, latency breakdown
   - DB: pg_stat_statements, pg_stat_activity (lock?)
   - Kafka: consumer lag tăng không?

3. 5 Whys
   Lỗi: action_execution bị TIMEOUT mass
   Why 1: tại sao TIMEOUT? → coupon-service không trả response
   Why 2: tại sao coupon-service không trả? → database của họ bị lock
   Why 3: tại sao bị lock? → migration chạy full table scan lúc peak traffic
   Why 4: tại sao migration chạy peak giờ? → cron job schedule sai timezone
   Why 5: tại sao schedule sai? → thiếu UTC/JST clarification trong config
   → Root cause: timezone misconfiguration trong migration cron
```

---

### 4.5 Giai đoạn 4 — FIX: hotfix branch, không dùng main

```
                  main branch
                      │
         ┌────────────┘
         │
   hotfix/fix-saga-timeout-handling   ← tạo từ TAG đang chạy production
         │
         │  1. Fix minimal — chỉ sửa bug, không refactor thêm
         │  2. Viết test cover case đó
         │  3. Code review nhanh (ít nhất 1 người) — focus vào risk
         │  4. Merge vào hotfix branch
         │
         ▼
   Deploy hotfix branch lên production
   (ArgoCD: point image tag → hotfix build)
         │
         ▼
   Sau khi verify ổn: merge ngược về main
   (cherry-pick hoặc merge)
```

**Quy tắc viết hotfix:**
- Fix **đúng 1 thứ** — không tận dụng hotfix để clean up hay refactor
- Test case phải cover **exact scenario gây ra bug** — nếu không reproduce được, chưa chắc fix đúng
- Reviewer ưu tiên check: **có side effect không?** và **có regression không?**

**Fast review checklist:**
```
□ Fix có đúng root cause không hay chỉ mask triệu chứng?
□ Fix có break existing behavior của path khác không?
□ Có cần DB migration không? (migration không rollback được dễ)
□ Fix có thread-safe không? (production traffic cao hơn local)
□ Timeout / retry config có bị ảnh hưởng không?
```

---

### 4.6 Giai đoạn 5 — RECOVER: verify và đóng incident

```
Deploy hotfix xong → chưa phải xong!

1. Monitor chủ động 30 phút sau deploy
   - Error rate về 0 chưa?
   - Latency p99 về baseline chưa?
   - Không có new exception pattern nào không?

2. Verify data integrity
   - Có execution nào bị kẹt state sai không?
   - Cần backfill / re-run gì không?
   
   -- Ví dụ: tìm execution bị kẹt PROCESSING trong lúc incident
   SELECT id, status, started_at FROM scenario_execution
   WHERE status = 'RUNNING'
     AND started_at BETWEEN '<incident_start>' AND '<incident_end>';

3. Resolve incident, update status page

4. Postmortem (trong 48 giờ)
   - Timeline: detect lúc nào, mitigate lúc nào, fix lúc nào
   - Impact: bao nhiêu user, bao nhiêu execution bị ảnh hưởng
   - Root cause (đã xác định ở giai đoạn 3)
   - Action items: mỗi item có owner + due date cụ thể
   - Blameless: lỗi của system/process, không phải của người
```

---

### 4.7 Tổng timeline mục tiêu

| Giai đoạn | P1 | P2 |
|---|---|---|
| Detect → On-call ack | < 5 phút | < 15 phút |
| Ack → Mitigate (giảm impact) | < 15 phút | < 1 giờ |
| Mitigate → Root cause xác định | < 1 giờ | < 4 giờ |
| Root cause → Hotfix deploy | < 2 giờ | < 1 ngày |
| Postmortem published | < 48 giờ | < 1 tuần |

---

### 4.8 Những lỗi hay mắc khi fix hotfix

| Sai lầm | Hậu quả | Thay vào đó |
|---|---|---|
| Fix trực tiếp trên main, không test | Hotfix gây thêm bug | Luôn branch từ production tag |
| Rush fix mà chưa hiểu root cause | Fix sai, vấn đề quay lại | Mất thêm 10 phút để 5-Whys |
| Không rollback plan | Hotfix bị lỗi, mắc kẹt | Chuẩn bị rollback command trước khi deploy |
| Tắt alert trong lúc debug để "bớt noise" | Miss secondary failure | Chỉ mute alert đã handle, không mute hết |
| Không viết postmortem vì "nhỏ" | Lỗi tương tự lặp lại | P2 trở lên: postmortem bắt buộc |

---

## 5. Console-specific Debug Flow

Stack cụ thể của project này: **EKS + CloudWatch + PostgreSQL (RDS) + Redis (ElastiCache) + Kafka (MSK)**.

### 4.1 Khi user báo scenario bị lỗi

```
User: "Chạy scenario coupon release lúc 10:23 bị lỗi"

Step 1: Lấy traceId
  → User copy X-Trace-Id từ FE error toast
  → Hoặc query DB: SELECT trace_id FROM scenario_execution WHERE id = ?

Step 2: CloudWatch Logs Insights
  fields @timestamp, level, message, executionId, actionExecId
  | filter traceId = "<traceId>"
  | sort @timestamp asc

Step 3: Đọc log sequence
  10:23:45.100  INFO   Starting scenario exec_01JW...
  10:23:45.234  INFO   Calling action: coupon-service/issue
  10:23:45.890  ERROR  HTTP 503 from coupon-service
  10:23:45.892  INFO   Saga triggered for exec_01JW...

  → Lỗi từ coupon-service (3rd party), không phải bug code console
  → Check coupon-service health dashboard hoặc liên hệ team đó

Step 4: Verify DB state
  → scenario_execution.status = FAILED
  → action_execution.status = FAILED
  → saga_compensation_log: có COMPENSATED row không?
  → Nếu Saga chạy đúng: compensation đã reverse side effect
```

### 4.2 Khi scenario bị stuck (status = RUNNING mãi không xong)

```
Suspect: TIMEOUT chưa được Reconciler xử lý

1. Check action_execution
   SELECT * FROM action_execution
   WHERE scenario_execution_id = ?
     AND status = 'TIMEOUT'
   ORDER BY started_at DESC;

2. Check reconciliation_log (nếu có)
   → Reconciler đã pick up chưa?
   → Kafka consumer lag có cao không?

   # Kafka consumer lag
   kafka-consumer-groups.sh \
     --bootstrap-server <MSK_ENDPOINT>:9092 \
     --describe --group reconciliation-group

3. Check Redis — context còn không?
   redis-cli -u <ELASTICACHE_URL>
   KEYS "exec:<execId>:*"
   → Nếu keys đã expire: context lost, cần rebuild từ DB

4. Manual trigger reconciliation nếu cần
   POST /internal/reconcile/{executionId}
```

### 4.3 Khi nghi ngờ idempotency bị bypass (duplicate action chạy)

```
1. Query action_execution theo idempotency_key
   SELECT id, status, created_at
   FROM action_execution
   WHERE idempotency_key = SHA256('<execId>:<actDefId>:<sorted_params>');

   → Nếu thấy 2 rows: bug! UNIQUE constraint phải ngăn điều này
   → Nếu 1 row + COMPLETED: client retry nhưng idempotency đã chặn đúng

2. Check trigger_execution (Tầng 3)
   SELECT * FROM trigger_execution
   WHERE scenario_execution_id = ?
     AND step_trigger_id = ?;
   → UNIQUE (scenario_execution_id, step_trigger_id) phải đảm bảo 1 row

3. Check Redis dedup key (Tầng 2)
   GET "dedup:<requestId>"
   → Nếu đã expire (TTL 60s) nhưng DB chưa COMPLETED: race condition window
```

---

## 6. Debug từng layer

### 5.1 Application (Spring Boot)

```bash
# Xem live logs của 1 pod cụ thể
kubectl logs -f <pod-name> -n console --since=5m

# Xem logs tất cả pod cùng label
kubectl logs -l app=console-api -n console --since=5m

# Exec vào pod để check (nếu cần — hạn chế dùng prod)
kubectl exec -it <pod-name> -n console -- /bin/sh

# Thread dump khi CPU cao hoặc deadlock
kubectl exec <pod-name> -- kill -3 1   # gửi SIGQUIT → JVM print thread dump stdout
```

**Spring Boot Actuator endpoints:**
```
GET /actuator/health          # overall health
GET /actuator/health/db       # DB connection
GET /actuator/health/redis    # Redis connection
GET /actuator/metrics         # metrics list
GET /actuator/prometheus      # Prometheus scrape endpoint
GET /actuator/loggers/com.simi.console  # xem log level hiện tại
POST /actuator/loggers/com.simi.console # đổi log level runtime (không restart)
```

**Đổi log level runtime để debug (không restart pod):**
```bash
curl -X POST http://<pod-ip>:8080/actuator/loggers/com.simi.console \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel": "DEBUG"}'
# Nhớ set lại INFO sau khi xong để tránh log flood
```

### 5.2 Database (PostgreSQL)

```sql
-- Xem query chậm (pg_stat_statements phải enable)
SELECT query, calls, mean_exec_time, total_exec_time
FROM pg_stat_statements
ORDER BY mean_exec_time DESC
LIMIT 20;

-- Xem lock hiện tại (nghi ngờ deadlock)
SELECT pid, state, wait_event_type, wait_event, query
FROM pg_stat_activity
WHERE wait_event IS NOT NULL;

-- Xem lock pairs
SELECT
  blocking.pid AS blocking_pid,
  blocked.pid  AS blocked_pid,
  blocked.query AS blocked_query
FROM pg_locks blocked_locks
JOIN pg_stat_activity blocked  ON blocked.pid  = blocked_locks.pid
JOIN pg_locks blocking_locks   ON blocking_locks.transactionid = blocked_locks.transactionid
JOIN pg_stat_activity blocking ON blocking.pid = blocking_locks.pid
WHERE NOT blocked_locks.granted;

-- Xem index usage (index bị miss?)
SELECT relname, idx_scan, seq_scan
FROM pg_stat_user_tables
ORDER BY seq_scan DESC;
```

### 5.3 Redis (ElastiCache)

```bash
redis-cli -u <REDIS_URL>

# Monitor commands realtime (CHỈ dùng khi traffic thấp)
MONITOR

# Xem memory usage
INFO memory

# Xem slow log
SLOWLOG GET 25

# Xem tất cả keys của 1 execution (dùng SCAN, không dùng KEYS trên prod)
SCAN 0 MATCH "exec:12345:*" COUNT 100

# Xem TTL của 1 key
TTL "exec:12345:action:67890:VAR_01ABC"

# Xem Lua script cache
SCRIPT EXISTS <sha1>
```

**Lưu ý**: dùng `SCAN` thay `KEYS` trên production — `KEYS` block Redis trong khi scan toàn bộ keyspace.

### 5.4 Kafka (MSK)

```bash
# Consumer group lag — metric quan trọng nhất
kafka-consumer-groups.sh \
  --bootstrap-server <MSK>:9092 \
  --describe --group reconciliation-group

# Output: TOPIC | PARTITION | CURRENT-OFFSET | LOG-END-OFFSET | LAG
# LAG > 0 và tăng dần → consumer chậm hơn producer (vấn đề)

# Xem messages trong topic (debug, không dùng prod thường xuyên)
kafka-console-consumer.sh \
  --bootstrap-server <MSK>:9092 \
  --topic action-execution-events \
  --from-beginning \
  --max-messages 10

# List consumer groups
kafka-consumer-groups.sh \
  --bootstrap-server <MSK>:9092 \
  --list
```

---

## 7. Common Production Issues & Playbook

### Issue 1: P99 latency spike đột ngột

```
Symptoms: Grafana alert, latency từ 200ms → 3s

Checklist:
□ CPU/Memory của pods → kubectl top pods
□ DB connection pool exhausted → Actuator metrics hikaricp_connections_active
□ Redis timeout → log "RedisCommandTimeoutException"
□ 3rd party slow → Jaeger trace, xem span của external call
□ GC pressure → JVM metrics jvm_gc_pause_seconds

Fix nhanh:
- DB pool cạn: tăng maximum-pool-size tạm, điều tra query chậm
- 3rd party slow: circuit breaker trip chưa? Check Resilience4j metrics
- GC pressure: heap dump → MAT analysis
```

### Issue 2: action_execution kẹt ở PROCESSING

```
Symptoms: execution không complete, user complain

Root causes:
A. Pod crash sau khi update status → PROCESSING nhưng trước khi gọi API
B. 3rd party không trả response (network timeout)
C. Kafka message delivery fail (dead letter queue)

Debug:
1. SELECT * FROM action_execution WHERE status='PROCESSING' AND started_at < NOW() - INTERVAL '5 minutes'
2. Check pod crash: kubectl get events -n console
3. Check dead letter: kafka-console-consumer.sh --topic action-execution-dlq
4. Reconciler sẽ detect TIMEOUT qua CDC → tự recover trong ~2 phút

Manual fix (nếu cần):
- Nếu biết 3rd party đã fail: UPDATE action_execution SET status='FAILED' → trigger Saga
- Nếu biết 3rd party đã success: UPDATE → COMPLETED + insert response_snapshot
```

### Issue 3: Redis Cache Miss storm (thundering herd)

```
Symptoms: DB CPU spike đột ngột sau Redis restart/failover

Lý do: Mọi request hit DB cùng lúc vì cache empty

Mitigation:
1. Probabilistic early expiration: không để tất cả key cùng TTL
   TTL = base_ttl + random(0, base_ttl * 0.1)

2. Cache warming: sau restart, preload hot execution data trước khi open traffic

3. Circuit breaker trên DB: nếu DB latency > threshold → fail fast, trả 503
   → Tránh DB cascade failure
```

### Issue 4: Idempotency key collision (hiếm nhưng nguy hiểm)

```
Symptoms: action chạy 2 lần, user bị charge 2 lần

Debug:
1. action_execution có 2 rows cùng content không?
   → Nếu có: SHA256 collision hoặc bug trong key generation
2. trigger_execution có duplicate không?
   → UNIQUE constraint phải đã prevent tại DB level

Root cause investigation:
- SHA256 collision: lý thuyết không xảy ra trong thực tế
- Thực tế hay gặp hơn: params sorting không deterministic
  → sorted_params phải sort theo key alphabetically, không theo insertion order
  → JSONObject trong Java không guarantee order!
  → Dùng TreeMap hoặc sort keys explicitly trước khi SHA256
```

---

## 8. Interview Q&A

### Q: Làm sao debug khi không có access vào production database?

**A:** Thiết kế hệ thống để không cần direct DB access:
1. **Structured logging**: log đủ context (executionId, actionExecId, params) nên query log là đủ
2. **Read-only API endpoint**: `/internal/executions/{id}/debug` expose state từ DB theo format an toàn
3. **Audit log**: mọi state transition đều được log → reconstruct timeline từ log
4. Direct DB access chỉ dùng trong emergency với approval và audit trail

---

### Q: Distributed tracing hoạt động thế nào qua nhiều service?

**A:** Dùng **Context Propagation** theo chuẩn W3C Trace Context:
1. Service A nhận request, tạo Trace ID và Root Span
2. Khi gọi Service B, inject header: `traceparent: 00-<traceId>-<parentSpanId>-01`
3. Service B extract header, tạo Child Span với cùng Trace ID
4. Tất cả spans collect về Jaeger/X-Ray, visualize thành 1 trace tree

OpenTelemetry Java agent tự động làm bước 2-3 cho HTTP, gRPC, Kafka.

---

### Q: Metrics vs Logs vs Traces — khi nào dùng cái nào?

**A:**
- **Metrics** dùng để *detect* vấn đề: error rate tăng, latency spike → set alert ở đây
- **Traces** dùng để *locate* vấn đề: request chậm ở span nào, bottleneck ở service nào
- **Logs** dùng để *understand* vấn đề: exactly what happened, stack trace, business context

Flow điển hình: **Metric alert → Trace tìm span lỗi → Log đọc chi tiết**

---

### Q: Blue/Green vs Canary deployment, debug khi nào dùng cái nào?

**A:**
- **Blue/Green**: cut traffic toàn bộ từ old → new. Rollback instant nhưng risk cao (100% user thấy lỗi nếu bug)
- **Canary**: route 5% → 20% → 100% traffic dần. Bug chỉ ảnh hưởng % nhỏ; dùng metrics so sánh canary vs stable để detect regression sớm

Debug với Canary: filter metrics/logs theo `deployment=canary` để compare. Nếu canary error rate > stable → rollback ngay.

---

### Q: Production incident response, bước đầu tiên là gì?

**A:** **STOP → LOCATE → FIX**, không phải FIX ngay:
1. **Contain blast radius**: feature flag off, circuit breaker manual trip, hoặc rollback ngay nếu biết commit gây ra
2. **Locate root cause**: metrics → traces → logs (thứ tự từ nhanh → chậm)
3. **Communicate**: update status page, ping stakeholders — đừng im lặng debug
4. **Fix**: hotfix hoặc rollback
5. **Postmortem**: 5 Whys, timeline, action items — không blame người

---

### Q: Tại sao cần cả 4 tầng idempotency? 1 tầng không đủ?

**A:** Mỗi tầng cover 1 failure mode khác nhau:
- **Tầng 1 (UI lock)**: prevent double-click, nhưng bypass được nếu user dùng API trực tiếp
- **Tầng 2 (Redis SETNX requestId)**: fast check in-flight, nhưng expire sau 60s và Redis có thể restart
- **Tầng 3 (DB UNIQUE trigger_execution)**: ACID guarantee, nhưng chỉ scope trong 1 scenario execution
- **Tầng 4 (SHA256 action idempotency_key)**: cross-session, cross-execution dedup — cover trường hợp retry sau crash của chính system

Defense in depth: mỗi tầng là 1 net, lỗ của tầng này được tầng khác catch.

---

## Tham khảo thêm

- [debug-traceid-cloudwatch.md](debug-traceid-cloudwatch.md) — TraceId + CloudWatch setup chi tiết
- [07-cdc-reconciliation.md](07-cdc-reconciliation.md) — Reconciler tự heal TIMEOUT execution
- [05-idempotency.md](05-idempotency.md) — 4 tầng chống duplicate chi tiết
- [10-aws-infrastructure.md](10-aws-infrastructure.md) — AWS infrastructure, EKS setup

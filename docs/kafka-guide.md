# Kafka — Configuration Guide & Interview Q&A

> Phiên bản áp dụng: Kafka 3.5+ (MSK). Các config dưới đây dùng trong context của project này (Debezium CDC, reconciliation consumer).

---

## 1. Core Concepts

```
Producer → Topic (Partition 0..N) → Consumer Group
                  ↓
              Broker (Leader + Replicas)
                  ↓
              ZooKeeper / KRaft (Kafka 3.3+)
```

| Term | Ý nghĩa |
|---|---|
| **Topic** | Kênh logical để publish/consume message |
| **Partition** | Đơn vị parallelism — message trong 1 partition có thứ tự |
| **Offset** | Vị trí của message trong partition (tăng dần, không reset) |
| **Consumer Group** | Nhiều consumer chia nhau consume — 1 partition → 1 consumer tại 1 thời điểm |
| **ISR** | In-Sync Replicas — replicas đang follow leader trong ngưỡng lag |
| **Rebalance** | Sắp xếp lại partition assignment khi consumer join/leave group |
| **Retention** | Thời gian / dung lượng lưu message trên disk |

---

## 2. Broker Configuration

File: `server.properties` (hoặc MSK cluster config)

```properties
# ── Identity ──────────────────────────────────────────────
broker.id=0                          # Unique per broker trong cluster
# KRaft mode (Kafka 3.3+ không cần ZooKeeper)
process.roles=broker,controller
node.id=1

# ── Network ───────────────────────────────────────────────
listeners=PLAINTEXT://:9092,SSL://:9093
advertised.listeners=PLAINTEXT://broker1.internal:9092
num.network.threads=3
num.io.threads=8
socket.send.buffer.bytes=102400
socket.receive.buffer.bytes=102400
socket.request.max.bytes=104857600   # 100 MB

# ── Log / Storage ─────────────────────────────────────────
log.dirs=/var/kafka/data
log.retention.hours=168              # 7 ngày
log.retention.bytes=-1               # -1 = không giới hạn theo size
log.segment.bytes=1073741824         # 1 GB mỗi segment file
log.cleanup.policy=delete            # delete | compact | delete,compact

# ── Replication ───────────────────────────────────────────
default.replication.factor=3         # Số replica mặc định
min.insync.replicas=2                # Phải có ≥2 ISR mới ack WRITE
unclean.leader.election.enable=false # Không bầu replica lạc hậu làm leader

# ── Performance ───────────────────────────────────────────
num.partitions=6                     # Số partition mặc định khi tạo topic
num.replica.fetchers=2
background.threads=10

# ── Consumer offset ───────────────────────────────────────
offsets.topic.replication.factor=3
offsets.retention.minutes=10080      # 7 ngày

# ── Transaction ───────────────────────────────────────────
transaction.state.log.replication.factor=3
transaction.state.log.min.isr=2
```

---

## 3. Producer Configuration

```properties
# ── Connection ────────────────────────────────────────────
bootstrap.servers=broker1:9092,broker2:9092,broker3:9092

# ── Serialization ─────────────────────────────────────────
key.serializer=org.apache.kafka.common.serialization.StringSerializer
value.serializer=org.apache.kafka.common.serialization.StringSerializer
# Hoặc dùng Avro + Schema Registry:
# value.serializer=io.confluent.kafka.serializers.KafkaAvroSerializer

# ── Reliability (bộ 3 quan trọng nhất) ────────────────────
acks=all          # 0 | 1 | all(=-1)
                  # 0: fire-and-forget
                  # 1: leader ack
                  # all: đợi tất cả ISR ack → không mất data
retries=2147483647               # MAX_INT — để idempotent producer tự quyết
delivery.timeout.ms=120000       # 2 phút tổng thời gian retry

# ── Idempotent / Exactly-once ─────────────────────────────
enable.idempotence=true          # BẮT BUỘC khi acks=all
max.in.flight.requests.per.connection=5   # ≤5 khi idempotent

# Transaction (Exactly-once semantics)
transactional.id=console-producer-${instanceId}

# ── Batching & Throughput ─────────────────────────────────
batch.size=16384                 # 16 KB — gom nhiều message thành 1 batch
linger.ms=5                      # Chờ thêm 5ms để gom batch lớn hơn
buffer.memory=33554432           # 32 MB buffer phía producer
compression.type=snappy          # none | gzip | snappy | lz4 | zstd
max.block.ms=60000               # Block send() tối đa 60s khi buffer đầy

# ── Partitioning ──────────────────────────────────────────
# Mặc định: hash(key) % numPartitions
# Custom: partitioner.class=com.example.CustomPartitioner
```

### Bộ 3 producer config phải nhớ

| Mục tiêu | Config |
|---|---|
| Không mất message | `acks=all` + `min.insync.replicas=2` + `enable.idempotence=true` |
| Throughput cao | `linger.ms=5-20` + `batch.size=65536` + `compression.type=lz4` |
| Exactly-once | Thêm `transactional.id` + wrap trong `beginTransaction/commitTransaction` |

---

## 4. Consumer Configuration

```properties
# ── Connection ────────────────────────────────────────────
bootstrap.servers=broker1:9092,broker2:9092,broker3:9092
group.id=reconciliation-consumer-group

# ── Deserialization ───────────────────────────────────────
key.deserializer=org.apache.kafka.common.serialization.StringDeserializer
value.deserializer=org.apache.kafka.common.serialization.StringDeserializer

# ── Offset management ─────────────────────────────────────
auto.offset.reset=earliest       # earliest | latest | none
enable.auto.commit=false         # Tắt auto commit — commit thủ công sau xử lý
# Nếu bật auto commit:
auto.commit.interval.ms=5000

# ── Fetch tuning ──────────────────────────────────────────
fetch.min.bytes=1                # Broker trả ngay khi có ≥1 byte
fetch.max.wait.ms=500            # Hoặc đợi tối đa 500ms
fetch.max.bytes=52428800         # 50 MB per fetch
max.partition.fetch.bytes=1048576 # 1 MB per partition per fetch
max.poll.records=500             # Số record mỗi lần poll()

# ── Liveness / Rebalance ──────────────────────────────────
session.timeout.ms=45000         # Broker coi consumer dead sau 45s không heartbeat
heartbeat.interval.ms=3000       # Heartbeat mỗi 3s (≤ session.timeout/3)
max.poll.interval.ms=300000      # Tối đa 5 phút giữa 2 lần poll() — nếu quá → leave group
```

### Commit strategy

```java
// Manual commit — đảm bảo at-least-once
ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
for (ConsumerRecord<String, String> record : records) {
    process(record);
}
consumer.commitSync();   // hoặc commitAsync() cho throughput cao hơn

// Commit theo từng offset (fine-grained)
Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
offsets.put(new TopicPartition(record.topic(), record.partition()),
            new OffsetAndMetadata(record.offset() + 1));
consumer.commitSync(offsets);
```

---

## 5. Topic Configuration

```bash
# Tạo topic
kafka-topics.sh --create \
  --bootstrap-server broker:9092 \
  --topic action-execution-events \
  --partitions 6 \
  --replication-factor 3 \
  --config retention.ms=604800000 \    # 7 ngày
  --config min.insync.replicas=2 \
  --config compression.type=snappy

# Alter config
kafka-configs.sh --alter \
  --bootstrap-server broker:9092 \
  --entity-type topics \
  --entity-name action-execution-events \
  --add-config retention.ms=86400000   # Giảm xuống 1 ngày
```

### Topic configs quan trọng

| Config | Default | Ý nghĩa |
|---|---|---|
| `retention.ms` | 604800000 (7d) | Xóa message sau N ms |
| `retention.bytes` | -1 | Xóa khi topic vượt N bytes |
| `cleanup.policy` | delete | `delete` / `compact` / cả hai |
| `min.insync.replicas` | 1 | Phải override = 2 cho production |
| `compression.type` | producer | Broker nén lại nếu set khác producer |
| `max.message.bytes` | 1048588 | Tăng nếu message lớn (e.g. Debezium snapshot) |
| `segment.ms` | 604800000 | Rotate segment file mỗi 7 ngày |

---

## 6. Spring Boot Kafka Config (dự án này)

```yaml
# application.yml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      retries: 3
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 5
        delivery.timeout.ms: 120000
    consumer:
      group-id: reconciliation-service
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      auto-offset-reset: earliest
      enable-auto-commit: false
      properties:
        max.poll.records: 100
        max.poll.interval.ms: 300000
        spring.json.trusted.packages: "com.simi.*"
    listener:
      ack-mode: MANUAL_IMMEDIATE   # commit sau khi xử lý xong
      concurrency: 3               # 3 thread mỗi @KafkaListener
```

```java
@KafkaListener(topics = "action-execution-timeout", groupId = "reconciliation-service")
public void handleTimeout(
        @Payload ActionTimeoutEvent event,
        Acknowledgment ack) {
    reconciliationUseCase.process(event);
    ack.acknowledge();   // commit offset chỉ sau khi xử lý thành công
}
```

---

## 7. Interview Q&A

### Cơ bản

**Q: Kafka là gì? Khác gì với message queue truyền thống (RabbitMQ)?**

> Kafka là distributed event streaming platform, lưu message dưới dạng append-only log trên disk. Khác với queue truyền thống:
> - Kafka **retain** message sau khi consume (xóa theo retention policy, không phải sau khi đọc).
> - Consumer chủ động pull (không phải broker push).
> - Nhiều consumer group có thể đọc cùng 1 topic độc lập nhau.
> - Throughput cực cao (triệu msg/s) nhờ sequential disk I/O + zero-copy sendfile.
> - RabbitMQ phù hợp khi cần routing phức tạp (exchange/binding); Kafka phù hợp khi cần replay, event sourcing, high throughput.

---

**Q: Tại sao Kafka nhanh?**

> 4 lý do chính:
> 1. **Sequential disk write** — append-only log, tránh random seek.
> 2. **Zero-copy** — dùng `sendfile()` syscall, data đi thẳng disk → NIC không qua user space.
> 3. **Batching** — gom nhiều message thành 1 batch, giảm overhead network.
> 4. **Page cache** — OS cache aggressive, read thường serve từ RAM.

---

**Q: Partition là gì? Nên có bao nhiêu partition?**

> Partition là đơn vị parallelism. Consumer group có thể đọc song song tối đa N partition với N consumer.
> - Nhiều partition → throughput cao hơn, nhưng tăng overhead (metadata, file handle, rebalance time).
> - Rule of thumb: `partitions = max(throughput_target / throughput_per_partition, num_consumers_peak)`.
> - Đừng set quá nhiều — 1 topic nên ≤ vài chục partition trừ khi throughput thực sự đòi hỏi.
> - **Lưu ý**: Tăng partition sau dễ; giảm partition không được hỗ trợ → phải recreate topic.

---

**Q: Replication Factor và ISR là gì?**

> - **Replication Factor**: số bản sao của mỗi partition. RF=3 → 1 leader + 2 follower.
> - **ISR (In-Sync Replicas)**: tập replica đang sync với leader trong ngưỡng lag (`replica.lag.time.max.ms=10000`).
> - `acks=all` + `min.insync.replicas=2` → producer chỉ nhận ack khi ≥2 ISR đã ghi → không mất data kể cả khi 1 broker chết.
> - `unclean.leader.election.enable=false` → không bầu replica lạc hậu → đổi durability > availability.

---

**Q: Offset commit và at-least-once / at-most-once / exactly-once?**

> | Semantic | Cách đạt được |
> |---|---|
> | **At-most-once** | Commit offset trước khi xử lý → nếu crash sau commit, message mất |
> | **At-least-once** | Commit offset sau khi xử lý → crash trước commit → đọc lại → duplicate |
> | **Exactly-once** | Producer idempotent + transaction + consumer trong cùng transaction |
>
> Trong thực tế: **at-least-once + idempotent consumer** (dedup bằng `idempotency_key` ở DB) là pattern phổ biến nhất, vì exactly-once Kafka transactions phức tạp và chỉ hoạt động trong Kafka ecosystem.

---

**Q: Rebalance là gì? Khi nào xảy ra? Có vấn đề gì?**

> Rebalance = broker coordinator phân lại partition cho consumer group. Xảy ra khi:
> - Consumer mới join group.
> - Consumer leave (graceful hoặc crash / `session.timeout.ms` hết).
> - Topic partition thay đổi.
>
> Vấn đề: **stop-the-world rebalance** — toàn bộ consumer group dừng consume trong khi rebalance. Giải pháp:
> - **Cooperative/Incremental rebalance** (`partition.assignment.strategy=CooperativeStickyAssignor`) — Kafka 2.4+ — chỉ revoke partition thực sự cần di chuyển.
> - Tăng `session.timeout.ms` và `max.poll.interval.ms` cho consumer xử lý chậm.
> - Dùng static group membership (`group.instance.id`) để consumer restart không trigger rebalance.

---

**Q: Consumer lag là gì? Monitor thế nào?**

> Consumer lag = `log-end-offset - consumer-committed-offset` — số message chưa được consume.
>
> Monitor:
> ```bash
> kafka-consumer-groups.sh --bootstrap-server broker:9092 \
>   --group reconciliation-service --describe
> ```
> Hoặc dùng: **Burrow** (LinkedIn), **Grafana + JMX Exporter**, **MSK CloudWatch** metric `SumOffsetLag`.
>
> Lag tăng đột ngột → consumer chậm hoặc chết → cần scale out (thêm consumer, tăng partition) hoặc tối ưu processing.

---

### Nâng cao

**Q: Kafka đảm bảo ordering thế nào?**

> Kafka chỉ đảm bảo **ordering trong 1 partition**. Để đảm bảo message liên quan đến cùng 1 entity được xử lý theo thứ tự:
> - Set **message key** = entity ID (`scenario_execution_id`, `user_id`...).
> - Kafka sẽ route tất cả message cùng key vào cùng 1 partition.
> - 1 partition chỉ được consume bởi 1 consumer trong group → ordering đảm bảo.
>
> **Lưu ý**: Nếu partition thay đổi (alter topic) hoặc key = null → ordering không còn đảm bảo.

---

**Q: Kafka Transactions hoạt động thế nào?**

> Producer transaction cho phép atomic write across multiple partitions/topics:
> ```java
> producer.initTransactions();
> producer.beginTransaction();
> producer.send(new ProducerRecord<>("topic-A", key, value1));
> producer.send(new ProducerRecord<>("topic-B", key, value2));
> producer.commitTransaction();   // hoặc abortTransaction() khi lỗi
> ```
> Broker dùng **Transaction Coordinator** (log `__transaction_state`) để track trạng thái.
> Consumer phải set `isolation.level=read_committed` để chỉ đọc message đã commit.
>
> Use case: consume-process-produce pipeline (read-process-write exactly-once).

---

**Q: Debezium CDC + Kafka hoạt động thế nào? (Liên quan dự án này)**

> ```
> PostgreSQL WAL (Write-Ahead Log)
>   → Debezium Connector (Kafka Connect)
>   → Kafka Topic (1 topic per table, e.g. postgres.public.action_execution)
>   → Reconciliation Service Consumer
> ```
>
> Debezium đọc PostgreSQL WAL qua logical replication slot (`pgoutput` plugin). Mỗi INSERT/UPDATE/DELETE tạo 1 event với `before` và `after` snapshot.
>
> Ưu điểm: không cần polling DB, không bỏ sót event, low latency.
> Lưu ý:
> - Replication slot phải được monitor để tránh WAL disk bloat khi consumer lag.
> - Dùng `publication` để chọn table cần track.
> - Debezium offset lưu trong Kafka topic `__debezium-offsets`.

---

**Q: Compaction topic là gì? Dùng khi nào?**

> Log compaction = Kafka giữ **latest value cho mỗi key**, xóa các giá trị cũ hơn.
> ```
> Key=user1: v1 v2 v3   →  chỉ giữ v3
> Key=user2: v1          →  giữ v1
> ```
>
> Config: `cleanup.policy=compact`
>
> Use case:
> - State store (latest user config, latest sensor reading).
> - Change Data Capture sink — chỉ cần trạng thái cuối.
> - Kafka Streams local store changelog.
>
> **KHÔNG dùng** cho event log cần replay đầy đủ (dùng `delete` policy).

---

**Q: Tại sao không dùng Kafka thay Redis cho session/cache?**

> Kafka là **append-only log**, không hỗ trợ random read/write theo key. Redis là **key-value store** với O(1) read/write.
> - Cache lookup cần sub-ms → Redis.
> - Event stream, audit log, async processing → Kafka.
> - Cả hai bổ sung nhau, không thay thế nhau.

---

**Q: Xử lý poison pill message thế nào?**

> Poison pill = message khiến consumer crash/loop vô hạn.
>
> Giải pháp:
> 1. **Dead Letter Topic (DLT)**: khi xử lý thất bại N lần → forward sang `{topic}.DLT`, commit offset tiếp tục.
> 2. **Spring Kafka** hỗ trợ built-in: `DefaultErrorHandler` với `DeadLetterPublishingRecoverer`.
> 3. Monitor DLT để alert và xử lý thủ công.
>
> ```java
> @Bean
> public DefaultErrorHandler errorHandler(KafkaTemplate<?, ?> template) {
>     DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
>     return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
> }
> ```

---

**Q: Kafka vs RabbitMQ vs SQS — chọn khi nào?**

| | Kafka | RabbitMQ | AWS SQS |
|---|---|---|---|
| Throughput | Rất cao (triệu msg/s) | Trung bình | Cao |
| Retention | Lưu theo policy (có thể replay) | Xóa sau consume | Xóa sau consume (max 14d) |
| Ordering | Per-partition | Per-queue | FIFO queue |
| Routing | Topic/Partition | Exchange + Binding (linh hoạt) | Đơn giản |
| Replay | Có | Không | Không |
| Managed | MSK (AWS) | Amazon MQ | Native AWS |
| Dùng khi | Event sourcing, CDC, audit log, high throughput | Complex routing, RPC-style, legacy integration | Simple async task queue, AWS-native |

---

## 8. Checklist Production

```
☐ replication.factor=3, min.insync.replicas=2
☐ unclean.leader.election.enable=false
☐ acks=all + enable.idempotence=true (producer)
☐ enable.auto.commit=false (consumer)
☐ Dead Letter Topic cho mọi consumer
☐ Monitor consumer lag (alert khi lag > threshold)
☐ Monitor ISR (alert khi ISR < RF)
☐ Debezium replication slot lag < 10k (tránh WAL bloat)
☐ retention.ms phù hợp với replay requirement
☐ Không dùng key=null nếu cần ordering
☐ CooperativeStickyAssignor để giảm rebalance impact
```

---

*Tài liệu liên quan: `docs/07-cdc-reconciliation.md` — Debezium + Reconciliation flow chi tiết.*

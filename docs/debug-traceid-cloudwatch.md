# Debug trên Production: TraceId → CloudWatch

## Full flow

```
1. FE gửi request  ──►  BE nhận, đọc X-Trace-Id header
                         │  (nếu header trống → tự gen UUID)
                         ▼
                    MDC.put("traceId", traceId)   ← gắn vào thread context
                         │
                         ▼
                    Mọi log.info / log.error trong request đều tự động có traceId
                    vì Logback đọc MDC khi format log
                         │
                         ▼
                    Log ra stdout dưới dạng JSON (LOG_FORMAT=json)
                         │
                         ▼
2. Fluent Bit (EKS) thu thập stdout → ship lên CloudWatch Log Group

3. FE thấy lỗi → lấy X-Trace-Id từ response header
   → vào CloudWatch Logs Insights → query theo traceId
   → thấy toàn bộ log của request đó (controller, service, DB call, ...)
```

---

## Phần đã có sẵn trong project

### 1. `TraceContextFilter` — gắn traceId vào MDC

File: `console-api/src/main/java/com/simi/console/observability/TraceContextFilter.java`

```java
// Đọc từ header (FE gửi lên), hoặc tự gen nếu không có
String traceId = request.getHeader("X-Trace-Id");
if (traceId == null || traceId.isBlank()) {
    traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
}
MDC.put("traceId", traceId);
// ...
response.setHeader("X-Trace-Id", traceId);  // trả về FE để debug
```

Ý nghĩa: mọi `log.info(...)` trong request đó sẽ **tự động** có `traceId` trong log — không cần pass tay.

### 2. `logback-spring.xml` — format JSON khi chạy prod

File: `console-api/src/main/resources/logback-spring.xml`

Khi set env `LOG_FORMAT=json`, log ra stdout có dạng:
```json
{
  "@timestamp": "2026-05-17T10:23:45.123Z",
  "level": "ERROR",
  "logger": "com.simi.console.feature.executor",
  "message": "Action execution failed",
  "traceId": "a3f2b1c4d5e6f7a8",
  "executionId": "exec_01JW...",
  "actionExecId": "actexec_01JW...",
  "userId": "usr_123",
  "service": "console-api"
}
```

---

## Phần cần config để log lên CloudWatch

Project dùng **EKS** → approach chuẩn là: log ra **stdout JSON** → **Fluent Bit** thu thập → ship lên **CloudWatch Logs**.

### Bước 1 — Set env var trong Kubernetes Deployment

```yaml
# k8s/console-api-deployment.yaml
env:
  - name: LOG_FORMAT
    value: "json"
  - name: LOG_LEVEL
    value: "INFO"
```

Với môi trường dev local thì để `LOG_FORMAT=text` (dễ đọc hơn).

### Bước 2 — Enable CloudWatch Container Insights trên EKS cluster

```bash
# Chạy 1 lần khi setup cluster — cài Fluent Bit DaemonSet
aws eks create-addon \
  --cluster-name <cluster-name> \
  --addon-name amazon-cloudwatch-observability \
  --region ap-northeast-1
```

Sau đó Fluent Bit tự động:
- Thu thập stdout của mọi container trong cluster
- Ship lên CloudWatch với log group: `/aws/containerinsights/<cluster>/application`
- Mỗi log line là 1 JSON event có thể query được

### Bước 3 — IAM permission cho EKS node

Node group cần có permission ghi CloudWatch Logs:
```json
{
  "Effect": "Allow",
  "Action": [
    "logs:CreateLogGroup",
    "logs:CreateLogStream",
    "logs:PutLogEvents",
    "logs:DescribeLogStreams"
  ],
  "Resource": "arn:aws:logs:*:*:*"
}
```

Thường attach policy `CloudWatchAgentServerPolicy` vào IAM Role của node group là đủ.

---

## Cách query trên CloudWatch Logs Insights

Vào **AWS Console → CloudWatch → Logs Insights**, chọn log group `/aws/containerinsights/<cluster>/application`.

### Query 1: Tìm toàn bộ log của 1 request theo traceId
```
fields @timestamp, level, message, executionId, actionExecId
| filter traceId = "a3f2b1c4d5e6f7a8"
| sort @timestamp asc
```

### Query 2: Tìm tất cả ERROR trong 1 tiếng qua
```
fields @timestamp, traceId, message, executionId
| filter level = "ERROR" and service = "console-api"
| sort @timestamp desc
| limit 50
```

### Query 3: Tìm log của 1 scenario execution cụ thể
```
fields @timestamp, level, message, traceId, actionExecId
| filter executionId = "exec_01JW..."
| sort @timestamp asc
```

---

## Flow debug thực tế khi có bug

```
User báo: "Tôi chạy scenario bị lỗi lúc 10:23"

1. Hỏi user lấy traceId từ response header (hoặc từ FE client log)
   → ví dụ: a3f2b1c4d5e6f7a8

2. Vào CloudWatch Logs Insights
   → filter traceId = "a3f2b1c4d5e6f7a8"
   → sort @timestamp asc

3. Thấy chuỗi log:
   10:23:45.100  INFO  ScenarioStartUseCase   - Starting scenario exec_01JW...
   10:23:45.234  INFO  ActionExecuteUseCase   - Calling external API: coupon-service
   10:23:45.890  ERROR ActionExecuteUseCase   - HTTP 503 from coupon-service: Service Unavailable
   10:23:45.892  INFO  SagaCompensationService - Triggering compensation for exec_01JW...

4. Biết ngay: coupon-service trả 503 → Saga trigger → không phải bug code mình
   → Check coupon-service status, không cần đào code thêm
```

---

## Thêm traceId vào FE (nếu muốn tracing end-to-end)

FE gửi `X-Trace-Id` trong mọi request:
```js
// axios interceptor
axios.interceptors.request.use((config) => {
  config.headers['X-Trace-Id'] = generateTraceId(); // hoặc lấy từ session
  return config;
});

// Lưu traceId từ response để hiện khi có lỗi
axios.interceptors.response.use(
  (response) => response,
  (error) => {
    const traceId = error.response?.headers['x-trace-id'];
    console.error(`Request failed. TraceId: ${traceId}`);
    // Hoặc ship lên client log endpoint kèm traceId
    return Promise.reject(error);
  }
);
```

User thấy lỗi → FE show "Error code: a3f2b1c4d5e6f7a8" → dev dùng code đó query CloudWatch.

---

## Tóm tắt: cần làm gì để production-ready

| Đã có | Cần thêm |
|---|---|
| `TraceContextFilter` — gắn MDC | Set `LOG_FORMAT=json` trong K8s deployment |
| `logback-spring.xml` — JSON format | Enable CloudWatch Container Insights addon trên EKS |
| `logstash-logback-encoder` dependency | IAM policy `CloudWatchAgentServerPolicy` cho node group |
| `X-Trace-Id` response header | FE đọc header và ship khi có lỗi (optional nhưng tốt) |

# Debug trên Production — Hướng dẫn trả lời phỏng vấn

## Câu hỏi thường gặp

> "Khi production có bug, bạn debug như thế nào?"  
> "Làm sao bạn biết user đang gặp lỗi gì?"  
> "Log strategy của bạn là gì?"

---

## Cấu trúc câu trả lời (STAR-lite)

Trả lời theo 3 tầng: **Detect → Diagnose → Fix**, mỗi tầng có tool + ví dụ cụ thể.

---

## Tầng 1 — Detect: Biết có vấn đề trước cả user báo

### 1.1 Client-side log shipping (hướng bạn đang nghĩ — đúng rồi)

**Cơ chế:**  
Frontend/mobile app không chỉ log ra console — gửi log lên một **log ingestion endpoint** (e.g. `/api/logs` hoặc service như Datadog RUM, Sentry, LogRocket).

```
User browser / mobile app
    │
    ├── console.error / uncaught exception
    ├── custom event: "checkout_failed", payload={orderId, errorCode}
    │
    └──► POST /api/ingest/client-log
              { level, message, stack, userId, sessionId, timestamp, appVersion }
              
    Server nhận, forward vào logging pipeline (ELK / Datadog / Loki)
```

**Trả lời mẫu:**

> "Tôi implement một client log shipper — mỗi khi có uncaught error hoặc business event quan trọng (e.g. payment failed), client gửi structured log lên server kèm `userId`, `sessionId`, `appVersion`. Server forward vào ELK. Nhờ vậy tôi thấy lỗi trước khi user kịp báo support."

**Ví dụ code (browser):**
```js
window.addEventListener('unhandledrejection', (event) => {
  fetch('/api/ingest/client-log', {
    method: 'POST',
    body: JSON.stringify({
      level: 'ERROR',
      message: event.reason?.message,
      stack: event.reason?.stack,
      userId: currentUser.id,
      sessionId: session.id,
      url: location.href,
      appVersion: APP_VERSION,
      timestamp: new Date().toISOString(),
    }),
  });
});
```

**Lưu ý khi phỏng vấn hỏi sâu:**
- Dùng **fire-and-forget** (`keepalive: true`) để không block UX.
- **Sample rate** nếu traffic lớn — không cần log 100% user, 10% là đủ cho error pattern.
- **PII scrubbing** trước khi ship — không log password, credit card.
- **Batch + debounce** để tránh DDoS chính mình khi có lỗi loop.

---

### 1.2 Server-side structured logging

```
// BAD — khó parse, khó search
log.info("User 123 bought item 456 failed")

// GOOD — structured JSON, query được ngay
log.info("purchase.failed", {
    userId: "123",
    itemId: "456",
    errorCode: "INSUFFICIENT_BALANCE",
    traceId: "abc-def-ghi",
    durationMs: 342
})
```

**Điểm quan trọng:** Mỗi log phải có `traceId` / `correlationId` để link toàn bộ request xuyên service.

---

### 1.3 Metrics + Alerting (Detect tự động)

- **Error rate spike**: nếu `5xx / total_request > 1%` → alert ngay.
- **Latency P99**: nếu tăng đột ngột → thường là DB slow query hoặc external API chậm.
- **Business metric**: số order thành công giảm 20% so với baseline giờ trước → bug payment.

Tool: Datadog, Prometheus + Grafana, CloudWatch.

---

## Tầng 2 — Diagnose: Tìm root cause

### Workflow thực tế

```
1. Nhận alert / user report
   ↓
2. Mở dashboard → xem error rate spike lúc mấy giờ
   ↓
3. Tìm traceId từ log của user bị ảnh hưởng
   ↓
4. Dùng distributed tracing (Jaeger / Zipkin / Datadog APM)
   → xem full request path xuyên service
   → span nào chậm / lỗi
   ↓
5. Drill down vào service đó → xem stack trace + context
   ↓
6. Check recent deploys (xem có deploy mới nào không)
   → git blame / deployment history
   ↓
7. Reproduce locally với data thật (anonymized)
```

### Distributed Tracing — điểm cộng lớn khi phỏng vấn

> "Tôi dùng OpenTelemetry inject `traceId` vào mọi request — từ API gateway, qua backend service, xuống DB query. Khi user báo lỗi, tôi lấy `traceId` từ response header, paste vào Jaeger, thấy ngay span nào fail và tại sao — thường mất < 5 phút để tìm ra tầng nào lỗi."

---

### Session Replay (cho frontend bug)

Tool như **LogRocket** / **FullStory** record lại toàn bộ DOM event của user session.

> "Bug UI khó reproduce nhất là UI-state bug. Tôi dùng LogRocket — khi user báo 'nút bị disable không click được', tôi mở session replay, thấy chính xác sequence action user đã làm, tìm ra race condition trong state management."

---

## Tầng 3 — Fix an toàn trên Production

### Feature Flag (không deploy để fix)

> "Nếu bug ở feature mới, tôi tắt feature flag ngay — rollback tức thì, không cần deploy lại. Sau đó fix, test, bật lại flag theo % user (canary)."

### Canary Deploy

> "Mọi deploy tôi đều canary 5% traffic trước. Alert tự động compare error rate giữa canary và stable. Nếu canary tệ hơn → tự động rollback, không cần manual intervention."

### Hotfix flow

```
production bug confirmed
    │
    ├── [Nhanh] Feature flag OFF → user không bị ảnh hưởng
    │
    ├── [Fix] Branch từ main → fix → test → PR
    │
    └── [Deploy] Canary 5% → 20% → 100% với auto-rollback
```

---

## Tóm tắt câu trả lời ngắn (nếu hỏi general)

> "Tôi tiếp cận theo 3 tầng:
> 
> **Detect** — client ship log lên server kèm `userId` và `sessionId`; server emit structured log + metrics; alert tự động khi error rate tăng.
>
> **Diagnose** — dùng `traceId` xuyên suốt để link log từ frontend xuống backend. Distributed tracing giúp tìm ra tầng nào lỗi trong < 5 phút thay vì đoán mò.
>
> **Fix** — feature flag để rollback không cần deploy; canary deploy với auto-rollback để giảm blast radius.
>
> Điều quan trọng nhất: **không debug trực tiếp trên production** — mọi thứ phải có observability sẵn từ trước, debug chỉ là việc đọc data đã có."

---

## Bẫy thường gặp — đừng nói những điều này

| Đừng nói | Nên nói thay |
|---|---|
| "Tôi SSH vào server xem log" | "Tôi dùng centralized logging, không cần SSH" |
| "Tôi thêm `console.log` rồi deploy lại" | "Log đã có sẵn với đúng level và context" |
| "Tôi hỏi user làm gì để reproduce" | "Tôi có session replay / event log, không cần hỏi" |
| "Tôi restart service xem có hết không" | "Tôi xem metrics xác định root cause trước khi action" |

---

## Câu hỏi follow-up hay gặp

**"Log nhiều quá thì sao?"**  
→ Log level (ERROR/WARN/INFO/DEBUG) + sampling + log rotation + retention policy (e.g. ERROR 90 ngày, INFO 7 ngày).

**"Nếu không có distributed tracing thì sao?"**  
→ Tự inject `X-Request-ID` header, pass qua mọi service call, log header đó ở mọi tầng — poor man's tracing.

**"Security — log có lộ data không?"**  
→ PII scrubbing trước khi ship; log ở server-side cần audit access; client log không bao giờ log sensitive field.

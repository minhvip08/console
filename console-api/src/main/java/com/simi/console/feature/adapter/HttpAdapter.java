package com.simi.console.feature.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simi.common.domain.entity.RetryPolicy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

/**
 * Sync HTTP adapter — dùng RestClient (Spring 6.1+). Honor {@code timeout_ms} +
 * {@code retry_policy} của action_definition.
 * <p>
 * Retry chỉ apply cho 5xx (transient) hoặc TIMEOUT. 4xx coi là deterministic failure → no retry.
 */
@Component
class HttpAdapter implements ServiceAdapter {

    private final ObjectMapper json;

    HttpAdapter(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String type() {
        return "http";
    }

    @Override
    public AdapterResult execute(AdapterRequest req) {
        URI url = buildUrl(req);
        RestClient client = RestClient.builder()
                .requestFactory(timeoutFactory(req.action().getTimeoutMs()))
                .build();

        RetryPolicy retry = req.retryPolicy();
        int maxAttempts = retry == null ? 1 : retry.getMaxAttempts();
        int initialBackoffMs = retry == null ? 1000 : retry.getInitialIntervalMs();
        int[] retryableCodes = retry == null ? new int[]{502, 503, 504} : retry.getRetryableStatusCodes();

        AdapterResult last = null;
        long start = System.currentTimeMillis();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            last = singleCall(client, url, req);

            if (last.status() == com.simi.common.domain.ActionExecutionStatus.COMPLETED) {
                return last;
            }
            if (last.status() == com.simi.common.domain.ActionExecutionStatus.FAILED
                    && last.httpStatus() != null
                    && !contains(retryableCodes, last.httpStatus())) {
                return last; // 4xx hoặc non-retryable 5xx → bail out ngay
            }
            if (attempt < maxAttempts) {
                sleep(backoff(retry, attempt, initialBackoffMs));
            }
        }
        long durationMs = System.currentTimeMillis() - start;
        return last == null ? AdapterResult.timeout("no attempts made", durationMs) : last;
    }

    private AdapterResult singleCall(RestClient client, URI url, AdapterRequest req) {
        long t0 = System.currentTimeMillis();
        try {
            RestClient.RequestBodySpec spec = client
                    .method(org.springframework.http.HttpMethod.valueOf(req.action().getHttpMethod()))
                    .uri(url)
                    .header("X-Idempotency-Key", req.idempotencyKey())
                    .contentType(MediaType.APPLICATION_JSON);
            req.headers().forEach((k, v) -> spec.header(k, String.valueOf(v)));

            var response = (req.body() == null || req.body().isEmpty()
                    ? spec.retrieve()
                    : spec.body(req.body().toString()).retrieve())
                    .onStatus(s -> true, (request, resp) -> {}) // không throw — handle thủ công bên dưới
                    .toEntity(String.class);

            long duration = System.currentTimeMillis() - t0;
            HttpStatusCode status = response.getStatusCode();
            JsonNode body = parseBody(response.getBody());

            if (status.is2xxSuccessful()) {
                return AdapterResult.completed(status.value(), body, duration);
            }
            return AdapterResult.failed(status.value(), body,
                    "HTTP " + status.value(), duration);
        } catch (ResourceAccessException timeout) {
            long duration = System.currentTimeMillis() - t0;
            // Socket timeout / connection refused / IOException → TIMEOUT (unknown outcome).
            return AdapterResult.timeout(rootCauseMessage(timeout), duration);
        } catch (Exception other) {
            long duration = System.currentTimeMillis() - t0;
            return AdapterResult.timeout("unexpected: " + rootCauseMessage(other), duration);
        }
    }

    private URI buildUrl(AdapterRequest req) {
        String path = req.action().getEndpoint();
        for (Map.Entry<String, Object> e : req.pathParams().entrySet()) {
            path = path.replace("{" + e.getKey() + "}",
                    URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
        }
        StringBuilder full = new StringBuilder(req.service().getBaseUrl()).append(path);
        if (!req.queryParams().isEmpty()) {
            full.append('?');
            boolean first = true;
            for (Map.Entry<String, Object> e : req.queryParams().entrySet()) {
                if (!first) full.append('&');
                full.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
                first = false;
            }
        }
        return URI.create(full.toString());
    }

    private static org.springframework.http.client.SimpleClientHttpRequestFactory timeoutFactory(int timeoutMs) {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofMillis(timeoutMs));
        f.setReadTimeout(Duration.ofMillis(timeoutMs));
        return f;
    }

    private JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return json.readTree(body);
        } catch (IOException e) {
            return json.valueToTree(Map.of("raw", body));
        }
    }

    private static int backoff(RetryPolicy retry, int attempt, int initialMs) {
        if (retry == null || "fixed".equals(retry.getBackoffType())) return initialMs;
        if ("linear".equals(retry.getBackoffType())) return initialMs * attempt;
        // exponential
        long v = (long) initialMs << Math.min(attempt - 1, 10);
        return retry.getMaxIntervalMs() == null ? (int) v : (int) Math.min(v, retry.getMaxIntervalMs());
    }

    private static void sleep(int ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static boolean contains(int[] arr, int v) {
        return Arrays.stream(arr).anyMatch(x -> x == v);
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}

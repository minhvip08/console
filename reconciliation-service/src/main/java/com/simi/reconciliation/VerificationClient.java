package com.simi.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simi.common.domain.entity.ActionDefinition;
import com.simi.common.domain.entity.ActionVerification;
import com.simi.common.domain.entity.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.Iterator;
import java.util.Map;

/**
 * Call 3rd party verification endpoint to check if an action was actually processed.
 * Uses the verification config from action_definition (docs/07 §action_definition verification Block).
 * <p>
 * Returns one of: PROCESSED, NOT_FOUND, PENDING, ERROR
 */
@Component
public class VerificationClient {

    private static final Logger log = LoggerFactory.getLogger(VerificationClient.class);

    private final RestClient restClient;
    private final ObjectMapper json;
    private final int defaultTimeoutMs;

    public VerificationClient(RestClient.Builder restClientBuilder,
                              ObjectMapper json,
                              @Value("${reconciliation.verification.default-timeout-ms:10000}") int defaultTimeoutMs) {
        this.restClient = restClientBuilder.build();
        this.json = json;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    /**
     * Verify action state with 3rd party.
     *
     * @param service    service definition (for base_url)
     * @param actionDef  action definition
     * @param verification verification config
     * @param idempotencyKey the idempotency key used when calling the action
     * @return verification result
     */
    public VerificationResult verify(Service service, ActionDefinition actionDef,
                                     ActionVerification verification, String idempotencyKey) {
        try {
            String url = buildVerificationUrl(service, verification, idempotencyKey);
            log.info("Verifying 3rd party state: {} {} (action={})",
                    verification.getHttpMethod(), url, actionDef.getId());

            ResponseEntity<String> response = restClient.get()
                    .uri(URI.create(url))
                    .retrieve()
                    .toEntity(String.class);

            int statusCode = response.getStatusCode().value();
            JsonNode responseBody = null;
            if (response.getBody() != null) {
                try {
                    responseBody = json.readTree(response.getBody());
                } catch (Exception e) {
                    // non-JSON response — store raw
                    responseBody = json.valueToTree(Map.of("raw", response.getBody()));
                }
            }

            // Evaluate conditions from verification config
            JsonNode conditions = verification.getConditions();
            String thirdPartyState = evaluateConditions(conditions, statusCode, responseBody);

            return new VerificationResult(thirdPartyState, responseBody, statusCode, null);

        } catch (RestClientException e) {
            log.error("Verification call failed for action {}: {}", actionDef.getId(), e.getMessage());
            return new VerificationResult("ERROR", null, 0, e.getMessage());
        }
    }

    /**
     * Evaluate 3rd party response against verification conditions.
     * Simple rule engine — checks status code and response body.
     */
    private String evaluateConditions(JsonNode conditions, int statusCode, JsonNode responseBody) {
        if (conditions == null) return "UNKNOWN";

        // Check "not_found" first — typically 404
        if (statusCode == 404) {
            return "NOT_FOUND";
        }

        // Check "pending" — e.g. response.status == 'PROCESSING'
        if (conditions.has("pending") && responseBody != null) {
            String pendingExpr = conditions.get("pending").asText();
            if (matchesSimpleCondition(pendingExpr, responseBody)) {
                return "PENDING";
            }
        }

        // Check "processed" — e.g. response.status == 'CHARGED'
        if (conditions.has("processed") && responseBody != null) {
            String processedExpr = conditions.get("processed").asText();
            if (matchesSimpleCondition(processedExpr, responseBody)) {
                return "PROCESSED";
            }
        }

        // Default: if 2xx and no condition matched, assume PROCESSED
        if (statusCode >= 200 && statusCode < 300 && responseBody != null) {
            return "PROCESSED";
        }

        return "UNKNOWN";
    }

    /**
     * Simple condition matcher — supports "response.{field} == '{value}'" and
     * "response.{field} != null" patterns.
     * <p>
     * Not a full expression engine — enough for learning project.
     */
    private boolean matchesSimpleCondition(String expression, JsonNode responseBody) {
        if (expression == null || expression.isBlank()) return false;

        // Handle "response.status == 'CHARGED'" pattern
        if (expression.contains("==")) {
            String[] parts = expression.split("==", 2);
            String fieldPath = parts[0].trim().replace("response.", "");
            String expectedValue = parts[1].trim().replace("'", "").replace("\"", "");

            if (fieldPath.contains("!= null")) return false; // handled separately

            JsonNode fieldValue = responseBody.get(fieldPath);
            if (fieldValue != null && fieldValue.asText().equals(expectedValue)) {
                return true;
            }
        }

        // Handle "response.{field} != null" pattern
        if (expression.contains("!= null")) {
            String fieldPath = expression.split("!=")[0].trim().replace("response.", "");
            return responseBody.has(fieldPath) && !responseBody.get(fieldPath).isNull();
        }

        // Handle "response_code == 404" pattern
        if (expression.contains("response_code")) {
            // Already handled by statusCode check above
            return false;
        }

        // Compound conditions with && — check all parts
        if (expression.contains("&&")) {
            String[] subConditions = expression.split("&&");
            for (String sub : subConditions) {
                if (!matchesSimpleCondition(sub.trim(), responseBody)) {
                    return false;
                }
            }
            return true;
        }

        return false;
    }

    private String buildVerificationUrl(Service service, ActionVerification verification,
                                        String idempotencyKey) {
        String baseUrl = service.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String endpoint = verification.getEndpoint();
        if (!endpoint.startsWith("/")) {
            endpoint = "/" + endpoint;
        }

        StringBuilder url = new StringBuilder(baseUrl + endpoint);

        // Append query params, replacing {idempotencyKey} placeholder
        JsonNode queryParams = verification.getQueryParams();
        if (queryParams != null && queryParams.isObject()) {
            url.append("?");
            Iterator<Map.Entry<String, JsonNode>> fields = queryParams.fields();
            boolean first = true;
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!first) url.append("&");
                first = false;
                String value = field.getValue().asText().replace("{idempotencyKey}", idempotencyKey);
                url.append(field.getKey()).append("=").append(value);
            }
        }

        return url.toString();
    }

    public record VerificationResult(
            String thirdPartyState,   // PROCESSED, NOT_FOUND, PENDING, ERROR, UNKNOWN
            JsonNode responseBody,
            int statusCode,
            String errorMessage
    ) {}
}

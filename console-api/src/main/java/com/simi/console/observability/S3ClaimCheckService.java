package com.simi.console.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;

/**
 * Claim Check Pattern for large action responses (docs/10 §S3 §Claim Check Pattern).
 * <pre>
 * If response size > THRESHOLD (100KB):
 *   1. Upload to S3: s3://console-artifacts/executions/{execId}/{actionExecId}.json
 *   2. Store s3_ref in action_execution.s3_ref
 *   3. Debezium CDC event stays small (no large JSONB in WAL)
 *
 * Compensation Manager / Reconciler fetch from S3 when needed.
 * </pre>
 */
@Service
public class S3ClaimCheckService {

    private static final Logger log = LoggerFactory.getLogger(S3ClaimCheckService.class);

    private final S3Client s3;
    private final ObjectMapper json;
    private final String bucket;
    private final int thresholdBytes;

    public S3ClaimCheckService(S3Client s3,
                                ObjectMapper json,
                                @Value("${aws.s3.artifacts-bucket:console-artifacts}") String bucket,
                                @Value("${aws.s3.claim-check-threshold-bytes:102400}") int thresholdBytes) {
        this.s3 = s3;
        this.json = json;
        this.bucket = bucket;
        this.thresholdBytes = thresholdBytes;
    }

    /**
     * Check if response should be offloaded to S3.
     * @return true if response exceeds threshold
     */
    public boolean shouldOffload(JsonNode response) {
        if (response == null) return false;
        try {
            byte[] bytes = json.writeValueAsBytes(response);
            return bytes.length > thresholdBytes;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Upload response to S3 and return the S3 reference key.
     * @return S3 key: executions/{execId}/{actionExecId}.json
     */
    public String upload(String executionId, String actionExecutionId, JsonNode response) {
        String key = "executions/" + executionId + "/" + actionExecutionId + ".json";
        try {
            byte[] data = json.writeValueAsBytes(response);
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType("application/json")
                            .build(),
                    RequestBody.fromBytes(data));
            log.info("S3 Claim Check: uploaded {} bytes to s3://{}/{}", data.length, bucket, key);
            return "s3://" + bucket + "/" + key;
        } catch (Exception e) {
            log.error("S3 upload failed for {}/{}: {}", executionId, actionExecutionId, e.getMessage());
            return null; // fallback: keep in DB JSONB
        }
    }

    /**
     * Fetch response from S3.
     * @param s3Ref full S3 URI: s3://console-artifacts/executions/...
     */
    public JsonNode fetch(String s3Ref) {
        try {
            // Parse s3://bucket/key
            String withoutProtocol = s3Ref.replace("s3://", "");
            int slashIdx = withoutProtocol.indexOf('/');
            String bucketName = withoutProtocol.substring(0, slashIdx);
            String key = withoutProtocol.substring(slashIdx + 1);

            var response = s3.getObject(GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
            byte[] data = response.readAllBytes();
            return json.readTree(new String(data, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("S3 fetch failed for {}: {}", s3Ref, e.getMessage());
            return null;
        }
    }
}

package com.simi.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tầng 4 idempotency key (docs/05 §Tầng 4):
 * <pre>
 *   SHA256( executionId + "|" + actionDefinitionId + "|" + sorted(name=value joined by '&') )
 * </pre>
 * {@code executionId} bắt buộc — không có nó thì 2 user tạo coupon cùng params sẽ bị chặn nhau.
 * <p>
 * Params được sort theo tên để stable ordering.
 */
public final class IdempotencyKey {

    private IdempotencyKey() {}

    public static String compute(String executionId, String actionDefinitionId, Map<String, ?> params) {
        TreeMap<String, ?> sorted = new TreeMap<>(params);
        StringBuilder paramStr = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, ?> e : sorted.entrySet()) {
            if (!first) paramStr.append('&');
            paramStr.append(e.getKey()).append('=').append(stringify(e.getValue()));
            first = false;
        }
        String input = executionId + "|" + actionDefinitionId + "|" + paramStr;

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String stringify(Object value) {
        if (value == null) return "";
        return value.toString();
    }
}

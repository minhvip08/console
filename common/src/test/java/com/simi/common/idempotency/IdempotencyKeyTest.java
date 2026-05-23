package com.simi.common.idempotency;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class IdempotencyKeyTest {

    @Test
    void sameInput_producesSameKey() {
        String k1 = IdempotencyKey.compute(1L, 10L, Map.of("userId", "u1", "amount", 100));
        String k2 = IdempotencyKey.compute(1L, 10L, Map.of("userId", "u1", "amount", 100));
        assertThat(k1).isEqualTo(k2);
    }

    @Test
    void parameterInsertionOrder_doesNotAffectKey() {
        // Dù Map có order khác nhau, TreeMap trong IdempotencyKey sort alphabetically
        // → cùng key. Đây là invariant quan trọng: client gửi params theo bất kỳ order
        // nào vẫn cho cùng idempotency key.
        Map<String, Object> params1 = new LinkedHashMap<>();
        params1.put("a", "1"); params1.put("b", "2"); params1.put("c", "3");

        Map<String, Object> params2 = new LinkedHashMap<>();
        params2.put("c", "3"); params2.put("a", "1"); params2.put("b", "2");

        assertThat(IdempotencyKey.compute(1L, 10L, params1))
                .isEqualTo(IdempotencyKey.compute(1L, 10L, params2));
    }

    @Test
    void differentExecutionId_producesDifferentKey() {
        Map<String, Object> params = Map.of("userId", "u1");
        assertThat(IdempotencyKey.compute(1L, 10L, params))
                .isNotEqualTo(IdempotencyKey.compute(2L, 10L, params));
    }

    @Test
    void differentActionDefinitionId_producesDifferentKey() {
        Map<String, Object> params = Map.of("userId", "u1");
        assertThat(IdempotencyKey.compute(1L, 10L, params))
                .isNotEqualTo(IdempotencyKey.compute(1L, 11L, params));
    }

    @Test
    void differentParamValues_produceDifferentKeys() {
        assertThat(IdempotencyKey.compute(1L, 10L, Map.of("amount", "100")))
                .isNotEqualTo(IdempotencyKey.compute(1L, 10L, Map.of("amount", "200")));
    }

    @Test
    void result_is64CharLowercaseHex() {
        String key = IdempotencyKey.compute(1L, 10L, Map.of("x", "y"));
        assertThat(key).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void nullParamValue_doesNotThrow() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("optionalField", null);
        assertThatNoException().isThrownBy(() -> IdempotencyKey.compute(1L, 10L, params));
    }

    @Test
    void emptyParams_stillProducesKey() {
        String key = IdempotencyKey.compute(1L, 10L, Map.of());
        assertThat(key).hasSize(64);
    }
}

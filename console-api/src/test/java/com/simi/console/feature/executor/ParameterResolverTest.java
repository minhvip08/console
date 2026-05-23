package com.simi.console.feature.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simi.common.domain.context.ContextDataItem;
import com.simi.common.domain.entity.ActionDefinition;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ParameterResolverTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ParameterResolver resolver = new ParameterResolver(json);

    @Test
    void resolves_pathQueryBodyParams_fromContext() {
        ActionDefinition action = actionWith(json.createArrayNode()
                .add(param("userId",     "VAR_USER_01", "path",  true))
                .add(param("page",       "VAR_PAGE_01", "query", false))
                .add(param("couponCode", "VAR_CODE_01", "body",  true)));

        List<ContextDataItem> ctx = List.of(
                item("VAR_USER_01", "u123"),
                item("VAR_PAGE_01", 2),
                item("VAR_CODE_01", "SUMMER50"));

        ParameterResolver.Resolved r = resolver.resolve(action, ctx);

        assertThat(r.path()).containsEntry("userId", "u123");
        assertThat(r.query()).containsEntry("page", 2);
        assertThat(r.body().get("couponCode").asText()).isEqualTo("SUMMER50");
        assertThat(r.flatForIdempotency()).containsKeys("userId", "page", "couponCode");
    }

    @Test
    void missingRequiredParam_throwsWithParamName() {
        ActionDefinition action = actionWith(json.createArrayNode()
                .add(param("amount", "VAR_AMT_01", "body", true)));

        assertThatThrownBy(() -> resolver.resolve(action, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount")
                .hasMessageContaining("VAR_AMT_01");
    }

    @Test
    void missingOptionalParam_isSkipped_noException() {
        ActionDefinition action = actionWith(json.createArrayNode()
                .add(param("description", "VAR_DESC_01", "body", false)));

        ParameterResolver.Resolved r = resolver.resolve(action, List.of());

        assertThat(r.body().isEmpty()).isTrue();
        assertThat(r.flatForIdempotency()).isEmpty();
    }

    @Test
    void headerParam_goesIntoHeaders() {
        ActionDefinition action = actionWith(json.createArrayNode()
                .add(param("X-Service-Token", "VAR_TOKEN_01", "header", true)));

        ParameterResolver.Resolved r = resolver.resolve(action,
                List.of(item("VAR_TOKEN_01", "svc-token-abc")));

        assertThat(r.headers()).containsEntry("X-Service-Token", "svc-token-abc");
        assertThat(r.body().isEmpty()).isTrue();
    }

    @Test
    void unknownInType_throws() {
        ActionDefinition action = actionWith(json.createArrayNode()
                .add(param("field", "VAR_X_01", "cookie", true)));

        assertThatThrownBy(() -> resolver.resolve(action, List.of(item("VAR_X_01", "v"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cookie");
    }

    @Test
    void flatForIdempotency_containsAllResolvedParams() {
        ActionDefinition action = actionWith(json.createArrayNode()
                .add(param("a", "VAR_A", "path",  true))
                .add(param("b", "VAR_B", "query", true))
                .add(param("c", "VAR_C", "body",  true)));

        List<ContextDataItem> ctx = List.of(
                item("VAR_A", "1"), item("VAR_B", "2"), item("VAR_C", "3"));

        ParameterResolver.Resolved r = resolver.resolve(action, ctx);

        assertThat(r.flatForIdempotency()).containsEntry("a", "1")
                .containsEntry("b", "2").containsEntry("c", "3");
    }

    // ─── helpers ───

    private ActionDefinition actionWith(ArrayNode params) {
        ActionDefinition def = new ActionDefinition();
        def.setParameters(params);
        def.setOutput(json.createArrayNode());
        return def;
    }

    private ObjectNode param(String name, String varId, String in, boolean required) {
        return json.createObjectNode()
                .put("name", name)
                .put("varId", varId)
                .put("in", in)
                .put("required", required);
    }

    private ContextDataItem item(String varId, Object value) {
        String type = value instanceof Number ? "number" : "string";
        return new ContextDataItem(
                ContextDataItem.buildKey("1", ContextDataItem.SourceType.UI_INPUT, "src1", varId),
                varId, value, type, "1.0",
                new ContextDataItem.Origin(
                        ContextDataItem.SourceType.UI_INPUT,
                        "scen1", "step1", 1, "src1", "input", null),
                OffsetDateTime.now());
    }
}

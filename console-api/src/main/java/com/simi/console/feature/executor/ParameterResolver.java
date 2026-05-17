package com.simi.console.feature.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simi.common.domain.context.ContextDataItem;
import com.simi.common.domain.entity.ActionDefinition;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolve action parameters từ Execution Context theo varId.
 * <p>
 * action_definition.parameters là JSONB array:
 * {@code [{name, varId, in: path|query|header|body, type, required}, ...]}
 * <p>
 * Mỗi parameter:
 * <ul>
 *   <li>Tìm context item với matching varId → lấy {@code value}</li>
 *   <li>Group theo {@code in}: path → URL substitution, query → ?k=v, body → JSON, header → HTTP header</li>
 * </ul>
 */
@Component
public class ParameterResolver {

    private final ObjectMapper json;

    public ParameterResolver(ObjectMapper json) {
        this.json = json;
    }

    public Resolved resolve(ActionDefinition action, List<ContextDataItem> contextItems) {
        Map<String, Object> byVarId = new HashMap<>();
        for (ContextDataItem item : contextItems) {
            byVarId.put(item.varId(), item.value());
        }

        Map<String, Object> path = new LinkedHashMap<>();
        Map<String, Object> query = new LinkedHashMap<>();
        Map<String, Object> headers = new LinkedHashMap<>();
        ObjectNode body = json.createObjectNode();

        // Flat list dùng cho idempotency key compute (param name → value).
        Map<String, Object> flat = new LinkedHashMap<>();

        JsonNode params = action.getParameters();
        if (params != null && params.isArray()) {
            for (JsonNode p : params) {
                String name = p.get("name").asText();
                String varId = p.get("varId").asText();
                String in = p.get("in").asText();
                boolean required = p.has("required") && p.get("required").asBoolean();

                Object value = byVarId.get(varId);
                if (value == null) {
                    if (required) {
                        throw new IllegalArgumentException(
                                "Missing required parameter '" + name + "' (varId=" + varId + ")");
                    }
                    continue;
                }

                flat.put(name, value);
                switch (in) {
                    case "path" -> path.put(name, value);
                    case "query" -> query.put(name, value);
                    case "header" -> headers.put(name, value);
                    case "body" -> body.set(name, json.valueToTree(value));
                    default -> throw new IllegalArgumentException("Unknown parameter `in`: " + in);
                }
            }
        }

        return new Resolved(path, query, headers, body, flat);
    }

    /** Resolved request — sẵn sàng để adapter dựng HTTP call. */
    public record Resolved(
            Map<String, Object> path,
            Map<String, Object> query,
            Map<String, Object> headers,
            ObjectNode body,
            Map<String, Object> flatForIdempotency
    ) {}
}

package com.simi.console.feature.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simi.common.ulid.Ulid;
import org.springframework.stereotype.Component;

/**
 * Mock adapter — echo body lại + gen fake output field cho mỗi output schema. Dùng cho
 * testing scenario flows mà không cần 3rd party thật.
 * <p>
 * Convention: action_definition.output JSONB gồm các field {@code {field, varId, type}};
 * mock returns 1 JSON object với mỗi field gán giá trị fake.
 */
@Component
class MockAdapter implements ServiceAdapter {

    private final ObjectMapper json;

    MockAdapter(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String type() {
        return "mock";
    }

    @Override
    public AdapterResult execute(AdapterRequest req) {
        long t0 = System.currentTimeMillis();
        ObjectNode body = json.createObjectNode();
        JsonNode output = req.action().getOutput();
        if (output != null && output.isArray()) {
            for (JsonNode field : output) {
                String fieldName = field.get("field").asText();
                String type = field.has("type") ? field.get("type").asText() : "string";
                body.set(fieldName, fakeValue(fieldName, type));
            }
        }
        long duration = System.currentTimeMillis() - t0;
        return AdapterResult.completed(200, body, duration);
    }

    private JsonNode fakeValue(String fieldName, String type) {
        return switch (type) {
            case "number", "integer" -> json.valueToTree(42);
            case "boolean" -> json.valueToTree(true);
            case "object" -> json.createObjectNode();
            case "array" -> json.createArrayNode();
            default -> json.valueToTree("mock_" + fieldName + "_" + Ulid.generate().substring(0, 8));
        };
    }
}

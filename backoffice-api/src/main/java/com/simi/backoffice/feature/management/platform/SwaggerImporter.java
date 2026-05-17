package com.simi.backoffice.feature.management.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simi.backoffice.feature.management.action.ActionRepository;
import com.simi.backoffice.feature.management.service.ServiceRepository;
import com.simi.common.domain.entity.ActionDefinition;
import com.simi.common.domain.entity.Service;
import com.simi.common.ulid.IdPrefix;
import com.simi.common.ulid.Ulid;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Import flow (per CLAUDE.md update + user clarification):
 *   1. User chọn platform service từ list
 *   2. PlatformApiClient.fetchSwagger() trả về YAML
 *   3. Parse với swagger-parser
 *   4. Mỗi operation → 1 ActionDefinition. Mỗi parameter/output field → VAR_ULID.
 *   5. Upsert local `service` row nếu chưa có
 */
@Component
public class SwaggerImporter {

    private final PlatformApiClient platform;
    private final ServiceRepository services;
    private final ActionRepository actions;
    private final ObjectMapper json;

    public SwaggerImporter(PlatformApiClient platform, ServiceRepository services,
                           ActionRepository actions, ObjectMapper json) {
        this.platform = platform;
        this.services = services;
        this.actions = actions;
        this.json = json;
    }

    @Transactional
    public ImportResult importFromPlatform(String platformServiceId) {
        PlatformApiClient.PlatformService meta = platform.listServices().stream()
                .filter(s -> s.id().equals(platformServiceId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Platform service not found: " + platformServiceId));

        String yaml = platform.fetchSwagger(platformServiceId);
        SwaggerParseResult parsed = new OpenAPIV3Parser().readContents(yaml);
        OpenAPI api = parsed.getOpenAPI();
        if (api == null) {
            throw new IllegalStateException("Failed to parse Swagger for " + platformServiceId
                    + ": " + parsed.getMessages());
        }

        Service service = services.findByName(meta.name()).orElseGet(() -> {
            Service s = new Service();
            s.setId(Ulid.prefixed(IdPrefix.SERVICE));
            s.setName(meta.name());
            s.setBaseUrl(meta.baseUrl());
            return services.save(s);
        });

        List<String> createdActionIds = new ArrayList<>();
        if (api.getPaths() != null) {
            for (Map.Entry<String, PathItem> path : api.getPaths().entrySet()) {
                PathItem item = path.getValue();
                for (Map.Entry<PathItem.HttpMethod, Operation> op : item.readOperationsMap().entrySet()) {
                    ActionDefinition a = buildAction(service.getId(), path.getKey(), op.getKey().name(),
                            item, op.getValue());
                    actions.save(a);
                    createdActionIds.add(a.getId());
                }
            }
        }

        return new ImportResult(service.getId(), service.getName(), createdActionIds);
    }

    private ActionDefinition buildAction(String serviceId, String pathTemplate, String httpMethod,
                                         PathItem item, Operation op) {
        ActionDefinition a = new ActionDefinition();
        a.setId(Ulid.prefixed(IdPrefix.ACTION_DEFINITION));
        a.setServiceId(serviceId);
        a.setEndpoint(pathTemplate);
        a.setHttpMethod(httpMethod);
        a.setAdapterType("http");
        a.setTimeoutMs(10000);
        a.setName(Optional.ofNullable(op.getOperationId())
                .orElse(httpMethod.toLowerCase() + "_" + pathTemplate));

        a.setParameters(buildParameters(item, op));
        a.setOutput(buildOutput(op));
        return a;
    }

    private JsonNode buildParameters(PathItem item, Operation op) {
        ArrayNode arr = json.createArrayNode();

        // path + query + header params
        List<Parameter> all = new ArrayList<>();
        if (item.getParameters() != null) all.addAll(item.getParameters());
        if (op.getParameters() != null) all.addAll(op.getParameters());
        for (Parameter p : all) {
            ObjectNode node = json.createObjectNode();
            node.put("name", p.getName());
            node.put("varId", Ulid.prefixed(IdPrefix.VARIABLE));
            node.put("in", p.getIn());
            node.put("type", schemaType(p.getSchema()));
            node.put("required", Boolean.TRUE.equals(p.getRequired()));
            arr.add(node);
        }

        // body params (flatten top-level properties — VAR per field)
        if (op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
            Schema<?> bodySchema = op.getRequestBody().getContent().values().stream()
                    .map(mt -> mt.getSchema())
                    .filter(s -> s != null)
                    .findFirst().orElse(null);
            if (bodySchema != null && bodySchema.getProperties() != null) {
                List<String> required = bodySchema.getRequired() == null ? List.of() : bodySchema.getRequired();
                for (Map.Entry<String, Schema> prop : bodySchema.getProperties().entrySet()) {
                    ObjectNode node = json.createObjectNode();
                    node.put("name", prop.getKey());
                    node.put("varId", Ulid.prefixed(IdPrefix.VARIABLE));
                    node.put("in", "body");
                    node.put("type", schemaType(prop.getValue()));
                    node.put("required", required.contains(prop.getKey()));
                    arr.add(node);
                }
            }
        }
        return arr;
    }

    private JsonNode buildOutput(Operation op) {
        ArrayNode arr = json.createArrayNode();
        if (op.getResponses() == null) return arr;

        // Lấy 2xx response đầu tiên — convention.
        var ok = op.getResponses().entrySet().stream()
                .filter(e -> e.getKey().startsWith("2"))
                .findFirst().orElse(null);
        if (ok == null || ok.getValue().getContent() == null) return arr;

        Schema<?> schema = ok.getValue().getContent().values().stream()
                .map(mt -> mt.getSchema())
                .filter(s -> s != null)
                .findFirst().orElse(null);
        if (schema == null || schema.getProperties() == null) return arr;

        for (Map.Entry<String, Schema> prop : schema.getProperties().entrySet()) {
            ObjectNode node = json.createObjectNode();
            node.put("field", prop.getKey());
            node.put("varId", Ulid.prefixed(IdPrefix.VARIABLE));
            node.put("type", schemaType(prop.getValue()));
            arr.add(node);
        }
        return arr;
    }

    private String schemaType(Schema<?> s) {
        if (s == null || s.getType() == null) return "string";
        return s.getType();
    }

    public record ImportResult(String serviceId, String serviceName, List<String> actionIds) {}
}

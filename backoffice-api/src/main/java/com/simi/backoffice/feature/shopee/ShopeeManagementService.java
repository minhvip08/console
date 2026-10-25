package com.simi.backoffice.feature.shopee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simi.backoffice.feature.management.action.ActionRepository;
import com.simi.backoffice.feature.management.platform.SwaggerImporter;
import com.simi.backoffice.feature.management.scenario.ScenarioRepository;
import com.simi.backoffice.feature.management.scenario.StepRepository;
import com.simi.backoffice.feature.management.step.StepTriggerRepository;
import com.simi.backoffice.feature.management.step.StepTriggerActionRepository;
import com.simi.common.domain.entity.*;
import com.simi.common.shopee.ShopeeException;
import com.simi.common.shopee.ShopeeMockPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@Transactional
public class ShopeeManagementService {
    private final ShopeeSourceRepository sources;
    private final ShopeeConnectionRepository connections;
    private final ShopeeMockPolicy policy;
    private final SwaggerImporter importer;
    private final ActionRepository actions;
    private final ScenarioRepository scenarios;
    private final StepRepository steps;
    private final StepTriggerRepository triggers;
    private final StepTriggerActionRepository triggerActions;
    private final ObjectMapper json;

    public ShopeeManagementService(ShopeeSourceRepository sources, ShopeeConnectionRepository connections,
            ShopeeMockPolicy policy, SwaggerImporter importer, ActionRepository actions,
            ScenarioRepository scenarios, StepRepository steps, StepTriggerRepository triggers,
            StepTriggerActionRepository triggerActions, ObjectMapper json) {
        this.sources = sources; this.connections = connections; this.policy = policy;
        this.importer = importer; this.actions = actions; this.scenarios = scenarios;
        this.steps = steps; this.triggers = triggers; this.triggerActions = triggerActions; this.json = json;
    }
    public List<ShopeeSource> listSources(String user) {
        policy.requireIdentity(user);
        return sources.findAll().stream().filter(s -> policy.canAccess(user, s)).toList();
    }
    public ShopeeSource source(String user, Long id) {
        ShopeeSource source = loadSource(id);
        policy.requireAccess(user, source);
        return source;
    }
    public ShopeeSource saveSource(String user, Long id, ShopeeManagementController.SourceRequest request) {
        policy.requireAdmin(user);
        ShopeeMockPolicy.dataTypes(request.kind());
        ShopeeSource source = id == null ? new ShopeeSource() : loadSource(id);
        if (id != null && !source.getKind().equals(request.kind()))
            throw new ShopeeException(400, "Source kind cannot be changed");
        source.setKind(request.kind()); source.setDisplayName(request.displayName());
        source.setDescription(request.description()); source.setEnabled(request.enabled());
        source.setAllowedUserIds(List.copyOf(request.allowedUserIds()));
        return sources.save(source);
    }
    public ShopeeSource authorize(String user, Long id, ShopeeManagementController.AuthorizationRequest request) {
        policy.requireAdmin(user);
        ShopeeSource source = loadSource(id);
        if (!source.getEnabled()) throw new ShopeeException(409, "Enable source before mock authorization");
        if (!"SHOP_ACCOUNT".equals(request.accountType()))
            throw new ShopeeException(400, "Only Shop Account is supported");
        if ("CANCELLED".equals(request.outcome())) return source;
        if (!"GRANTED".equals(request.outcome()) || request.shopAccountId() == null
                || !request.shopAccountId().matches("fixture_[A-Za-z0-9_-]{1,64}"))
            throw new ShopeeException(400, "Use a synthetic fixture_ Shop Account; no credentials are accepted");
        source.setShopAccountId(request.shopAccountId());
        // Local simulation only; this is not a vendor token lifetime.
        source.setAuthorizedUntil(policy.clock().instant().plus(30, ChronoUnit.DAYS));
        return sources.save(source);
    }
    public void deleteSource(String user, Long id) {
        policy.requireAdmin(user);
        ShopeeSource source = lockSource(id);
        if (connections.existsBySourceIdAndDeletedFalse(id))
            throw new ShopeeException(409, "Delete dependent connections before deleting this source");
        source.setEnabled(false); source.setDeleted(true); source.setAuthorizedUntil(null);
        sources.save(source); // Preserve execution history; no vendor revocation is implied.
    }
    public List<ShopeeConnection> listConnections(String user) {
        policy.requireIdentity(user);
        return connections.findAll().stream().filter(c -> !c.getDeleted())
                .filter(c -> policy.canAccess(user, loadSource(c.getSourceId()))).toList();
    }
    public ShopeeConnection connection(String user, Long id) {
        ShopeeConnection connection = loadConnection(id);
        policy.requireAccess(user, loadSource(connection.getSourceId()));
        return connection;
    }
    public ShopeeConnection saveConnection(String user, Long id, ShopeeManagementController.ConnectionRequest request) {
        policy.requireAdmin(user);
        if (!"MANUAL".equals(request.executionMode()))
            throw new ShopeeException(400, "Scheduled sync is not available; use MANUAL");
        ShopeeSource source = lockSource(request.sourceId());
        policy.requireReady(user, source);
        ShopeeConnection connection = id == null ? new ShopeeConnection() : loadConnection(id);
        if (id != null && !connection.getSourceId().equals(request.sourceId()))
            throw new ShopeeException(400, "Connection source cannot be changed; create a new connection");
        connection.setSourceId(source.getId()); connection.setName(request.name());
        connection.setDescription(request.description()); connection.setDataTypes(List.copyOf(request.dataTypes()));
        connection.setFirstLoadDate(request.firstLoadDate()); connection.setLookbackDays(request.lookbackDays());
        connection.setFrequencyMinutes(request.frequencyMinutes());
        policy.validate(connection, source);
        return connections.save(connection);
    }
    public void deleteConnection(String user, Long id) {
        policy.requireAdmin(user);
        ShopeeConnection connection = loadConnection(id);
        connection.setDeleted(true); connections.save(connection);
    }
    public ShopeeConnection provisionScenario(String user, Long id) {
        policy.requireAdmin(user);
        ShopeeConnection connection = connections.lockById(id)
                .orElseThrow(() -> new ShopeeException(404, "Connection not found"));
        if (connection.getDeleted()) throw new ShopeeException(404, "Connection not found");
        ShopeeSource source = loadSource(connection.getSourceId());
        policy.requireReady(user, source);
        if (connection.getScenarioDefinitionId() != null) return connection;
        String catalogId = "SHOPEE".equals(source.getKind()) ? "mock_shopee" : "mock_shopee_ads";
        SwaggerImporter.ImportResult imported = importer.importFromPlatform(catalogId);
        ActionDefinition action = actions.findById(imported.actionIds().getFirst()).orElseThrow();
        ScenarioDefinition scenario = new ScenarioDefinition();
        scenario.setName("[MOCK] " + connection.getName());
        scenario.setDescription("Synthetic Shopee preview. Manual only; no vendor calls.");
        scenario.setCreatedBy(user); scenarios.save(scenario);
        StepDefinition step = new StepDefinition();
        step.setScenarioDefinitionId(scenario.getId()); step.setName("Mock connection preview"); step.setStepOrder(1);
        ObjectNode ui = json.createObjectNode(); ui.put("mode", "mock-only"); ui.put("connectionId", id);
        ArrayNode elements = ui.putArray("elements");
        action.getParameters().forEach(parameter -> {
            ObjectNode widget = elements.addObject(); widget.put("type", "select");
            widget.put("label", parameter.path("name").asText());
            widget.put("outputVarId", parameter.path("varId").asText());
            ArrayNode options = widget.putArray("options");
            if ("load_mode".equals(parameter.path("name").asText())) {
                options.add("INITIAL"); options.add("INCREMENTAL"); widget.put("defaultValue", "INITIAL");
            } else {
                options.add("SUCCESS"); options.add("FAILED"); options.add("TIMEOUT"); widget.put("defaultValue", "SUCCESS");
            }
        });
        step.setUi(ui); steps.save(step);
        StepTrigger trigger = new StepTrigger(); trigger.setStepDefinitionId(step.getId());
        trigger.setName("preview"); trigger.setTriggerType("button_click"); trigger.setWidgetId("preview"); triggers.save(trigger);
        elements.addObject().put("type", "button").put("id", "preview").put("label", "Run mock preview")
                .put("triggerId", trigger.getId());
        elements.addObject().put("type", "json_viewer").put("label", "Synthetic results")
                .put("outputVarId", action.getOutput().get(0).path("varId").asText());
        steps.save(step);
        StepTriggerAction link = new StepTriggerAction(); link.setStepTriggerId(trigger.getId());
        link.setActionDefinitionId(action.getId()); link.setActionOrder(1); triggerActions.save(link);
        connection.setScenarioDefinitionId(scenario.getId()); connection.setActionDefinitionId(action.getId());
        return connections.save(connection);
    }
    // Serialize connection creation with source deletion, preserving lifecycle integrity.
    private ShopeeSource lockSource(Long id) {
        return sources.lockById(id).filter(s -> !s.getDeleted())
                .orElseThrow(() -> new ShopeeException(404, "Source not found"));
    }
    private ShopeeSource loadSource(Long id) {
        return sources.findById(id).filter(s -> !s.getDeleted())
                .orElseThrow(() -> new ShopeeException(404, "Source not found"));
    }
    private ShopeeConnection loadConnection(Long id) {
        return connections.findById(id).filter(c -> !c.getDeleted())
                .orElseThrow(() -> new ShopeeException(404, "Connection not found"));
    }
}

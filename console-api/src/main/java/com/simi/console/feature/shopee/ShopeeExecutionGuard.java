package com.simi.console.feature.shopee;

import com.simi.common.domain.entity.*;
import com.simi.common.shopee.ShopeeException;
import com.simi.common.shopee.ShopeeMockPolicy;
import com.simi.console.feature.context.ScenarioExecutionRepository;
import com.simi.console.feature.executor.*;
import com.simi.console.feature.rbac.PermissionService;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks run before cached results, resume and adapter dispatch. */
@Component
public class ShopeeExecutionGuard {
    private final ShopeeConnectionRepository connections;
    private final ShopeeSourceRepository sources;
    private final ShopeeRunRepository runs;
    private final ScenarioExecutionRepository executions;
    private final TriggerRepository triggers;
    private final StepDefinitionRepository steps;
    private final TriggerActionRepository links;
    private final ActionDefinitionRepository actions;
    private final ServiceRepository services;
    private final PermissionService permissions;
    private final ShopeeMockPolicy policy;

    public ShopeeExecutionGuard(ShopeeConnectionRepository connections, ShopeeSourceRepository sources,
            ShopeeRunRepository runs, ScenarioExecutionRepository executions, TriggerRepository triggers,
            StepDefinitionRepository steps, TriggerActionRepository links, ActionDefinitionRepository actions,
            ServiceRepository services, PermissionService permissions, ShopeeMockPolicy policy) {
        this.connections=connections; this.sources=sources; this.runs=runs; this.executions=executions;
        this.triggers=triggers; this.steps=steps; this.links=links; this.actions=actions;
        this.services=services; this.permissions=permissions; this.policy=policy;
    }
    public boolean visible(String user, Long scenarioId) {
        try { checkScenario(user, scenarioId); return true; }
        catch (ShopeeException | ResponseStatusException denied) { return false; }
    }
    public void checkScenario(String user, Long scenarioId) {
        connections.findByScenarioDefinitionId(scenarioId).ifPresent(connection -> {
            if (connection.getDeleted()) throw new ShopeeException(403, "Connection is deleted");
            policy.requireReady(user, source(connection));
            permissions.checkScenarioPermission(user, scenarioId);
        });
    }
    public void snapshot(String user, ScenarioExecution execution) {
        connections.findByScenarioDefinitionId(execution.getScenarioDefinitionId()).ifPresent(connection -> {
            checkScenario(user, execution.getScenarioDefinitionId());
            ShopeeSource source = source(connection);
            policy.validate(connection, source);
            ShopeeRun run = new ShopeeRun(); run.setId(execution.getId()); run.setConnectionId(connection.getId());
            run.setKind(source.getKind()); run.setShopAccountId(source.getShopAccountId());
            run.setDataTypes(List.copyOf(connection.getDataTypes())); run.setFirstLoadDate(connection.getFirstLoadDate());
            run.setLookbackDays(connection.getLookbackDays()); run.setRunAt(policy.clock().instant());
            runs.save(run);
        });
    }
    public void checkExecution(String user, Long executionId) {
        ScenarioExecution execution = executions.findById(executionId)
                .orElseThrow(() -> new ShopeeException(404, "Execution not found"));
        connections.findByScenarioDefinitionId(execution.getScenarioDefinitionId()).ifPresent(connection -> {
            checkScenario(user, execution.getScenarioDefinitionId());
            ShopeeRun run = runs.findById(executionId)
                    .orElseThrow(() -> new ShopeeException(409, "Mock run snapshot is missing; start a new execution"));
            if (!run.getConnectionId().equals(connection.getId())
                    || !run.getShopAccountId().equals(source(connection).getShopAccountId()))
                throw new ShopeeException(409, "Source account changed; start a new execution");
        });
    }
    public void checkTrigger(String user, Long executionId, Long triggerId, Map<String, Object> inputs) {
        ScenarioExecution execution = executions.findById(executionId)
                .orElseThrow(() -> new ShopeeException(404, "Execution not found"));
        // Also prevents invoking a Shopee trigger against an unrelated execution.
        StepTrigger trigger = triggers.findById(triggerId)
                .orElseThrow(() -> new ShopeeException(404, "Trigger not found"));
        StepDefinition step = steps.findById(trigger.getStepDefinitionId()).orElseThrow();
        if (!step.getScenarioDefinitionId().equals(execution.getScenarioDefinitionId()))
            throw new ShopeeException(403, "Trigger does not belong to execution scenario");
        connections.findByScenarioDefinitionId(execution.getScenarioDefinitionId()).ifPresent(connection -> {
            checkExecution(user, executionId);
            List<StepTriggerAction> triggerLinks = links.findByStepTriggerIdOrderByActionOrderAsc(triggerId);
            if (triggerLinks.size() != 1 || !triggerLinks.getFirst().getActionDefinitionId().equals(connection.getActionDefinitionId()))
                throw new ShopeeException(409, "Mock scenario definition has changed");
            ActionDefinition action = actions.findById(connection.getActionDefinitionId()).orElseThrow();
            checkAction(user, executionId, action);
            Set<String> allowed = new java.util.HashSet<>();
            action.getParameters().forEach(parameter -> {
                String name = parameter.path("name").asText();
                String varId = parameter.path("varId").asText();
                if (!Set.of("load_mode", "outcome").contains(name))
                    throw new ShopeeException(409, "Unsupported mock action parameter");
                allowed.add(varId);
                Object value = inputs == null ? null : inputs.get(varId);
                Set<String> values = "load_mode".equals(name) ? Set.of("INITIAL", "INCREMENTAL")
                        : Set.of("SUCCESS", "FAILED", "TIMEOUT");
                if (value != null && (!(value instanceof String text) || !values.contains(text)))
                    throw new ShopeeException(400, "Invalid mock preview option");
            });
            if (inputs != null && !allowed.containsAll(inputs.keySet()))
                throw new ShopeeException(400, "Only declared mock preview options are accepted");
        });
    }
    public void checkAction(String user, Long executionId, ActionDefinition action) {
        ScenarioExecution execution = executions.findById(executionId).orElseThrow();
        var connection = connections.findByScenarioDefinitionId(execution.getScenarioDefinitionId());
        Service service = services.findById(action.getServiceId()).orElseThrow();
        if (connection.isEmpty()) {
            if (ShopeeMockPolicy.isMockService(service.getName()))
                throw new ShopeeException(403, "Mock actions require a provisioned connection scenario");
            return;
        }
        checkExecution(user, executionId);
        ShopeeRun run = runs.findById(executionId).orElseThrow();
        if (!action.getId().equals(connection.get().getActionDefinitionId())
                || !ShopeeMockPolicy.serviceName(run.getKind()).equals(service.getName())
                || !"mock".equals(action.getAdapterType()) || !"/fixture/preview".equals(action.getEndpoint())
                || !"POST".equals(action.getHttpMethod()))
            throw new ShopeeException(409, "Mock scenario only permits its synthetic preview action");
    }
    private ShopeeSource source(ShopeeConnection connection) {
        return sources.findById(connection.getSourceId())
                .orElseThrow(() -> new ShopeeException(404, "Source not found"));
    }
}

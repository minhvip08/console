package com.simi.console.feature.executor;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simi.common.domain.ScenarioExecutionStatus;
import com.simi.common.domain.entity.ScenarioDefinition;
import com.simi.common.domain.entity.ScenarioExecution;
import com.simi.common.domain.entity.StepDefinition;
import com.simi.console.feature.context.ContextSnapshot;
import com.simi.console.feature.context.ContextStore;
import com.simi.console.feature.context.ScenarioExecutionRepository;
import com.simi.console.feature.rbac.PermissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/executions")
class ExecutionController {

    private final ScenarioDefinitionRepository scenarios;
    private final StepDefinitionRepository steps;
    private final ScenarioExecutionRepository executions;
    private final ContextStore contextStore;
    private final PermissionService permissionService;
    private final ObjectMapper json;

    ExecutionController(ScenarioDefinitionRepository scenarios,
                        StepDefinitionRepository steps,
                        ScenarioExecutionRepository executions,
                        ContextStore contextStore,
                        PermissionService permissionService,
                        ObjectMapper json) {
        this.scenarios = scenarios;
        this.steps = steps;
        this.executions = executions;
        this.contextStore = contextStore;
        this.permissionService = permissionService;
        this.json = json;
    }

    /** POST /api/executions {scenarioId, userId} → start scenario (docs/03 §Flow 1). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ExecutionDto start(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @Valid @RequestBody StartRequest req) {
        String userId = headerUserId != null ? headerUserId : req.userId();

        ScenarioDefinition def = scenarios.findById(req.scenarioId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "scenario not found: " + req.scenarioId()));

        // RBAC pre-check: user must have permission for this scenario (docs/09)
        if (userId != null && !userId.isBlank()) {
            permissionService.checkScenarioPermission(userId, def.getId());
        }

        List<StepDefinition> stepList = steps.findByScenarioDefinitionIdOrderByStepOrderAsc(def.getId());
        if (stepList.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scenario has no steps: " + def.getId());
        }

        ScenarioExecution exec = new ScenarioExecution();
        exec.setScenarioDefinitionId(def.getId());
        exec.setStatus(ScenarioExecutionStatus.EXECUTING);
        exec.setCurrentStepOrder(stepList.get(0).getStepOrder());
        ObjectNode emptyContext = json.createObjectNode();
        exec.setContext(emptyContext);
        exec.setCreatedBy(userId);
        executions.save(exec);

        return ExecutionDto.from(exec, def, stepList);
    }

    /** GET /api/executions/{id} — load execution + scenario info + step list. */
    @GetMapping("/{id}")
    public ExecutionDto get(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        // RBAC: check execution permission (docs/09)
        if (userId != null && !userId.isBlank()) {
            permissionService.checkExecutionPermission(userId, id);
        }
        ScenarioExecution exec = executions.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "execution not found: " + id));
        ScenarioDefinition def = scenarios.findById(exec.getScenarioDefinitionId()).orElseThrow();
        List<StepDefinition> stepList = steps.findByScenarioDefinitionIdOrderByStepOrderAsc(def.getId());
        return ExecutionDto.from(exec, def, stepList);
    }

    /**
     * POST /api/executions/{id}/resume — restore Redis 3-key model từ DB context JSONB.
     * Dùng khi resume sau crash / page reload / Redis restart (docs/03 §Flow 3).
     */
    @PostMapping("/{id}/resume")
    @Transactional
    public ExecutionDto resume(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        // RBAC: check execution permission (docs/09)
        if (userId != null && !userId.isBlank()) {
            permissionService.checkExecutionPermission(userId, id);
        }
        ScenarioExecution exec = executions.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "execution not found: " + id));
        if (exec.getStatus() == ScenarioExecutionStatus.COMPLETED
                || exec.getStatus() == ScenarioExecutionStatus.COMPENSATED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "execution is " + exec.getStatus() + ", read-only — cannot resume");
        }
        ContextSnapshot snapshot = contextStore.loadSnapshotFromDb(String.valueOf(id));
        contextStore.restoreToRedis(String.valueOf(id), snapshot);

        ScenarioDefinition def = scenarios.findById(exec.getScenarioDefinitionId()).orElseThrow();
        List<StepDefinition> stepList = steps.findByScenarioDefinitionIdOrderByStepOrderAsc(def.getId());
        return ExecutionDto.from(exec, def, stepList);
    }

    public record StartRequest(@NotNull Long scenarioId, String userId) {}
}

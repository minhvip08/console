package com.simi.backoffice.feature.management.step;

import com.simi.backoffice.feature.management.action.ActionRepository;
import com.simi.backoffice.feature.management.scenario.ScenarioRepository;
import com.simi.backoffice.feature.management.scenario.StepRepository;
import com.simi.common.domain.entity.StepDefinition;
import com.simi.common.domain.entity.StepTrigger;
import com.simi.common.domain.entity.StepTriggerAction;
import com.simi.common.ulid.IdPrefix;
import com.simi.common.ulid.Ulid;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/scenarios/{scenarioId}/steps")
class StepController {

    private final ScenarioRepository scenarios;
    private final StepRepository steps;
    private final StepTriggerRepository triggers;
    private final StepTriggerActionRepository triggerActions;
    private final ActionRepository actions;

    StepController(ScenarioRepository scenarios, StepRepository steps,
                   StepTriggerRepository triggers, StepTriggerActionRepository triggerActions,
                   ActionRepository actions) {
        this.scenarios = scenarios;
        this.steps = steps;
        this.triggers = triggers;
        this.triggerActions = triggerActions;
        this.actions = actions;
    }

    @GetMapping
    public List<StepDto> list(@PathVariable String scenarioId) {
        requireScenario(scenarioId);
        return steps.findByScenarioDefinitionIdOrderByStepOrderAsc(scenarioId).stream()
                .map(this::toDto)
                .toList();
    }

    @GetMapping("/{stepId}")
    public StepDto get(@PathVariable String scenarioId, @PathVariable String stepId) {
        requireScenario(scenarioId);
        StepDefinition s = loadStep(scenarioId, stepId);
        return toDto(s);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public StepDto create(@PathVariable String scenarioId, @Valid @RequestBody StepDto in) {
        requireScenario(scenarioId);

        StepDefinition s = new StepDefinition();
        s.setId(Ulid.prefixed(IdPrefix.STEP_DEFINITION));
        s.setScenarioDefinitionId(scenarioId);
        s.setName(in.name());
        s.setStepOrder(in.stepOrder());
        s.setUi(in.ui());
        steps.save(s);

        saveTriggers(s.getId(), in.triggers());

        return toDto(s);
    }

    @PutMapping("/{stepId}")
    @Transactional
    public StepDto update(@PathVariable String scenarioId, @PathVariable String stepId,
                          @Valid @RequestBody StepDto in) {
        requireScenario(scenarioId);
        StepDefinition s = loadStep(scenarioId, stepId);
        s.setName(in.name());
        s.setStepOrder(in.stepOrder());
        s.setUi(in.ui());
        steps.save(s);

        replaceTriggers(stepId, in.triggers());

        return toDto(s);
    }

    @DeleteMapping("/{stepId}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable String scenarioId, @PathVariable String stepId) {
        requireScenario(scenarioId);
        if (!steps.existsById(stepId)) return ResponseEntity.notFound().build();
        replaceTriggers(stepId, List.of());
        steps.deleteById(stepId);
        return ResponseEntity.noContent().build();
    }

    // ---- helpers ----

    private void saveTriggers(String stepId, List<StepDto.TriggerDto> in) {
        if (in == null) return;
        for (StepDto.TriggerDto t : in) {
            String triggerId = Ulid.prefixed(IdPrefix.STEP_TRIGGER);
            StepTrigger st = new StepTrigger();
            st.setId(triggerId);
            st.setStepDefinitionId(stepId);
            st.setName(t.name());
            st.setTriggerType(t.triggerType());
            st.setWidgetId(t.widgetId());
            triggers.save(st);

            if (t.actions() != null) {
                for (StepDto.TriggerActionDto a : t.actions()) {
                    if (!actions.existsById(a.actionDefinitionId())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "action_definition not found: " + a.actionDefinitionId());
                    }
                    StepTriggerAction sta = new StepTriggerAction();
                    sta.setId(Ulid.prefixed("sta"));
                    sta.setStepTriggerId(triggerId);
                    sta.setActionDefinitionId(a.actionDefinitionId());
                    sta.setActionOrder(a.actionOrder());
                    triggerActions.save(sta);
                }
            }
        }
    }

    private void replaceTriggers(String stepId, List<StepDto.TriggerDto> in) {
        // Cascade delete triggers + their junction rows
        for (StepTrigger t : triggers.findByStepDefinitionId(stepId)) {
            triggerActions.deleteByStepTriggerId(t.getId());
        }
        triggers.deleteByStepDefinitionId(stepId);
        saveTriggers(stepId, in);
    }

    private StepDto toDto(StepDefinition s) {
        List<StepDto.TriggerDto> triggerDtos = triggers.findByStepDefinitionId(s.getId()).stream()
                .map(t -> {
                    List<StepDto.TriggerActionDto> acts = triggerActions
                            .findByStepTriggerIdOrderByActionOrderAsc(t.getId()).stream()
                            .map(StepDto.TriggerActionDto::from)
                            .toList();
                    return StepDto.TriggerDto.from(t, acts);
                })
                .toList();
        return StepDto.from(s, triggerDtos);
    }

    private void requireScenario(String scenarioId) {
        if (!scenarios.existsById(scenarioId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "scenario not found: " + scenarioId);
        }
    }

    private StepDefinition loadStep(String scenarioId, String stepId) {
        StepDefinition s = steps.findById(stepId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "step not found: " + stepId));
        if (!s.getScenarioDefinitionId().equals(scenarioId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "step " + stepId + " does not belong to scenario " + scenarioId);
        }
        return s;
    }
}

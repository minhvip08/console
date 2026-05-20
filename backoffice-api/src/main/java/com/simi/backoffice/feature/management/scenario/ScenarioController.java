package com.simi.backoffice.feature.management.scenario;

import com.simi.common.domain.entity.ScenarioDefinition;
import com.simi.common.domain.entity.StepDefinition;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/scenarios")
class ScenarioController {

    private final ScenarioRepository scenarios;
    private final StepRepository steps;

    ScenarioController(ScenarioRepository scenarios, StepRepository steps) {
        this.scenarios = scenarios;
        this.steps = steps;
    }

    @GetMapping
    public List<ScenarioDto> list() {
        return scenarios.findAll().stream()
                .map(s -> ScenarioDto.from(s, summariesOf(s.getId())))
                .toList();
    }

    @GetMapping("/{id}")
    public ScenarioDto get(@PathVariable Long id) {
        ScenarioDefinition s = load(id);
        return ScenarioDto.from(s, summariesOf(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScenarioDto create(@Valid @RequestBody ScenarioDto in) {
        ScenarioDefinition s = new ScenarioDefinition();
        s.setName(in.name());
        s.setDescription(in.description());
        s.setCreatedBy(in.createdBy());
        scenarios.save(s);
        return ScenarioDto.from(s, List.of());
    }

    @PutMapping("/{id}")
    public ScenarioDto update(@PathVariable Long id, @Valid @RequestBody ScenarioDto in) {
        ScenarioDefinition s = load(id);
        s.setName(in.name());
        s.setDescription(in.description());
        scenarios.save(s);
        return ScenarioDto.from(s, summariesOf(id));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!scenarios.existsById(id)) return ResponseEntity.notFound().build();
        // Cascade phải explicit vì entity không có @OneToMany relationship.
        steps.deleteByScenarioDefinitionId(id);
        scenarios.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private List<ScenarioDto.StepSummary> summariesOf(Long scenarioId) {
        return steps.findByScenarioDefinitionIdOrderByStepOrderAsc(scenarioId).stream()
                .map(this::summary)
                .toList();
    }

    private ScenarioDto.StepSummary summary(StepDefinition s) {
        return new ScenarioDto.StepSummary(s.getId(), s.getName(), s.getStepOrder());
    }

    private ScenarioDefinition load(Long id) {
        return scenarios.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "scenario not found: " + id));
    }
}

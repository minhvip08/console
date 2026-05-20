package com.simi.backoffice.feature.management.action;

import com.simi.backoffice.feature.management.service.ServiceRepository;
import com.simi.common.domain.entity.ActionCompensation;
import com.simi.common.domain.entity.ActionDefinition;
import com.simi.common.domain.entity.ActionVerification;
import com.simi.common.domain.entity.AsyncActionConfig;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/actions")
class ActionController {

    private final ActionRepository actions;
    private final CompensationRepository compensations;
    private final VerificationRepository verifications;
    private final AsyncConfigRepository asyncConfigs;
    private final ServiceRepository services;

    ActionController(ActionRepository actions, CompensationRepository compensations,
                     VerificationRepository verifications, AsyncConfigRepository asyncConfigs,
                     ServiceRepository services) {
        this.actions = actions;
        this.compensations = compensations;
        this.verifications = verifications;
        this.asyncConfigs = asyncConfigs;
        this.services = services;
    }

    @GetMapping
    public List<ActionDto> list(@RequestParam(required = false) Long serviceId) {
        List<ActionDefinition> rows = serviceId == null
                ? actions.findAll()
                : actions.findByServiceId(serviceId);
        return rows.stream().map(this::toDto).toList();
    }

    @GetMapping("/{id}")
    public ActionDto get(@PathVariable Long id) {
        return toDto(load(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ActionDto create(@Valid @RequestBody ActionDto in) {
        if (!services.existsById(in.serviceId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "service not found: " + in.serviceId());
        }
        ActionDefinition a = new ActionDefinition();
        applyTo(a, in);
        ActionDefinition saved = actions.save(a);
        upsertNested(saved.getId(), in);
        return toDto(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    public ActionDto update(@PathVariable Long id, @Valid @RequestBody ActionDto in) {
        ActionDefinition a = load(id);
        applyTo(a, in);
        actions.save(a);
        upsertNested(id, in);
        return toDto(a);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!actions.existsById(id)) return ResponseEntity.notFound().build();
        compensations.deleteByActionDefinitionId(id);
        verifications.deleteByActionDefinitionId(id);
        asyncConfigs.deleteByActionDefinitionId(id);
        actions.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ---- helpers ----

    private void applyTo(ActionDefinition a, ActionDto in) {
        a.setName(in.name());
        a.setServiceId(in.serviceId());
        a.setEndpoint(in.endpoint());
        a.setHttpMethod(in.httpMethod());
        a.setAdapterType(Optional.ofNullable(in.adapterType()).orElse("http"));
        a.setTimeoutMs(Optional.ofNullable(in.timeoutMs()).orElse(10000));
        a.setRetryPolicyId(in.retryPolicyId());
        a.setParameters(in.parameters());
        a.setOutput(in.output());
    }

    private void upsertNested(Long actionId, ActionDto in) {
        // Compensation
        compensations.deleteByActionDefinitionId(actionId);
        if (in.compensation() != null) {
            ActionCompensation c = new ActionCompensation();
            c.setActionDefinitionId(actionId);
            c.setCompensationActionDefinitionId(in.compensation().compensationActionDefinitionId());
            c.setTriggerType(in.compensation().triggerType());
            c.setRequiredVarIds(in.compensation().requiredVarIds() == null
                    ? new String[0]
                    : in.compensation().requiredVarIds().toArray(new String[0]));
            compensations.save(c);
        }

        // Verification
        verifications.deleteByActionDefinitionId(actionId);
        if (in.verification() != null) {
            ActionVerification v = new ActionVerification();
            v.setActionDefinitionId(actionId);
            v.setEndpoint(in.verification().endpoint());
            v.setHttpMethod(in.verification().httpMethod());
            v.setQueryParams(in.verification().queryParams());
            v.setConditions(in.verification().conditions());
            v.setPollIntervalMs(Optional.ofNullable(in.verification().pollIntervalMs()).orElse(5000));
            v.setMaxPollAttempts(Optional.ofNullable(in.verification().maxPollAttempts()).orElse(12));
            verifications.save(v);
        }

        // Async config
        asyncConfigs.deleteByActionDefinitionId(actionId);
        if ("async".equals(in.adapterType()) && in.asyncConfig() != null) {
            AsyncActionConfig ac = new AsyncActionConfig();
            ac.setActionDefinitionId(actionId);
            ac.setPublishTo(in.asyncConfig().publishTo());
            ac.setStatusCheckEndpoint(in.asyncConfig().statusCheckEndpoint());
            ac.setStatusCheckIntervalMs(in.asyncConfig().statusCheckIntervalMs());
            ac.setStatusCheckTimeoutMs(in.asyncConfig().statusCheckTimeoutMs());
            ac.setDoneCondition(in.asyncConfig().doneCondition());
            asyncConfigs.save(ac);
        }
    }

    private ActionDto toDto(ActionDefinition a) {
        ActionCompensation c = compensations.findByActionDefinitionId(a.getId()).orElse(null);
        ActionVerification v = verifications.findByActionDefinitionId(a.getId()).orElse(null);
        AsyncActionConfig ac = asyncConfigs.findByActionDefinitionId(a.getId()).orElse(null);
        return ActionDto.from(a, c, v, ac);
    }

    private ActionDefinition load(Long id) {
        return actions.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "action not found: " + id));
    }
}

package com.simi.backoffice.feature.management.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.simi.common.domain.entity.StepDefinition;
import com.simi.common.domain.entity.StepTrigger;
import com.simi.common.domain.entity.StepTriggerAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record StepDto(
        Long id,
        @NotBlank String name,
        int stepOrder,
        @NotNull JsonNode ui,
        List<TriggerDto> triggers
) {
    public record TriggerDto(
            Long id,
            @NotBlank String name,
            @NotBlank String triggerType,   // button_click | on_load | on_change
            String widgetId,                // null khi trigger_type = on_load
            List<TriggerActionDto> actions
    ) {
        public static TriggerDto from(StepTrigger t, List<TriggerActionDto> actions) {
            return new TriggerDto(t.getId(), t.getName(), t.getTriggerType(), t.getWidgetId(), actions);
        }
    }

    public record TriggerActionDto(
            @NotNull Long actionDefinitionId,
            int actionOrder
    ) {
        public static TriggerActionDto from(StepTriggerAction a) {
            return new TriggerActionDto(a.getActionDefinitionId(), a.getActionOrder());
        }
    }

    public static StepDto from(StepDefinition s, List<TriggerDto> triggers) {
        return new StepDto(s.getId(), s.getName(), s.getStepOrder(), s.getUi(), triggers);
    }
}

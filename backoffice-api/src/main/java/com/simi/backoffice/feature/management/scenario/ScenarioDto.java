package com.simi.backoffice.feature.management.scenario;

import com.simi.common.domain.entity.ScenarioDefinition;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;
import java.util.List;

public record ScenarioDto(
        Long id,
        @NotBlank String name,
        String description,
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<StepSummary> steps
) {
    public static ScenarioDto from(ScenarioDefinition s, List<StepSummary> steps) {
        return new ScenarioDto(
                s.getId(), s.getName(), s.getDescription(), s.getCreatedBy(),
                s.getCreatedAt(), s.getUpdatedAt(), steps);
    }

    public record StepSummary(Long id, String name, int stepOrder) {}
}

package com.simi.console.feature.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.simi.common.domain.ScenarioExecutionStatus;
import com.simi.common.domain.entity.ScenarioDefinition;
import com.simi.common.domain.entity.ScenarioExecution;
import com.simi.common.domain.entity.StepDefinition;

import java.time.OffsetDateTime;
import java.util.List;

public record ExecutionDto(
        String executionId,
        ScenarioInfo scenario,
        ScenarioExecutionStatus status,
        Integer currentStepOrder,
        List<StepInfo> steps,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public record ScenarioInfo(String id, String name, String description) {
        public static ScenarioInfo from(ScenarioDefinition d) {
            return new ScenarioInfo(d.getId(), d.getName(), d.getDescription());
        }
    }

    public record StepInfo(String id, String name, int stepOrder, JsonNode ui) {
        public static StepInfo from(StepDefinition s) {
            return new StepInfo(s.getId(), s.getName(), s.getStepOrder(), s.getUi());
        }
    }

    public static ExecutionDto from(ScenarioExecution exec, ScenarioDefinition def, List<StepDefinition> steps) {
        return new ExecutionDto(
                exec.getId(),
                ScenarioInfo.from(def),
                exec.getStatus(),
                exec.getCurrentStepOrder(),
                steps.stream().map(StepInfo::from).toList(),
                exec.getCreatedAt(),
                exec.getUpdatedAt()
        );
    }
}

package com.simi.console.feature.executor;

import com.simi.common.domain.entity.TriggerExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TriggerExecutionRepository extends JpaRepository<TriggerExecution, String> {

    /** Tầng 3 lookup — UNIQUE (scenario_execution_id, step_trigger_id) guarantees ≤1 row. */
    Optional<TriggerExecution> findByScenarioExecutionIdAndStepTriggerId(
            String scenarioExecutionId, String stepTriggerId);
}

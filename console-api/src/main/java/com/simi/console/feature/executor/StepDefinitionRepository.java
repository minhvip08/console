package com.simi.console.feature.executor;

import com.simi.common.domain.entity.StepDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StepDefinitionRepository extends JpaRepository<StepDefinition, String> {
    List<StepDefinition> findByScenarioDefinitionIdOrderByStepOrderAsc(String scenarioDefinitionId);
}

package com.simi.backoffice.feature.management.scenario;

import com.simi.common.domain.entity.StepDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StepRepository extends JpaRepository<StepDefinition, String> {
    List<StepDefinition> findByScenarioDefinitionIdOrderByStepOrderAsc(String scenarioDefinitionId);
    void deleteByScenarioDefinitionId(String scenarioDefinitionId);
}

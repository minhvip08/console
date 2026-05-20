package com.simi.backoffice.feature.management.scenario;

import com.simi.common.domain.entity.StepDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StepRepository extends JpaRepository<StepDefinition, Long> {
    List<StepDefinition> findByScenarioDefinitionIdOrderByStepOrderAsc(Long scenarioDefinitionId);
    void deleteByScenarioDefinitionId(Long scenarioDefinitionId);
}

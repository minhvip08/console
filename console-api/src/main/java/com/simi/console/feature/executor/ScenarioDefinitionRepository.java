package com.simi.console.feature.executor;

import com.simi.common.domain.entity.ScenarioDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioDefinitionRepository extends JpaRepository<ScenarioDefinition, Long> {
}

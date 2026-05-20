package com.simi.backoffice.feature.management.scenario;

import com.simi.common.domain.entity.ScenarioDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioRepository extends JpaRepository<ScenarioDefinition, Long> {
}

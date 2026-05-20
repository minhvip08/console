package com.simi.reconciliation;

import com.simi.common.domain.entity.ScenarioExecution;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioExecutionRepository extends JpaRepository<ScenarioExecution, Long> {
}

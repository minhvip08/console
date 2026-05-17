package com.simi.console.feature.compensation;

import com.simi.common.domain.entity.SagaCompensationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SagaLogRepository extends JpaRepository<SagaCompensationLog, String> {

    /** Phase 5 hot path — reverse-order load cho compensation manager. */
    List<SagaCompensationLog> findByScenarioExecutionIdOrderByStepOrderDesc(String scenarioExecutionId);
}

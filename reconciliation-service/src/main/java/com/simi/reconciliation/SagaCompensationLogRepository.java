package com.simi.reconciliation;

import com.simi.common.domain.entity.SagaCompensationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SagaCompensationLogRepository extends JpaRepository<SagaCompensationLog, Long> {
    List<SagaCompensationLog> findByScenarioExecutionIdOrderByStepOrderDesc(Long scenarioExecutionId);
}

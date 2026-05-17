package com.simi.console.feature.compensation;

import com.simi.common.domain.entity.CompensationDeadLetter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeadLetterRepository extends JpaRepository<CompensationDeadLetter, String> {

    /** Unresolved dead letters cho 1 execution — hiển thị trên compensation-status. */
    List<CompensationDeadLetter> findByScenarioExecutionIdAndResolvedFalse(String scenarioExecutionId);

    /** Tất cả dead letters (cả resolved) cho 1 execution — audit. */
    List<CompensationDeadLetter> findByScenarioExecutionId(String scenarioExecutionId);
}

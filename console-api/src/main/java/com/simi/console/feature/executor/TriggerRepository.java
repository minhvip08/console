package com.simi.console.feature.executor;

import com.simi.common.domain.entity.StepTrigger;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TriggerRepository extends JpaRepository<StepTrigger, Long> {
}

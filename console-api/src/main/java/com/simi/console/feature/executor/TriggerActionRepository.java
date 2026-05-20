package com.simi.console.feature.executor;

import com.simi.common.domain.entity.StepTriggerAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TriggerActionRepository extends JpaRepository<StepTriggerAction, Long> {
    List<StepTriggerAction> findByStepTriggerIdOrderByActionOrderAsc(Long stepTriggerId);
}

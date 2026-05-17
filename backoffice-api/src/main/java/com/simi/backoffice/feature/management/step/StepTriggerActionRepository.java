package com.simi.backoffice.feature.management.step;

import com.simi.common.domain.entity.StepTriggerAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StepTriggerActionRepository extends JpaRepository<StepTriggerAction, String> {
    List<StepTriggerAction> findByStepTriggerIdOrderByActionOrderAsc(String stepTriggerId);
    void deleteByStepTriggerId(String stepTriggerId);
}

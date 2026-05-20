package com.simi.backoffice.feature.management.step;

import com.simi.common.domain.entity.StepTrigger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StepTriggerRepository extends JpaRepository<StepTrigger, Long> {
    List<StepTrigger> findByStepDefinitionId(Long stepDefinitionId);
    void deleteByStepDefinitionId(Long stepDefinitionId);
}

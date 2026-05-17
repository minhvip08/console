package com.simi.backoffice.feature.management.action;

import com.simi.common.domain.entity.ActionCompensation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompensationRepository extends JpaRepository<ActionCompensation, String> {
    Optional<ActionCompensation> findByActionDefinitionId(String actionDefinitionId);
    void deleteByActionDefinitionId(String actionDefinitionId);
}

package com.simi.console.feature.executor;

import com.simi.common.domain.entity.ActionCompensation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompensationDefinitionRepository extends JpaRepository<ActionCompensation, String> {
    Optional<ActionCompensation> findByActionDefinitionId(String actionDefinitionId);
}

package com.simi.backoffice.feature.management.action;

import com.simi.common.domain.entity.ActionVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VerificationRepository extends JpaRepository<ActionVerification, String> {
    Optional<ActionVerification> findByActionDefinitionId(String actionDefinitionId);
    void deleteByActionDefinitionId(String actionDefinitionId);
}

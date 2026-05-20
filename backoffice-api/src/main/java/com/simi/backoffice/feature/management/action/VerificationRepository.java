package com.simi.backoffice.feature.management.action;

import com.simi.common.domain.entity.ActionVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VerificationRepository extends JpaRepository<ActionVerification, Long> {
    Optional<ActionVerification> findByActionDefinitionId(Long actionDefinitionId);
    void deleteByActionDefinitionId(Long actionDefinitionId);
}

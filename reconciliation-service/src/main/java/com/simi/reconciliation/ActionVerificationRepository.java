package com.simi.reconciliation;

import com.simi.common.domain.entity.ActionVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ActionVerificationRepository extends JpaRepository<ActionVerification, Long> {
    Optional<ActionVerification> findByActionDefinitionId(Long actionDefinitionId);
}

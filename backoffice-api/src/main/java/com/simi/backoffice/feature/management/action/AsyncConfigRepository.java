package com.simi.backoffice.feature.management.action;

import com.simi.common.domain.entity.AsyncActionConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AsyncConfigRepository extends JpaRepository<AsyncActionConfig, Long> {
    Optional<AsyncActionConfig> findByActionDefinitionId(Long actionDefinitionId);
    void deleteByActionDefinitionId(Long actionDefinitionId);
}

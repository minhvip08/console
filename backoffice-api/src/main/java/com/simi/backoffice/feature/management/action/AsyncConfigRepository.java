package com.simi.backoffice.feature.management.action;

import com.simi.common.domain.entity.AsyncActionConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AsyncConfigRepository extends JpaRepository<AsyncActionConfig, String> {
    Optional<AsyncActionConfig> findByActionDefinitionId(String actionDefinitionId);
    void deleteByActionDefinitionId(String actionDefinitionId);
}

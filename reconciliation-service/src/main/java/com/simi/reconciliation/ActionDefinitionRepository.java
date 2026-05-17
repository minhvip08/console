package com.simi.reconciliation;

import com.simi.common.domain.entity.ActionDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActionDefinitionRepository extends JpaRepository<ActionDefinition, String> {
}

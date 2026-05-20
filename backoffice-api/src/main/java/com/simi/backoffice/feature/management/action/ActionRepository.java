package com.simi.backoffice.feature.management.action;

import com.simi.common.domain.entity.ActionDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActionRepository extends JpaRepository<ActionDefinition, Long> {
    List<ActionDefinition> findByServiceId(Long serviceId);
}

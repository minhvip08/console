package com.simi.reconciliation;

import com.simi.common.domain.entity.ActionExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ActionExecutionRepository extends JpaRepository<ActionExecution, Long> {
    Optional<ActionExecution> findByIdempotencyKey(String idempotencyKey);
}

package com.simi.console.feature.executor;

import com.simi.common.domain.entity.ActionExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ActionExecutionRepository extends JpaRepository<ActionExecution, Long> {

    /** Tầng 4 lookup — idempotency_key UNIQUE. */
    Optional<ActionExecution> findByIdempotencyKey(String idempotencyKey);
}

package com.simi.reconciliation;

import com.simi.common.domain.ActionExecutionStatus;
import com.simi.common.domain.entity.ActionExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ActionExecutionRepository extends JpaRepository<ActionExecution, Long> {
    Optional<ActionExecution> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT ae FROM ActionExecution ae WHERE ae.status = :status AND ae.startedAt < :cutoff")
    List<ActionExecution> findByStatusAndStartedAtBefore(
            @Param("status") ActionExecutionStatus status,
            @Param("cutoff") OffsetDateTime cutoff);
}

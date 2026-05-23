package com.simi.reconciliation;

import com.simi.common.domain.entity.CompensationDeadLetter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface CompensationDeadLetterRepository extends JpaRepository<CompensationDeadLetter, Long> {
    Optional<CompensationDeadLetter> findBySagaCompensationLogId(Long sagaCompensationLogId);

    @Query("""
            SELECT dl FROM CompensationDeadLetter dl
            WHERE dl.resolved = false
              AND dl.nextRetryAt IS NOT NULL
              AND dl.nextRetryAt <= :now
              AND dl.retryCount < :maxRetries
            """)
    List<CompensationDeadLetter> findDueForRetry(
            @Param("now") OffsetDateTime now,
            @Param("maxRetries") int maxRetries);
}

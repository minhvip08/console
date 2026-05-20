package com.simi.reconciliation;

import com.simi.common.domain.entity.ReconciliationEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationEventRepository extends JpaRepository<ReconciliationEvent, Long> {
}

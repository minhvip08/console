package com.simi.console.feature.executor;

import com.simi.common.domain.entity.RetryPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RetryPolicyRepository extends JpaRepository<RetryPolicy, Long> {
}

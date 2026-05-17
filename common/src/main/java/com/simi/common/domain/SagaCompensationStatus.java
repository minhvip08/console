package com.simi.common.domain;

/** Status của saga_compensation_log row (docs/06). */
public enum SagaCompensationStatus {
    PENDING,
    COMPENSATING,
    COMPENSATED,
    COMPENSATION_FAILED
}

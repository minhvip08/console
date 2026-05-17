package com.simi.common.domain;

/** Trạng thái trigger_execution (Tầng 3 idempotency — docs/05). */
public enum TriggerExecutionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}

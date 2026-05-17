package com.simi.common.domain;

/**
 * Trạng thái scenario_execution (docs/03 §Status Machine, docs/08 §scenario_execution).
 * <pre>
 * PENDING ─▶ EXECUTING ─▶ COMPLETED
 *               │
 *               └─▶ FAILED ─▶ COMPENSATING ─▶ COMPENSATED
 *                                  │
 *                                  └─▶ COMPENSATION_FAILED
 * </pre>
 */
public enum ScenarioExecutionStatus {
    PENDING,
    EXECUTING,
    COMPLETED,
    FAILED,
    COMPENSATING,
    COMPENSATED,
    COMPENSATION_FAILED
}

package com.simi.common.domain;

/**
 * Trạng thái action_execution (docs/03 + docs/08).
 * <ul>
 *   <li>FAILED — nhận response 4xx/5xx rõ ràng → Saga compensation</li>
 *   <li>TIMEOUT — không nhận được response → Reconciler verify 3rd party</li>
 * </ul>
 * Split này là quyết định kiến trúc cốt lõi: Saga handle known outcome, CDC+Reconciler
 * handle unknown outcome. Không bao giờ collapse 2 trạng thái này.
 */
public enum ActionExecutionStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    TIMEOUT
}

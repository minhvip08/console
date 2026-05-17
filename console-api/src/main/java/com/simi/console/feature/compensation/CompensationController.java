package com.simi.console.feature.compensation;

import com.simi.common.domain.SagaCompensationStatus;
import com.simi.common.domain.ScenarioExecutionStatus;
import com.simi.common.domain.entity.CompensationDeadLetter;
import com.simi.common.domain.entity.SagaCompensationLog;
import com.simi.console.feature.rbac.PermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Compensation endpoints (docs/06 §Dead Letter):
 * <ul>
 *   <li>GET  /api/executions/{id}/compensation-status — current compensation state</li>
 *   <li>POST /api/executions/{id}/retry-compensation  — retry failed compensations</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/executions/{executionId}")
class CompensationController {

    private final CompensationManager compensationManager;
    private final PermissionService permissionService;

    CompensationController(CompensationManager compensationManager,
                           PermissionService permissionService) {
        this.compensationManager = compensationManager;
        this.permissionService = permissionService;
    }

    /**
     * GET /api/executions/{id}/compensation-status
     * Returns saga entries + dead letters for the execution.
     */
    @GetMapping("/compensation-status")
    public ResponseEntity<CompensationStatusResponse> getStatus(
            @PathVariable String executionId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        // RBAC: check execution permission (docs/09)
        if (userId != null && !userId.isBlank()) {
            permissionService.checkExecutionPermission(userId, executionId);
        }
        try {
            var result = compensationManager.getCompensationStatus(executionId);
            return ResponseEntity.ok(CompensationStatusResponse.from(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * POST /api/executions/{id}/retry-compensation
     * Retry compensation for COMPENSATION_FAILED entries.
     */
    @PostMapping("/retry-compensation")
    public ResponseEntity<CompensationRetryResponse> retryCompensation(
            @PathVariable String executionId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        // RBAC: check execution permission (docs/09)
        if (userId != null && !userId.isBlank()) {
            permissionService.checkExecutionPermission(userId, executionId);
        }
        try {
            var result = compensationManager.retryCompensation(executionId);
            HttpStatus status = result.executionStatus() == ScenarioExecutionStatus.COMPENSATED
                    ? HttpStatus.OK
                    : HttpStatus.INTERNAL_SERVER_ERROR;
            return ResponseEntity.status(status).body(CompensationRetryResponse.from(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ─── Response DTOs ───

    public record CompensationStatusResponse(
            ScenarioExecutionStatus executionStatus,
            List<SagaEntryDto> sagaEntries,
            List<DeadLetterDto> deadLetters
    ) {
        static CompensationStatusResponse from(CompensationManager.CompensationStatusResult r) {
            return new CompensationStatusResponse(
                    r.executionStatus(),
                    r.sagaEntries().stream().map(SagaEntryDto::from).toList(),
                    r.deadLetters().stream().map(DeadLetterDto::from).toList()
            );
        }
    }

    public record CompensationRetryResponse(
            ScenarioExecutionStatus executionStatus,
            List<SagaEntryDto> sagaEntries,
            String message
    ) {
        static CompensationRetryResponse from(CompensationManager.CompensationResult r) {
            return new CompensationRetryResponse(
                    r.executionStatus(),
                    r.sagaEntries().stream().map(SagaEntryDto::from).toList(),
                    r.message()
            );
        }
    }

    public record SagaEntryDto(
            String id,
            String actionDefinitionId,
            int stepOrder,
            SagaCompensationStatus status,
            String compensationActionExecutionId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        static SagaEntryDto from(SagaCompensationLog log) {
            return new SagaEntryDto(
                    log.getId(),
                    log.getActionDefinitionId(),
                    log.getStepOrder(),
                    log.getStatus(),
                    log.getCompensationActionExecutionId(),
                    log.getCreatedAt(),
                    log.getUpdatedAt()
            );
        }
    }

    public record DeadLetterDto(
            String id,
            String sagaCompensationLogId,
            String actionDefinitionId,
            String failureReason,
            int retryCount,
            boolean resolved,
            OffsetDateTime createdAt
    ) {
        static DeadLetterDto from(CompensationDeadLetter dl) {
            return new DeadLetterDto(
                    dl.getId(),
                    dl.getSagaCompensationLogId(),
                    dl.getActionDefinitionId(),
                    dl.getFailureReason(),
                    dl.getRetryCount(),
                    dl.isResolved(),
                    dl.getCreatedAt()
            );
        }
    }
}

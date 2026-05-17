package com.simi.backoffice.feature.management.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.simi.common.domain.entity.ActionCompensation;
import com.simi.common.domain.entity.ActionDefinition;
import com.simi.common.domain.entity.ActionVerification;
import com.simi.common.domain.entity.AsyncActionConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ActionDto(
        String id,
        @NotBlank String name,
        @NotBlank String serviceId,
        @NotBlank String endpoint,
        @NotBlank String httpMethod,
        String adapterType,        // default 'http'
        Integer timeoutMs,         // default 10000
        String retryPolicyId,
        @NotNull JsonNode parameters,
        @NotNull JsonNode output,
        Compensation compensation,  // nullable
        Verification verification,  // nullable
        AsyncConfig asyncConfig     // nullable, chỉ khi adapterType=async
) {
    public record Compensation(
            String compensationActionDefinitionId,
            String triggerType,         // auto|manual|none
            List<String> requiredVarIds
    ) {
        static Compensation from(ActionCompensation c) {
            return new Compensation(
                    c.getCompensationActionDefinitionId(),
                    c.getTriggerType(),
                    List.of(c.getRequiredVarIds()));
        }
    }

    public record Verification(
            String endpoint,
            String httpMethod,
            JsonNode queryParams,
            JsonNode conditions,
            Integer pollIntervalMs,
            Integer maxPollAttempts
    ) {
        static Verification from(ActionVerification v) {
            return new Verification(
                    v.getEndpoint(), v.getHttpMethod(),
                    v.getQueryParams(), v.getConditions(),
                    v.getPollIntervalMs(), v.getMaxPollAttempts());
        }
    }

    public record AsyncConfig(
            String publishTo,
            String statusCheckEndpoint,
            Integer statusCheckIntervalMs,
            Integer statusCheckTimeoutMs,
            String doneCondition
    ) {
        static AsyncConfig from(AsyncActionConfig a) {
            return new AsyncConfig(
                    a.getPublishTo(), a.getStatusCheckEndpoint(),
                    a.getStatusCheckIntervalMs(), a.getStatusCheckTimeoutMs(),
                    a.getDoneCondition());
        }
    }

    public static ActionDto from(ActionDefinition a, ActionCompensation c, ActionVerification v, AsyncActionConfig ac) {
        return new ActionDto(
                a.getId(), a.getName(), a.getServiceId(), a.getEndpoint(), a.getHttpMethod(),
                a.getAdapterType(), a.getTimeoutMs(), a.getRetryPolicyId(),
                a.getParameters(), a.getOutput(),
                c == null ? null : Compensation.from(c),
                v == null ? null : Verification.from(v),
                ac == null ? null : AsyncConfig.from(ac));
    }
}

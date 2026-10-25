package com.simi.console.feature.adapter;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simi.common.domain.entity.ActionDefinition;
import com.simi.common.domain.entity.RetryPolicy;
import com.simi.common.domain.entity.Service;

import java.util.Map;

/**
 * Input cho ServiceAdapter — đủ info để dựng HTTP request mà không touch DB.
 * idempotencyKey được forward xuống 3rd party qua header (cho phép verification — docs/07).
 */
public record AdapterRequest(
        ActionDefinition action,
        Service service,
        RetryPolicy retryPolicy,   // nullable
        Map<String, Object> pathParams,
        Map<String, Object> queryParams,
        Map<String, Object> headers,
        ObjectNode body,
        String idempotencyKey,
        Long executionId,
        String userId
) {}

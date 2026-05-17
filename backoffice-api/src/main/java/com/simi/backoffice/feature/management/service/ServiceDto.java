package com.simi.backoffice.feature.management.service;

import com.simi.common.domain.entity.Service;
import jakarta.validation.constraints.NotBlank;

public record ServiceDto(
        String id,
        @NotBlank String name,
        @NotBlank String baseUrl
) {
    public static ServiceDto from(Service s) {
        return new ServiceDto(s.getId(), s.getName(), s.getBaseUrl());
    }
}

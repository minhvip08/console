package com.simi.backoffice.feature.management.platform;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Mock implementation — sample Swagger YAML embedded trong classpath (mock-platform/).
 * Real impl sẽ gọi Nexon Platform API qua HTTP với auth (xem CLAUDE.md). Đây là demo.
 */
@Component
class MockPlatformApiClient implements PlatformApiClient {

    private static final List<PlatformService> SERVICES = List.of(
            new PlatformService("platform_coupon", "coupon-service", "https://coupon.internal",
                    "Issue + delete coupons for campaigns"),
            new PlatformService("platform_payment", "payment-gateway", "https://payment.internal",
                    "Charge + refund payments")
    );

    private static final Map<String, String> SWAGGER_RESOURCES = Map.of(
            "platform_coupon", "mock-platform/coupon-service.yaml",
            "platform_payment", "mock-platform/payment-gateway.yaml"
    );

    @Override
    public List<PlatformService> listServices() {
        return SERVICES;
    }

    @Override
    public String fetchSwagger(String platformServiceId) {
        String path = SWAGGER_RESOURCES.get(platformServiceId);
        if (path == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Platform service not found: " + platformServiceId);
        }
        try {
            return new String(new ClassPathResource(path).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load mock swagger: " + path, e);
        }
    }
}

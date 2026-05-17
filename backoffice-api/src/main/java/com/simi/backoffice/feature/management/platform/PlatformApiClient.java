package com.simi.backoffice.feature.management.platform;

import java.util.List;

/**
 * Port — Backoffice gọi Platform API để (a) list services khả dụng, (b) fetch Swagger
 * YAML của một service cụ thể. Implementation thực sự call Nexon Platform API qua HTTP;
 * trong demo này dùng MockPlatformApiClient với sample YAML embedded.
 */
public interface PlatformApiClient {

    List<PlatformService> listServices();

    /** Trả về raw OpenAPI/Swagger YAML (hoặc JSON). */
    String fetchSwagger(String platformServiceId);

    record PlatformService(String id, String name, String baseUrl, String description) {}
}

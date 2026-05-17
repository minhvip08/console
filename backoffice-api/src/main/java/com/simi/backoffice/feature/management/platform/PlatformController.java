package com.simi.backoffice.feature.management.platform;

import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/platform")
class PlatformController {

    private final PlatformApiClient platform;
    private final SwaggerImporter importer;

    PlatformController(PlatformApiClient platform, SwaggerImporter importer) {
        this.platform = platform;
        this.importer = importer;
    }

    /** List services khả dụng để import (sau khi user chọn → POST /api/imports). */
    @GetMapping("/services")
    public List<PlatformApiClient.PlatformService> listServices() {
        return platform.listServices();
    }

    /** Preview swagger trước khi import — debug/inspection. */
    @GetMapping("/services/{platformServiceId}/swagger")
    public String previewSwagger(@org.springframework.web.bind.annotation.PathVariable String platformServiceId) {
        return platform.fetchSwagger(platformServiceId);
    }

    /** Import action_definitions từ một platform service. */
    @PostMapping("/imports")
    public SwaggerImporter.ImportResult importService(@RequestBody ImportRequest req) {
        return importer.importFromPlatform(req.platformServiceId());
    }

    public record ImportRequest(@NotBlank String platformServiceId) {}
}

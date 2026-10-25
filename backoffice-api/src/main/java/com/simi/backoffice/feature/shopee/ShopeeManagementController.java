package com.simi.backoffice.feature.shopee;

import com.simi.common.domain.entity.ShopeeConnection;
import com.simi.common.domain.entity.ShopeeSource;
import com.simi.common.shopee.ShopeeMockPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Console-owned mock management API; these are not Elton API endpoints. */
@RestController
@RequestMapping("/api/shopee")
class ShopeeManagementController {
    private final ShopeeManagementService management;
    private final ShopeeMockPolicy policy;
    ShopeeManagementController(ShopeeManagementService management, ShopeeMockPolicy policy) {
        this.management = management; this.policy = policy;
    }
    @GetMapping("/catalog")
    Map<String, Object> catalog(@RequestHeader(value="X-User-Id", required=false) String user) {
        policy.requireIdentity(user);
        return Map.of("mode", "mock-only", "liveAvailable", false, "scheduledSyncAvailable", false,
                "accountTypes", List.of("SHOP_ACCOUNT"), "dataTypes", Map.of(
                        "SHOPEE", ShopeeMockPolicy.dataTypes("SHOPEE"),
                        "SHOPEE_ADS", ShopeeMockPolicy.dataTypes("SHOPEE_ADS")));
    }
    @GetMapping("/sources")
    List<SourceView> sources(@RequestHeader(value="X-User-Id", required=false) String user) {
        return management.listSources(user).stream().map(this::view).toList();
    }
    @GetMapping("/sources/{id}")
    SourceView source(@RequestHeader(value="X-User-Id", required=false) String user, @PathVariable Long id) {
        return view(management.source(user, id));
    }
    @PostMapping("/sources") @ResponseStatus(HttpStatus.CREATED)
    SourceView createSource(@RequestHeader(value="X-User-Id", required=false) String user,
                           @Valid @RequestBody SourceRequest request) {
        return view(management.saveSource(user, null, request));
    }
    @PutMapping("/sources/{id}")
    SourceView editSource(@RequestHeader(value="X-User-Id", required=false) String user,
                         @PathVariable Long id, @Valid @RequestBody SourceRequest request) {
        return view(management.saveSource(user, id, request));
    }
    @PostMapping({"/sources/{id}/authorize", "/sources/{id}/reauthorize"})
    SourceView authorize(@RequestHeader(value="X-User-Id", required=false) String user,
                         @PathVariable Long id, @Valid @RequestBody AuthorizationRequest request) {
        return view(management.authorize(user, id, request));
    }
    @DeleteMapping("/sources/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteSource(@RequestHeader(value="X-User-Id", required=false) String user, @PathVariable Long id) {
        management.deleteSource(user, id);
    }
    @GetMapping("/connections")
    List<ConnectionView> connections(@RequestHeader(value="X-User-Id", required=false) String user) {
        return management.listConnections(user).stream().map(ConnectionView::from).toList();
    }
    @GetMapping("/connections/{id}")
    ConnectionView connection(@RequestHeader(value="X-User-Id", required=false) String user, @PathVariable Long id) {
        return ConnectionView.from(management.connection(user, id));
    }
    @PostMapping("/connections") @ResponseStatus(HttpStatus.CREATED)
    ConnectionView createConnection(@RequestHeader(value="X-User-Id", required=false) String user,
                                    @Valid @RequestBody ConnectionRequest request) {
        return ConnectionView.from(management.saveConnection(user, null, request));
    }
    @PutMapping("/connections/{id}")
    ConnectionView editConnection(@RequestHeader(value="X-User-Id", required=false) String user,
                                  @PathVariable Long id, @Valid @RequestBody ConnectionRequest request) {
        return ConnectionView.from(management.saveConnection(user, id, request));
    }
    @DeleteMapping("/connections/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteConnection(@RequestHeader(value="X-User-Id", required=false) String user, @PathVariable Long id) {
        management.deleteConnection(user, id);
    }
    @PostMapping("/connections/{id}/scenario")
    ConnectionView scenario(@RequestHeader(value="X-User-Id", required=false) String user, @PathVariable Long id) {
        return ConnectionView.from(management.provisionScenario(user, id));
    }
    private SourceView view(ShopeeSource source) {
        String status = !source.getEnabled() ? "DISABLED" : source.getAuthorizedUntil() == null ? "AUTH_REQUIRED"
                : !source.getAuthorizedUntil().isAfter(policy.clock().instant()) ? "EXPIRED" : "AUTHORIZED";
        return new SourceView(source.getId(), source.getKind(), source.getDisplayName(), source.getDescription(),
                source.getEnabled(), status, source.getShopAccountId(), source.getAuthorizedUntil(),
                source.getAllowedUserIds(), ShopeeMockPolicy.dataTypes(source.getKind()), "mock-only");
    }
    record SourceRequest(@NotBlank String kind, @NotBlank @Size(max=120) String displayName,
                         @Size(max=1000) String description, @NotNull Boolean enabled,
                         @NotNull @Size(max=100) List<@NotBlank @Size(max=120) String> allowedUserIds) {}
    record AuthorizationRequest(@NotBlank String accountType, @Size(max=80) String shopAccountId,
                                @NotBlank String outcome) {}
    record ConnectionRequest(@NotNull @Positive Long sourceId, @NotBlank @Size(max=120) String name,
                             @Size(max=1000) String description, @NotEmpty List<@NotBlank String> dataTypes,
                             @NotNull LocalDate firstLoadDate, @Min(1) @Max(3650) int lookbackDays,
                             @Positive Integer frequencyMinutes, @NotBlank String executionMode) {}
    record SourceView(Long id, String kind, String displayName, String description, boolean enabled,
                      String status, String shopAccountId, Instant authorizedUntil, List<String> allowedUserIds,
                      List<String> dataTypes, String mode) {}
    record ConnectionView(Long id, Long sourceId, String name, String description, List<String> dataTypes,
                          LocalDate firstLoadDate, int lookbackDays, Integer frequencyMinutes,
                          boolean frequencyActive, String executionMode, String mode, Long scenarioDefinitionId) {
        static ConnectionView from(ShopeeConnection connection) {
            return new ConnectionView(connection.getId(), connection.getSourceId(), connection.getName(),
                    connection.getDescription(), connection.getDataTypes(), connection.getFirstLoadDate(),
                    connection.getLookbackDays(), connection.getFrequencyMinutes(), false, "MANUAL", "mock-only",
                    connection.getScenarioDefinitionId());
        }
    }
}

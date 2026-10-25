package com.simi.console.feature.executor;

import com.simi.common.domain.entity.ScenarioDefinition;
import com.simi.console.feature.rbac.PermissionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * GET /api/scenarios — read-only list của scenarios khả dụng cho user.
 * RBAC filter: chỉ trả về scenarios user có quyền (docs/09 §Permission Check Points).
 */
@RestController
@RequestMapping("/api/scenarios")
class ScenarioController {

    private final ScenarioDefinitionRepository scenarios;
    private final PermissionService permissionService;
    private final com.simi.console.feature.shopee.ShopeeExecutionGuard shopee;

    ScenarioController(ScenarioDefinitionRepository scenarios,
                       PermissionService permissionService, com.simi.console.feature.shopee.ShopeeExecutionGuard shopee) {
        this.scenarios = scenarios;
        this.permissionService = permissionService;
        this.shopee = shopee;
    }

    @GetMapping
    public List<ScenarioSummary> list(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            // No user context — return all (Tầng 1 gateway should handle auth)
            return scenarios.findAll().stream().filter(s -> shopee.visible(userId, s.getId())).map(ScenarioSummary::from).toList();
        }
        Set<String> allowed = permissionService.getAllowedScenarioIds(userId);
        return scenarios.findAll().stream()
                .filter(s -> allowed.contains(String.valueOf(s.getId())))
                .filter(s -> shopee.visible(userId, s.getId()))
                .map(ScenarioSummary::from)
                .toList();
    }

    public record ScenarioSummary(Long id, String name, String description) {
        static ScenarioSummary from(ScenarioDefinition d) {
            return new ScenarioSummary(d.getId(), d.getName(), d.getDescription());
        }
    }
}

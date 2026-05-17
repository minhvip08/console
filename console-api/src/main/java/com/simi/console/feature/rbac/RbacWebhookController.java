package com.simi.console.feature.rbac;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Webhook endpoint for RoleStore cache invalidation (docs/09 §Cache Invalidation).
 * <p>
 * When RoleStore updates permissions, it POSTs to this webhook to force cache eviction.
 * Next request from the user will re-query RoleStore and cache fresh data.
 */
@RestController
@RequestMapping("/api/rbac/webhook")
class RbacWebhookController {

    private final PermissionService permissionService;

    RbacWebhookController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /**
     * DELETE /api/rbac/webhook/cache/{userId}
     * RoleStore calls this when user permissions change.
     */
    @DeleteMapping("/cache/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void invalidateUserCache(@PathVariable String userId) {
        permissionService.invalidateCache(userId);
    }

    /**
     * POST /api/rbac/webhook/invalidate
     * Alternative: RoleStore POSTs with body containing userId.
     */
    @PostMapping("/invalidate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void invalidateViaPost(@RequestBody InvalidateRequest req) {
        permissionService.invalidateCache(req.userId());
    }

    public record InvalidateRequest(String userId) {}
}

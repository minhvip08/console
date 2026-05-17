package com.simi.console.feature.rbac;

import java.util.Set;

/**
 * Port interface cho RoleStore integration (docs/09 §RoleStore Integration).
 * Mock cho dev, real HTTP client cho prod.
 * <p>
 * Returns set of scenario_definition IDs mà user được phép thực thi.
 */
public interface RoleStoreClient {

    /**
     * Query RoleStore cho allowed scenario IDs.
     * GET /rolestore/users/{userId}/permissions?resource=scenario
     *
     * @param userId user identifier
     * @return set of scenario_definition IDs allowed
     */
    Set<String> getAllowedScenarioIds(String userId);
}

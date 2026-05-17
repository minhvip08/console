package com.simi.console.feature.rbac;

import com.simi.console.feature.executor.ScenarioDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mock RoleStore cho dev/test — returns ALL scenario IDs.
 * Activate khi {@code rolestore.mock=true} (default cho dev profile).
 * <p>
 * Real impl sẽ HTTP call qua service-to-service auth (mTLS / Secrets Manager).
 */
@Component
@ConditionalOnProperty(name = "rolestore.mock", havingValue = "true", matchIfMissing = true)
public class MockRoleStoreClient implements RoleStoreClient {

    private static final Logger log = LoggerFactory.getLogger(MockRoleStoreClient.class);

    private final ScenarioDefinitionRepository scenarios;

    public MockRoleStoreClient(ScenarioDefinitionRepository scenarios) {
        this.scenarios = scenarios;
    }

    @Override
    public Set<String> getAllowedScenarioIds(String userId) {
        // Mock: mọi user đều có quyền tất cả scenario
        Set<String> all = scenarios.findAll().stream()
                .map(s -> s.getId())
                .collect(Collectors.toSet());
        log.debug("MockRoleStoreClient: user={} → allowed {} scenarios", userId, all.size());
        return all;
    }
}

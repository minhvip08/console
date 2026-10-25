package com.simi.console.feature.shopee;

import com.simi.common.domain.entity.ShopeeConnection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopeeConnectionRepository extends JpaRepository<ShopeeConnection, Long> {
    java.util.Optional<ShopeeConnection> findByScenarioDefinitionId(Long scenarioId);
}

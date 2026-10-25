package com.simi.backoffice.feature.shopee;

import com.simi.common.domain.entity.ShopeeConnection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopeeConnectionRepository extends JpaRepository<ShopeeConnection, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from ShopeeConnection c where c.id = :id")
    java.util.Optional<ShopeeConnection> lockById(@org.springframework.data.repository.query.Param("id") Long id);
    java.util.Optional<ShopeeConnection> findByScenarioDefinitionId(Long scenarioId);
    boolean existsBySourceIdAndDeletedFalse(Long sourceId);
}

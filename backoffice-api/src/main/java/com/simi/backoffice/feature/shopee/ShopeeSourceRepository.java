package com.simi.backoffice.feature.shopee;

import com.simi.common.domain.entity.ShopeeSource;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface ShopeeSourceRepository extends JpaRepository<ShopeeSource, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ShopeeSource s where s.id = :id")
    Optional<ShopeeSource> lockById(@Param("id") Long id);
}

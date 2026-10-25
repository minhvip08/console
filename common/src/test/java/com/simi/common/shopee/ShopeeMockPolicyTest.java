package com.simi.common.shopee;

import com.simi.common.domain.entity.ShopeeConnection;
import com.simi.common.domain.entity.ShopeeSource;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ShopeeMockPolicyTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC);
    private final ShopeeMockPolicy policy = new ShopeeMockPolicy(true, "admin", clock);
    @Test void requiresExplicitEnablementIdentityAndAdminGrant() {
        assertThatThrownBy(() -> new ShopeeMockPolicy(false, "admin", clock).requireAdmin("admin"))
                .isInstanceOf(ShopeeException.class).hasMessageContaining("disabled");
        assertThatThrownBy(() -> policy.requireAdmin(null)).hasMessageContaining("identity");
        assertThatThrownBy(() -> policy.requireAdmin("operator")).hasMessageContaining("administrator");
        assertThatThrownBy(() -> new ShopeeMockPolicy(true, "", clock).requireAdmin("admin"))
                .hasMessageContaining("administrator");
    }
    @Test void rejectsDisabledExpiredAndRevokedSourceAccess() {
        ShopeeSource source = source();
        policy.requireReady("operator", source);
        assertThatThrownBy(() -> policy.requireReady("stranger", source)).hasMessageContaining("denied");
        source.setEnabled(false);
        assertThatThrownBy(() -> policy.requireReady("operator", source)).hasMessageContaining("disabled");
        source.setEnabled(true); source.setAuthorizedUntil(clock.instant());
        assertThatThrownBy(() -> policy.requireReady("operator", source)).hasMessageContaining("reauthorization");
        source.setAllowedUserIds(List.of());
        assertThatThrownBy(() -> policy.requireAccess("operator", source)).hasMessageContaining("denied");
        source.setDeleted(true);
        assertThatThrownBy(() -> policy.requireAccess("admin", source)).hasMessageContaining("denied");
    }
    @Test void validatesDateLookbackAndFixtureTypeCompatibility() {
        ShopeeSource source = source(); ShopeeConnection c = connection();
        policy.validate(c, source);
        c.setDataTypes(List.of("fixture_ads_performance"));
        assertThatThrownBy(() -> policy.validate(c, source)).hasMessageContaining("fixture data types");
        c.setDataTypes(List.of("fixture_sales", "fixture_sales"));
        assertThatThrownBy(() -> policy.validate(c, source)).hasMessageContaining("distinct");
        c.setDataTypes(List.of("fixture_sales")); c.setFirstLoadDate(LocalDate.of(2026,10,26));
        assertThatThrownBy(() -> policy.validate(c, source)).hasMessageContaining("future");
        c.setFirstLoadDate(LocalDate.of(2026,10,25)); c.setLookbackDays(0);
        assertThatThrownBy(() -> policy.validate(c, source)).hasMessageContaining("Lookback");
        c.setLookbackDays(7); c.setFrequencyMinutes(-1);
        assertThatThrownBy(() -> policy.validate(c, source)).hasMessageContaining("frequency");
        source.setKind("SHOPEE_ADS"); c.setDataTypes(List.of("fixture_ads_performance", "fixture_ads_summary"));
        c.setFrequencyMinutes(60); policy.validate(c, source);
    }
    private ShopeeSource source() {
        ShopeeSource s = new ShopeeSource(); s.setKind("SHOPEE"); s.setEnabled(true);
        s.setAllowedUserIds(List.of("operator")); s.setAuthorizedUntil(clock.instant().plusSeconds(3600)); return s;
    }
    private ShopeeConnection connection() {
        ShopeeConnection c = new ShopeeConnection(); c.setDataTypes(List.of("fixture_sales"));
        c.setFirstLoadDate(LocalDate.of(2026,10,1)); c.setLookbackDays(7); return c;
    }
}

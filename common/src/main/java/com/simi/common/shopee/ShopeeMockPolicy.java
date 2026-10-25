package com.simi.common.shopee;

import com.simi.common.domain.entity.ShopeeConnection;
import com.simi.common.domain.entity.ShopeeSource;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Synthetic Shop Account policy. Contains no live integration or token contract. */
public class ShopeeMockPolicy {
    private final boolean enabled;
    private final Set<String> admins;
    private final Clock clock;

    public ShopeeMockPolicy(boolean enabled, String adminUsers, Clock clock) {
        this.enabled = enabled;
        this.admins = Arrays.stream(adminUsers.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        this.clock = clock;
    }
    public Clock clock() { return clock; }
    public void requireIdentity(String userId) {
        if (!enabled) throw new ShopeeException(503, "Shopee mock integration is disabled; live integration is unavailable");
        if (userId == null || userId.isBlank()) throw new ShopeeException(403, "Trusted user identity required");
    }
    public void requireAdmin(String userId) {
        requireIdentity(userId);
        if (!admins.contains(userId)) throw new ShopeeException(403, "Shopee mock administrator required");
    }
    public boolean canAccess(String userId, ShopeeSource source) {
        return enabled && userId != null && !userId.isBlank() && !source.getDeleted()
                && (admins.contains(userId) || source.getAllowedUserIds().contains(userId));
    }
    public void requireAccess(String userId, ShopeeSource source) {
        requireIdentity(userId);
        if (!canAccess(userId, source)) throw new ShopeeException(403, "Source access denied");
    }
    public void requireReady(String userId, ShopeeSource source) {
        requireAccess(userId, source);
        if (!source.getEnabled()) throw new ShopeeException(409, "Source is disabled");
        if (source.getAuthorizedUntil() == null || !source.getAuthorizedUntil().isAfter(clock.instant()))
            throw new ShopeeException(409, "Source requires mock authorization or reauthorization");
    }
    public static List<String> dataTypes(String kind) {
        return switch (kind) {
            case "SHOPEE" -> List.of("fixture_sales", "fixture_sales_summary");
            case "SHOPEE_ADS" -> List.of("fixture_ads_performance", "fixture_ads_summary");
            default -> throw new ShopeeException(400, "Unsupported mock source kind");
        };
    }
    public void validate(ShopeeConnection connection, ShopeeSource source) {
        if (connection.getDataTypes() == null || connection.getDataTypes().isEmpty()
                || !dataTypes(source.getKind()).containsAll(connection.getDataTypes())
                || connection.getDataTypes().stream().distinct().count() != connection.getDataTypes().size())
            throw new ShopeeException(400, "Select one or more distinct supported fixture data types");
        if (connection.getFirstLoadDate() == null || connection.getFirstLoadDate().isAfter(LocalDate.now(clock)))
            throw new ShopeeException(400, "Initial-load date must not be in the future");
        if (connection.getLookbackDays() < 1 || connection.getLookbackDays() > 3650)
            throw new ShopeeException(400, "Lookback must be between 1 and 3650 days");
        if (connection.getFrequencyMinutes() != null && connection.getFrequencyMinutes() < 1)
            throw new ShopeeException(400, "Inactive frequency preference must be positive");
    }
    public static boolean isMockService(String name) {
        return "shopee-mock".equals(name) || "shopee-ads-mock".equals(name);
    }
    public static String serviceName(String kind) {
        dataTypes(kind);
        return "SHOPEE".equals(kind) ? "shopee-mock" : "shopee-ads-mock";
    }
}

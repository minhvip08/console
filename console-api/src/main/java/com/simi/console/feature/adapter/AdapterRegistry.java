package com.simi.console.feature.adapter;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Resolve ServiceAdapter theo {@code action_definition.adapter_type}.
 * Spring inject mọi {@link ServiceAdapter} bean → index theo {@link ServiceAdapter#type()}.
 */
@Component
public class AdapterRegistry {

    private final Map<String, ServiceAdapter> byType;

    public AdapterRegistry(List<ServiceAdapter> adapters) {
        this.byType = adapters.stream().collect(java.util.stream.Collectors.toMap(
                ServiceAdapter::type,
                a -> a,
                (a, b) -> { throw new IllegalStateException("Duplicate adapter type: " + a.type()); }
        ));
    }

    public ServiceAdapter forType(String adapterType) {
        ServiceAdapter a = byType.get(adapterType);
        if (a == null) {
            throw new IllegalArgumentException(
                    "No adapter registered for type: " + adapterType
                    + ", available: " + byType.keySet());
        }
        return a;
    }
}

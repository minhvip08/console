package com.simi.console.feature.adapter;

/**
 * Port — abstract 3rd party call. Resolve by {@code action_definition.adapter_type}
 * tại runtime (docs/03 §Service Adapter Layer).
 * <ul>
 *   <li>{@code http}: sync request/response (HttpAdapter)</li>
 *   <li>{@code async}: fire to queue + poll (AsyncAdapter — Phase 5+)</li>
 *   <li>{@code mock}: testing only, return canned response (MockAdapter)</li>
 * </ul>
 */
public interface ServiceAdapter {

    /** Adapter type này handle — dùng để registry resolve. */
    String type();

    AdapterResult execute(AdapterRequest request);
}

package com.simi.common.ulid;

/**
 * ULID prefixes dùng xuyên suốt hệ thống (xem CLAUDE.md §Naming).
 * Mọi ID đều dạng {prefix}_{ULID} ngoại trừ Variable ID dùng dạng VAR_{ULID}.
 */
public final class IdPrefix {
    public static final String SCENARIO_DEFINITION = "scen";
    public static final String STEP_DEFINITION = "step";
    public static final String ACTION_DEFINITION = "actdef";
    public static final String STEP_TRIGGER = "trigger";
    public static final String SCENARIO_EXECUTION = "exec";
    public static final String TRIGGER_EXECUTION = "trgexec";
    public static final String ACTION_EXECUTION = "actexec";
    public static final String WIDGET = "widget";
    public static final String REQUEST = "req";
    public static final String VARIABLE = "VAR";
    public static final String SERVICE = "svc";
    public static final String RETRY_POLICY = "retry";
    public static final String SAGA_LOG = "saga";
    public static final String DEAD_LETTER = "dl";
    public static final String RECONCILIATION = "rec";

    private IdPrefix() {}
}

package io.github.aplite36.chunkcarts.core;

/** In {@link #STRICT} the invariants are not configurable and not disableable. */
public enum EnforcementMode {

    /** Invariants enforced; carts cannot be used as chunk loaders. The default. */
    STRICT,

    /** Invariants bypassed entirely. Minecarts CAN be used as chunk loaders. */
    PERMISSIVE
}

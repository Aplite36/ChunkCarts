package io.github.aplite36.chunkcarts.core;

/** In {@link #STRICT} the invariants are not configurable and not disableable. */
public enum EnforcementMode {

    /** Invariants enforced; carts cannot be used as chunk loaders. The default. */
    STRICT,

    /** Loop detection and the travel budget are bypassed; everything else (departure tokens, the
     * cooldown, the concurrency cap, the cart-type list) still applies. Minecarts CAN be used as
     * chunk loaders. */
    PERMISSIVE
}

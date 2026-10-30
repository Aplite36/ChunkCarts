package io.github.aplite36.chunkcarts.core;

/** In {@link #STRICT} the invariants are not configurable and not disableable. */
public enum EnforcementMode {

    /** Invariants enforced; carts cannot be used as chunk loaders. The default. */
    STRICT,

    /** Loop detection and the travel budget are bypassed. Departure tokens, the cooldown, the
     * concurrency cap and the cart-type list still apply, but the STRICT caps on individual
     * settings ({@link Config#resolve()}) are lifted and unload-hold is honoured. Minecarts CAN be
     * used as chunk loaders. */
    PERMISSIVE
}

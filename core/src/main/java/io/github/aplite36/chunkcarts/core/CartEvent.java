package io.github.aplite36.chunkcarts.core;

/**
 * Things that can happen to a cart, of which only some count as <em>interaction</em> for the
 * cooldown waiver (SPEC §4.1).
 *
 * <p>The classification lives here rather than in each adapter on purpose. "Hopper insertion is
 * not interaction" is a policy decision that the §9 suite tests by name, and policy the tests
 * cover belongs in {@code core} — if each adapter decided for itself, the AFK-dispatcher guard
 * would be untested on every platform and could drift apart between them.
 */
public enum CartEvent {

    /** Interaction. A player right-clicked the cart. */
    RIGHT_CLICK,

    /** Interaction. A player opened the cart's inventory. */
    INVENTORY_OPEN,

    /** Interaction. A player placed the cart. */
    PLACEMENT,

    /** NOT interaction. A hopper pushed items in — no player was involved. */
    HOPPER_INSERT,

    /** NOT interaction. A dispenser launched the cart. */
    DISPENSER_DISPATCH,

    /**
     * NOT interaction. A player is merely nearby. This grants a token through presence (§3.3)
     * but must never waive the cooldown, or an AFK player beside a dispenser cycles carts forever.
     */
    PLAYER_NEARBY
}

package io.github.aplite36.chunkcarts.core;

/**
 * Things an adapter observes happening to a cart, of which only some count as <em>interaction</em>
 * for the cooldown waiver.
 *
 * <p>The classification lives here rather than in each adapter on purpose. An adapter reports what
 * happened; {@code core} decides what it means. "A right-click waives the cooldown" is a policy
 * decision, and an adapter that called something interaction on its own initiative could make the
 * four platforms disagree about the AFK-dispenser guard without any of them being obviously wrong.
 * All three constants currently qualify, so {@link #isInteraction()} reads as trivially true today.
 * It is still where the decision belongs, and adding a fourth event means classifying it here
 * rather than in whichever adapter noticed it.
 *
 * <h2>The AFK-dispenser guard is enforced by not listening</h2>
 *
 * <p>This enum used to carry {@code HOPPER_INSERT}, {@code DISPENSER_DISPATCH} and
 * {@code PLAYER_NEARBY}, classified as not-interaction, and the class doc claimed that
 * classification was what kept an AFK player beside a dispenser from cycling a cart forever. It was
 * not. No adapter ever emitted any of the three — none of them registers a hopper or dispenser
 * listener at all — so the classification decided nothing and the three tests that named it passed
 * by feeding the engine values nothing produced.
 *
 * <p>What actually holds the guard up is that a dispenser-fired cart reaches {@code track()} and
 * nothing else. No {@code record} call is made for it, so no waiver is set, so the cooldown applies
 * — which is the right outcome, arrived at by an adapter declining to look rather than by a rule in
 * {@code core}. That is worth stating rather than dressing up, because the alternative costs
 * something real: hopper insertion is one of the hottest events on any server with a sorting
 * system, and subscribing to it on four platforms so that {@code core} can classify it as harmless
 * would buy no behaviour that is not already the behaviour.
 *
 * <p>The guard is pinned by {@code TokenTest} on the path a dispenser actually takes — a departure
 * with no interaction recorded at all — instead of on a constant no adapter emits.
 */
public enum CartEvent {

    /** Interaction. A player right-clicked the cart. */
    RIGHT_CLICK,

    /** Interaction. A player opened the cart's inventory. */
    INVENTORY_OPEN,

    /** Interaction. A player placed the cart. */
    PLACEMENT;

    /** Whether this event waives the cooldown for exactly one dispatch. */
    public boolean isInteraction() {
        return this == RIGHT_CLICK || this == INVENTORY_OPEN || this == PLACEMENT;
    }
}

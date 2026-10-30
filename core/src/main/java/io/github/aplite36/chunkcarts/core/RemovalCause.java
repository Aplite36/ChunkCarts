package io.github.aplite36.chunkcarts.core;

/**
 * Why a cart left the server's view, as the adapter saw it.
 *
 * <p>Every platform reports all three through the same untrack path, and before this was passed
 * along the engine had to treat them alike. It treated them as a destruction, which is right for one
 * of the three: a train whose lead crossed a portal first had its followers written back to DORMANT
 * on the near side, while the lead itself resumed as a solo journey on the far side.
 */
public enum RemovalCause {

    /** Killed, broken or discarded. The cart is gone and is not coming back. */
    DESTROYED,

    /**
     * Its chunk unloaded, or it was saved with a player who logged out. The cart still exists, its
     * journey is on the entity, and it re-registers through {@code restore} when it loads again.
     */
    UNLOADED,

    /**
     * It went through a portal. The same cart, with the same id and the same saved state, is being
     * added to the destination world, and is usually there before the next poll.
     */
    CHANGED_DIMENSION
}

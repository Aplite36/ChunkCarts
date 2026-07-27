package io.github.aplite36.chunkcarts.core;

import java.util.UUID;

/**
 * The result of evaluating the departure gates.
 *
 * <p>Sealed so that adding a third outcome is a compile error at every call site rather than a
 * silently unhandled case — the difference between departing and being denied is the difference
 * between consuming a token and preserving it.
 */
public sealed interface DepartureOutcome {

    /** Gates passed. The token has been consumed and a journey exists. */
    record Departed(UUID journeyId) implements DepartureOutcome {}

    /**
     * A gate refused. <strong>The token is untouched</strong> — a denial is not a termination
     * and must leave the cart able to depart once the gate clears.
     */
    record Denied(DenialReason reason) implements DepartureOutcome {}
}

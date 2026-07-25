package io.github.aplite36.chunkcarts.core;

/**
 * The journey lifecycle from SPEC §2.
 *
 * <p>{@link #PAUSED} and {@link #HOLDING} both hold chunks, so both occupy a slot against
 * {@code max-concurrent-journeys} and both appear in {@code /chunkcarts list}.
 */
public enum JourneyState {

    /** No token, not travelling. A cart here needs a fresh token before it can depart again. */
    DORMANT,

    /** Holds a token and the cooldown has elapsed, but has not yet passed the departure gates. */
    ELIGIBLE,

    /** Holding tickets; token consumed. */
    TRAVELLING,

    /** Stopped mid-route. Region still held, journey state preserved — resuming is not a departure. */
    PAUSED,

    /** Arrived; serving the arrival hold before release. */
    HOLDING,

    /** Terminated for any reason other than ARRIVED. Region released immediately, then DORMANT. */
    STRANDED
}

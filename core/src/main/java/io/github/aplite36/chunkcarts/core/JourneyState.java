package io.github.aplite36.chunkcarts.core;

import java.util.Optional;

/**
 * The journey lifecycle.
 *
 * <p>{@link #PAUSED} and {@link #HOLDING} both hold chunks, so both occupy a slot against
 * {@code max-concurrent-journeys} and both appear in {@code /chunkcarts list}.
 *
 * <p>Each constant carries an explicit {@link #code()}, and that code is what
 * {@link JourneyCodec} writes. <strong>The codes are the persisted format; the declaration order
 * is free.</strong> Reorder these constants, or delete one, and nothing on disk changes meaning.
 * What you must not do is change a constant's code or reuse a retired one, because carts written
 * by an older build are sitting in unloaded chunks all over the world.
 *
 * <p>Code 1 is a permanent gap. It belonged to a state that no longer exists, and closing the gap
 * would silently reinterpret every journey saved under the old numbering. Leave it alone.
 */
public enum JourneyState {

    /** No token, not travelling. A cart here needs a fresh token before it can depart again. */
    DORMANT(0),

    /** Holding tickets; token consumed. */
    TRAVELLING(2),

    /** Stopped mid-route. Region still held, journey state preserved — resuming is not a departure. */
    PAUSED(3),

    /** Arrived; serving the arrival hold before release. */
    HOLDING(4),

    /** Terminated for any reason other than ARRIVED. Region released immediately, then DORMANT. */
    STRANDED(5);

    private final byte code;

    JourneyState(int code) {
        this.code = (byte) code;
    }

    /** The byte this state is persisted as. Stable across releases; not the ordinal. */
    public byte code() {
        return code;
    }

    /**
     * @return the state with this code, or empty if no state has it — a byte written by a build
     *         that knew a state this one does not. Same answer as an unknown schema version:
     *         discard rather than guess.
     */
    public static Optional<JourneyState> fromCode(byte code) {
        for (JourneyState state : values()) {
            if (state.code == code) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }
}

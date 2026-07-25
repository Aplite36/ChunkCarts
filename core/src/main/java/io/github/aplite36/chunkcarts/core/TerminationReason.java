package io.github.aplite36.chunkcarts.core;

/**
 * Why a journey ended (SPEC §2.1). Every terminal transition records one.
 *
 * <p>Log the reason, not just the coordinates: it is both recovery information and the abuse
 * signal, and without it working-as-intended behaviour is indistinguishable from a bug.
 *
 * <p>Note these are <em>terminations</em>. A refused departure is a {@link DenialReason} and must
 * not consume the cart's token.
 */
public enum TerminationReason {

    /** Pause tolerance expired with velocity below min-speed. The only reason that gets a hold. */
    ARRIVED,

    /** Pause tolerance expired while still above min-speed — wedged, blocked, or oscillating. */
    STALLED,

    BUDGET_EXHAUSTED,

    /** No new distance record within the slack allowance — a closed loop was detected. */
    SLACK_EXHAUSTED,

    UNGENERATED_CHUNK,

    LEFT_RAILS,

    DIMENSION_LIMIT,

    WORLD_DISABLED
}

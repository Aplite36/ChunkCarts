package io.github.aplite36.chunkcarts.core;

/**
 * Why a departure was refused, in gate order (cheapest first).
 *
 * <p><strong>A denial is not a termination and must never consume the token.</strong> Gates 1–4
 * and 6 correspond to reasons the test suite tests by name ({@code disabledWorldBlocksDeparture},
 * {@code tntCartNeverDeparts}, {@code belowSpeedThresholdDoesNotDepart}) and therefore need to be
 * distinguishable.
 */
public enum DenialReason {

    /** Gate 1 — world excluded by config. */
    WORLD_DISABLED,

    /** Gate 2 — cart type not permitted. TNT, COMMAND_BLOCK and SPAWNER can never pass. */
    CART_TYPE_FORBIDDEN,

    /** Gate 3 — not on a rail. */
    NOT_ON_RAIL,

    /** Gate 4 — velocity below min-speed. */
    BELOW_MIN_SPEED,

    /** Gate 5 — cooldown not yet satisfied, and no interaction has waived it. */
    COOLDOWN,

    /** Gate 6 — departure-requirement not met. */
    DEPARTURE_REQUIREMENT,

    /** Gate 7 — no token. */
    NO_TOKEN,

    /** Gate 8 — derived TPS below min-tps. Refuses to start; never interrupts a journey. */
    TPS_BACKOFF,

    /** Gate 9 — concurrency cap reached. Denied, not queued. */
    CONCURRENCY_CAP
}

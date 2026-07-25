package io.github.aplite36.chunkcarts.core;

/** SPEC §6. What a cart must satisfy before it may begin a journey. */
public enum DepartureRequirement {

    /**
     * Any qualifying cart may depart. The cooldown is the only limit, which is why it gains a
     * 60s floor in STRICT under this setting and not the others (SPEC §3.5).
     */
    NONE,

    /** Cart must have been in a player-loaded chunk since its last journey. The default. */
    PLAYER_SEEN,

    /** At least one player online anywhere on the server. */
    PLAYER_ONLINE
}

package io.github.aplite36.chunkcarts.core.platform;

import io.github.aplite36.chunkcarts.core.WorldId;

/**
 * Holds and releases chunks. One of the two halves of the platform surface.
 *
 * <p><strong>Every hold must reach entity-ticking level.</strong> This is the classic silent
 * failure of the whole project: a chunk held at border level loads but does not
 * tick, so the cart sits motionless while every unit test passes and the logs look correct.
 * A skeleton that compiles proves nothing about this — it is only observable on a real server.
 */
public interface ChunkTicketBackend {


    /**
     * Holds the given chunk. MUST result in an entity-ticking chunk, not merely a loaded one.
     */
    void hold(WorldId world, int cx, int cz);

    void release(WorldId world, int cx, int cz);

    /**
     * Releases every chunk this backend is holding. Called on disable — without it,
     * held chunks leak until the next restart.
     */
    void releaseAll();
}

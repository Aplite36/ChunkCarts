package io.github.aplite36.chunkcarts.core;

import java.util.Objects;

/** A chunk coordinate in a specific world. Used for journey origins and ticket bookkeeping. */
public record ChunkPos(WorldId world, int cx, int cz) {

    public ChunkPos {
        Objects.requireNonNull(world, "world");
    }

    /**
     * Chebyshev chunk distance, the metric the progress rule uses. Chebyshev rather
     * than Euclidean so a diagonal route is not penalised against a straight one.
     *
     * @throws IllegalArgumentException if the two positions are in different worlds, which would
     *         make the distance meaningless — a dimension transition resets the origin instead
     */
    public int chebyshevTo(ChunkPos other) {
        if (!world.equals(other.world)) {
            throw new IllegalArgumentException(
                    "cannot measure distance across worlds: " + world + " vs " + other.world);
        }
        return Math.max(Math.abs(cx - other.cx), Math.abs(cz - other.cz));
    }
}

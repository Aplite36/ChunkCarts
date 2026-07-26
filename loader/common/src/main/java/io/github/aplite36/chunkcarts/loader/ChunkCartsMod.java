package io.github.aplite36.chunkcarts.loader;

import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;

/**
 * What {@code /chunkcarts} needs from whichever loader's entry point is running (SPEC §10).
 *
 * <p>The command is identical on every loader — it reads the engine and prints — so it is shared,
 * and this interface is the one thing it cannot get from {@code core}: the running adapter's own
 * bookkeeping, which is per-platform because holding chunks is.
 *
 * <p>Deliberately read-mostly. {@link #reloadRuntime()} and {@link #toggleDebug()} are the only
 * mutations, and neither decides anything about whether a cart may travel — that stays in
 * {@code core}, and an adapter interface wide enough to express policy would invite putting some
 * here.
 */
public interface ChunkCartsMod {

    /** The running engine, or {@code null} before the server has started. */
    ChunkCartsEngine engine();

    int heldChunkCount();

    /** A tally of the load levels of the chunks currently held, as {@code ENTITY_TICKING x9}. */
    String heldChunkLevels();

    /** Flips verbose logging and returns the new state. */
    boolean toggleDebug();

    /** Tears the runtime down and builds it again from the file on disk. */
    void reloadRuntime();
}

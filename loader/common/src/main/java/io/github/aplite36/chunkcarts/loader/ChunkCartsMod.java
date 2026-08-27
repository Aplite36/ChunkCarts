package io.github.aplite36.chunkcarts.loader;

import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import io.github.aplite36.chunkcarts.core.ConfigSwap;
import io.github.aplite36.chunkcarts.core.WhyReport;
import io.github.aplite36.chunkcarts.core.platform.CartView;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What {@code /chunkcarts} needs from whichever loader's entry point is running.
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

    /**
     * Whether every held chunk is at {@code ENTITY_TICKING}, which is the only level at which a
     * cart inside one actually moves.
     *
     * <p>Separate from {@link #heldChunkLevels()} rather than derived from it, because the two
     * platforms tally different enum types and the command would otherwise have to parse a string
     * to find out whether to sound an alarm.
     */
    boolean heldChunksAllTicking();

    /**
     * A core view of one tracked cart, for {@code /chunkcarts why}.
     *
     * <p>A read, so it does not widen this interface in the direction its doc warns about — it
     * reports a decision the engine has already made and changes nothing.
     */
    Optional<CartView> viewOf(UUID cartId);

    /** Tracked carts within {@code range} of a player, for {@code /chunkcarts why}. */
    List<WhyReport.Candidate> cartsNear(ServerPlayer player, double range);

    /** Flips verbose logging and returns the new state. */
    boolean toggleDebug();

    /**
     * Rebuilds the runtime from the file on disk.
     *
     * <p>The config is read and validated first, so a reload that cannot produce a working
     * replacement leaves the one already running untouched. The three outcomes need three different
     * things said to whoever ran the command, and the warnings the reload produced are the answer
     * to the question that made them run it — which is why this returns both rather than nothing.
     */
    ConfigSwap.Result reloadRuntime();
}

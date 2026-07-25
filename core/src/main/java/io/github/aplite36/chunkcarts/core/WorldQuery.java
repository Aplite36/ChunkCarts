package io.github.aplite36.chunkcarts.core;

import java.util.concurrent.CompletableFuture;

/**
 * Read-only questions about the world. The other half of the platform surface.
 *
 * <p>Note what is <em>not</em> here: there is no {@code currentTps()}. TPS is derived in
 * {@code core} from scheduler drift (SPEC §4.2) because it is the one quantity with no good
 * cross-platform answer. Adding it here would be the eighth operation across this interface
 * and {@link ChunkTicketBackend}, which SPEC §1.1 says to treat as a warning sign.
 */
public interface WorldQuery {

    /**
     * Whether the chunk already exists on disk. ChunkCarts refuses to generate terrain, both
     * to avoid worldgen-bot abuse and to keep world folders from bloating (SPEC §5).
     */
    boolean isGenerated(WorldId world, int cx, int cz);

    /**
     * Whether this chunk is loaded <strong>because a player is present</strong>.
     *
     * <p><strong>This must not be implemented by asking whether the chunk is loaded.</strong>
     * A chunk ChunkCarts is holding is loaded, and is loaded at entity-ticking level, so any
     * implementation that consults chunk load state will report {@code true} for our own
     * tickets. Tokens would then be self-granting, and every rule downstream of SPEC §3.3
     * becomes decorative while all of it still compiles and appears to work.
     *
     * <p>Implement it from player positions only.
     */
    boolean isPlayerLoaded(WorldId world, int cx, int cz);

    /**
     * Asks for the chunk to be brought in asynchronously, so a boundary crossing never
     * triggers a synchronous load (SPEC §5).
     */
    CompletableFuture<Void> prefetch(WorldId world, int cx, int cz);

    boolean isOnRail(CartView cart);
}

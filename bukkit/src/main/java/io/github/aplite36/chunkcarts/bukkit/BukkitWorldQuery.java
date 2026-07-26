package io.github.aplite36.chunkcarts.bukkit;

import io.github.aplite36.chunkcarts.core.WorldId;
import io.github.aplite36.chunkcarts.core.platform.CartView;
import io.github.aplite36.chunkcarts.core.platform.WorldQuery;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

/**
 * The read-only half of the platform surface (SPEC §1.1).
 */
final class BukkitWorldQuery implements WorldQuery {

    private final Server server;

    BukkitWorldQuery(Server server) {
        this.server = server;
    }

    World world(WorldId id) {
        return server.getWorld(id.value());
    }

    @Override
    public boolean isGenerated(WorldId world, int cx, int cz) {
        World w = world(world);
        // A world that is not loaded has nothing generated as far as we are concerned: refusing to
        // travel is the safe direction, and it is what an unloaded multiverse world should do.
        return w != null && w.isChunkGenerated(cx, cz);
    }

    /**
     * <strong>Answered from player positions, never from chunk load state (SPEC §3.3).</strong>
     *
     * <p>This is the load-bearing line of the whole adapter. Chunks ChunkCarts holds are loaded,
     * and loaded at entity-ticking level, so {@code isChunkLoaded} here would report true for our
     * own tickets: carts would grant themselves tokens, the scheme would be circular, and every
     * rule downstream of it would be decorative while still compiling and appearing to work. The
     * §9 suite cannot catch that mistake — it runs against a fake — so the guarantee lives here.
     */
    @Override
    public boolean isPlayerLoaded(WorldId world, int cx, int cz) {
        World w = world(world);
        if (w == null) {
            return false;
        }
        for (Player player : w.getPlayers()) {
            Location at = player.getLocation();
            int distance = Math.max(Math.abs((at.getBlockX() >> 4) - cx),
                    Math.abs((at.getBlockZ() >> 4) - cz));
            // Simulation distance, not view distance: the question is whether the chunk is being
            // ticked because of this player, which is what "loaded by a player" has to mean for a
            // token to be worth anything.
            if (distance <= Math.max(1, player.getSimulationDistance())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public CompletableFuture<Void> prefetch(WorldId world, int cx, int cz) {
        World w = world(world);
        if (w == null) {
            return CompletableFuture.completedFuture(null);
        }
        // gen = false: ChunkCarts never generates terrain (SPEC §5). Paper-only, and the reason
        // this module requires Paper — on Spigot every boundary crossing would be a synchronous
        // main-thread load instead.
        return w.getChunkAtAsync(cx, cz, false).thenApply(chunk -> null);
    }

    @Override
    public boolean isOnRail(CartView cart) {
        World w = world(cart.world());
        if (w == null) {
            return false;
        }
        Location at = new Location(w, cart.x(), cart.y(), cart.z());
        // A minecart rides slightly above the rail, and which of the two blocks its position
        // rounds into depends on the rail shape and the tick. Checking both is what stops a cart
        // on a slope being reported as derailed every few polls.
        return isRail(at.getBlock()) || isRail(at.getBlock().getRelative(0, -1, 0));
    }

    private static boolean isRail(Block block) {
        return Tag.RAILS.isTagged(block.getType());
    }
}

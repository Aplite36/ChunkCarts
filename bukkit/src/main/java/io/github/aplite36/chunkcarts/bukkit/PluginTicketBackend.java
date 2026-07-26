package io.github.aplite36.chunkcarts.bukkit;

import io.github.aplite36.chunkcarts.core.WorldId;
import io.github.aplite36.chunkcarts.core.platform.ChunkTicketBackend;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds chunks with Paper's plugin chunk tickets (SPEC §1.1).
 *
 * <p><strong>The ticket level is the classic silent failure of this project.</strong> A chunk held
 * at border level loads but does not tick: the cart sits motionless, every unit test passes, and
 * the log looks correct. Paper's javadoc for {@code addPluginChunkTicket} promises only that the
 * chunk is loaded and will not unload — it says nothing about the level. So rather than trust it,
 * this class <em>measures</em> it: the first chunk held after startup is sampled a tick later and
 * anything below {@link Chunk.LoadLevel#ENTITY_TICKING} is a loud WARN. That converts an
 * undocumented claim into a runtime check that keeps working on versions nobody has tested yet.
 */
final class PluginTicketBackend implements ChunkTicketBackend {

    private final Plugin plugin;
    private final BukkitWorldQuery worlds;
    private final Logger log;

    /** What we believe we are holding, so a release cannot drift from a hold. */
    private final Set<Held> held = new LinkedHashSet<>();

    private boolean levelVerified;
    private boolean debug;

    private record Held(String world, int cx, int cz) {}

    PluginTicketBackend(Plugin plugin, BukkitWorldQuery worlds, Logger log) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.log = log;
    }

    void setDebug(boolean debug) {
        this.debug = debug;
        if (debug) {
            levelVerified = false;  // re-sample on demand, so an admin can check any version
        }
    }

    int heldCount() {
        return held.size();
    }

    @Override
    public void hold(WorldId world, int cx, int cz) {
        World w = worlds.world(world);
        if (w == null) {
            return;
        }
        w.addPluginChunkTicket(cx, cz, plugin);
        held.add(new Held(world.value(), cx, cz));
        if (!levelVerified) {
            verifyEntityTicking(w, cx, cz);
        }
    }

    @Override
    public void release(WorldId world, int cx, int cz) {
        World w = worlds.world(world);
        if (w != null) {
            w.removePluginChunkTicket(cx, cz, plugin);
        }
        held.remove(new Held(world.value(), cx, cz));
    }

    /**
     * Releases everything, then sweeps (SPEC §1.1). The sweep is not redundant: whether tickets
     * are dropped on disable is undocumented too, and a chunk left held leaks until the next
     * restart — a failure the server owner sees and we never do.
     */
    @Override
    public void releaseAll() {
        for (Held chunk : Set.copyOf(held)) {
            release(new WorldId(chunk.world()), chunk.cx(), chunk.cz());
        }
        held.clear();
        for (World world : plugin.getServer().getWorlds()) {
            world.removePluginChunkTickets(plugin);
        }
    }

    /**
     * Samples the load level one tick after the hold, because the level immediately after adding
     * the ticket is not yet settled and would report a false failure.
     */
    private void verifyEntityTicking(World world, int cx, int cz) {
        levelVerified = true;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Chunk.LoadLevel level = world.getChunkAt(cx, cz).getLoadLevel();
            if (level == Chunk.LoadLevel.ENTITY_TICKING) {
                if (debug) {
                    log.info("Chunk ticket level verified: " + world.getName() + " " + cx + "," + cz
                            + " is " + level + " — carts will tick in held chunks.");
                }
                return;
            }
            log.log(Level.WARNING, "ChunkCarts held " + world.getName() + " " + cx + "," + cz
                    + " but its load level is " + level + ", not ENTITY_TICKING. Carts will load "
                    + "chunks and then sit motionless inside them. This server's chunk-ticket "
                    + "behaviour differs from what ChunkCarts expects — please report it with the "
                    + "server version.");
        });
    }
}

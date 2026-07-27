package io.github.aplite36.chunkcarts.bukkit;

import io.github.aplite36.chunkcarts.core.WorldId;
import io.github.aplite36.chunkcarts.core.platform.ChunkTicketBackend;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds chunks with Paper's plugin chunk tickets.
 *
 * <p><strong>The ticket level is the classic silent failure of this project.</strong> A chunk held
 * at border level loads but does not tick: the cart sits motionless, every unit test passes, and
 * the log looks correct. Paper's javadoc for {@code addPluginChunkTicket} promises only that the
 * chunk is loaded and will not unload — it says nothing about the level. So rather than trust it,
 * this class <em>measures</em> it: the first chunk held after startup is sampled once it has
 * settled, and anything below {@link Chunk.LoadLevel#ENTITY_TICKING} is a loud WARN. That converts
 * an undocumented claim into a runtime check that keeps working on versions nobody has tested yet.
 * {@link #heldLevels()} exposes the same measurement live, for {@code /chunkcarts list}.
 */
final class PluginTicketBackend implements ChunkTicketBackend {

    private final Plugin plugin;
    private final BukkitWorldQuery worlds;
    private final Logger log;

    /** What we believe we are holding, so a release cannot drift from a hold. */
    private final Set<Held> held = new LinkedHashSet<>();

    /**
     * How long to let a freshly held chunk settle before reading its level. Two seconds is far
     * longer than the promotion takes and costs nothing — this runs once per session.
     */
    private static final long SETTLE_TICKS = 40L;

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
     * Releases everything, then sweeps. The sweep is not redundant: whether tickets
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
     * Samples the load level a couple of seconds after the hold.
     *
     * <p>The delay is the whole trick. A cold chunk is promoted through the load levels over
     * several ticks, so sampling on the next tick reports {@code TICKING} for a chunk that reaches
     * {@code ENTITY_TICKING} shortly afterwards — measured on Paper 1.21.11, where it produced a
     * false alarm about a cart that was in fact moving perfectly well.
     */
    private void verifyEntityTicking(World world, int cx, int cz) {
        levelVerified = true;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!held.contains(new Held(world.getName(), cx, cz))) {
                // The cart moved on and this chunk was released inside the settle window — its level
                // is decaying because we let it go, which is the system working. Measured on a
                // travelling cart, where the first chunk held is behind the region within a second.
                // Re-arm rather than pass: a check that reports success on a released chunk is worse
                // than no check, and the next hold gets a fresh window.
                levelVerified = false;
                return;
            }
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
        }, SETTLE_TICKS);
    }

    /**
     * The load level of every chunk currently held, tallied by level, for {@code /chunkcarts list}.
     *
     * <p>This is how an admin checks the one claim no unit test can reach: that the chunks this
     * plugin holds are actually ticking. Anything other than {@code ENTITY_TICKING} here means
     * carts will sit motionless in loaded chunks.
     */
    Map<Chunk.LoadLevel, Integer> heldLevels() {
        Map<Chunk.LoadLevel, Integer> tally = new EnumMap<>(Chunk.LoadLevel.class);
        for (Held chunk : held) {
            World world = worlds.world(new WorldId(chunk.world()));
            if (world == null) {
                continue;
            }
            Chunk.LoadLevel level = world.isChunkLoaded(chunk.cx(), chunk.cz())
                    ? world.getChunkAt(chunk.cx(), chunk.cz()).getLoadLevel()
                    : Chunk.LoadLevel.UNLOADED;
            tally.merge(level, 1, Integer::sum);
        }
        return tally;
    }
}

package io.github.aplite36.chunkcarts.fabric;

import io.github.aplite36.chunkcarts.core.WorldId;
import io.github.aplite36.chunkcarts.core.platform.ChunkTicketBackend;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Holds chunks with a custom chunk ticket (SPEC §1.1).
 *
 * <p><strong>The ticket level is the classic silent failure of this project.</strong> A chunk held
 * at border level loads but does not tick: the cart sits motionless, every unit test passes, and the
 * log looks correct. Everything about the numbers below was read out of the 26.2 sources rather than
 * recalled, because this is exactly the code that compiles while being wrong.
 *
 * <p>{@code TicketStorage.addTicketWithRadius} creates its ticket as
 * {@code new Ticket(type, ChunkLevel.byStatus(FullChunkStatus.FULL) - radius)}, and
 * {@code ChunkLevel} puts FULL at 33, block-ticking at 32 and entity-ticking at 31. So the level is
 * {@code 33 - radius} and <strong>{@link #TICKET_RADIUS} must be at least 2</strong>. A radius of 0
 * or 1 reproduces precisely the failure this paragraph is about.
 *
 * <p>Because that arithmetic is invisible at the call site, {@link #heldLevels()} measures the
 * result live for {@code /chunkcarts list}, and the first hold after startup is checked once it has
 * settled. That converts a derivation into a runtime check that keeps working on versions nobody has
 * tested yet.
 */
final class LevelTicketBackend implements ChunkTicketBackend {

    /**
     * Level {@code 33 - 2 = 31}, which is {@code ChunkLevel.ENTITY_TICKING_LEVEL}. Not a tuning
     * knob: below 2 the held chunk stops ticking entities and carts stand still inside it.
     */
    static final int TICKET_RADIUS = 2;

    /**
     * How long to let a freshly held chunk settle before reading its level. Two seconds is far
     * longer than the promotion takes and costs nothing — this runs once per session.
     */
    private static final int SETTLE_TICKS = 40;

    /**
     * How often to re-add every held ticket. <strong>This is not an optimisation; without it the
     * mod stops working after five seconds.</strong>
     *
     * <p>The ticket carries a 100-tick timeout so that a crash cannot leak a permanently loaded
     * chunk (SPEC §1.1), which means something has to keep renewing it. {@code core} cannot: it
     * calls {@link #hold} once, when a chunk <em>enters</em> the region, and never again while the
     * chunk stays in it — correct for Paper, where a plugin ticket does not expire, and silently
     * fatal here. Measured on 26.2 before this existed: a cart three chunks into a journey was
     * reported as holding nine chunks of which three were {@code BLOCK_TICKING} and three had
     * decayed all the way to {@code FULL}, i.e. loaded and not ticking — the exact failure §1.1
     * warns about, arriving on a delay rather than at the first hold.
     *
     * <p>Forty ticks is two and a half renewals inside every timeout, and it is deliberately
     * independent of {@code poll-interval-ticks}: that value is the admin's, and nothing stops them
     * setting it longer than the ticket lives.
     */
    private static final int REFRESH_TICKS = 40;

    private final FabricWorldQuery worlds;
    private final Logger log;

    /** What we believe we are holding, so a release cannot drift from a hold. */
    private final Set<Held> held = new LinkedHashSet<>();

    private boolean levelVerified;
    private boolean debug;

    private int settleCountdown;
    private Held settling;
    private int refreshCountdown = REFRESH_TICKS;

    private record Held(String world, int cx, int cz) {}

    LevelTicketBackend(FabricWorldQuery worlds, Logger log) {
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
        Held chunk = new Held(world.value(), cx, cz);
        if (!addTicket(chunk)) {
            return;
        }
        held.add(chunk);
        if (!levelVerified) {
            beginVerification(chunk);
        }
    }

    /**
     * Adds — or renews — the ticket on one chunk. Returns false if its world has gone.
     *
     * <p>Re-adding an identical ticket does not stack: {@code TicketStorage} matches on type
     * identity and level, calls {@code resetTicksLeft()} and returns false without inserting.
     * That is what makes {@link #REFRESH_TICKS} a renewal rather than a leak — and it is also why
     * the ticket type is one shared singleton. A fresh, equal {@code TicketType} per call would
     * compare unequal by reference, defeat the match, and leak a ticket every renewal.
     */
    private boolean addTicket(Held chunk) {
        ServerLevel level = worlds.level(new WorldId(chunk.world()));
        if (level == null) {
            return false;
        }
        level.getChunkSource().addTicketWithRadius(
                ChunkCartsFabric.journeyTicket(), new ChunkPos(chunk.cx(), chunk.cz()),
                TICKET_RADIUS);
        return true;
    }

    @Override
    public void release(WorldId world, int cx, int cz) {
        ServerLevel level = worlds.level(world);
        if (level != null) {
            level.getChunkSource().removeTicketWithRadius(
                    ChunkCartsFabric.journeyTicket(), new ChunkPos(cx, cz), TICKET_RADIUS);
        }
        held.remove(new Held(world.value(), cx, cz));
    }

    /**
     * Releases everything (SPEC §1.1). Without it, held chunks leak until the next restart — a
     * failure the server owner sees and we never do.
     *
     * <p>There is no sweep to do afterwards, unlike the Bukkit module: the ticket carries a timeout,
     * so anything this misses expires on its own within a hundred ticks. That self-expiry is also
     * what makes a hard crash safe here, which is the reason §1.1 specifies a timeout on the loader
     * ticket in the first place.
     */
    @Override
    public void releaseAll() {
        for (Held chunk : Set.copyOf(held)) {
            release(new WorldId(chunk.world()), chunk.cx(), chunk.cz());
        }
        held.clear();
        settling = null;
    }

    /**
     * Samples the load level a couple of seconds after a hold.
     *
     * <p>The delay is the whole trick. A cold chunk is promoted through the load levels over several
     * ticks, so sampling on the next tick reports {@code BLOCK_TICKING} for a chunk that reaches
     * {@code ENTITY_TICKING} shortly afterwards — measured on Paper 1.21.11, where it produced a
     * false alarm about a cart that was in fact moving perfectly well.
     */
    private void beginVerification(Held chunk) {
        levelVerified = true;
        settling = chunk;
        settleCountdown = SETTLE_TICKS;
    }

    /**
     * Driven from the server tick: renews the tickets, and runs the settle timer for the one-off
     * level check. Both need a per-tick pulse and there is no delayed-task scheduler here.
     */
    void tick() {
        if (--refreshCountdown <= 0) {
            refreshCountdown = REFRESH_TICKS;
            for (Held chunk : held) {
                addTicket(chunk);
            }
        }
        if (settling == null || --settleCountdown > 0) {
            return;
        }
        Held chunk = settling;
        settling = null;
        if (!held.contains(chunk)) {
            // The cart moved on and this chunk was released inside the settle window — its level is
            // decaying because we let it go, which is the system working. Re-arm rather than pass: a
            // check that reports success on a released chunk is worse than no check.
            levelVerified = false;
            return;
        }
        FullChunkStatus status = statusOf(chunk);
        if (status == FullChunkStatus.ENTITY_TICKING) {
            if (debug) {
                log.info("Chunk ticket level verified: {} {},{} is {} — carts will tick in held chunks.",
                        chunk.world(), chunk.cx(), chunk.cz(), status);
            }
            return;
        }
        log.warn("ChunkCarts held {} {},{} but its load level is {}, not ENTITY_TICKING. Carts will "
                        + "load chunks and then sit motionless inside them. This server's chunk-ticket "
                        + "behaviour differs from what ChunkCarts expects — please report it with the "
                        + "server version.",
                chunk.world(), chunk.cx(), chunk.cz(), status);
    }

    /**
     * The load level of every chunk currently held, tallied by level, for {@code /chunkcarts list}.
     *
     * <p>This is how an admin checks the one claim no unit test can reach: that the chunks this mod
     * holds are actually ticking. Anything other than {@code ENTITY_TICKING} here means carts will
     * sit motionless in loaded chunks.
     *
     * <p>One caveat, so a healthy server is not misread: a chunk takes a tick or two to be promoted
     * after its ticket is added, so a moving cart usually shows the three chunks of its leading edge
     * one level low. Persistent lower levels, or lower levels on a cart that is standing still, are
     * the real thing.
     */
    Map<FullChunkStatus, Integer> heldLevels() {
        Map<FullChunkStatus, Integer> tally = new EnumMap<>(FullChunkStatus.class);
        for (Held chunk : held) {
            tally.merge(statusOf(chunk), 1, Integer::sum);
        }
        return tally;
    }

    /**
     * The holder's own view of its level. {@code getUpdatingChunkIfPresent} rather than the visible
     * map because the visible one is a copy published between update passes: a chunk we asked for a
     * tick ago is in the updating map and not yet in the visible one, and reading the stale copy
     * would report a false alarm for a chunk that is on its way up.
     */
    private FullChunkStatus statusOf(Held chunk) {
        ServerLevel level = worlds.level(new WorldId(chunk.world()));
        if (level == null) {
            return FullChunkStatus.INACCESSIBLE;
        }
        ChunkHolder holder = level.getChunkSource().chunkMap
                .getUpdatingChunkIfPresent(ChunkPos.pack(chunk.cx(), chunk.cz()));
        return holder == null ? FullChunkStatus.INACCESSIBLE : holder.getFullStatus();
    }
}

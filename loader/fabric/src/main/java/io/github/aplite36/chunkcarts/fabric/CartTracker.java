package io.github.aplite36.chunkcarts.fabric;

import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import io.github.aplite36.chunkcarts.core.DepartureOutcome;
import io.github.aplite36.chunkcarts.core.TerminationReason;
import io.github.aplite36.chunkcarts.core.platform.CartView;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
//? if >=1.21.11 {
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
//?} else
/*import net.minecraft.world.entity.vehicle.AbstractMinecart;*/
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The travel loop's Fabric end (SPEC §5): keeps the set of minecarts the engine can see, hands them
 * over each poll, and turns the outcomes into departures and log lines.
 *
 * <p>Carts are tracked in a registry fed by events rather than re-scanned from every level each
 * poll. Scanning would walk every loaded entity on the server twice a second to find the handful
 * that are minecarts.
 */
final class CartTracker {

    /**
     * How close two carts must be to count as coupled. Vanilla carts push each other apart at about
     * one block, so this is contact plus a margin — wide enough that a train polls as one cluster,
     * narrow enough that two carts stopped at opposite ends of a station do not.
     */
    private static final double CONTACT_RADIUS = 1.6;

    /** Bounds the cost of the contact search on a track packed end to end with carts. */
    private static final int MAX_CLUSTER = 32;

    private final ChunkCartsEngine engine;
    private final MinecraftServer server;
    private final Logger log;

    private final Map<UUID, AbstractMinecart> tracked = new LinkedHashMap<>();
    private final Map<UUID, UUID> lastInteractor = new LinkedHashMap<>();

    private boolean debug;

    CartTracker(ChunkCartsEngine engine, MinecraftServer server, Logger log) {
        this.engine = engine;
        this.server = server;
        this.log = log;
    }

    void setDebug(boolean debug) {
        this.debug = debug;
    }

    int trackedCount() {
        return tracked.size();
    }

    /** Registers a cart and restores any journey it carries in its attached data (SPEC §7). */
    void track(AbstractMinecart cart) {
        if (tracked.putIfAbsent(cart.getUUID(), cart) == null) {
            engine.restore(view(cart));
        }
    }

    /**
     * Drops a cart the server no longer has, releasing anything held for it.
     *
     * <p>Called for an unloaded cart as well as a destroyed one. Releasing is the safe direction in
     * both cases: the journey lives in the cart's own persistent data, so an unloaded cart
     * re-registers itself when its chunk comes back, whereas a journey whose lead has quietly
     * vanished would hold its region until the ticket expires.
     */
    void untrack(UUID cartId) {
        if (tracked.remove(cartId) != null) {
            engine.onCartRemoved(cartId);
        }
        lastInteractor.remove(cartId);
    }

    void recordInteractor(UUID cartId, UUID player) {
        lastInteractor.put(cartId, player);
    }

    FabricCartView view(AbstractMinecart cart) {
        return new FabricCartView(cart, lastInteractor.get(cart.getUUID()));
    }

    /** One turn of the travel loop. Runs on the server thread, on the scheduler's interval. */
    void poll() {
        List<CartView> views = new ArrayList<>(tracked.size());
        for (AbstractMinecart cart : List.copyOf(tracked.values())) {
            if (cart.isRemoved()) {
                untrack(cart.getUUID());
                continue;
            }
            views.add(view(cart));
        }

        Set<UUID> travellingBefore = new LinkedHashSet<>();
        for (CartView view : views) {
            if (engine.journeyOf(view.id()).isPresent()) {
                travellingBefore.add(view.id());
            }
        }

        engine.poll(views);

        for (UUID cartId : travellingBefore) {
            if (engine.journeyOf(cartId).isEmpty()) {
                engine.lastTermination(cartId).ifPresent(reason -> reportEnd(cartId, reason));
            }
        }

        tryDepartures(views);
    }

    /**
     * Offers every cart that is not already travelling to the gates.
     *
     * <p>Stationary carts are skipped before the gates rather than inside them: gate 3 reads the
     * block under the cart, and there is no reason to do that twice a second for every parked cart
     * on the server when gate 4 is going to refuse it anyway.
     */
    private void tryDepartures(List<CartView> views) {
        double minSpeed = engine.config().minSpeed();
        for (CartView view : views) {
            if (engine.journeyOf(view.id()).isPresent()) {
                continue;
            }
            engine.observe(view);
            if (view.velocity() < minSpeed) {
                continue;
            }
            DepartureOutcome outcome = engine.tryDepart(view, contactsOf(view));
            if (outcome instanceof DepartureOutcome.Departed departed) {
                if (debug) {
                    log.info("Departed: {} journey {}", describe(view), departed.journeyId());
                }
            } else if (debug && outcome instanceof DepartureOutcome.Denied denied) {
                log.info("Denied: {} — {}", describe(view), denied.reason());
            }
        }
    }

    /**
     * The carts that depart as one cluster: everything reachable from the lead through a chain of
     * contacts (SPEC §8). A train is a chain, not a ball, so a plain radius search around the lead
     * alone would find the second cart and miss the third.
     *
     * <p>Carts already travelling are excluded. Membership is assigned at departure, and sweeping one
     * into a second cluster would silently move it out of the journey holding its chunks.
     */
    private List<CartView> contactsOf(CartView lead) {
        AbstractMinecart leadEntity = tracked.get(lead.id());
        if (leadEntity == null) {
            return List.of();
        }
        List<CartView> cluster = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        Deque<AbstractMinecart> queue = new ArrayDeque<>();
        seen.add(lead.id());
        cluster.add(lead);
        queue.add(leadEntity);

        while (!queue.isEmpty() && cluster.size() < MAX_CLUSTER) {
            AbstractMinecart from = queue.poll();
            AABB box = from.getBoundingBox().inflate(CONTACT_RADIUS);
            for (AbstractMinecart nearby
                    : from.level().getEntitiesOfClass(AbstractMinecart.class, box)) {
                if (!seen.add(nearby.getUUID())) {
                    continue;
                }
                if (!tracked.containsKey(nearby.getUUID())
                        || engine.journeyOf(nearby.getUUID()).isPresent()) {
                    continue;
                }
                cluster.add(view(nearby));
                queue.add(nearby);
                if (cluster.size() >= MAX_CLUSTER) {
                    break;
                }
            }
        }
        return cluster;
    }

    /**
     * Logs where and why a journey ended (SPEC §8). The reason is the whole point: without it,
     * working-as-intended loop detection is indistinguishable from a bug report.
     */
    private void reportEnd(UUID cartId, TerminationReason reason) {
        AbstractMinecart cart = tracked.get(cartId);
        String where = cart == null ? "an unloaded chunk"
                : FabricCartView.worldIdOf(cart.level()).value() + " "
                        + cart.blockPosition().getX() + "," + cart.blockPosition().getY()
                        + "," + cart.blockPosition().getZ();
        String type = cart == null ? "minecart" : FabricCartView.typeOf(cart).name();
        String line = "Journey ended: " + reason + " — " + type + " at " + where;

        // ARRIVED and WORLD_DISABLED are DEBUG in §2.1: one is the normal end of a journey and the
        // other is the mod being told to stay out of a world. The rest are worth a line.
        if (reason == TerminationReason.ARRIVED || reason == TerminationReason.WORLD_DISABLED) {
            if (debug) {
                log.info(line);
            }
            return;
        }
        if (engine.config().strandedLog()) {
            log.info(line);
        }
        if (engine.config().notifyLastInteractor()) {
            notifyInteractor(cartId, line);
        }
    }

    /** Dropped rather than queued if the player is offline — queueing is persistent state (§8). */
    private void notifyInteractor(UUID cartId, String line) {
        UUID playerId = lastInteractor.get(cartId);
        if (playerId == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            player.sendSystemMessage(Component.literal("[ChunkCarts] " + line));
        }
    }

    private static String describe(CartView view) {
        return view.type() + " in " + view.world().value()
                + " at " + (int) view.x() + "," + (int) view.z();
    }
}

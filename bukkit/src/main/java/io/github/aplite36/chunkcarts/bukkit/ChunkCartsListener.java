package io.github.aplite36.chunkcarts.bukkit;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.github.aplite36.chunkcarts.core.CartEvent;
import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import io.github.aplite36.chunkcarts.core.RemovalCause;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Turns server events into the three things {@code core} needs from the outside world: which carts
 * exist, what players did to them, and whether anyone is online.
 *
 * <p>Everything here is {@code MONITOR} priority and cancellation-aware. ChunkCarts changes no
 * game behaviour — it never cancels an event, and it must not react to one another plugin
 * cancelled, or a protection plugin denying a right-click would still waive the cooldown.
 */
final class ChunkCartsListener implements Listener {

    /**
     * How long an unload mark is believed. Paper removes a chunk's entities in the same pass that
     * fires {@link EntitiesUnloadEvent}, so a mark is normally spent within the tick; this only
     * bounds how long one whose removal never came can sit in the map.
     */
    private static final int UNLOAD_MARK_TICKS = 20;

    private final ChunkCartsEngine engine;
    private final CartTracker tracker;

    /** Carts whose chunk is unloading, by the server tick it started on. */
    private final Map<UUID, Integer> unloading = new HashMap<>();

    /** The server tick, read through here so a test can run the unload marks without a server. */
    private final IntSupplier currentTick;

    ChunkCartsListener(ChunkCartsEngine engine, CartTracker tracker) {
        this(engine, tracker, Bukkit::getCurrentTick);
    }

    ChunkCartsListener(ChunkCartsEngine engine, CartTracker tracker, IntSupplier currentTick) {
        this.engine = engine;
        this.tracker = tracker;
        this.currentTick = currentTick;
    }

    /**
     * Re-registers carts as their chunks come back. Entity NBT is the source of truth,
     * so a cart that was travelling before a restart resumes from its own state rather than from
     * anything the plugin kept.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Minecart cart) {
                tracker.track(cart);
            }
        }
    }

    /**
     * A cart that came into being in an already-loaded chunk: dispensed, summoned, or placed.
     *
     * <p>{@link EntitiesLoadEvent} does not cover this — it fires for chunks arriving, not for
     * entities appearing in a chunk that is already here. Without this handler a dispenser-fed
     * station, which is the archetypal ChunkCarts setup, produces carts the plugin cannot see
     * until something makes their chunk reload.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleCreate(VehicleCreateEvent event) {
        if (event.getVehicle() instanceof Minecart cart) {
            tracker.track(cart);
        }
    }

    /**
     * {@code getPlayer()} is {@code @Nullable} on this event — both its constructors take a nullable
     * player and {@code CraftEventFactory.callEntityPlaceEvent} branches on it — so it is checked
     * rather than dereferenced. No call site that actually passes null has been identified; the
     * guard is here because this is a {@code MONITOR} handler on the entity-placement path, where an
     * NPE escapes into Bukkit's dispatch and the cart is left tracked but unattributed.
     *
     * <p>Without a player the {@code PLACEMENT} record is skipped too, not just the attribution.
     * {@code PLACEMENT} waives the journey cooldown, and a placement nobody performed should not
     * waive anything. The cart is still tracked, which is what {@link #onVehicleCreate} would have
     * done for it anyway.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getEntity() instanceof Minecart cart) {
            tracker.track(cart);
            Player placer = event.getPlayer();
            if (placer != null) {
                tracker.recordInteractor(cart.getUniqueId(), placer.getUniqueId());
                engine.record(cart.getUniqueId(), CartEvent.PLACEMENT);
            }
        }
    }

    /**
     * Marks the carts in a chunk that is unloading, so {@link #onEntityRemove} can tell them from
     * destroyed ones. The event fires before the entities are removed, and it is the only signal of
     * an unload that this API version and every later one both have.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        int now = currentTick.getAsInt();
        unloading.values().removeIf(tick -> now - tick > UNLOAD_MARK_TICKS);
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Minecart cart) {
                unloading.put(cart.getUniqueId(), now);
            }
        }
    }

    /**
     * Fires for a cart that was destroyed, one whose chunk unloaded, and one that went through a
     * portal. Only the first should end a journey, so the cause is worked out here.
     *
     * <p>Paper's event rather than Bukkit's {@code EntityRemoveEvent}, which is annotated
     * {@code @Deprecated(forRemoval = true)} in the 1.21 API this module compiles against. One jar
     * has to load on every version through 26.3, and registering a handler for a class that has
     * since been deleted fails the whole plugin at enable time, not just this listener. That event
     * carries a cause and this one does not, so the cause comes from two other places: the unload
     * mark, and the entity's world. A cart changing dimension has its Bukkit entity handed over to
     * the copy being added on the far side before the original is removed, so at this point it
     * already reports the destination world, which is not the world it is being removed from.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof Minecart cart) {
            tracker.untrack(cart.getUniqueId(), causeOf(cart, event));
        }
    }

    private RemovalCause causeOf(Minecart cart, EntityRemoveFromWorldEvent event) {
        Integer markedAt = unloading.remove(cart.getUniqueId());
        if (markedAt != null && currentTick.getAsInt() - markedAt <= UNLOAD_MARK_TICKS) {
            return RemovalCause.UNLOADED;
        }
        if (!cart.getWorld().equals(event.getWorld())) {
            return RemovalCause.CHANGED_DIMENSION;
        }
        return RemovalCause.DESTROYED;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Minecart cart) {
            tracker.recordInteractor(cart.getUniqueId(), event.getPlayer().getUniqueId());
            engine.record(cart.getUniqueId(), CartEvent.RIGHT_CLICK);
        }
    }

    /**
     * Opening a chest cart's inventory counts, however it was opened. A hopper pushing items in
     * does not — that is not an event a player caused, and treating it as one is exactly how an
     * automated loader would waive its own cooldown forever.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof Minecart cart) {
            tracker.recordInteractor(cart.getUniqueId(), event.getPlayer().getUniqueId());
            engine.record(cart.getUniqueId(), CartEvent.INVENTORY_OPEN);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        engine.setPlayersOnline(true);
    }

    /**
     * The quitting player is still in the online set when this fires, so "anyone else" is a count
     * of one rather than zero.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        engine.setPlayersOnline(event.getPlayer().getServer().getOnlinePlayers().size() > 1);
    }
}

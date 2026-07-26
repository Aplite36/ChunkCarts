package io.github.aplite36.chunkcarts.bukkit;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.github.aplite36.chunkcarts.core.CartEvent;
import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
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
import org.bukkit.inventory.InventoryHolder;

/**
 * Turns server events into the three things {@code core} needs from the outside world: which carts
 * exist, what players did to them, and whether anyone is online.
 *
 * <p>Everything here is {@code MONITOR} priority and cancellation-aware. ChunkCarts changes no
 * game behaviour — it never cancels an event, and it must not react to one another plugin
 * cancelled, or a protection plugin denying a right-click would still waive the cooldown.
 */
final class ChunkCartsListener implements Listener {

    private final ChunkCartsEngine engine;
    private final CartTracker tracker;

    ChunkCartsListener(ChunkCartsEngine engine, CartTracker tracker) {
        this.engine = engine;
        this.tracker = tracker;
    }

    /**
     * Re-registers carts as their chunks come back (SPEC §7). Entity NBT is the source of truth,
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getEntity() instanceof Minecart cart) {
            tracker.track(cart);
            tracker.recordInteractor(cart.getUniqueId(), event.getPlayer().getUniqueId());
            engine.record(cart.getUniqueId(), CartEvent.PLACEMENT);
        }
    }

    /**
     * Fires for a cart that was destroyed <em>and</em> for one whose chunk unloaded, which is the
     * pair of cases the tracker wants to treat identically.
     *
     * <p>Paper's event rather than Bukkit's {@code EntityRemoveEvent}, which is annotated
     * {@code @Deprecated(forRemoval = true)} in the 1.21 API this module compiles against. One jar
     * has to load on every version through 26.2, and registering a handler for a class that has
     * since been deleted fails the whole plugin at enable time, not just this listener.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof Minecart cart) {
            tracker.untrack(cart.getUniqueId());
        }
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
     * automated loader would waive its own cooldown forever (SPEC §4.1).
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

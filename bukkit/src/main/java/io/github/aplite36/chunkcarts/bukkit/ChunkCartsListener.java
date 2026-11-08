package io.github.aplite36.chunkcarts.bukkit;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.github.aplite36.chunkcarts.core.CartEvent;
import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import io.github.aplite36.chunkcarts.core.RemovalCause;
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
     * Every way a cart comes into a world: its chunk loading, being dispensed, placed or summoned
     * into a loaded chunk, and arriving through a portal. Entity NBT is the source of truth, so a
     * cart that was travelling before a restart or a crossing resumes from its own state rather than
     * from anything the plugin kept.
     *
     * <p>The portal arrival is why this is the event and not the ones that came before it. Paper
     * fires no {@code EntitiesLoadEvent}, {@code VehicleCreateEvent} or {@code EntityPlaceEvent} for
     * a cart coming out of a portal, only this one, right after the removal from the world it left.
     * With only those three, a train that crossed was never tracked on the far side and its journey
     * held the chunks at the near-side portal until the destination chunk happened to reload. This
     * event also fires for each entity of a loading chunk and for a newly created cart, so it
     * replaces the load and create handlers rather than adding to them: one path in, not three that
     * have to agree.
     *
     * <p>Present in the 1.21 API this module compiles against and neither removed nor deprecated in
     * 26.3; see {@link #onEntityRemove} for why that matters.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAdd(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof Minecart cart) {
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
     * waive anything. The cart is still tracked, which {@link #onEntityAdd} does for it anyway.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getEntity() instanceof Minecart cart) {
            tracker.track(cart);
            Player placer = event.getPlayer();
            if (placer != null) {
                tracker.recordInteractor(cart.getUniqueId(), placer.getUniqueId());
                engine.record(tracker.view(cart), CartEvent.PLACEMENT);
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
     * carries a cause and this one does not, so the cause is read off the cart itself.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof Minecart cart) {
            tracker.untrack(cart.getUniqueId(), causeOf(cart, event));
        }
    }

    /**
     * What a live Paper 1.21.11 server reports at the moment of removal. A cart that was killed or
     * broken is already dead. One going through a portal is not dead, and Paper has handed its Bukkit
     * entity to the copy on the far side, so it already reports the destination world, which is not
     * the world it is being removed from. Anything else that leaves alive and in the same world is a
     * chunk unloading, whether a player walked away, a forceload was removed or a portal ticket ran
     * out.
     *
     * <p>Destroyed is checked first because a dead cart's world says nothing about a portal, and an
     * unload is the remainder because it is the one cause that leaves no trace on the entity. An
     * earlier version marked carts from {@code EntitiesUnloadEvent} instead; on Paper that mark was
     * never present when the removal came, so every unload was reported as a destruction and ended
     * the journey.
     */
    static RemovalCause causeOf(Minecart cart, EntityRemoveFromWorldEvent event) {
        if (cart.isDead()) {
            return RemovalCause.DESTROYED;
        }
        if (!cart.getWorld().equals(event.getWorld())) {
            return RemovalCause.CHANGED_DIMENSION;
        }
        return RemovalCause.UNLOADED;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Minecart cart) {
            tracker.recordInteractor(cart.getUniqueId(), event.getPlayer().getUniqueId());
            engine.record(tracker.view(cart), CartEvent.RIGHT_CLICK);
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
            engine.record(tracker.view(cart), CartEvent.INVENTORY_OPEN);
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

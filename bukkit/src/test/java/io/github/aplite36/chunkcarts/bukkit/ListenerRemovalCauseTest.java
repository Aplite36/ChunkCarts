package io.github.aplite36.chunkcarts.bukkit;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import io.github.aplite36.chunkcarts.core.RemovalCause;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChunkCartsListener#onEntityRemove}: the removal event carries no cause on this API, so the
 * listener reads one off the cart, and only a destroyed cart may be reported as destroyed.
 *
 * <p>Each case is the state a live Paper 1.21.11 server showed at removal time.
 */
class ListenerRemovalCauseTest {

    private final CartTracker tracker = mock(CartTracker.class);
    private final ChunkCartsListener listener =
            new ChunkCartsListener(mock(ChunkCartsEngine.class), tracker);

    private final World overworld = mock(World.class);
    private final World nether = mock(World.class);
    private final UUID cartId = UUID.randomUUID();
    private final Minecart cart = mock(Minecart.class);

    ListenerRemovalCauseTest() {
        when(cart.getUniqueId()).thenReturn(cartId);
        when(cart.getWorld()).thenReturn(overworld);
    }

    private EntityRemoveFromWorldEvent removedFrom(Entity entity, World world) {
        EntityRemoveFromWorldEvent event = mock(EntityRemoveFromWorldEvent.class);
        when(event.getEntity()).thenReturn(entity);
        when(event.getWorld()).thenReturn(world);
        return event;
    }

    @Test
    void aDeadCartIsDestroyed() {
        // /kill, /damage, a player breaking it: all dead by the time the removal fires.
        when(cart.isDead()).thenReturn(true);

        listener.onEntityRemove(removedFrom(cart, overworld));

        verify(tracker).untrack(cartId, RemovalCause.DESTROYED);
    }

    @Test
    void aLiveCartLeavingTheWorldItIsInIsUnloaded() {
        // A chunk unloading: a forceload removed, a portal ticket expiring, a player walking away.
        // No EntitiesUnloadEvent mark is needed, or available: Paper's never reached the removal.
        listener.onEntityRemove(removedFrom(cart, overworld));

        verify(tracker).untrack(cartId, RemovalCause.UNLOADED);
    }

    @Test
    void aLiveCartAlreadyReportingAnotherWorldChangedDimension() {
        // Paper hands the Bukkit entity to the copy on the far side of the portal before the
        // original is removed, so the cart being removed from the overworld says it is in the nether.
        when(cart.getWorld()).thenReturn(nether);

        listener.onEntityRemove(removedFrom(cart, overworld));

        verify(tracker).untrack(cartId, RemovalCause.CHANGED_DIMENSION);
    }

    @Test
    void aDeadCartIsDestroyedWhateverWorldItReports() {
        when(cart.isDead()).thenReturn(true);
        when(cart.getWorld()).thenReturn(nether);

        listener.onEntityRemove(removedFrom(cart, overworld));

        verify(tracker).untrack(cartId, RemovalCause.DESTROYED);
    }

    @Test
    void somethingOtherThanACartIsIgnored() {
        listener.onEntityRemove(removedFrom(mock(ArmorStand.class), overworld));

        verify(tracker, never()).untrack(any(), any());
    }
}

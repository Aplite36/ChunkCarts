package io.github.aplite36.chunkcarts.bukkit;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import io.github.aplite36.chunkcarts.core.RemovalCause;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChunkCartsListener#onEntityRemove}: the removal event carries no cause on this API, so the
 * listener works one out, and only a destroyed cart may be reported as destroyed.
 */
class ListenerRemovalCauseTest {

    private final CartTracker tracker = mock(CartTracker.class);
    private int tick = 100;
    private final ChunkCartsListener listener =
            new ChunkCartsListener(mock(ChunkCartsEngine.class), tracker, () -> tick);

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

    private void unloading(Entity... entities) {
        EntitiesUnloadEvent event = mock(EntitiesUnloadEvent.class);
        when(event.getEntities()).thenReturn(List.of(entities));
        listener.onEntitiesUnload(event);
    }

    @Test
    void aCartRemovedFromTheWorldItIsInIsDestroyed() {
        listener.onEntityRemove(removedFrom(cart, overworld));

        verify(tracker).untrack(cartId, RemovalCause.DESTROYED);
    }

    @Test
    void aCartInAnUnloadingChunkIsUnloaded() {
        unloading(cart);

        listener.onEntityRemove(removedFrom(cart, overworld));

        verify(tracker).untrack(cartId, RemovalCause.UNLOADED);
    }

    @Test
    void anUnloadMarkIsSpentByTheRemovalItExplains() {
        // The same cart loads again later and is then broken. The mark from the earlier unload
        // must not make that look like another unload.
        unloading(cart);
        listener.onEntityRemove(removedFrom(cart, overworld));

        listener.onEntityRemove(removedFrom(cart, overworld));

        verify(tracker).untrack(cartId, RemovalCause.UNLOADED);
        verify(tracker).untrack(cartId, RemovalCause.DESTROYED);
    }

    @Test
    void aStaleUnloadMarkIsNotBelieved() {
        unloading(cart);
        tick += 21;

        listener.onEntityRemove(removedFrom(cart, overworld));

        verify(tracker).untrack(cartId, RemovalCause.DESTROYED);
    }

    @Test
    void aCartAlreadyReportingAnotherWorldChangedDimension() {
        // CraftBukkit hands the Bukkit entity to the copy on the far side of the portal before the
        // original is removed, so the cart being removed from the overworld says it is in the nether.
        when(cart.getWorld()).thenReturn(nether);

        listener.onEntityRemove(removedFrom(cart, overworld));

        verify(tracker).untrack(cartId, RemovalCause.CHANGED_DIMENSION);
    }

    @Test
    void somethingOtherThanACartIsIgnored() {
        ArmorStand stand = mock(ArmorStand.class);
        unloading(stand);

        listener.onEntityRemove(removedFrom(stand, overworld));

        verify(tracker, never()).untrack(any(), any());
    }
}

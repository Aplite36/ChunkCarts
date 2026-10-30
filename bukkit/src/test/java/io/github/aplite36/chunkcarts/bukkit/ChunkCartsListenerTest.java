package io.github.aplite36.chunkcarts.bukkit;

import io.github.aplite36.chunkcarts.core.CartEvent;
import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ChunkCartsListener#onEntityPlace}: {@code EntityPlaceEvent.getPlayer()} is nullable, and
 * a placement nobody performed must still track the cart without waiving its cooldown.
 */
class ChunkCartsListenerTest {

    private final ChunkCartsEngine engine = mock(ChunkCartsEngine.class);
    private final CartTracker tracker = mock(CartTracker.class);
    private final ChunkCartsListener listener = new ChunkCartsListener(engine, tracker);

    private final UUID cartId = UUID.randomUUID();
    private final Minecart cart = mock(Minecart.class);

    private EntityPlaceEvent placed(Entity entity, Player player) {
        EntityPlaceEvent event = mock(EntityPlaceEvent.class);
        when(event.getEntity()).thenReturn(entity);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    @Test
    void aCartPlacedByAPlayerIsTrackedAttributedAndWaivesTheCooldown() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(cart.getUniqueId()).thenReturn(cartId);

        listener.onEntityPlace(placed(cart, player));

        verify(tracker).track(cart);
        verify(tracker).recordInteractor(cartId, playerId);
        verify(engine).record(cartId, CartEvent.PLACEMENT);
    }

    @Test
    void aCartPlacedWithNoPlayerIsTrackedButNotAttributedOrCredited() {
        when(cart.getUniqueId()).thenReturn(cartId);

        listener.onEntityPlace(placed(cart, null));

        verify(tracker).track(cart);
        verify(tracker, never()).recordInteractor(any(), any());
        verify(engine, never()).record(any(), any());
    }

    @Test
    void somethingOtherThanACartIsIgnored() {
        listener.onEntityPlace(placed(mock(ArmorStand.class), mock(Player.class)));

        verifyNoInteractions(tracker, engine);
    }
}

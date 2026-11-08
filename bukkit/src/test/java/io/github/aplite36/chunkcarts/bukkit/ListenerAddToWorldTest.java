package io.github.aplite36.chunkcarts.bukkit;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import io.github.aplite36.chunkcarts.core.RemovalCause;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ChunkCartsListener#onEntityAdd}: the one signal Paper gives for a cart coming out of a
 * portal, so the one the far side of a crossing is tracked from.
 */
class ListenerAddToWorldTest {

    private final CartTracker tracker = mock(CartTracker.class);

    /** What the listener handed to the scheduler, run only when a test says the tick is over. */
    private final List<Runnable> nextTick = new ArrayList<>();
    private final ChunkCartsListener listener =
            new ChunkCartsListener(mock(ChunkCartsEngine.class), tracker, nextTick::add);

    private final World overworld = mock(World.class);
    private final World nether = mock(World.class);
    private final UUID cartId = UUID.randomUUID();
    private final Minecart cart = mock(Minecart.class);

    ListenerAddToWorldTest() {
        when(cart.getUniqueId()).thenReturn(cartId);
        when(cart.isValid()).thenReturn(true);
    }

    private void endTick() {
        List<Runnable> tasks = List.copyOf(nextTick);
        nextTick.clear();
        tasks.forEach(Runnable::run);
    }

    private static EntityAddToWorldEvent addedTo(Entity entity, World world) {
        EntityAddToWorldEvent event = mock(EntityAddToWorldEvent.class);
        when(event.getEntity()).thenReturn(entity);
        when(event.getWorld()).thenReturn(world);
        return event;
    }

    @Test
    void aCartAddedToAWorldIsTrackedOnTheNextTick() {
        listener.onEntityAdd(addedTo(cart, overworld));
        verify(tracker, never()).track(any());

        endTick();

        verify(tracker).track(cart);
    }

    @Test
    void nothingIsTrackedInsideTheAddEvent() {
        // Paper fires the event inside the entity manager update, where the ticket a restore holds
        // throws IllegalStateException. Live on Paper 1.21.11 and 26.3 for every travelling cart
        // arriving through a portal or loading with its chunk.
        doThrow(new IllegalStateException(
                "Cannot update ticket level while unloading chunks or updating entity manager"))
                .when(tracker).track(any());

        assertDoesNotThrow(() -> listener.onEntityAdd(addedTo(cart, overworld)));
    }

    @Test
    void oneTaskTracksEverythingThatArrivedInATick() {
        Minecart second = mock(Minecart.class);
        when(second.getUniqueId()).thenReturn(UUID.randomUUID());
        when(second.isValid()).thenReturn(true);

        listener.onEntityAdd(addedTo(cart, overworld));
        listener.onEntityAdd(addedTo(second, overworld));
        listener.onEntityAdd(addedTo(cart, overworld));
        assertEquals(1, nextTick.size(), "a chunk of carts is one task, not one each");

        endTick();

        verify(tracker).track(cart);
        verify(tracker).track(second);
        listener.onEntityAdd(addedTo(cart, nether));
        assertEquals(1, nextTick.size(), "and the next tick gets a task of its own");
    }

    @Test
    void aCartGoneAgainByTheNextTickIsNotTracked() {
        listener.onEntityAdd(addedTo(cart, overworld));
        when(cart.isValid()).thenReturn(false);

        endTick();

        verify(tracker, never()).track(any());
    }

    @Test
    void nothingQueuedIsTrackedAfterClose() {
        // A reload shuts the engine down; a cart tracked after that would hold chunks nobody releases.
        listener.onEntityAdd(addedTo(cart, overworld));
        listener.close();

        endTick();
        listener.onEntityAdd(addedTo(cart, overworld));
        endTick();

        verify(tracker, never()).track(any());
    }

    @Test
    void aCartThroughAPortalIsUntrackedThenTrackedAgainOnTheFarSide() {
        // The order a live Paper 1.21.11 server fires them in, in the same tick: the removal from
        // the overworld, with the entity already reporting the nether, then the add to the nether.
        when(cart.getWorld()).thenReturn(nether);
        EntityRemoveFromWorldEvent removed = mock(EntityRemoveFromWorldEvent.class);
        when(removed.getEntity()).thenReturn(cart);
        when(removed.getWorld()).thenReturn(overworld);

        listener.onEntityRemove(removed);
        listener.onEntityAdd(addedTo(cart, nether));
        endTick();

        InOrder order = inOrder(tracker);
        order.verify(tracker).untrack(cartId, RemovalCause.CHANGED_DIMENSION);
        order.verify(tracker).track(cart);
    }

    @Test
    void somethingOtherThanACartIsIgnored() {
        listener.onEntityAdd(addedTo(mock(ArmorStand.class), overworld));
        endTick();

        assertEquals(List.of(), nextTick);
        verifyNoInteractions(tracker);
    }

    @Test
    void theAddHandlerIsRegisteredAtMonitor() throws NoSuchMethodException {
        Method handler = ChunkCartsListener.class.getMethod("onEntityAdd", EntityAddToWorldEvent.class);
        EventHandler annotation = handler.getAnnotation(EventHandler.class);

        assertTrue(annotation != null, "without @EventHandler Bukkit never calls it");
        assertEquals(EventPriority.MONITOR, annotation.priority());
    }

    /**
     * One jar loads on every version through 26.3, and a handler for an event class that a later
     * API deletes fails the whole plugin at enable time. Deprecated-for-removal in the API floor is
     * the warning that comes before the deletion.
     */
    @Test
    void noHandlerListensForAnEventMarkedForRemoval() {
        List<String> doomed = new ArrayList<>();
        for (Method method : ChunkCartsListener.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(EventHandler.class)) {
                continue;
            }
            for (Class<?> type = method.getParameterTypes()[0];
                 type != null && Event.class.isAssignableFrom(type); type = type.getSuperclass()) {
                Deprecated deprecated = type.getAnnotation(Deprecated.class);
                if (deprecated != null && deprecated.forRemoval()) {
                    doomed.add(method.getName() + " -> " + type.getName());
                }
            }
        }
        assertEquals(List.of(), doomed);
    }
}

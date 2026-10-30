package io.github.aplite36.chunkcarts.loader;

import io.github.aplite36.chunkcarts.core.RemovalCause;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CartTracker#causeOf}: the entity's removal reason, as the engine needs it. Only a
 * destruction may end a journey for the rest of a train.
 */
class CartTrackerRemovalCauseTest {

    @Test
    void killedAndDiscardedAreDestroyed() {
        assertEquals(RemovalCause.DESTROYED, CartTracker.causeOf(Entity.RemovalReason.KILLED));
        assertEquals(RemovalCause.DESTROYED, CartTracker.causeOf(Entity.RemovalReason.DISCARDED));
    }

    @Test
    void bothUnloadReasonsAreUnloaded() {
        assertEquals(RemovalCause.UNLOADED,
                CartTracker.causeOf(Entity.RemovalReason.UNLOADED_TO_CHUNK));
        assertEquals(RemovalCause.UNLOADED,
                CartTracker.causeOf(Entity.RemovalReason.UNLOADED_WITH_PLAYER));
    }

    @Test
    void aPortalCrossingIsADimensionChange() {
        assertEquals(RemovalCause.CHANGED_DIMENSION,
                CartTracker.causeOf(Entity.RemovalReason.CHANGED_DIMENSION));
    }

    @Test
    void noReasonAtAllIsTreatedAsDestroyed() {
        assertEquals(RemovalCause.DESTROYED, CartTracker.causeOf(null));
    }

    @Test
    void everyReasonMapsToSomething() {
        // A reason a later version adds lands somewhere through shouldDestroy() rather than
        // throwing; this fails only if the mapping itself does.
        for (Entity.RemovalReason reason : Entity.RemovalReason.values()) {
            RemovalCause cause = CartTracker.causeOf(reason);
            assertEquals(reason.shouldDestroy(), cause == RemovalCause.DESTROYED, reason.name());
        }
    }
}

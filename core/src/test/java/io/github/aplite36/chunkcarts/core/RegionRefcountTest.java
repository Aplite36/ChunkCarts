package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two independent journeys whose held regions overlap. The engine must not treat one journey's
 * release of a shared chunk as the last word — the platform ticket has to outlive it until the
 * other journey lets go too, or that journey's cart strands in unloaded terrain.
 */
@DisplayName("Overlapping regions across independent journeys")
class RegionRefcountTest extends EngineTest {

    @Test
    @DisplayName("a chunk shared by two journeys survives the first journey's termination")
    void sharedChunkSurvivesFirstJourneyTermination() {
        // Region radius 1 means a 3x3 chunk hold around each cart. Departed two chunks apart
        // (not clustered — each departs alone, with no contacts) their regions overlap by one
        // column of chunks without the carts ever touching.
        cfg.regionRadius(1);
        FakeCart first = FakeCart.at(OVERWORLD, 0, 0);
        FakeCart second = FakeCart.at(OVERWORLD, 2, 0);

        departWithToken(first);
        departWithToken(second);

        // (1, 0) is inside both carts' 3x3 regions: first covers x -1..1, second covers x 1..3.
        ChunkPos shared = new ChunkPos(OVERWORLD, 1, 0);
        assertTrue(tickets.isHolding(OVERWORLD, shared.cx(), shared.cz()),
                "setup sanity: the shared chunk should be held before either journey ends");

        // Terminate the first journey only.
        world.derail(first.id());
        tickPoll(first);
        assertTerminated(first, TerminationReason.LEFT_RAILS);

        assertTrue(tickets.isHolding(OVERWORLD, shared.cx(), shared.cz()),
                "the second journey still wants this chunk; the first journey's release must not "
                        + "drop the platform ticket out from under it");

        // The second journey must still be able to progress normally afterwards.
        assertState(second, JourneyState.TRAVELLING);
        travelStraight(second, 3);
        assertState(second, JourneyState.TRAVELLING);
    }
}

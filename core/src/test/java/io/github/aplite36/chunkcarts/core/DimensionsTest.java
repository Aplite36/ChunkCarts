package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * SPEC §8 dimension transitions. Exactly one per journey.
 *
 * <p>Unlimited transitions would be an abuse vector in itself: each transit resets the origin,
 * so a cart bouncing through a portal loops the progress rule forever.
 */
@DisplayName("§8 dimension transitions")
class DimensionsTest extends EngineTest {

    @Test
    @DisplayName("a portal transition resets the origin without charging a token")
    void portalTransitionResetsOriginWithoutCharging() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        travelStraight(cart, 5);
        assertEquals(5, journeyOf(cart).distanceRecord());

        cart.world(NETHER).atChunk(100, 100);
        tickPoll(cart);

        JourneySnapshot after = journeyOf(cart);
        assertEquals(new ChunkPos(NETHER, 100, 100), after.origin(), "fresh origin in the new world");
        assertEquals(0, after.distanceRecord(), "the record resets with the origin");
        assertEquals(1, after.dimensionTransitionsUsed());
        assertEquals(JourneyState.TRAVELLING, after.state());
        assertFalse(engine().hasToken(cart.id()), "no token is charged, and none is refunded");
    }

    @Test
    @DisplayName("a second portal transition terminates the journey")
    void secondPortalTransitionTerminates() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        cart.world(NETHER).atChunk(10, 10);
        tickPoll(cart);
        cart.world(OVERWORLD).atChunk(80, 80);
        tickPoll(cart);

        assertTerminated(cart, TerminationReason.DIMENSION_LIMIT);
        assertEquals(0, tickets.heldCount());
    }

    @Test
    @DisplayName("a portal loop cannot sustain loading")
    void portalLoopCannotSustainLoading() {
        // The abuse case the one-transit limit exists for: bounce back and forth to keep resetting
        // the origin. The second transit ends it, so the loop cannot be sustained.
        cfg.progressSlackChunks(64);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        for (int i = 0; i < 6; i++) {
            cart.world(i % 2 == 0 ? NETHER : OVERWORLD).atChunk(i * 10, 0);
            tickPoll(cart);
        }

        assertTerminated(cart, TerminationReason.DIMENSION_LIMIT);
        assertNoJourney(cart);
        assertEquals(0, tickets.heldCount());
    }
}

package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a cart leaving the server's view does to its journey, per cause. Only a destruction ends
 * anything; a cart that unloaded or went through a portal is still out there, carrying its journey.
 *
 * <p>The destroyed cases are in {@link LifecycleTest}, which pins #32: a destroyed lead must not
 * leave its followers saved as TRAVELLING.
 */
@DisplayName("Removal causes")
class RemovalCauseTest extends EngineTest {

    private FakeCart lead;
    private FakeCart follower;

    /** A two-cart train, three chunks into its journey along +X. */
    private UUID departTrain() {
        cfg.regionRadius(1);
        lead = FakeCart.at(OVERWORLD, 0, 0).atBlock(8, 8);
        follower = new FakeCart(OVERWORLD).atBlock(9, 8);
        grantToken(lead);
        UUID journeyId = assertDeparted(engine().tryDepart(lead, List.of(lead, follower)));
        for (int i = 0; i < 3; i++) {
            lead.atChunk(lead.chunkX() + 1, lead.chunkZ());
            follower.atBlock(lead.x() - 1, lead.z());
            tickPoll(lead, follower);
        }
        return journeyId;
    }

    @Test
    @DisplayName("a lead that crosses a portal first keeps its followers")
    void leadThroughPortalKeepsFollowers() {
        UUID journeyId = departTrain();

        removeCart(RemovalCause.CHANGED_DIMENSION, lead, follower);

        assertEquals(journeyId, journeyOf(follower).journeyId(), "the follower is still on the journey");
        assertEquals(JourneyState.TRAVELLING, persisted(follower).state(),
                "and its saved state must still say so");
        assertTrue(engine().lastTermination(follower.id()).isEmpty(), "nothing ended");
        assertTrue(tickets.heldCount() > 0, "the follower still needs the ground up to the portal");

        // The lead arrives on the far side. The follower is still on the near side for a poll.
        lead.world(NETHER).atChunk(100, 100);
        engine().restore(lead);
        tickPoll(lead, follower);

        assertEquals(journeyId, journeyOf(lead).journeyId(), "the lead rejoins its own journey");
        assertEquals(2, journeyOf(lead).cartCount(), "a follower still at the portal is not a drifter");
        assertEquals(1, journeyOf(lead).dimensionTransitionsUsed());

        // Then the follower crosses too.
        removeCart(RemovalCause.CHANGED_DIMENSION, follower, lead);
        follower.world(NETHER).atBlock(lead.x() - 1, lead.z());
        engine().restore(follower);
        tickPoll(lead, follower);

        assertEquals(journeyId, journeyOf(follower).journeyId());
        assertEquals(2, journeyOf(lead).cartCount(), "the whole train made it through");
        assertEquals(1, journeyOf(lead).dimensionTransitionsUsed(),
                "a follower crossing is the same transit, not a second one");
        assertEquals(JourneyState.TRAVELLING, journeyOf(lead).state());
    }

    @Test
    @DisplayName("a follower left behind at a portal is still dropped once it wanders off")
    void followerLeftAtPortalIsDroppedWhenItWanders() {
        departTrain();
        removeCart(RemovalCause.CHANGED_DIMENSION, lead, follower);
        lead.world(NETHER).atChunk(100, 100);
        engine().restore(lead);
        tickPoll(lead, follower);

        follower.atChunk(40, 40);
        tickPoll(lead, follower);

        assertNoJourney(follower);
        assertTerminated(follower, TerminationReason.DROPPED);
        assertEquals(JourneyState.DORMANT, persisted(follower).state());
        assertEquals(1, journeyOf(lead).cartCount());
    }

    @Test
    @DisplayName("an unloaded lead keeps its cluster's journey and resumes it when it loads")
    void unloadedLeadResumesWithItsCluster() {
        UUID journeyId = departTrain();

        removeCart(RemovalCause.UNLOADED, lead, follower);

        assertEquals(journeyId, journeyOf(follower).journeyId());
        assertEquals(JourneyState.TRAVELLING, persisted(follower).state());
        assertTrue(engine().lastTermination(follower.id()).isEmpty());
        assertEquals(0, tickets.heldCount(),
                "nothing moves the journey while the lead is away, so nothing is held for it");
        assertEquals(1, engine().activeJourneyCount(), "but the journey itself is still there");

        engine().restore(lead);
        assertTrue(tickets.heldCount() > 0, "the lead re-holds its region when it loads");
        lead.atChunk(lead.chunkX() + 1, lead.chunkZ());
        follower.atBlock(lead.x() - 1, lead.z());
        tickPoll(lead, follower);

        assertEquals(journeyId, journeyOf(lead).journeyId());
        assertEquals(2, journeyOf(lead).cartCount());
        assertEquals(JourneyState.TRAVELLING, journeyOf(lead).state());
    }

    @Test
    @DisplayName("an unloaded follower stays on the journey and rejoins it when it loads")
    void unloadedFollowerRejoins() {
        UUID journeyId = departTrain();

        removeCart(RemovalCause.UNLOADED, follower, lead);

        assertEquals(2, journeyOf(lead).cartCount(), "an unloaded follower is not a destroyed one");
        assertTrue(tickets.heldCount() > 0, "the lead's journey carries on");

        engine().restore(follower);
        tickPoll(lead, follower);

        assertEquals(journeyId, journeyOf(follower).journeyId());
        assertEquals(2, journeyOf(lead).cartCount());
    }

    @Test
    @DisplayName("an unloaded solitary cart resumes the same journey")
    void unloadedSolitaryCartResumes() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        UUID journeyId = departWithToken(cart);
        travelStraight(cart, 3);
        int budget = journeyOf(cart).budgetRemaining();

        removeCart(RemovalCause.UNLOADED, cart);
        assertEquals(0, tickets.heldCount());

        engine().restore(cart);
        travelStraight(cart, 1);

        assertEquals(journeyId, journeyOf(cart).journeyId());
        assertTrue(journeyOf(cart).budgetRemaining() < budget, "and keeps spending the same budget");
    }

    @Test
    @DisplayName("a destroyed follower still leaves the lead's journey running")
    void destroyedFollowerIsDropped() {
        departTrain();

        removeCart(RemovalCause.DESTROYED, follower, lead);

        assertEquals(1, journeyOf(lead).cartCount());
        assertTrue(engine().journeyOf(follower.id()).isEmpty());
    }

    @Test
    @DisplayName("a destroyed lead still ends the journey for its followers")
    void destroyedLeadEndsJourney() {
        departTrain();

        removeCart(RemovalCause.DESTROYED, lead, follower);

        assertNoJourney(follower);
        assertTerminated(follower, TerminationReason.DROPPED);
        assertEquals(JourneyState.DORMANT, persisted(follower).state());
        assertEquals(0, tickets.heldCount());
    }
}

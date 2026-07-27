package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clustering. Carts in contact form one journey: shared budget, shared region, one slot.
 *
 * <p>Per-cart journeys would let twenty carts on one track consume twenty slots and instantly
 * exhaust a cap of sixteen — a self-inflicted denial of service.
 */
@DisplayName("Clustering")
class ClusteringTest extends EngineTest {

    /** Two carts a couple of blocks apart, i.e. touching on the same track. */
    private List<FakeCart> coupledPair() {
        FakeCart lead = FakeCart.at(OVERWORLD, 0, 0).atBlock(8, 8);
        FakeCart follower = new FakeCart(OVERWORLD).atBlock(9, 8);
        return List.of(lead, follower);
    }

    @Test
    @DisplayName("adjacent carts form a single journey")
    void adjacentCartsFormSingleJourney() {
        List<FakeCart> pair = coupledPair();
        FakeCart lead = pair.get(0);
        grantToken(lead);

        assertDeparted(engine().tryDepart(lead, List.copyOf(pair)));

        assertEquals(1, engine().journeys().size());
        assertEquals(2, engine().journeys().get(0).cartCount());
        assertEquals(journeyOf(lead).journeyId(), journeyOf(pair.get(1)).journeyId());
    }

    @Test
    @DisplayName("a cluster shares one budget")
    void clusterSharesOneBudget() {
        cfg.journeyBudgetChunks(256);
        List<FakeCart> pair = coupledPair();
        FakeCart lead = pair.get(0);
        FakeCart follower = pair.get(1);
        grantToken(lead);
        assertDeparted(engine().tryDepart(lead, List.copyOf(pair)));

        for (int i = 0; i < 6; i++) {
            lead.atChunk(lead.chunkX() + 1, 0);
            follower.atBlock(lead.x() - 1, lead.z());
            tickPoll(lead, follower);
        }

        assertEquals(journeyOf(lead).budgetRemaining(), journeyOf(follower).budgetRemaining());
        int spent = 256 - journeyOf(lead).budgetRemaining();
        assertEquals(tickets.distinctEverHeld(), spent,
                "one region, charged once — not once per cart");
    }

    @Test
    @DisplayName("a cluster counts once against the concurrency cap")
    void clusterCountsOnceAgainstConcurrencyCap() {
        cfg.maxConcurrentJourneys(1);
        List<FakeCart> pair = coupledPair();
        grantToken(pair.get(0));

        assertDeparted(engine().tryDepart(pair.get(0), List.copyOf(pair)));

        assertEquals(1, engine().activeJourneyCount(),
                "twenty carts on one track must not consume twenty slots");
    }

    @Test
    @DisplayName("membership is fixed at departure and not recomputed per poll")
    void membershipFixedAtDepartureNotRecomputed() {
        List<FakeCart> pair = coupledPair();
        FakeCart lead = pair.get(0);
        FakeCart follower = pair.get(1);
        grantToken(lead);
        assertDeparted(engine().tryDepart(lead, List.copyOf(pair)));

        // A third cart rolls into contact mid-route. It was not a member at departure, and it
        // holds no token, so it must not be absorbed.
        FakeCart bystander = new FakeCart(OVERWORLD).atBlock(lead.x() + 1, lead.z());
        tickPoll(lead, follower, bystander);

        assertEquals(2, journeyOf(lead).cartCount());
        assertTrue(engine().journeyOf(bystander.id()).isEmpty());
    }

    @Test
    @DisplayName("a cart breaking contact does not terminate mid-route — the station-shunting case")
    void cartBreakingContactDoesNotTerminateMidRoute() {
        // At unloading stations carts typically advance and separate one at a time. Re-clustering
        // each poll would make the separated cart its own journey, and holding no token it would
        // terminate immediately.
        cfg.regionRadius(1);
        List<FakeCart> pair = coupledPair();
        FakeCart lead = pair.get(0);
        FakeCart follower = pair.get(1);
        grantToken(lead);
        assertDeparted(engine().tryDepart(lead, List.copyOf(pair)));

        // Shunted a few blocks onto a siding — still well inside region-radius.
        follower.atBlock(lead.x() - 6, lead.z());
        tickPoll(lead, follower);

        assertEquals(2, journeyOf(lead).cartCount(), "still one journey");
        assertEquals(JourneyState.TRAVELLING, journeyOf(follower).state());
        assertTrue(engine().lastTermination(follower.id()).isEmpty());
    }

    @Test
    @DisplayName("a member drifting beyond region-radius is dropped and terminated individually")
    void driftingClusterMemberIsDroppedAndTerminated() {
        // The leash on static membership: otherwise five carts could fan out to five places and
        // hold 45 chunks against one budget and one slot.
        cfg.regionRadius(1);
        List<FakeCart> pair = coupledPair();
        FakeCart lead = pair.get(0);
        FakeCart drifter = pair.get(1);
        grantToken(lead);
        assertDeparted(engine().tryDepart(lead, List.copyOf(pair)));

        drifter.atChunk(40, 40);
        tickPoll(lead, drifter);

        assertEquals(1, journeyOf(lead).cartCount(), "dropped from the cluster");
        assertNotEquals(JourneyState.TRAVELLING,
                engine().journeyOf(drifter.id()).map(JourneySnapshot::state).orElse(null));
        assertTrue(engine().lastTermination(drifter.id()).isPresent(),
                "a cart a hundred blocks away has arrived somewhere else and is accounted separately");
    }

    @Test
    @DisplayName("a dropped member releases its own chunks")
    void droppedMemberReleasesItsOwnChunks() {
        cfg.regionRadius(1);
        List<FakeCart> pair = coupledPair();
        FakeCart lead = pair.get(0);
        FakeCart drifter = pair.get(1);
        grantToken(lead);
        assertDeparted(engine().tryDepart(lead, List.copyOf(pair)));

        drifter.atChunk(40, 40);
        tickPoll(lead, drifter);
        tickPoll(lead, drifter);

        assertTrue(tickets.held().stream().noneMatch(c -> c.cx() == 40 && c.cz() == 40),
                "the drifter's own region must not be left held by the cluster it left");
    }
}

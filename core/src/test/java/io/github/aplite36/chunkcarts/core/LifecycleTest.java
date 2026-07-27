package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shutdown and cleanup paths. Release every ticket on disable, or loaded chunks leak
 * until restart.
 */
@DisplayName("Lifecycle")
class LifecycleTest extends EngineTest {

    @Test
    @DisplayName("disable releases all tickets")
    void disableReleasesAllTickets() {
        FakeCart first = FakeCart.at(OVERWORLD, 0, 0);
        FakeCart second = FakeCart.at(OVERWORLD, 40, 40);
        departWithToken(first);
        departWithToken(second);
        travelStraight(first, 3);
        travelStraight(second, 3);
        assertTrue(tickets.heldCount() > 0);

        engine().shutdown();

        assertEquals(0, tickets.heldCount());
        assertEquals(0, engine().activeJourneyCount());
    }

    @Test
    @DisplayName("removing a cart releases its tickets")
    void cartRemovalReleasesItsTickets() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        travelStraight(cart, 3);
        assertTrue(tickets.heldCount() > 0);

        engine().onCartRemoved(cart.id());

        assertEquals(0, tickets.heldCount(), "the entity is gone; nothing should still be held for it");
        assertNoJourney(cart);
    }

    /**
     * Swept across every reason rather than asserted for one, because a leak on any single path is
     * the failure mode that shows up only as a slowly growing loaded-chunk count on someone else's
     * server. ARRIVED is excluded because it holds first, by design.
     */
    @ParameterizedTest(name = "{0} releases the region")
    @EnumSource(value = TerminationReason.class, names = "ARRIVED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("every termination reason releases the region")
    void terminationAlwaysReleasesRegion(TerminationReason reason) {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);

        switch (reason) {
            case SLACK_EXHAUSTED -> {
                cfg.progressSlackChunks(4);
                departWithToken(cart);
                travelLoop(cart, 3, 4);
            }
            case BUDGET_EXHAUSTED -> {
                cfg.journeyBudgetChunks(8).progressSlackChunks(64);
                departWithToken(cart);
                travelStraight(cart, 40);
            }
            case UNGENERATED_CHUNK -> {
                departWithToken(cart);
                world.withUngenerated(OVERWORLD, 3, 0);
                travelStraight(cart, 4);
            }
            case LEFT_RAILS -> {
                departWithToken(cart);
                travelStraight(cart, 2);
                world.derail(cart.id());
                tickPoll(cart);
            }
            case DIMENSION_LIMIT -> {
                departWithToken(cart);
                cart.world(NETHER).atChunk(10, 10);
                tickPoll(cart);
                cart.world(OVERWORLD).atChunk(70, 70);
                tickPoll(cart);
            }
            case WORLD_DISABLED -> {
                // Blocked before departure, so the config is fixed at construction rather than
                // mutated underneath a running engine.
                cfg.worlds(WorldsMode.BLOCKLIST, Set.of(NETHER.value()));
                departWithToken(cart);
                travelStraight(cart, 2);
                cart.world(NETHER).atChunk(5, 5);
                tickPoll(cart);
            }
            case STALLED -> {
                departWithToken(cart);
                cart.velocity(0.3);
                clock.advance(config().pauseTolerance().plusSeconds(1));
                poll(cart);
            }
            case DROPPED -> {
                // The one reason where the terminated cart is not the journey's lead: `cart` is
                // the member the leash drops. The lead is then derailed so that a held count
                // of zero accounts for both regions — the cluster's and the one the drifter
                // dragged out with it.
                cfg.regionRadius(1);
                FakeCart lead = FakeCart.at(OVERWORLD, 0, 0).atBlock(8, 8);
                cart.atBlock(9, 8);
                grantToken(lead);
                assertDeparted(engine().tryDepart(lead, List.of(lead, cart)));

                cart.atChunk(40, 40);
                tickPoll(lead, cart);
                tickPoll(lead);

                world.derail(lead.id());
                tickPoll(lead);
            }
            case ARRIVED -> throw new IllegalStateException("excluded by @EnumSource");
        }

        assertTerminated(cart, reason);
        assertEquals(0, tickets.heldCount(), "region left held after " + reason);
    }
}

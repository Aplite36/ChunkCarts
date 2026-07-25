package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** SPEC §3.2. The backstop, and the reason routes near populated areas do not fail for free. */
@DisplayName("§3.2 budget")
class BudgetTest extends EngineTest {

    @Test
    @DisplayName("the budget is charged only for chunks the plugin actually held")
    void budgetDecrementsOnlyForHeldChunks() {
        cfg.journeyBudgetChunks(256).regionRadius(1);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        travelStraight(cart, 10);

        int spent = 256 - journeyOf(cart).budgetRemaining();
        assertEquals(tickets.distinctEverHeld(), spent,
                "spend must equal the number of distinct chunks held, not polls taken");
    }

    @Test
    @DisplayName("a journey through player-loaded terrain spends nothing")
    void journeyThroughPlayerLoadedTerrainSpendsNothing() {
        cfg.journeyBudgetChunks(256).regionRadius(1);
        // A wide corridor of player-loaded terrain: the plugin has nothing to hold.
        world.withPlayerLoadedArea(OVERWORLD, 10, 0, 20);

        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        travelStraight(cart, 15);

        assertEquals(256, journeyOf(cart).budgetRemaining(),
                "nothing was spent because nothing was held");
    }

    @Test
    @DisplayName("exhausting the budget strands the cart")
    void budgetExhaustionStrandsCart() {
        cfg.journeyBudgetChunks(12).progressSlackChunks(64);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        travelStraight(cart, 60);

        assertTerminated(cart, TerminationReason.BUDGET_EXHAUSTED);
        assertNoJourney(cart);
        assertEquals(0, tickets.heldCount(), "a termination releases the region immediately");
    }
}

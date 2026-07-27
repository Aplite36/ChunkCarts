package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The concurrency cap gate. Deny, do not queue. */
@DisplayName("Concurrency cap")
class CapsTest extends EngineTest {

    @Test
    @DisplayName("the concurrency cap denies new departures")
    void concurrencyCapDeniesNewDepartures() {
        cfg.maxConcurrentJourneys(2);

        FakeCart first = FakeCart.at(OVERWORLD, 0, 0);
        FakeCart second = FakeCart.at(OVERWORLD, 20, 0);
        FakeCart third = FakeCart.at(OVERWORLD, 40, 0);
        departWithToken(first);
        departWithToken(second);

        grantToken(third);
        assertDenied(depart(third), DenialReason.CONCURRENCY_CAP);
        assertEquals(2, engine().activeJourneyCount());
    }

    @Test
    @DisplayName("a cap denial is not a termination")
    void capDenialIsNotATermination() {
        cfg.maxConcurrentJourneys(0);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        grantToken(cart);

        assertDenied(depart(cart), DenialReason.CONCURRENCY_CAP);

        assertTrue(engine().lastTermination(cart.id()).isEmpty(),
                "a refused departure is not a journey that ended");
        assertTrue(engine().hasToken(cart.id()), "and it costs the cart nothing");
    }
}

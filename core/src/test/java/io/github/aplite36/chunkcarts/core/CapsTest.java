package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The concurrency cap gate. Deny, do not queue. */
@DisplayName("Concurrency cap")
class CapsTest extends EngineTest {

    // ---- poll-interval-ticks / region-radius absolute floors ------------------------------
    //
    // These floors apply in BOTH modes, not just STRICT: a negative poll-interval-ticks is not a
    // policy choice, it is a broken plugin. Bukkit's runTaskTimer treats a period of -1 as
    // non-repeating, which silently kills the whole travel loop. See Config.MIN_POLL_INTERVAL_TICKS.

    @Test
    @DisplayName("STRICT raises a negative poll-interval-ticks to the absolute floor")
    void strictRaisesNegativePollIntervalToFloor() {
        var resolved = Config.defaults().enforcement(EnforcementMode.STRICT)
                .pollIntervalTicks(-1).build().resolve();

        assertEquals(Config.MIN_POLL_INTERVAL_TICKS, resolved.config().pollIntervalTicks());
        assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("poll-interval-ticks")));
    }

    @Test
    @DisplayName("STRICT raises a zero poll-interval-ticks to the absolute floor")
    void strictRaisesZeroPollIntervalToFloor() {
        var resolved = Config.defaults().enforcement(EnforcementMode.STRICT)
                .pollIntervalTicks(0).build().resolve();

        assertEquals(Config.MIN_POLL_INTERVAL_TICKS, resolved.config().pollIntervalTicks());
        assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("poll-interval-ticks")));
    }

    @Test
    @DisplayName("PERMISSIVE still raises a negative poll-interval-ticks to the absolute floor")
    void permissiveRaisesNegativePollIntervalToFloor() {
        // The important case: this floor must sit above the PERMISSIVE early return, not below it
        // with the rest of the STRICT-only policy clamps.
        var resolved = Config.defaults().enforcement(EnforcementMode.PERMISSIVE)
                .pollIntervalTicks(-1).build().resolve();

        assertEquals(Config.MIN_POLL_INTERVAL_TICKS, resolved.config().pollIntervalTicks());
        assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("poll-interval-ticks")));
    }

    @Test
    @DisplayName("PERMISSIVE still raises a zero poll-interval-ticks to the absolute floor")
    void permissiveRaisesZeroPollIntervalToFloor() {
        var resolved = Config.defaults().enforcement(EnforcementMode.PERMISSIVE)
                .pollIntervalTicks(0).build().resolve();

        assertEquals(Config.MIN_POLL_INTERVAL_TICKS, resolved.config().pollIntervalTicks());
        assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("poll-interval-ticks")));
    }

    @Test
    @DisplayName("PERMISSIVE still raises a negative region-radius to the absolute floor")
    void permissiveRaisesNegativeRegionRadiusToFloor() {
        // A negative radius produces an empty region — a cart would travel holding nothing.
        var resolved = Config.defaults().enforcement(EnforcementMode.PERMISSIVE)
                .regionRadius(-1).build().resolve();

        assertEquals(Config.MIN_REGION_RADIUS, resolved.config().regionRadius());
        assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("region-radius")));
    }

    @Test
    @DisplayName("STRICT raises a negative region-radius to the absolute floor")
    void strictRaisesNegativeRegionRadiusToFloor() {
        var resolved = Config.defaults().enforcement(EnforcementMode.STRICT)
                .regionRadius(-1).build().resolve();

        assertEquals(Config.MIN_REGION_RADIUS, resolved.config().regionRadius());
        assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("region-radius")));
    }

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

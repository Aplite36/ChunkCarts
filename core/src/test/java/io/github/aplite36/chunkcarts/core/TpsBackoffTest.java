package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TPS derived from scheduler drift rather than a platform API.
 *
 * <p>One implementation, unit-testable with a fake clock, correct on every platform including
 * Folia — where it measures the region that actually matters. This file is why that choice is
 * testable at all: there is no {@code currentTps()} on the adapter surface to stub.
 */
@DisplayName("TPS backoff")
class TpsBackoffTest extends EngineTest {

    /** Runs polls at the given real interval, where the expected interval is 10 ticks = 500ms. */
    private void pollAt(Duration actualInterval, int samples) {
        for (int i = 0; i < samples; i++) {
            clock.advanceNanosOnly(actualInterval);
            engine().poll(List.of());
        }
    }

    @Test
    @DisplayName("drift measurement yields the expected TPS under a fake clock")
    void driftMeasurementYieldsExpectedTpsUnderFakeClock() {
        cfg.pollIntervalTicks(10);

        // Expected 500ms per poll. Taking 1000ms means the server is running at half speed.
        pollAt(Duration.ofMillis(1000), 40);

        assertEquals(10.0, engine().effectiveTps(), 0.5,
                "expected / actual, expressed as effective TPS");
    }

    @Test
    @DisplayName("the EMA smooths a single spike")
    void emaSmoothsSingleSpike() {
        cfg.pollIntervalTicks(10).minTps(18.0);

        pollAt(Duration.ofMillis(500), 40);
        double healthy = engine().effectiveTps();
        assertTrue(healthy >= 19.0, "precondition: a healthy server reads near 20");

        // One enormous GC pause. A single sample must not block departures.
        clock.advanceNanosOnly(Duration.ofMillis(5000));
        engine().poll(List.of());

        assertTrue(engine().effectiveTps() > 18.0,
                "raw drift is noisy; one spike must not read as sustained load");
    }

    @Test
    @DisplayName("hysteresis prevents flapping at the threshold")
    void hysteresisPreventsFlappingAtThreshold() {
        cfg.pollIntervalTicks(10).minTps(18.0);

        // Settle below the threshold: departures are denied.
        pollAt(Duration.ofMillis(1000), 40);
        FakeCart first = FakeCart.at(OVERWORLD, 0, 0);
        grantToken(first);
        assertDenied(depart(first), DenialReason.TPS_BACKOFF);

        // Recover to just above min-tps but below min-tps + 1.0. Still denied, or the log fills
        // with transitions on a server hovering at the threshold.
        pollAt(Duration.ofMillis(543), 60);
        double recovered = engine().effectiveTps();
        assertTrue(recovered > 18.0 && recovered < 19.0,
                "precondition: recovered into the hysteresis band, not merely closer to it");
        FakeCart second = FakeCart.at(OVERWORLD, 30, 0);
        grantToken(second);
        assertDenied(depart(second), DenialReason.TPS_BACKOFF);

        // Comfortably clear of min-tps + 1.0: resumed.
        pollAt(Duration.ofMillis(500), 40);
        FakeCart third = FakeCart.at(OVERWORLD, 60, 0);
        grantToken(third);
        assertDeparted(depart(third));
    }

    @Test
    @DisplayName("startup grace skips the check until a full sample window exists")
    void startupGraceSkipsCheckUntilSampleWindowFull() {
        // Readings are meaningless for the first ~60s after boot: world load, chunk gen, other
        // plugins initialising. Without the grace, every departure right after a restart is denied.
        cfg.pollIntervalTicks(10).minTps(18.0);

        clock.advanceNanosOnly(Duration.ofMillis(4000));
        engine().poll(List.of());

        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        grantToken(cart);
        assertDeparted(depart(cart));
    }
}

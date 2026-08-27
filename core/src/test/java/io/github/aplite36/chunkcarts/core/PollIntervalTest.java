package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The poll interval: configurable, and clamped downward on observed velocity.
 *
 * <p>The interval lives here rather than in the adapter because TPS is derived from the gap
 * between polls measured against the interval the task was scheduled at. An adapter that quietly
 * polled faster would read as a server running at a fraction of its speed and would start denying
 * departures — the clamp would silently switch off the plugin on exactly the servers that need it.
 * So {@code core} decides the interval, and the same object tells the TPS monitor what to expect.
 */
@DisplayName("Poll interval")
class PollIntervalTest extends EngineTest {

    /** Advances by whatever interval the engine is currently asking for, then polls. */
    private void pollAtEffectiveInterval(FakeCart... carts) {
        clock.advanceTicks(engine().effectivePollIntervalTicks());
        poll(carts);
    }

    /** A cart the size of a TrainCarts express: far too fast for the configured interval. */
    private FakeCart fastCartInFlight(double velocity) {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0).velocity(velocity);
        departWithToken(cart);
        cart.atChunk(1, 0);
        pollAtEffectiveInterval(cart);
        return cart;
    }

    @Test
    @DisplayName("the interval defaults to the configured value")
    void intervalDefaultsToConfiguredValue() {
        cfg.pollIntervalTicks(10);

        assertEquals(10, engine().effectivePollIntervalTicks());

        engine().poll(List.of());
        assertEquals(10, engine().effectivePollIntervalTicks(),
                "with nothing in flight there is no velocity to clamp against");
    }

    @Test
    @DisplayName("a fast cart shortens the interval")
    void fastCartShortensInterval() {
        cfg.pollIntervalTicks(10).regionRadius(1);

        FakeCart cart = fastCartInFlight(4.0);

        assertTrue(engine().effectivePollIntervalTicks() < 10,
                "a cart at 4 blocks/tick covers 40 blocks between polls at the configured interval");
        assertTrue(engine().effectivePollIntervalTicks() * cart.velocity()
                        <= config().regionRadius() * 16.0,
                "the point of the clamp: a cart must not cross out of its own held region in one "
                        + "poll, or it strands itself in terrain nothing is holding");
    }

    @Test
    @DisplayName("the interval never exceeds the configured value")
    void intervalNeverExceedsConfiguredValue() {
        // The interval clamps downward. A slow cart is not licence to poll less often than the
        // admin asked: the interval is also the arrival-detection resolution, not just a
        // stranding guard.
        cfg.pollIntervalTicks(10);

        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0).velocity(0.1);
        departWithToken(cart);
        travelStraight(cart, 3);

        assertEquals(10, engine().effectivePollIntervalTicks());
    }

    @Test
    @DisplayName("the interval does not flap back the moment a cart slows")
    void intervalDoesNotFlapBackWhenCartSlows() {
        // Rescheduling the travel loop on every velocity wobble would churn the scheduler and, worse,
        // hand the TPS monitor a stream of samples whose expected interval changed underneath them.
        cfg.pollIntervalTicks(10).journeyBudgetChunks(1024);
        FakeCart cart = fastCartInFlight(4.0);
        int shortened = engine().effectivePollIntervalTicks();
        assertTrue(shortened < 10, "precondition: the interval shortened");

        cart.velocity(0.4);
        cart.atChunk(2, 0);
        pollAtEffectiveInterval(cart);
        assertEquals(shortened, engine().effectivePollIntervalTicks(),
                "one slow poll must not undo the clamp");

        // Sustained, it does return — the clamp tracks the cart, it does not latch for the journey.
        for (int i = 0; i < 80; i++) {
            cart.atChunk(cart.chunkX() + 1, cart.chunkZ());
            pollAtEffectiveInterval(cart);
        }
        assertEquals(10, engine().effectivePollIntervalTicks());
    }

    @Test
    @DisplayName("the interval returns to the configured value once nothing is in flight")
    void intervalReturnsToConfiguredValueWhenIdle() {
        cfg.pollIntervalTicks(10);
        FakeCart cart = fastCartInFlight(4.0);
        assertTrue(engine().effectivePollIntervalTicks() < 10, "precondition: the interval shortened");

        removeCart(cart);
        for (int i = 0; i < 80; i++) {
            clock.advanceTicks(engine().effectivePollIntervalTicks());
            engine().poll(List.of());
        }

        assertEquals(10, engine().effectivePollIntervalTicks());
    }

    @Test
    @DisplayName("shortening the interval does not read as a lagging server")
    void shorteningIntervalDoesNotReadAsLag() {
        // The whole reason this lives in core. The travel loop now runs every few ticks instead of
        // every ten, and each of those gaps is punctual — a TPS monitor still measuring against the
        // *configured* interval would read a healthy server at a fraction of its speed and deny
        // every departure on it.
        cfg.pollIntervalTicks(10).minTps(18.0).progressSlackChunks(64).journeyBudgetChunks(1024);
        FakeCart cart = fastCartInFlight(4.0);
        assertTrue(engine().effectivePollIntervalTicks() < 10, "precondition: the interval shortened");

        for (int i = 0; i < 40; i++) {
            cart.atChunk(cart.chunkX() + 1, cart.chunkZ());
            pollAtEffectiveInterval(cart);
        }

        assertTrue(engine().effectiveTps() >= 19.0,
                "a punctual server polling on a shortened interval reads as healthy, got "
                        + engine().effectiveTps());
        FakeCart other = FakeCart.at(OVERWORLD, 500, 500);
        grantToken(other);
        assertDeparted(depart(other));
    }

    @Test
    @DisplayName("a genuinely lagging server still reads as lagging on a shortened interval")
    void laggingServerStillReadsAsLaggingOnShortenedInterval() {
        cfg.pollIntervalTicks(10).minTps(18.0).progressSlackChunks(64).journeyBudgetChunks(1024);
        FakeCart cart = fastCartInFlight(4.0);
        int interval = engine().effectivePollIntervalTicks();
        assertTrue(interval < 10, "precondition: the interval shortened");

        // Every gap arrives at twice its scheduled length. The clamp must not launder that away.
        for (int i = 0; i < 40; i++) {
            cart.atChunk(cart.chunkX() + 1, cart.chunkZ());
            clock.advance(Duration.ofMillis(engine().effectivePollIntervalTicks() * 50L * 2));
            poll(cart);
        }

        assertTrue(engine().effectiveTps() < 18.0,
                "half-speed is half-speed whatever the interval, got " + engine().effectiveTps());
        FakeCart other = FakeCart.at(OVERWORLD, 500, 500);
        grantToken(other);
        assertDenied(depart(other), DenialReason.TPS_BACKOFF);
    }
}

package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arrival hold. Two settings, and the first is unconditional.
 *
 * <p>The completion test is <strong>net decrease</strong>, not movement. A hopper minecart parked
 * under a farm's output has items moving constantly inward, so a movement-based test would hold
 * forever — wrong, and wrong in an exploitable direction.
 */
@DisplayName("Arrival hold")
class ArrivalHoldTest extends EngineTest {

    /** Runs a cart out and brings it to rest, so it terminates ARRIVED and enters the hold. */
    private FakeCart arrive() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        travelStraight(cart, 3);
        cart.velocity(0.0);
        clock.advance(config().pauseTolerance().plusSeconds(1));
        poll(cart);
        return cart;
    }

    @Test
    @DisplayName("the arrival hold applies even when unload-hold is zero — settle time is unconditional")
    void arrivalHoldAppliesWhenUnloadHoldIsZero() {
        cfg.arrivalHoldTicks(60).unloadHold(Duration.ZERO);
        FakeCart cart = arrive();

        assertState(cart, JourneyState.HOLDING);
        assertTrue(tickets.heldCount() > 0,
                "settle time: long enough for a detector rail to fire and shunt the cart");
    }

    @Test
    @DisplayName("unload-hold is ignored in STRICT mode")
    void unloadHoldIgnoredInStrictMode() {
        cfg.enforcement(EnforcementMode.STRICT)
                .arrivalHoldTicks(60)
                .unloadHold(Duration.ofMinutes(5));
        FakeCart cart = arrive();

        // Only the 60-tick settle time applies, so three seconds later the region is gone.
        clock.advanceTicks(61);
        poll(cart);

        assertEquals(0, tickets.heldCount(),
                "holding a station for minutes is chunk loading — that is what STRICT forbids");
    }

    @Test
    @DisplayName("an ignored unload-hold emits a startup warning")
    void unloadHoldIgnoredEmitsStartupWarning() {
        cfg.enforcement(EnforcementMode.STRICT).unloadHold(Duration.ofMinutes(5));

        List<String> warnings = engine().startupWarnings();

        assertTrue(warnings.stream().anyMatch(w -> w.contains("unload-hold")),
                "ignored rather than clamped, and said out loud on every startup");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("forceload")),
                "the warning must point at the STRICT alternative rather than merely refusing");
    }

    @Test
    @DisplayName("the effective hold is the longer of the two settings, not their sum")
    void effectiveHoldIsMaxNotSumOfBothSettings() {
        cfg.enforcement(EnforcementMode.PERMISSIVE)
                .arrivalHoldTicks(60)          // 3s
                .unloadHold(Duration.ofSeconds(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0).itemCount(0);
        departWithToken(cart);
        travelStraight(cart, 2);
        cart.velocity(0.0);
        clock.advance(config().pauseTolerance().plusSeconds(1));
        poll(cart);

        // 11s in: past the 10s unload-hold and past the 3s settle, but short of their 13s sum.
        clock.advance(Duration.ofSeconds(11));
        poll(cart);

        assertEquals(0, tickets.heldCount(), "max, not sum");
    }

    @Test
    @DisplayName("the hold releases early when the cart is already empty")
    void holdReleasesEarlyWhenCartEmpty() {
        cfg.enforcement(EnforcementMode.PERMISSIVE)
                .arrivalHoldTicks(1)
                .unloadHold(Duration.ofMinutes(5));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0).type(CartType.CHEST).itemCount(0);
        departWithToken(cart);
        travelStraight(cart, 2);
        cart.velocity(0.0);
        clock.advance(config().pauseTolerance().plusSeconds(1));
        poll(cart);

        clock.advance(Duration.ofSeconds(11));
        poll(cart);

        assertEquals(0, tickets.heldCount(), "nothing to drain, so nothing to wait for");
    }

    @Test
    @DisplayName("a cluster hold ends on net decrease, not on movement")
    void clusterHoldEndsOnNetDecreaseNotMovement() {
        cfg.enforcement(EnforcementMode.PERMISSIVE)
                .arrivalHoldTicks(1)
                .unloadHold(Duration.ofMinutes(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0).type(CartType.CHEST).itemCount(100);
        departWithToken(cart);
        travelStraight(cart, 2);
        cart.velocity(0.0);
        clock.advance(config().pauseTolerance().plusSeconds(1));
        poll(cart);

        // Draining: held throughout, because the total keeps going down.
        for (int n = 90; n >= 10; n -= 10) {
            cart.itemCount(n);
            clock.advance(Duration.ofSeconds(2));
            poll(cart);
            assertTrue(tickets.heldCount() > 0, "still decreasing at " + n + " items");
        }

        // Drained and static for over ten seconds: released.
        cart.itemCount(0);
        clock.advance(Duration.ofSeconds(11));
        poll(cart);
        assertEquals(0, tickets.heldCount());
    }

    @Test
    @DisplayName("a filling hopper cart does not extend the hold — inbound items must not count")
    void hopperCartFillingDoesNotExtendHold() {
        // The exploit a movement-based test would open: park a hopper cart under a farm's output
        // and items pour in forever, holding the chunk forever.
        cfg.enforcement(EnforcementMode.PERMISSIVE)
                .arrivalHoldTicks(1)
                .unloadHold(Duration.ofMinutes(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0).type(CartType.HOPPER).itemCount(0);
        departWithToken(cart);
        travelStraight(cart, 2);
        cart.velocity(0.0);
        clock.advance(config().pauseTolerance().plusSeconds(1));
        poll(cart);

        for (int n = 10; n <= 120; n += 10) {
            cart.itemCount(n);
            clock.advance(Duration.ofSeconds(2));
            poll(cart);
        }

        assertEquals(0, tickets.heldCount(),
                "items were always moving, but never decreasing — the hold must have ended");
    }

    @Test
    @DisplayName("recirculation does not extend the hold")
    void recirculationDoesNotExtendHold() {
        // A hopper pulls from a chest cart, feeds a hopper line, and feeds back into a hopper cart
        // in the same cluster: net inventory constant, items always in motion.
        cfg.enforcement(EnforcementMode.PERMISSIVE)
                .arrivalHoldTicks(1)
                .unloadHold(Duration.ofMinutes(10));
        FakeCart chest = FakeCart.at(OVERWORLD, 0, 0).type(CartType.CHEST).itemCount(100);
        FakeCart hopper = new FakeCart(OVERWORLD).atBlock(9, 8);
        hopper.type(CartType.HOPPER).itemCount(0);
        grantToken(chest);
        assertDeparted(engine().tryDepart(chest, List.of(chest, hopper)));
        chest.atChunk(2, 0);
        hopper.atBlock(chest.x() - 1, chest.z());
        tickPoll(chest, hopper);
        chest.velocity(0.0);
        hopper.velocity(0.0);
        clock.advance(config().pauseTolerance().plusSeconds(1));
        poll(chest, hopper);

        // Shuffle 100 items back and forth. The cluster total never drops below 100.
        for (int i = 0; i < 20; i++) {
            chest.itemCount(i % 2 == 0 ? 40 : 100);
            hopper.itemCount(i % 2 == 0 ? 60 : 0);
            clock.advance(Duration.ofSeconds(2));
            poll(chest, hopper);
        }

        assertEquals(0, tickets.heldCount(),
                "testing net decrease closes recirculation as a side effect");
    }

    @Test
    @DisplayName("a cluster gets one hold, not one per cart")
    void clusterGetsOneHoldNotOnePerCart() {
        cfg.enforcement(EnforcementMode.PERMISSIVE).arrivalHoldTicks(60);
        FakeCart lead = FakeCart.at(OVERWORLD, 0, 0).atBlock(8, 8);
        FakeCart follower = new FakeCart(OVERWORLD).atBlock(9, 8);
        grantToken(lead);
        assertDeparted(engine().tryDepart(lead, List.of(lead, follower)));
        lead.atChunk(2, 0);
        follower.atBlock(lead.x() - 1, lead.z());
        tickPoll(lead, follower);
        lead.velocity(0.0);
        follower.velocity(0.0);
        clock.advance(config().pauseTolerance().plusSeconds(1));
        poll(lead, follower);

        assertEquals(1, engine().journeys().size(), "one timer, one region, one slot");
        assertEquals(journeyOf(lead).journeyId(), journeyOf(follower).journeyId());
    }

    @Test
    @DisplayName("the unload-hold timeout is a total for the whole cluster, not per cart")
    void unloadHoldTimeoutIsClusterTotal() {
        // Five full chest carts draining sequentially is roughly 57 minutes of continuously
        // decreasing inventory, so completion detection will not cut it short and the timeout does
        // all the work. Tolerable only because this is PERMISSIVE-only.
        cfg.enforcement(EnforcementMode.PERMISSIVE)
                .arrivalHoldTicks(1)
                .unloadHold(Duration.ofSeconds(30));
        FakeCart first = FakeCart.at(OVERWORLD, 0, 0).atBlock(8, 8);
        first.type(CartType.CHEST).itemCount(500);
        FakeCart second = new FakeCart(OVERWORLD).atBlock(9, 8);
        second.type(CartType.CHEST).itemCount(500);
        grantToken(first);
        assertDeparted(engine().tryDepart(first, List.of(first, second)));
        first.atChunk(2, 0);
        second.atBlock(first.x() - 1, first.z());
        tickPoll(first, second);
        first.velocity(0.0);
        second.velocity(0.0);
        clock.advance(config().pauseTolerance().plusSeconds(1));
        poll(first, second);

        // Still visibly draining when the shared 30s budget runs out.
        for (int i = 0; i < 20; i++) {
            first.itemCount(Math.max(0, 500 - i * 25));
            clock.advance(Duration.ofSeconds(2));
            poll(first, second);
        }

        assertEquals(0, tickets.heldCount(), "one timeout for the cluster, not one each");
    }

    @Test
    @DisplayName("a holding journey occupies a concurrency slot")
    void holdingOccupiesConcurrencySlot() {
        cfg.maxConcurrentJourneys(1).arrivalHoldTicks(200);
        FakeCart holding = arrive();
        assertState(holding, JourneyState.HOLDING);

        FakeCart newcomer = FakeCart.at(OVERWORLD, 60, 60);
        grantToken(newcomer);

        assertDenied(depart(newcomer), DenialReason.CONCURRENCY_CAP);
    }

    @Test
    @DisplayName("a stranded cart gets no arrival hold")
    void strandedCartGetsNoArrivalHold() {
        cfg.arrivalHoldTicks(200).progressSlackChunks(4);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        travelLoop(cart, 3, 4);

        assertTerminated(cart, TerminationReason.SLACK_EXHAUSTED);
        assertEquals(0, tickets.heldCount(), "every reason but ARRIVED releases immediately");
    }
}

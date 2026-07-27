package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One tolerance, two labels, and the fix for the hole the progress rule cannot see.
 *
 * <p>Slack accrues per chunk <em>entered</em>, so a cart that stops changing chunk never accrues
 * any and would hold its region indefinitely. This is the mechanism that closes that.
 */
@DisplayName("Pause tolerance")
class PauseToleranceTest extends EngineTest {

    @Test
    @DisplayName("a stationary cart terminates once the tolerance elapses — slack alone never fires")
    void stationaryCartTerminatesAfterPauseTolerance() {
        cfg.pauseTolerance(Duration.ofSeconds(15)).minSpeed(0.05).progressSlackChunks(16);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        travelStraight(cart, 3);

        // Sitting still. Note the cart never enters a new chunk, so slackUsed stays at zero
        // forever — this is precisely why the progress rule alone is not sufficient.
        cart.velocity(0.0);
        clock.advance(Duration.ofSeconds(16));
        poll(cart);

        assertTerminated(cart, TerminationReason.ARRIVED);
    }

    @Test
    @DisplayName("a cart oscillating inside one chunk terminates as STALLED")
    void cartOscillatingWithinOneChunkTerminatesAsStalled() {
        cfg.pauseTolerance(Duration.ofSeconds(15)).minSpeed(0.05);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        // Rattling back and forth within one chunk: above min-speed, going nowhere. The tolerance
        // is measured against chunk progress, not velocity, which is what catches this.
        cart.velocity(0.3);
        for (int i = 0; i < 40; i++) {
            cart.atBlock(4 + (i % 2) * 6, 8);
            tickPoll(cart);
        }

        assertTerminated(cart, TerminationReason.STALLED);
    }

    @Test
    @DisplayName("a brief pause resumes the same journey — the sorting-junction case")
    void briefPauseResumesSameJourney() {
        cfg.pauseTolerance(Duration.ofSeconds(15));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        var journeyId = departWithToken(cart);
        travelStraight(cart, 4);

        cart.velocity(0.0);
        clock.advance(Duration.ofSeconds(5));
        poll(cart);
        assertState(cart, JourneyState.PAUSED);

        cart.velocity(0.4).atChunk(5, 0);
        tickPoll(cart);

        assertState(cart, JourneyState.TRAVELLING);
        assertEquals(journeyId, journeyOf(cart).journeyId(), "the same journey, not a new one");
    }

    @Test
    @DisplayName("resuming from a pause needs no token and ignores the cooldown")
    void resumingFromPauseRequiresNoTokenOrCooldown() {
        cfg.pauseTolerance(Duration.ofSeconds(15)).journeyCooldown(Duration.ofMinutes(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        travelStraight(cart, 3);

        cart.velocity(0.0);
        clock.advance(Duration.ofSeconds(5));
        poll(cart);
        assertFalse(engine().hasToken(cart.id()), "precondition: the token was consumed on departure");

        cart.velocity(0.4).atChunk(4, 0);
        tickPoll(cart);

        assertState(cart, JourneyState.TRAVELLING);
    }

    @Test
    @DisplayName("a pause does not reset origin, record or budget")
    void pauseDoesNotResetOriginRecordOrBudget() {
        cfg.pauseTolerance(Duration.ofSeconds(15));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        travelStraight(cart, 6);
        JourneySnapshot before = journeyOf(cart);

        cart.velocity(0.0);
        clock.advance(Duration.ofSeconds(5));
        poll(cart);

        JourneySnapshot after = journeyOf(cart);
        assertEquals(before.origin(), after.origin());
        assertEquals(before.distanceRecord(), after.distanceRecord());
        assertEquals(before.budgetRemaining(), after.budgetRemaining());
    }

    @Test
    @DisplayName("the region stays held while paused")
    void regionStaysHeldWhilePaused() {
        // Not a convenience. A cart stopped at a sorting station needs its chunk loaded for the
        // sorter to push it onward; releasing would unload the very redstone meant to restart it.
        cfg.pauseTolerance(Duration.ofSeconds(15)).regionRadius(1);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        travelStraight(cart, 3);
        int heldWhileMoving = tickets.heldCount();
        assertTrue(heldWhileMoving > 0);

        cart.velocity(0.0);
        clock.advance(Duration.ofSeconds(5));
        poll(cart);

        assertState(cart, JourneyState.PAUSED);
        assertEquals(heldWhileMoving, tickets.heldCount());
        assertTrue(tickets.isHolding(OVERWORLD, cart.chunkX(), cart.chunkZ()));
    }

    @Test
    @DisplayName("chunk progress resets the pause timer")
    void chunkProgressResetsPauseTimer() {
        cfg.pauseTolerance(Duration.ofSeconds(15));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        // Creep forward one chunk every 10 seconds. Each chunk change refreshes the window, so the
        // journey survives far longer than the tolerance in absolute terms.
        for (int i = 1; i <= 8; i++) {
            clock.advance(Duration.ofSeconds(10));
            cart.atChunk(i, 0);
            poll(cart);
        }

        assertState(cart, JourneyState.TRAVELLING);
        assertTrue(engine().lastTermination(cart.id()).isEmpty());
    }

    @Test
    @DisplayName("ARRIVED and STALLED are distinguished only by velocity")
    void arrivedAndStalledAreDistinguishedByVelocity() {
        cfg.pauseTolerance(Duration.ofSeconds(15)).minSpeed(0.05);

        FakeCart stopped = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(stopped);
        stopped.velocity(0.0);
        clock.advance(Duration.ofSeconds(16));
        poll(stopped);
        assertTerminated(stopped, TerminationReason.ARRIVED);

        FakeCart wedged = FakeCart.at(OVERWORLD, 60, 60);
        grantToken(wedged);
        assertDeparted(depart(wedged));
        wedged.velocity(0.3);
        clock.advance(Duration.ofSeconds(16));
        poll(wedged);
        assertTerminated(wedged, TerminationReason.STALLED);
    }

    @Test
    @DisplayName("a paused journey still occupies a concurrency slot")
    void pausedJourneyOccupiesConcurrencySlot() {
        // Otherwise fifty paused carts hold fifty regions while the cap looks satisfied.
        cfg.maxConcurrentJourneys(1).pauseTolerance(Duration.ofSeconds(15));
        FakeCart paused = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(paused);
        paused.velocity(0.0);
        clock.advance(Duration.ofSeconds(5));
        poll(paused);
        assertState(paused, JourneyState.PAUSED);

        FakeCart newcomer = FakeCart.at(OVERWORLD, 40, 0);
        grantToken(newcomer);
        assertDenied(depart(newcomer), DenialReason.CONCURRENCY_CAP);
        assertEquals(1, engine().activeJourneyCount());
    }
}

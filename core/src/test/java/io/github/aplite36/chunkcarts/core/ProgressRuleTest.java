package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loop detector, and the reason the product can make its claim at all.
 *
 * <p>Do not relax an assertion here to make it pass. A closed track that keeps loading is the
 * whole failure the project exists to prevent.
 */
@DisplayName("Progress rule")
class ProgressRuleTest extends EngineTest {

    @Test
    @DisplayName("a closed loop expires once its slack allowance is spent")
    void closedLoopExpiresAfterSlackExhausted() {
        cfg.progressSlackChunks(16);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        // A 4-chunk-square circuit: 16 chunks per lap, so a handful of laps must exhaust 16 slack.
        travelLoop(cart, 4, 5);

        assertTerminated(cart, TerminationReason.SLACK_EXHAUSTED);
        assertNoJourney(cart);
    }

    @Test
    @DisplayName("loop diameter does not matter — a bigger circuit dies the same way")
    void largeClosedLoopAlsoExpires() {
        cfg.progressSlackChunks(16).journeyBudgetChunks(1024);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        // 40 chunks a side. The record is set on the first lap and can never be beaten.
        travelLoop(cart, 40, 3);

        assertTerminated(cart, TerminationReason.SLACK_EXHAUSTED);
    }

    @Test
    @DisplayName("a straight line travels indefinitely, bounded only by budget")
    void straightLineTravelsIndefinitelyWithinBudget() {
        cfg.progressSlackChunks(16).journeyBudgetChunks(1024);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        travelStraight(cart, 200);

        assertEquals(JourneyState.TRAVELLING, journeyOf(cart).state(),
                "an open-ended line sets a new record almost every chunk and must not expire");
        assertEquals(200, journeyOf(cart).distanceRecord());
        assertEquals(0, journeyOf(cart).slackUsed());
    }

    @Test
    @DisplayName("a new distance record resets the slack counter")
    void newRecordResetsSlackCounter() {
        cfg.progressSlackChunks(16);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        travelStraight(cart, 5);
        assertEquals(5, journeyOf(cart).distanceRecord());

        // Double back two chunks: no new record, so slack accrues.
        cart.atChunk(4, 0);
        tickPoll(cart);
        cart.atChunk(3, 0);
        tickPoll(cart);
        assertTrue(journeyOf(cart).slackUsed() > 0, "losing ground must spend slack");

        // Beat the record and the counter goes back to zero.
        travelStraight(cart, 4);
        assertEquals(7, journeyOf(cart).distanceRecord());
        assertEquals(0, journeyOf(cart).slackUsed());
    }

    @Test
    @DisplayName("a route that deviates within its slack allowance survives")
    void routeDeviationWithinSlackSurvives() {
        cfg.progressSlackChunks(16);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        travelStraight(cart, 10);

        // Curve around terrain: four chunks sideways, losing ground but well inside the allowance.
        for (int i = 1; i <= 4; i++) {
            cart.atChunk(10, i);
            tickPoll(cart);
        }

        assertEquals(JourneyState.TRAVELLING, journeyOf(cart).state(),
                "real routes curve around terrain; slack exists exactly for this");
    }

    @Test
    @DisplayName("a figure-eight track expires — it is still closed")
    void figureEightTrackExpires() {
        cfg.progressSlackChunks(16);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        // Two lobes sharing a crossing point. Every chunk is revisited, so no record can be set
        // after the first pass over each lobe.
        for (int lap = 0; lap < 4; lap++) {
            for (int[] step : squarePath(0, 0, 3)) {
                cart.atChunk(step[0], step[1]);
                tickPoll(cart);
            }
            for (int[] step : squarePath(0, 0, -3)) {
                cart.atChunk(step[0], step[1]);
                tickPoll(cart);
            }
        }

        assertTerminated(cart, TerminationReason.SLACK_EXHAUSTED);
    }

    /** Moves the cart out {@code depth} chunks and back to the origin, {@code cycles} times. */
    private void shuttle(FakeCart cart, int depth, int cycles) {
        for (int cycle = 0; cycle < cycles; cycle++) {
            for (int x = 1; x <= depth; x++) {
                cart.atChunk(x, 0);
                tickPoll(cart);
            }
            for (int x = depth - 1; x >= 0; x--) {
                cart.atChunk(x, 0);
                tickPoll(cart);
            }
        }
    }

    @Test
    @DisplayName("a there-and-back shuttle expires — the far end is a record only once")
    void shuttleExpiresAfterSlackExhausted() {
        cfg.progressSlackChunks(16);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        // README:52 names this shape and invites readers to try it: "put the cart on a long
        // there-and-back shuttle." The far end sets the distance record on the first outbound
        // trip; every trip after that, in both directions, only spends slack.
        shuttle(cart, 8, 5);

        assertTerminated(cart, TerminationReason.SLACK_EXHAUSTED);
        assertNoJourney(cart);
    }

    /**
     * Moves the cart out {@code startDepth + cycle} chunks and back to the origin, so the far end
     * advances by one chunk every cycle instead of staying fixed.
     */
    private void advancingShuttle(FakeCart cart, int startDepth, int cycles) {
        for (int cycle = 0; cycle < cycles; cycle++) {
            int far = startDepth + cycle;
            for (int x = 1; x <= far; x++) {
                cart.atChunk(x, 0);
                tickPoll(cart);
            }
            for (int x = far - 1; x >= 0; x--) {
                cart.atChunk(x, 0);
                tickPoll(cart);
            }
        }
    }

    /**
     * A shuttle whose far end creeps one chunk further every cycle sets a new distance record on
     * every single lap, which resets {@code slackUsed} to zero before it can accrue. The slack
     * rule alone would never fire here — this is the case where it is not sufficient by itself.
     *
     * <p>It cannot escape termination anyway, and this is the argument the whole guarantee rests
     * on: beating the distance record means entering a chunk at a greater Chebyshev distance from
     * the origin than any chunk visited so far, so that chunk is necessarily one the journey has
     * not been charged for yet. A record advance therefore always costs budget. Budget is
     * bounded, so records are finite, so slack eventually accrues without a reset and the journey
     * dies — just by the other mechanism. Slack and budget cover for each other exactly here:
     * slack catches a cart that is not progressing, budget catches one that is progressing
     * without bound.
     */
    @Test
    @DisplayName("an advancing shuttle still expires — budget catches what slack alone cannot")
    void advancingShuttleExpiresAfterBudgetExhausted() {
        cfg.progressSlackChunks(16).journeyBudgetChunks(8);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        advancingShuttle(cart, 1, 20);

        assertTerminated(cart, TerminationReason.BUDGET_EXHAUSTED);
        assertNoJourney(cart);
    }
}

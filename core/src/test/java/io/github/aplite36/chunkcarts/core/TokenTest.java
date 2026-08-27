package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-shot property that defeats shuttles, and the cooldown that rate
 * limits repetition.
 */
@DisplayName("Token and cooldown")
class TokenTest extends EngineTest {

    @Test
    @DisplayName("a player-loaded chunk grants a token")
    void tokenGrantedByPlayerLoadedChunk() {
        FakeCart cart = FakeCart.at(OVERWORLD, 5, 5);
        world.withPlayerLoaded(OVERWORLD, 5, 5);

        engine().observe(cart);

        assertTrue(engine().hasToken(cart.id()));
    }

    @Test
    @DisplayName("chunks the plugin itself holds never grant a token — the circularity guard")
    void tokenNotGrantedByOwnHeldChunks() {
        // The single most important test in this file. If our own tickets can grant tokens, the
        // scheme is circular and every rule downstream of it is decorative.
        FakeCart traveller = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(traveller);
        travelStraight(traveller, 5);
        assertTrue(tickets.heldCount() > 0, "precondition: the engine is holding a region");

        // A second cart sitting inside the held region, with no player anywhere near it.
        FakeCart parked = FakeCart.at(OVERWORLD, traveller.chunkX(), traveller.chunkZ());
        world.clearPlayerLoaded();
        engine().observe(parked);

        assertFalse(engine().hasToken(parked.id()),
                "a chunk is only a token source when a PLAYER loaded it");
        assertDenied(depart(parked), DenialReason.NO_TOKEN);
    }

    @Test
    @DisplayName("departing consumes the token")
    void tokenConsumedOnDeparture() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        grantToken(cart);
        assertTrue(engine().hasToken(cart.id()));

        assertDeparted(depart(cart));

        assertFalse(engine().hasToken(cart.id()));
    }

    @Test
    @DisplayName("an out-and-back shuttle cannot re-authorise itself")
    void shuttleDeniedWithoutFreshToken() {
        cfg.journeyCooldown(Duration.ZERO);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        // Out, then back to the origin. Never near a player again.
        travelStraight(cart, 6);
        world.clearPlayerLoaded();
        for (int i = 5; i >= 0; i--) {
            cart.atChunk(i, 0);
            tickPoll(cart);
        }

        engine().observe(cart);
        assertDenied(depart(cart), DenialReason.NO_TOKEN);
    }

    @Test
    @DisplayName("a denied departure leaves the token intact")
    void deniedDepartureDoesNotConsumeToken() {
        // Filling a cap of 1, rather than setting the cap to 0: 0 refuses every departure on the
        // server, so resolve() now raises it to the floor. See Config.MIN_CONCURRENT_JOURNEYS.
        cfg.maxConcurrentJourneys(1);
        departWithToken(FakeCart.at(OVERWORLD, 0, 0));

        FakeCart cart = FakeCart.at(OVERWORLD, 40, 0);
        grantToken(cart);

        assertDenied(depart(cart), DenialReason.CONCURRENCY_CAP);

        assertTrue(engine().hasToken(cart.id()),
                "a denial is not a termination and must cost the cart nothing");
    }

    @Test
    @DisplayName("the cooldown blocks an immediate second departure")
    void cooldownBlocksImmediateRedeparture() {
        cfg.journeyCooldown(Duration.ofMinutes(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        travelStraight(cart, 2);
        engine().shutdown();

        grantToken(cart);
        assertDenied(depart(cart), DenialReason.COOLDOWN);
    }

    @Test
    @DisplayName("the cooldown is wall-clock, so it is unaffected by tick counters resetting")
    void cooldownUsesWallClockNotTicks() {
        cfg.journeyCooldown(Duration.ofMinutes(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        engine().shutdown();

        // Wall-clock moves on without any polls happening at all — a restart, or an idle server.
        clock.advanceMillisOnly(Duration.ofMinutes(11));

        grantToken(cart);
        assertDeparted(depart(cart));
    }

    @Test
    @DisplayName("the cooldown runs from journey start, not journey end")
    void cooldownMeasuredFromJourneyStartNotEnd() {
        cfg.journeyCooldown(Duration.ofMinutes(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        // A long journey: nine minutes in flight, then it ends.
        clock.advance(Duration.ofMinutes(9));
        travelStraight(cart, 3);
        engine().shutdown();

        // Only one more minute is owed, because the clock started at departure.
        // Only one more minute is owed, because the clock started at departure — a long journey
        // must not be penalised twice over.
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        grantToken(cart);
        assertDeparted(depart(cart));
    }

    @Test
    @DisplayName("a player interaction waives the cooldown")
    void playerInteractionWaivesCooldown() {
        cfg.journeyCooldown(Duration.ofMinutes(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        engine().shutdown();

        clock.advance(Duration.ofSeconds(30));
        engine().record(cart.id(), CartEvent.RIGHT_CLICK);

        // "Stop the cart, reload it, send it off again" is the most legitimate action possible,
        // and a ten-minute lockout on it is pure friction.
        grantToken(cart);
        assertDeparted(depart(cart));
    }

    @Test
    @DisplayName("proximity without interaction does not waive the cooldown — the AFK guard")
    void proximityWithoutInteractionDoesNotWaiveCooldown() {
        cfg.journeyCooldown(Duration.ofMinutes(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        engine().shutdown();

        clock.advance(Duration.ofSeconds(30));
        engine().record(cart.id(), CartEvent.PLAYER_NEARBY);

        grantToken(cart);
        assertDenied(depart(cart), DenialReason.COOLDOWN);
    }

    @Test
    @DisplayName("hopper insertion is not interaction")
    void hopperInsertionIsNotInteraction() {
        cfg.journeyCooldown(Duration.ofMinutes(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0).type(CartType.HOPPER);
        departWithToken(cart);
        engine().shutdown();

        engine().record(cart.id(), CartEvent.HOPPER_INSERT);

        grantToken(cart);
        assertDenied(depart(cart), DenialReason.COOLDOWN);
    }

    @Test
    @DisplayName("dispenser dispatch is not interaction")
    void dispenserDispatchIsNotInteraction() {
        cfg.journeyCooldown(Duration.ofMinutes(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        engine().shutdown();

        engine().record(cart.id(), CartEvent.DISPENSER_DISPATCH);

        grantToken(cart);
        assertDenied(depart(cart), DenialReason.COOLDOWN);
    }

    @Test
    @DisplayName("one interaction waives exactly one dispatch")
    void oneInteractionWaivesOnlyOneDispatch() {
        cfg.journeyCooldown(Duration.ofMinutes(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        engine().shutdown();

        engine().record(cart.id(), CartEvent.RIGHT_CLICK);
        grantToken(cart);
        assertDeparted(depart(cart));
        engine().shutdown();

        // No new interaction. The waiver is spent, so the cooldown applies again.
        grantToken(cart);
        assertDenied(depart(cart), DenialReason.COOLDOWN);
    }
}

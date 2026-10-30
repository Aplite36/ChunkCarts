package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("a cart nothing interacted with keeps its cooldown — the AFK-dispenser guard")
    void aCartNothingInteractedWithKeepsItsCooldown() {
        // This is the path a dispenser-fired cart actually takes. It reaches track() and nothing
        // else: no adapter registers a hopper or dispenser listener, so no record() call is ever
        // made for it and no waiver is set.
        //
        // Three tests used to stand here instead, feeding the engine HOPPER_INSERT,
        // DISPENSER_DISPATCH and PLAYER_NEARBY and asserting nothing changed. record() only acts
        // when isInteraction() is true, so they asserted that values nothing produces do nothing,
        // and could not fail. This one can: waive the cooldown for a cart with no interaction and
        // it goes red.
        cfg.journeyCooldown(Duration.ofMinutes(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0).type(CartType.HOPPER);
        departWithToken(cart);
        engine().shutdown();

        clock.advance(Duration.ofSeconds(30));

        grantToken(cart);
        assertDenied(depart(cart), DenialReason.COOLDOWN);
    }

    @Test
    @DisplayName("every cart event is classified, so adding one is a decision made in core")
    void everyCartEventIsClassified() {
        // The point of routing events through core rather than letting each adapter decide is that
        // a new event has to be classified in one place. This fails on any constant added without
        // being listed, which is what makes that true rather than merely intended.
        assertEquals(
                Set.of(CartEvent.RIGHT_CLICK, CartEvent.INVENTORY_OPEN, CartEvent.PLACEMENT),
                Arrays.stream(CartEvent.values()).filter(CartEvent::isInteraction)
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                "these three waive the cooldown");
        assertEquals(
                Set.of(),
                Arrays.stream(CartEvent.values()).filter(event -> !event.isInteraction())
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                "nothing else is emitted by any adapter; a dispenser dispatch is handled by not "
                        + "listening for it, not by classifying it");
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

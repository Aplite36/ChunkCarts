package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /chunkcarts reload} as every adapter performs it: shut the engine down, build a new one,
 * and track every cart in a loaded chunk again, which restores each from its own saved state.
 * Nothing in memory crosses over, so whatever a cart has earned must already be on the cart.
 */
@DisplayName("Reload")
class ReloadTest extends EngineTest {

    private ChunkCartsEngine reload(FakeCart... loaded) {
        engine().shutdown();
        ChunkCartsEngine next = restarted();
        for (FakeCart cart : loaded) {
            next.restore(cart);
        }
        return next;
    }

    /** Ends the cart's journey as STALLED, which writes it back to DORMANT. */
    private void stall(FakeCart cart) {
        cart.velocity(0.3);
        clock.advance(config().pauseTolerance().plusSeconds(1));
        poll(cart);
        assertTerminated(cart, TerminationReason.STALLED);
        cart.velocity(0.4);
    }

    @Test
    @DisplayName("a reload keeps an earned departure token")
    void reloadKeepsToken() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        grantToken(cart);
        world.clearPlayerLoaded();

        ChunkCartsEngine next = reload(cart);

        assertTrue(next.hasToken(cart.id()), "the token was earned and never spent");
        assertDeparted(next.tryDepart(cart));
    }

    @Test
    @DisplayName("a reload does not hand back a token that was spent")
    void reloadDoesNotRefundSpentToken() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        stall(cart);
        world.clearPlayerLoaded();

        ChunkCartsEngine next = reload(cart);

        assertFalse(next.hasToken(cart.id()));
    }

    @Test
    @DisplayName("a token is written once when granted, not on every observation")
    void tokenWrittenOnceWhenGranted() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        grantToken(cart);
        int writes = cart.writeCount();
        for (int i = 0; i < 10; i++) {
            engine().observe(cart);
        }
        assertEquals(1, writes, "the grant is written");
        assertEquals(writes, cart.writeCount(), "and holding it costs nothing more");
    }

    @Test
    @DisplayName("a reload keeps an unspent interaction waiver")
    void reloadKeepsWaiver() {
        cfg.journeyCooldown(Duration.ofMinutes(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        stall(cart);
        clock.advance(Duration.ofSeconds(30));
        engine().record(cart, CartEvent.RIGHT_CLICK);

        ChunkCartsEngine next = reload(cart);

        next.observe(cart);  // still player-loaded from grantToken
        assertDeparted(next.tryDepart(cart));

        // And it is still exactly one dispatch: the departure spent it, on the cart too.
        stall(cart);
        ChunkCartsEngine after = reload(cart);
        after.observe(cart);
        assertDenied(after.tryDepart(cart), DenialReason.COOLDOWN);
    }

    @Test
    @DisplayName("an interaction during a journey survives a reload as well")
    void reloadKeepsWaiverRecordedWhileTravelling() {
        cfg.journeyCooldown(Duration.ofMinutes(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        UUID journeyId = departWithToken(cart);
        travelStraight(cart, 2);
        clock.advance(Duration.ofSeconds(1));
        engine().record(cart, CartEvent.RIGHT_CLICK);

        ChunkCartsEngine next = reload(cart);

        assertEquals(journeyId, next.journeyOf(cart.id()).orElseThrow().journeyId(),
                "writing the interaction must not end the journey on the cart");
        assertTrue(next.cooldownRemaining(cart.id()).isEmpty(), "the waiver came back with it");
    }

    @Test
    @DisplayName("a running journey still survives a reload")
    void reloadKeepsRunningJourney() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        UUID journeyId = departWithToken(cart);
        travelStraight(cart, 3);

        ChunkCartsEngine next = reload(cart);

        assertEquals(journeyId, next.journeyOf(cart.id()).orElseThrow().journeyId());
        assertTrue(tickets.heldCount() > 0, "the new engine holds the journey's region again");
    }
}

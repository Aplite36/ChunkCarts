package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** SPEC §4 departure gates, plus the in-flight re-checks from §5 step 4. */
@DisplayName("§4 departure gates")
class GatesTest extends EngineTest {

    @Test
    @DisplayName("an ungenerated chunk halts the journey rather than generating it")
    void ungeneratedChunkHaltsJourney() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        // Unexplored terrain three chunks ahead. Refusing keeps carts from being worldgen bots.
        world.withUngenerated(OVERWORLD, 4, 0);
        travelStraight(cart, 5);

        assertTerminated(cart, TerminationReason.UNGENERATED_CHUNK);
        assertEquals(0, tickets.heldCount());
    }

    @Test
    @DisplayName("leaving the rails terminates the journey")
    void leavingRailsTerminatesJourney() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        travelStraight(cart, 3);

        world.derail(cart.id());
        tickPoll(cart);

        assertTerminated(cart, TerminationReason.LEFT_RAILS);
        assertEquals(0, tickets.heldCount());
    }

    @Test
    @DisplayName("a cart below the speed threshold does not depart")
    void belowSpeedThresholdDoesNotDepart() {
        cfg.minSpeed(0.05);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0).velocity(0.01);
        grantToken(cart);

        assertDenied(depart(cart), DenialReason.BELOW_MIN_SPEED);
    }

    @Test
    @DisplayName("a TNT cart never departs")
    void tntCartNeverDeparts() {
        // Even if someone edits the config to permit it: TNT is in NEVER_PERMITTED.
        cfg.allowedCartTypes(EnumSet.allOf(CartType.class));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0).type(CartType.TNT);
        grantToken(cart);

        assertDenied(depart(cart), DenialReason.CART_TYPE_FORBIDDEN);
    }

    @Test
    @DisplayName("a command block cart never departs")
    void commandBlockCartNeverDeparts() {
        cfg.allowedCartTypes(EnumSet.allOf(CartType.class));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0).type(CartType.COMMAND_BLOCK);
        grantToken(cart);

        assertDenied(depart(cart), DenialReason.CART_TYPE_FORBIDDEN);
    }

    @Test
    @DisplayName("a furnace minecart on a loop still expires")
    void furnaceMinecartOnLoopStillExpires() {
        // Furnace carts self-propel on coal, so they are the only type that can run a loop forever
        // with no redstone. The progress rule should catch it; this asserts it rather than assuming.
        cfg.progressSlackChunks(16);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0).type(CartType.FURNACE).velocity(0.4);
        departWithToken(cart);

        travelLoop(cart, 5, 6);

        assertTerminated(cart, TerminationReason.SLACK_EXHAUSTED);
    }

    @Test
    @DisplayName("a disabled world blocks departure")
    void disabledWorldBlocksDeparture() {
        cfg.worlds(WorldsMode.BLOCKLIST, Set.of(OVERWORLD.value()));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        grantToken(cart);

        assertDenied(depart(cart), DenialReason.WORLD_DISABLED);
    }

    @Test
    @DisplayName("low TPS blocks a departure but never interrupts a journey in flight")
    void lowTpsBlocksDepartureButNotJourneyInFlight() {
        cfg.minTps(18.0).pollIntervalTicks(10);
        FakeCart traveller = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(traveller);
        travelStraight(traveller, 3);

        // Drive the server into the ground: each poll takes four times its expected 500ms.
        for (int i = 0; i < 40; i++) {
            clock.advanceNanosOnly(Duration.ofMillis(2000));
            poll(traveller);
        }

        FakeCart newcomer = FakeCart.at(OVERWORLD, 50, 50);
        grantToken(newcomer);
        assertDenied(depart(newcomer), DenialReason.TPS_BACKOFF);

        assertEquals(JourneyState.TRAVELLING, journeyOf(traveller).state(),
                "interrupting a journey in flight would strand the cart mid-route");
    }
}

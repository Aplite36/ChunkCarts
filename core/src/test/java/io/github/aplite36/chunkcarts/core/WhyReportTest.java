package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /chunkcarts why}: the two most common support questions this mod will ever get.
 *
 * <p>Both answers were already computed and both were thrown away. {@code lastTermination} was read
 * only by {@code CartTracker.reportEnd}, which writes a console line when {@code stranded.log} is
 * on, so a player standing next to a stranded cart could learn nothing. The denial reason was worse:
 * {@code gate()} produces one on every departure attempt and it was discarded unless debug logging
 * was on, so a station that had quietly stopped dispatching gave a completely healthy-looking
 * {@code list}.
 */
@DisplayName("why a cart stopped, or will not depart")
class WhyReportTest extends EngineTest {

    private String reportOn(FakeCart cart) {
        return String.join("\n", WhyReport.lines(cart, engine()));
    }

    // ---- what the engine now keeps ------------------------------------------------------------

    @Test
    @DisplayName("a refused departure is remembered, not just returned")
    void aRefusedDepartureIsRememberedNotJustReturned() {
        cfg.journeyCooldown(Duration.ofMinutes(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        engine().shutdown();

        grantToken(cart);
        assertDenied(depart(cart), DenialReason.COOLDOWN);

        assertEquals(Optional.of(DenialReason.COOLDOWN), engine().lastDenial(cart.id()),
                "the tracker runs the gates every poll, so this stays current on its own");
    }

    @Test
    @DisplayName("a departing cart's old denial is cleared, because it departed")
    void aDepartingCartsOldDenialIsCleared() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        assertDenied(depart(cart), DenialReason.NO_TOKEN);
        assertTrue(engine().lastDenial(cart.id()).isPresent());

        departWithToken(cart);

        assertEquals(Optional.empty(), engine().lastDenial(cart.id()),
                "stale as soon as it leaves, and 'will not depart' is a wrong answer for one that did");
    }

    @Test
    @DisplayName("a travelling cart reports no denial even if one is stored")
    void aTravellingCartReportsNoDenialEvenIfOneIsStored() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        assertDenied(depart(cart), DenialReason.NO_TOKEN);
        departWithToken(cart);

        assertEquals(Optional.empty(), engine().lastDenial(cart.id()));
    }

    @Test
    @DisplayName("a removed cart's denial is forgotten")
    void aRemovedCartsDenialIsForgotten() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        assertDenied(depart(cart), DenialReason.NO_TOKEN);

        removeCart(cart);

        assertEquals(Optional.empty(), engine().lastDenial(cart.id()),
                "nothing asks about a cart the tracker no longer has, so keeping it is a leak");
    }

    @Test
    @DisplayName("the cooldown says how much longer, not just that there is one")
    void theCooldownSaysHowMuchLonger() {
        // COOLDOWN alone is not an answer an admin can act on. "For another seven minutes" is the
        // difference between waiting and going to look for a fault.
        cfg.journeyCooldown(Duration.ofMinutes(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        engine().shutdown();

        clock.advance(Duration.ofMinutes(2).plusSeconds(48));

        Optional<Duration> left = engine().cooldownRemaining(cart.id());
        assertTrue(left.isPresent());
        assertEquals(Duration.ofMinutes(7).plusSeconds(12).toSeconds(), left.get().toSeconds());
    }

    @Test
    @DisplayName("a cart that has never travelled has no cooldown to report")
    void aCartThatHasNeverTravelledHasNoCooldownToReport() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        assertEquals(Optional.empty(), engine().cooldownRemaining(cart.id()));
    }

    // ---- the report ---------------------------------------------------------------------------

    @Test
    @DisplayName("a travelling cart reports its journey, not a stale ending")
    void aTravellingCartReportsItsJourney() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        String report = reportOn(cart);

        assertTrue(report.contains("TRAVELLING"), report);
        assertTrue(report.contains("budget"), report);
        assertFalse(report.contains("Not travelling"), report);
    }

    @Test
    @DisplayName("a stranded cart names the reason its journey ended")
    void aStrandedCartNamesTheReasonItsJourneyEnded() {
        // A closed loop, which is the case the whole mod exists for and the one an admin is most
        // likely to be standing next to wondering about.
        cfg.progressSlackChunks(4);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        for (int lap = 0; lap < 6; lap++) {
            for (int x = 0; x <= 2; x++) {
                cart.atChunk(x, 0);
                tickPoll(cart);
            }
            for (int x = 1; x >= 0; x--) {
                cart.atChunk(x, 0);
                tickPoll(cart);
            }
        }

        String report = reportOn(cart);

        assertTrue(report.contains("Not travelling"), report);
        assertTrue(report.contains(TerminationReason.SLACK_EXHAUSTED.name()), report);
    }

    @Test
    @DisplayName("a cart with nothing to say still says where it is")
    void aCartWithNothingToSayStillSaysWhereItIs() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);

        List<String> lines = WhyReport.lines(cart, engine());

        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains(cart.type().name()), lines.get(0));
        assertTrue(lines.get(0).contains("world"), lines.get(0));
        assertTrue(lines.get(1).contains("No journey has ended"), lines.get(1));
    }

    @Test
    @DisplayName("negative coordinates name the block the cart is in")
    void negativeCoordinatesNameTheBlockTheCartIsIn() {
        // A cast rounds toward zero, so -0.5 came out as 0: the block next door.
        FakeCart cart = new FakeCart(OVERWORLD).atBlock(-0.5, -15.5);

        String first = WhyReport.lines(cart, engine()).get(0);

        assertTrue(first.endsWith(" -1,64,-16"), first);
    }

    @Test
    @DisplayName("the report reaches for the cooldown detail when the denial is a cooldown")
    void theReportReachesForTheCooldownDetail() {
        cfg.journeyCooldown(Duration.ofMinutes(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        engine().shutdown();
        clock.advance(Duration.ofMinutes(2).plusSeconds(48));
        grantToken(cart);
        assertDenied(depart(cart), DenialReason.COOLDOWN);

        String report = reportOn(cart);

        assertTrue(report.contains("Will not depart: COOLDOWN"), report);
        assertTrue(report.contains("7m 12s remaining"), report);
    }

    // ---- picking the cart the sender means ----------------------------------------------------

    @Test
    @DisplayName("the cart being looked at wins over a nearer one off to the side")
    void theCartBeingLookedAtWinsOverANearerOneOffToTheSide() {
        // The rail-yard case, which is the only one where this matters at all.
        WhyReport.Candidate beside = new WhyReport.Candidate(UUID.randomUUID(), 2.0, 0.0, 0.0);
        WhyReport.Candidate ahead = new WhyReport.Candidate(UUID.randomUUID(), 0.0, 0.0, 5.0);

        assertEquals(Optional.of(ahead.id()),
                WhyReport.target(0, 0, 0, 0, 0, 1, List.of(beside, ahead)));
    }

    @Test
    @DisplayName("looking at nothing falls back to the nearest cart")
    void lookingAtNothingFallsBackToTheNearestCart() {
        // Someone standing over a cart rather than sighting down the track still gets an answer.
        WhyReport.Candidate near = new WhyReport.Candidate(UUID.randomUUID(), 1.0, 0.0, 0.0);
        WhyReport.Candidate far = new WhyReport.Candidate(UUID.randomUUID(), -6.0, 0.0, 0.0);

        assertEquals(Optional.of(near.id()),
                WhyReport.target(0, 0, 0, 0, 0, 1, List.of(far, near)));
    }

    @Test
    @DisplayName("a cart out of range is never the answer")
    void aCartOutOfRangeIsNeverTheAnswer() {
        WhyReport.Candidate distant =
                new WhyReport.Candidate(UUID.randomUUID(), 0.0, 0.0, WhyReport.RANGE + 1);

        assertEquals(Optional.empty(),
                WhyReport.target(0, 0, 0, 0, 0, 1, List.of(distant)));
    }

    @Test
    @DisplayName("a cart behind you is not one you are looking at")
    void aCartBehindYouIsNotOneYouAreLookingAt() {
        WhyReport.Candidate behind = new WhyReport.Candidate(UUID.randomUUID(), 0.0, 0.0, -3.0);
        WhyReport.Candidate ahead = new WhyReport.Candidate(UUID.randomUUID(), 0.0, 0.0, 6.0);

        assertEquals(Optional.of(ahead.id()),
                WhyReport.target(0, 0, 0, 0, 0, 1, List.of(behind, ahead)),
                "the nearer cart is behind the sender; the one they can see is the one they mean");
    }

    @Test
    @DisplayName("no carts at all is no answer, not an arbitrary one")
    void noCartsAtAllIsNoAnswer() {
        assertEquals(Optional.empty(), WhyReport.target(0, 0, 0, 0, 0, 1, List.of()));
    }

    @Test
    @DisplayName("durations read as an admin would say them")
    void durationsReadAsAnAdminWouldSayThem() {
        assertEquals("12s", WhyReport.human(Duration.ofSeconds(12)));
        assertEquals("7m 12s", WhyReport.human(Duration.ofMinutes(7).plusSeconds(12)));
        assertEquals("1h 0m 5s", WhyReport.human(Duration.ofHours(1).plusSeconds(5)));
        assertEquals("0s", WhyReport.human(Duration.ofSeconds(-5)), "never a negative countdown");
    }
}

package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPEC §7 persistence, and §7.1 — the restart reset, without which persistence does not work. */
@DisplayName("§7 persistence")
class PersistenceTest extends EngineTest {

    private final JourneyCodec codec = new JourneyCodec();

    private JourneyRecord sampleRecord() {
        return new JourneyRecord(
                false,
                JourneyState.TRAVELLING,
                new ChunkPos(OVERWORLD, -12, 34),
                7,
                3,
                200,
                1_700_000_000_000L,
                1_699_999_000_000L,
                1,
                UUID.randomUUID(),
                Set.of(UUID.randomUUID()));
    }

    @Test
    @DisplayName("state survives a round trip")
    void stateSurvivesRoundTrip() {
        JourneyRecord original = sampleRecord();

        Optional<JourneyRecord> decoded = codec.decode(codec.encode(original));

        assertEquals(Optional.of(original), decoded);
    }

    @Test
    @DisplayName("an unknown schema version resets the cart to DORMANT")
    void unknownSchemaVersionResetsToDormant() {
        // Carts written by an old version will be sitting in unloaded chunks all over the world.
        // You cannot migrate what you cannot parse, so discard and log once.
        byte[] fromTheFuture = codec.encode(sampleRecord());
        fromTheFuture[0] = (byte) (JourneyCodec.SCHEMA_VERSION + 99);

        assertTrue(codec.decode(fromTheFuture).isEmpty());

        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0).rawState(fromTheFuture);
        engine().restore(cart);
        assertNoJourney(cart);
        assertFalse(engine().hasToken(cart.id()), "DORMANT means it needs a fresh token");
    }

    @Test
    @DisplayName("a travelling cart re-registers on chunk load")
    void travellingCartReregistersOnChunkLoad() {
        JourneyRecord record = sampleRecord();
        FakeCart cart = FakeCart.at(OVERWORLD, -12, 34).rawState(codec.encode(record));

        engine().restore(cart);

        assertEquals(JourneyState.TRAVELLING, journeyOf(cart).state());
        assertEquals(record.budgetRemaining(), journeyOf(cart).budgetRemaining());
        assertEquals(record.distanceRecord(), journeyOf(cart).distanceRecord());
    }

    @Test
    @DisplayName("a paused cart re-registers on chunk load")
    void pausedCartReregistersOnChunkLoad() {
        JourneyRecord paused = new JourneyRecord(false, JourneyState.PAUSED,
                new ChunkPos(OVERWORLD, 5, 5), 4, 2, 100,
                clock.nowMillis(), 0L, 0, UUID.randomUUID(), Set.of());
        FakeCart cart = FakeCart.at(OVERWORLD, 5, 5).rawState(codec.encode(paused));

        engine().restore(cart);

        assertEquals(JourneyState.PAUSED, journeyOf(cart).state());
        assertTrue(tickets.heldCount() > 0, "a paused journey still holds its region");
    }

    @Test
    @DisplayName("a restored journey survives long downtime — the restart bug")
    void restoredJourneySurvivesLongDowntime() {
        // lastProgressAt is wall-clock, so after any downtime now - lastProgressAt vastly exceeds
        // pause-tolerance. Without the reset, the first poll after boot terminates every restored
        // journey as ARRIVED or STALLED.
        cfg.pauseTolerance(Duration.ofSeconds(15));
        JourneyRecord record = sampleRecord();
        FakeCart cart = FakeCart.at(OVERWORLD, -12, 34).rawState(codec.encode(record));

        clock.advanceMillisOnly(Duration.ofDays(3));
        engine().restore(cart);
        poll(cart);

        assertTrue(engine().lastTermination(cart.id()).isEmpty(),
                "three days of downtime must not read as three days of standing still");
        assertEquals(JourneyState.TRAVELLING, journeyOf(cart).state());
    }

    @Test
    @DisplayName("a restored journey gets a fresh pause window")
    void restoredJourneyGetsFreshPauseWindow() {
        cfg.pauseTolerance(Duration.ofSeconds(15));
        FakeCart cart = FakeCart.at(OVERWORLD, -12, 34).rawState(codec.encode(sampleRecord()));
        clock.advanceMillisOnly(Duration.ofDays(1));
        engine().restore(cart);

        // Fourteen seconds of genuine standing still: inside the fresh window, so still alive.
        cart.velocity(0.0);
        clock.advance(Duration.ofSeconds(14));
        poll(cart);
        assertTrue(engine().lastTermination(cart.id()).isEmpty());

        // Past it: now it ends, measured from the restore rather than from before the restart.
        clock.advance(Duration.ofSeconds(2));
        poll(cart);
        assertTerminated(cart, TerminationReason.ARRIVED);
    }

    // ---- flush freshness (SPEC §7) ------------------------------------------------------------

    /** What is actually on the entity right now, which is the source of truth §7 cares about. */
    private JourneyRecord persisted(FakeCart cart) {
        return cart.readState().flatMap(codec::decode)
                .orElseThrow(() -> new AssertionError("expected persisted state on " + cart.id()));
    }

    /** A second engine over the same carts: what a restart or {@code /chunkcarts reload} does. */
    private ChunkCartsEngine restarted() {
        return new ChunkCartsEngine(config(), world, tickets, clock);
    }

    @Test
    @DisplayName("a new distance record is flushed immediately")
    void newDistanceRecordIsFlushedImmediately() {
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        travelStraight(cart, 3);

        assertEquals(3, journeyOf(cart).distanceRecord(), "precondition: the live record moved");
        assertEquals(3, persisted(cart).distanceRecord(),
                "§7 flushes on a new record; a journey restored from this must not come back at 0");
    }

    @Test
    @DisplayName("slack is flushed on a slow timer even without a new record")
    void slackIsFlushedOnSlowTimerWithoutNewRecord() {
        cfg.progressSlackChunks(64);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        travelStraight(cart, 4);

        // Shuffling between two chunks it has already reached: never a new record, so nothing here
        // is a transition. Only the timer can carry this to disk, and without it a restart hands
        // the journey back its whole slack allowance — the loop rule getting quietly weaker.
        for (int i = 0; i < 20; i++) {
            cart.atChunk(i % 2 == 0 ? 3 : 4, 0);
            tickPoll(cart);
        }

        assertEquals(4, journeyOf(cart).distanceRecord(), "precondition: no new record was set");
        assertTrue(journeyOf(cart).slackUsed() > 0, "precondition: the live journey used slack");
        assertEquals(journeyOf(cart).slackUsed(), persisted(cart).slackUsed(),
                "a journey that loses its slack on every restart hands a loop a fresh allowance");
    }

    @Test
    @DisplayName("state is not written every poll")
    void stateIsNotWrittenEveryPoll() {
        // The other half of the rule §7 states outright: the in-memory index is the per-poll one,
        // and PDC is written on transitions and a slow timer. A flush per poll would satisfy every
        // freshness test above by writing constantly, on every cart, twice a second.
        cfg.pauseTolerance(Duration.ofSeconds(30));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        int afterDeparture = cart.writeCount();

        // Standing still inside the pause tolerance: one PAUSED transition, then nothing to report.
        cart.velocity(0.0);
        int polls = 20;
        for (int i = 0; i < polls; i++) {
            tickPoll(cart);
        }

        int writes = cart.writeCount() - afterDeparture;
        assertTrue(writes < polls / 2,
                "expected transition and timer flushes, got " + writes + " writes in " + polls
                        + " polls — that is a write per poll in all but name");
    }

    @Test
    @DisplayName("a journey restored mid-flight keeps its record, slack and budget")
    void restoredMidFlightJourneyKeepsRecordSlackAndBudget() {
        // The step-4 symptom: reload a server mid-journey and /chunkcarts list showed record 0,
        // because the only flushes were at departure and at ARRIVED.
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        travelStraight(cart, 5);
        JourneySnapshot before = journeyOf(cart);

        ChunkCartsEngine afterRestart = restarted();
        afterRestart.restore(cart);
        JourneySnapshot after = afterRestart.journeyOf(cart.id())
                .orElseThrow(() -> new AssertionError("expected the journey to be restored"));

        assertEquals(before.distanceRecord(), after.distanceRecord());
        assertEquals(before.slackUsed(), after.slackUsed());
        assertEquals(before.budgetRemaining(), after.budgetRemaining());
    }

    @Test
    @DisplayName("the cooldown is not reset by a restart")
    void cooldownIsNotResetByRestart() {
        // The asymmetry with lastProgressAt: downtime legitimately counts toward a cooldown
        // elapsing, so this timestamp must persist as-is.
        cfg.journeyCooldown(Duration.ofMinutes(10));
        long startedAt = clock.nowMillis();
        JourneyRecord record = new JourneyRecord(true, JourneyState.DORMANT,
                new ChunkPos(OVERWORLD, 0, 0), 0, 0, 256,
                startedAt, 0L, 0, UUID.randomUUID(), Set.of());
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0).rawState(codec.encode(record));

        engine().restore(cart);
        clock.advanceMillisOnly(Duration.ofMinutes(2));
        grantToken(cart);
        assertDenied(depart(cart), DenialReason.COOLDOWN);

        // Eleven minutes have now elapsed in wall-clock terms, downtime included.
        clock.advanceMillisOnly(Duration.ofMinutes(9));
        assertDeparted(depart(cart));
    }

    @Test
    @DisplayName("the interaction timestamp is not reset by a restart")
    void interactionTimestampIsNotResetByRestart() {
        cfg.journeyCooldown(Duration.ofMinutes(10));
        long startedAt = clock.nowMillis();
        JourneyRecord record = new JourneyRecord(true, JourneyState.DORMANT,
                new ChunkPos(OVERWORLD, 0, 0), 0, 0, 256,
                startedAt, startedAt + 1_000, 0, UUID.randomUUID(), Set.of());
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0).rawState(codec.encode(record));

        engine().restore(cart);
        grantToken(cart);

        // The interaction recorded before the restart is later than the last journey start, so the
        // waiver survives the restart and this departs despite the cooldown.
        assertDeparted(depart(cart));
        assertNotEquals(0L, record.lastInteractionAt());
    }
}

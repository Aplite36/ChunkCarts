package io.github.aplite36.chunkcarts.core.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tick-counting scheduler the three mod loaders run on.
 *
 * <p>Two things are pinned here. One is the accounting: the gap between two runs of a task is
 * exactly its interval in server ticks, because that number is what the TPS monitor uses as the
 * expected side of its ratio. The other is that a task body cannot take the server down —
 * Bukkit's scheduler catches what a task throws, and without a catch here the same engine bug is a
 * stack trace on Paper and a crash report on Fabric, NeoForge and Forge.
 */
@DisplayName("the loader tick loop")
class TickLoopTest {

    private record Fault(String task, Throwable error, long consecutive) {}

    private record Recovery(String task, long failures) {}

    private static final class RecordingFaults implements TickLoop.Faults {
        final List<Fault> caught = new ArrayList<>();
        final List<Recovery> recovered = new ArrayList<>();

        @Override
        public void caught(String task, Throwable error, long consecutive) {
            caught.add(new Fault(task, error, consecutive));
        }

        @Override
        public void recovered(String task, long failures) {
            recovered.add(new Recovery(task, failures));
        }
    }

    private final RecordingFaults faults = new RecordingFaults();
    private final TickLoop loop = new TickLoop(faults);

    /** Advances the loop by {@code ticks} server ticks. */
    private void tick(int ticks) {
        for (int i = 0; i < ticks; i++) {
            loop.tick(TickLoop.PLAIN);
        }
    }

    @Test
    @DisplayName("a task runs once per interval, and not on the ticks between")
    void aTaskRunsOncePerIntervalAndNotOnTheTicksBetween() {
        List<Integer> runs = new ArrayList<>();
        loop.repeating("travel", 10, () -> runs.add(runs.size()));

        tick(9);
        assertEquals(0, runs.size(), "nothing is due before the interval elapses");

        tick(1);
        assertEquals(1, runs.size(), "due on the tenth tick");

        tick(20);
        assertEquals(3, runs.size(), "and every tenth tick after it — no coalescing, no skipping");
    }

    @Test
    @DisplayName("a task body that throws does not escape into the server tick")
    void aTaskBodyThatThrowsDoesNotEscapeIntoTheServerTick() {
        RuntimeException boom = new IllegalStateException("the engine threw");
        loop.repeating("travel", 1, () -> {
            throw boom;
        });

        assertDoesNotThrow(() -> tick(1),
                "on a loader this reaches the tick event, and an escaping throw is a crash report");

        assertEquals(1, faults.caught.size(), "and it is reported, not swallowed");
        assertEquals("travel", faults.caught.get(0).task(), "reported against the task that threw");
        assertSame(boom, faults.caught.get(0).error(), "with the original throwable, for the trace");
    }

    @Test
    @DisplayName("an Error is caught too, matching what Bukkit's scheduler does")
    void anErrorIsCaughtTooMatchingWhatBukkitsSchedulerDoes() {
        loop.repeating("travel", 1, () -> {
            throw new StackOverflowError("deep recursion in the engine");
        });

        assertDoesNotThrow(() -> tick(1),
                "catching Throwable rather than Exception is what makes the platforms agree");
        assertEquals(1, faults.caught.size());
    }

    @Test
    @DisplayName("a task that throws does not stop the tasks scheduled after it")
    void aTaskThatThrowsDoesNotStopTheTasksScheduledAfterIt() {
        loop.repeating("throws", 1, () -> {
            throw new IllegalStateException("boom");
        });
        List<String> ran = new ArrayList<>();
        loop.repeating("runs", 1, () -> ran.add("runs"));

        tick(1);

        assertEquals(List.of("runs"), ran,
                "one broken task must not take the rest of the mod with it");
    }

    @Test
    @DisplayName("a caught fault leaves the task's interval intact")
    void aCaughtFaultLeavesTheTasksIntervalIntact() {
        List<Integer> runs = new ArrayList<>();
        loop.repeating("travel", 5, () -> {
            runs.add(runs.size());
            throw new IllegalStateException("throws every time");
        });

        tick(20);

        assertEquals(4, runs.size(),
                "the countdown is reset before the body runs, so TPS measurement is unaffected");
        assertEquals(5, loop.intervalOf("travel"), "and the interval is untouched");
    }

    @Test
    @DisplayName("a continuing fault is reported in full a few times, then throttled")
    void aContinuingFaultIsReportedInFullAFewTimesThenThrottled() {
        loop.repeating("travel", 1, () -> {
            throw new IllegalStateException("throws every time");
        });

        tick(TickLoop.VERBOSE_FAILURES);
        assertEquals(TickLoop.VERBOSE_FAILURES, faults.caught.size(),
                "the first few failures are worth a line each");

        tick(TickLoop.REPEAT_INTERVAL - TickLoop.VERBOSE_FAILURES - 1);
        assertEquals(TickLoop.VERBOSE_FAILURES, faults.caught.size(),
                "a cart-tick bug throwing twice a second would otherwise fill the log");

        tick(1);
        assertEquals(TickLoop.VERBOSE_FAILURES + 1, faults.caught.size(),
                "but a fault that is still going gets restated, or it looks like it cleared");
        assertEquals(TickLoop.REPEAT_INTERVAL, faults.caught.get(TickLoop.VERBOSE_FAILURES).consecutive(),
                "and the line says how many failures it stands for");
    }

    @Test
    @DisplayName("a task that starts working again says so, once")
    void aTaskThatStartsWorkingAgainSaysSoOnce() {
        boolean[] failing = {true};
        loop.repeating("travel", 1, () -> {
            if (failing[0]) {
                throw new IllegalStateException("boom");
            }
        });

        tick(2);
        assertTrue(faults.recovered.isEmpty(), "still failing");

        failing[0] = false;
        tick(1);
        assertEquals(List.of(new Recovery("travel", 2)), faults.recovered,
                "silence and recovery look identical in a log unless recovery is stated");

        tick(5);
        assertEquals(1, faults.recovered.size(), "and it is stated once, not on every later tick");
    }

    @Test
    @DisplayName("the failure count restarts after a recovery")
    void theFailureCountRestartsAfterARecovery() {
        boolean[] failing = {true};
        loop.repeating("travel", 1, () -> {
            if (failing[0]) {
                throw new IllegalStateException("boom");
            }
        });

        tick(2);
        failing[0] = false;
        tick(1);
        failing[0] = true;
        tick(1);

        assertEquals(1, faults.caught.get(faults.caught.size() - 1).consecutive(),
                "a fresh fault is a fresh count, or the throttle would hide it");
    }

    @Test
    @DisplayName("work driven from the adapter's own tick handler is guarded the same way")
    void workDrivenFromTheAdaptersOwnTickHandlerIsGuardedTheSameWay() {
        // The ticket backend's per-tick sweep runs on the same event, immediately before tick(),
        // so a throw from it reaches the loader exactly as a throw from a task body would.
        boolean completed = loop.guarded("ticket-sweep", () -> {
            throw new IllegalStateException("the sweep threw");
        });

        assertEquals(false, completed, "the caller is told the body did not finish");
        assertEquals(1, faults.caught.size());
        assertEquals("ticket-sweep", faults.caught.get(0).task());

        assertTrue(loop.guarded("ticket-sweep", () -> { }), "and a working sweep reports true");
        assertEquals(List.of(new Recovery("ticket-sweep", 1)), faults.recovered);
    }

    @Test
    @DisplayName("shortening an interval takes effect on the next due tick, not a full old one later")
    void shorteningAnIntervalTakesEffectOnTheNextDueTickNotAFullOldOneLater() {
        // The velocity clamp exists for a cart already moving too fast. Waiting out the interval
        // just decided to be too long is the one thing reschedule must not do.
        List<Integer> runs = new ArrayList<>();
        loop.repeating("travel", 20, () -> runs.add(runs.size()));

        tick(1);
        loop.reschedule("travel", 2);

        tick(2);
        assertEquals(1, runs.size(), "due two ticks after the clamp, not nineteen");
    }
}

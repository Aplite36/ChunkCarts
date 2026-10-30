package io.github.aplite36.chunkcarts.core.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link Scheduler} for platforms that do not ship one: it counts server ticks and runs each
 * task when its counter reaches its interval.
 *
 * <p>The gap between two runs is exactly {@code intervalTicks} server ticks, however long those
 * ticks took. TPS is measured as expected wall-clock gap over actual, and a tick counter is
 * precisely the "expected" side of that ratio, so a lagging server shows up as a longer real gap
 * rather than as skipped runs.
 *
 * <p>Nothing here coalesces or drops a run. An adapter that quietly skipped one would be read as a
 * server running at a fraction of its speed, and the plugin's response to that is to deny
 * departures — so the honest tick count is load-bearing, not bookkeeping. It lives in {@code core}
 * for that reason: the accounting is the TPS monitor's measuring stick, which is policy, and policy
 * that cannot be unit-tested is policy nobody is holding.
 *
 * <p>{@link #reschedule} exists for one thing only: the velocity clamp, where the engine decides
 * the loop should run more often and tells its TPS monitor the same number. Nothing in this class
 * may change an interval on its own initiative.
 *
 * <h2>A task body may not take the server down</h2>
 *
 * <p>Task bodies run behind a catch. The travel loop touches entity state, chunk state and config
 * on every tick across fifteen game versions and three loaders, and treating it as code that can
 * never throw is optimistic. Bukkit's scheduler already catches what a task throws, logs it and
 * keeps the task alive; without this, the same engine bug is a stack trace on Paper and a crash
 * report on Fabric, NeoForge and Forge. Catching {@link Throwable} rather than {@link Exception} is
 * what makes the two match.
 *
 * <p>A failing task is not disabled. Going inert is not a safe resting state for this mod: the
 * travel loop is what releases held chunks, so a loop that stopped running would leave every chunk
 * it had already claimed held indefinitely, with nothing left to notice. A noisy server that keeps
 * releasing is the better failure, so repeats are throttled instead — reported in full
 * {@link #VERBOSE_FAILURES} times, then restated every {@link #REPEAT_INTERVAL} failures.
 */
public final class TickLoop implements Scheduler {

    /** Consecutive failures reported in full before a continuing fault is throttled. */
    static final int VERBOSE_FAILURES = 3;

    /**
     * Failures between restatements, once a fault has stopped being reported in full. At the
     * default ten-tick poll interval that is roughly a minute between lines.
     */
    static final int REPEAT_INTERVAL = 120;

    /**
     * Where a caught fault goes. Each adapter supplies one over its own logger; {@code core} has no
     * logging of its own and should not grow one for this.
     */
    public interface Faults {

        /**
         * A task body threw and the throw was stopped here.
         *
         * @param consecutive this failure and the unbroken run of failures before it
         */
        void caught(String task, Throwable error, long consecutive);

        /** A task that had been failing completed again. */
        void recovered(String task, long failures);
    }

    /**
     * How a task body is entered. The loaders wrap the platform profiler around it, so the task
     * name reaches {@code /debug} output and spark reports and a server owner can see what
     * ChunkCarts costs without instrumenting it.
     */
    @FunctionalInterface
    public interface Section {

        /** Runs {@code body} inside whatever the platform wants around it. */
        void around(String task, Runnable body);
    }

    /** Runs the body with nothing around it. */
    public static final Section PLAIN = (task, body) -> body.run();

    private static final class Entry {
        final String name;
        final Runnable body;
        int intervalTicks;
        int countdown;
        long consecutiveFailures;

        Entry(String name, Runnable body, int intervalTicks) {
            this.name = name;
            this.body = body;
            this.intervalTicks = intervalTicks;
            this.countdown = intervalTicks;
        }
    }

    private final Faults faults;
    private final Map<String, Entry> tasks = new LinkedHashMap<>();

    /** Failure counts for bodies run through {@link #guarded} rather than scheduled here. */
    private final Map<String, Long> unscheduledFailures = new LinkedHashMap<>();

    public TickLoop(Faults faults) {
        this.faults = Objects.requireNonNull(faults, "faults");
    }

    @Override
    public void repeating(String name, int intervalTicks, Runnable task) {
        tasks.put(name, new Entry(name, task, Math.max(1, intervalTicks)));
    }

    /** The interval a named task is currently running at, or 0 if there is no such task. */
    public int intervalOf(String name) {
        Entry entry = tasks.get(name);
        return entry == null ? 0 : entry.intervalTicks;
    }

    /**
     * Changes a task's interval, keeping its body.
     *
     * <p>The countdown is clamped rather than restarted, so shortening the interval takes effect on
     * the next due tick instead of a full old interval later. That matters because the clamp exists
     * for a cart that is already moving too fast — waiting out the interval we just decided was too
     * long is the one thing this must not do.
     */
    public void reschedule(String name, int intervalTicks) {
        Entry entry = tasks.get(name);
        if (entry == null || entry.intervalTicks == intervalTicks) {
            return;
        }
        entry.intervalTicks = Math.max(1, intervalTicks);
        entry.countdown = Math.min(entry.countdown, entry.intervalTicks);
    }

    public void cancelAll() {
        tasks.clear();
        unscheduledFailures.clear();
    }

    /**
     * One server tick: runs whichever tasks are due, each inside {@code section}.
     *
     * <p>A task that throws does not stop the tasks after it, and does not disturb its own
     * accounting — the countdown is reset before the body runs, so a caught fault leaves the
     * interval intact and TPS measurement unaffected.
     */
    public void tick(Section section) {
        // Snapshot: a task body may reschedule itself, and the velocity clamp does exactly that.
        for (Entry entry : List.copyOf(tasks.values())) {
            if (--entry.countdown > 0) {
                continue;
            }
            entry.countdown = entry.intervalTicks;
            try {
                section.around(entry.name, entry.body);
            } catch (Throwable error) {
                report(entry.name, error, ++entry.consecutiveFailures);
                continue;
            }
            if (entry.consecutiveFailures > 0) {
                faults.recovered(entry.name, entry.consecutiveFailures);
                entry.consecutiveFailures = 0;
            }
        }
    }

    /**
     * Runs one body behind the same catch, for work an adapter drives from its own tick handler
     * rather than scheduling here. The ticket backend's per-tick sweep is the only such caller, and
     * it is on the same event as {@link #tick} with the same consequence for throwing.
     *
     * @return whether the body completed
     */
    public boolean guarded(String task, Runnable body) {
        try {
            body.run();
        } catch (Throwable error) {
            report(task, error, unscheduledFailures.merge(task, 1L, Long::sum));
            return false;
        }
        Long failures = unscheduledFailures.remove(task);
        if (failures != null) {
            faults.recovered(task, failures);
        }
        return true;
    }

    /** This failure and the unbroken run before it, for a task that is currently failing. */
    long consecutiveFailures(String task) {
        Entry entry = tasks.get(task);
        return entry != null ? entry.consecutiveFailures : unscheduledFailures.getOrDefault(task, 0L);
    }

    private void report(String task, Throwable error, long consecutive) {
        if (consecutive <= VERBOSE_FAILURES || consecutive % REPEAT_INTERVAL == 0) {
            faults.caught(task, error, consecutive);
        }
    }
}

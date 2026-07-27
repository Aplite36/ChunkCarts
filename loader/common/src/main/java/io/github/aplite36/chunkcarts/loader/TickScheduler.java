package io.github.aplite36.chunkcarts.loader;

import io.github.aplite36.chunkcarts.core.platform.Scheduler;
import net.minecraft.server.MinecraftServer;
//? if >=1.21.3 {
import net.minecraft.util.profiling.Profiler;
//?}
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schedules the travel loop by counting server ticks.
 *
 * <p>There is no scheduler in vanilla, so this is one: {@link #tick()} is driven from
 * {@code END_SERVER_TICK} and runs each task when its counter reaches its interval. That is closer
 * to the metal than Bukkit's scheduler and has one consequence worth stating — the gap between two
 * runs is exactly {@code intervalTicks} server ticks, however long those ticks took. TPS is
 * measured as expected wall-clock gap over actual, and a tick counter is precisely the "expected"
 * side of that ratio, so a lagging server shows up as a longer real gap rather than as skipped runs.
 *
 * <p>Nothing here coalesces or drops a run. An adapter that quietly skipped one would be read as a
 * server running at a fraction of its speed, and the plugin's response to that is to deny
 * departures — so the honest tick count is load-bearing, not bookkeeping.
 *
 * <p>{@link #reschedule} exists for one thing only: the velocity clamp, where the engine decides
 * the loop should run more often and tells its TPS monitor the same number. Nothing in this class
 * may change an interval on its own initiative.
 */
public final class TickScheduler {

    private final Map<String, Entry> tasks = new LinkedHashMap<>();

    private static final class Entry {
        final String name;
        final Runnable body;
        int intervalTicks;
        int countdown;

        Entry(String name, Runnable body, int intervalTicks) {
            this.name = name;
            this.body = body;
            this.intervalTicks = intervalTicks;
            this.countdown = intervalTicks;
        }
    }

    /** The {@link Scheduler} view handed to {@code core}. */
    public Scheduler asScheduler() {
        return this::repeating;
    }

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
    }

    /**
     * One server tick. Task bodies run inside a named profiler section: on this platform the name
     * reaches {@code /debug} output and spark reports, so a server owner can see what ChunkCarts
     * costs without instrumenting it.
     */
    public void tick(MinecraftServer server) {
        // Snapshot: a task body may reschedule itself, and the velocity clamp does exactly that.
        for (Entry entry : List.copyOf(tasks.values())) {
            if (--entry.countdown > 0) {
                continue;
            }
            entry.countdown = entry.intervalTicks;
            // The thread-local Profiler holder arrived in 1.21.3; before that the profiler is the
            // server's, which is why this takes one.
            //? if >=1.21.3 {
            ProfilerFiller profiler = Profiler.get();
            //?} else
            /*ProfilerFiller profiler = server.getProfiler();*/
            profiler.push(entry.name);
            try {
                entry.body.run();
            } finally {
                profiler.pop();
            }
        }
    }
}

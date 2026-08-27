package io.github.aplite36.chunkcarts.loader;

import io.github.aplite36.chunkcarts.core.platform.Scheduler;
import io.github.aplite36.chunkcarts.core.platform.TickLoop;
import net.minecraft.server.MinecraftServer;
//? if >=1.21.3 {
import net.minecraft.util.profiling.Profiler;
//?}
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

/**
 * Schedules the travel loop by counting server ticks.
 *
 * <p>There is no scheduler in vanilla, so this is one. The counting, the intervals and the catch
 * around a task body all live in {@link TickLoop} over in {@code core}, where they can be tested;
 * what is left here is the half that needs Minecraft, which is the profiler section each task runs
 * inside. On this platform the section name reaches {@code /debug} output and spark reports, so a
 * server owner can see what ChunkCarts costs without instrumenting it.
 *
 * <p>Read {@link TickLoop} for why the tick count is load-bearing rather than bookkeeping, and for
 * why a task that throws is caught and kept rather than disabled.
 */
public final class TickScheduler {

    private final TickLoop loop;

    public TickScheduler(Logger log) {
        this.loop = new TickLoop(new LoggedFaults(log));
    }

    /** The {@link Scheduler} view handed to {@code core}. */
    public Scheduler asScheduler() {
        return loop;
    }

    public void repeating(String name, int intervalTicks, Runnable task) {
        loop.repeating(name, intervalTicks, task);
    }

    /** The interval a named task is currently running at, or 0 if there is no such task. */
    public int intervalOf(String name) {
        return loop.intervalOf(name);
    }

    /** Changes a task's interval, keeping its body. For the velocity clamp and nothing else. */
    public void reschedule(String name, int intervalTicks) {
        loop.reschedule(name, intervalTicks);
    }

    public void cancelAll() {
        loop.cancelAll();
    }

    /**
     * Runs one body behind the same catch as a scheduled task, for work an adapter drives from its
     * own tick handler. The ticket backend's per-tick sweep is the only caller: it runs on the same
     * event, immediately before {@link #tick}, and a throw from it reaches the loader's tick event
     * exactly as a throw from a task body would.
     */
    public boolean guarded(String task, Runnable body) {
        return loop.guarded(task, body);
    }

    /** One server tick. */
    public void tick(MinecraftServer server) {
        loop.tick((name, body) -> {
            // The thread-local Profiler holder arrived in 1.21.3; before that the profiler is the
            // server's, which is why this takes one.
            //? if >=1.21.3 {
            ProfilerFiller profiler = Profiler.get();
            //?} else
            /*ProfilerFiller profiler = server.getProfiler();*/
            profiler.push(name);
            try {
                body.run();
            } finally {
                profiler.pop();
            }
        });
    }

    /** {@link TickLoop.Faults} over the adapter's logger. */
    private record LoggedFaults(Logger log) implements TickLoop.Faults {

        @Override
        public void caught(String task, Throwable error, long consecutive) {
            log.error("ChunkCarts task '{}' threw; the throw was stopped here and the server keeps "
                    + "ticking. Consecutive failures: {}.", task, consecutive, error);
        }

        @Override
        public void recovered(String task, long failures) {
            log.warn("ChunkCarts task '{}' is running again after {} consecutive failure(s).",
                    task, failures);
        }
    }
}

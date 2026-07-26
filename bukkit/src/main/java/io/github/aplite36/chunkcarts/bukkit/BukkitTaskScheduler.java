package io.github.aplite36.chunkcarts.bukkit;

import io.github.aplite36.chunkcarts.core.platform.Scheduler;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * Schedules the travel loop (SPEC §1.1).
 *
 * <p>The interval is also the measuring stick for derived TPS (SPEC §4.2): the expected gap
 * between runs is {@code intervalTicks × 50ms}, and {@code core} reads the shortfall as load. That
 * only holds while the task actually runs on the interval it was given, which is why nothing here
 * reschedules or coalesces runs — a "helpful" adapter that skipped a run would be reported as a
 * lagging server and would start denying departures.
 */
final class BukkitTaskScheduler implements Scheduler {

    private final Plugin plugin;
    private final List<BukkitTask> tasks = new ArrayList<>();

    BukkitTaskScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Bukkit has no task-naming API, so {@code name} only reaches profilers indirectly, through
     * the plugin the task is registered against. The loaders can honour it properly.
     */
    @Override
    public void repeating(String name, int intervalTicks, Runnable task) {
        tasks.add(plugin.getServer().getScheduler()
                .runTaskTimer(plugin, task, intervalTicks, intervalTicks));
    }

    void cancelAll() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
    }
}

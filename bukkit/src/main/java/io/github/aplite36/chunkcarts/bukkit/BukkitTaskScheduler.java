package io.github.aplite36.chunkcarts.bukkit;

import io.github.aplite36.chunkcarts.core.platform.Scheduler;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Schedules the travel loop.
 *
 * <p>The interval is also the measuring stick for derived TPS: the expected gap between
 * runs is {@code intervalTicks × 50ms}, and {@code core} reads the shortfall as load. That only
 * holds while the task runs on the interval {@code core} believes it is running on, which is why
 * nothing here coalesces or skips a run — a "helpful" adapter that dropped one would be reported as
 * a lagging server and would start denying departures.
 *
 * <p>{@link #reschedule} exists for one thing only: the velocity clamp, where the engine itself
 * decides the loop should run more often and tells its TPS monitor the same number. Nothing in this
 * class may change an interval on its own initiative.
 */
final class BukkitTaskScheduler implements Scheduler {

    private final Plugin plugin;
    private final Map<String, Entry> tasks = new LinkedHashMap<>();

    private record Entry(BukkitTask task, Runnable body, int intervalTicks) {}

    BukkitTaskScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Bukkit has no task-naming API, so {@code name} only reaches profilers indirectly, through
     * the plugin the task is registered against. The loaders can honour it properly.
     */
    @Override
    public void repeating(String name, int intervalTicks, Runnable task) {
        Entry existing = tasks.remove(name);
        if (existing != null) {
            existing.task().cancel();
        }
        tasks.put(name, new Entry(plugin.getServer().getScheduler()
                .runTaskTimer(plugin, task, intervalTicks, intervalTicks), task, intervalTicks));
    }

    /** The interval a named task is currently running at, or 0 if there is no such task. */
    int intervalOf(String name) {
        Entry entry = tasks.get(name);
        return entry == null ? 0 : entry.intervalTicks();
    }

    /**
     * Re-registers a task at a new interval, keeping its body. Safe to call from inside the task
     * itself: Bukkit lets a repeating task cancel itself, and the replacement's first run is a full
     * interval away, so the gap the engine measures next is the one it just asked for.
     */
    void reschedule(String name, int intervalTicks) {
        Entry entry = tasks.get(name);
        if (entry == null || entry.intervalTicks() == intervalTicks) {
            return;
        }
        repeating(name, intervalTicks, entry.body());
    }

    void cancelAll() {
        tasks.values().forEach(entry -> entry.task().cancel());
        tasks.clear();
    }
}

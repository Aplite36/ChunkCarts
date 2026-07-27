package io.github.aplite36.chunkcarts.core.platform;

/**
 * Schedules the travel loop.
 *
 * <p>The interval is also the measuring stick for derived TPS: the expected wall-clock gap
 * between runs is {@code intervalTicks * 50ms}, and the ratio of expected to actual gives the
 * load ratio. That only holds if the platform actually honours the interval.
 */
public interface Scheduler {

    /**
     * @param name a human-readable task name — it shows up in profilers, so name it properly
     */
    void repeating(String name, int intervalTicks, Runnable task);
}

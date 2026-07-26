package io.github.aplite36.chunkcarts.bukkit;

import io.github.aplite36.chunkcarts.core.platform.Clock;

/**
 * The real clock. Two sources, deliberately (SPEC §1.1): wall-clock millis for anything that must
 * survive a restart, and monotonic nanos for scheduler drift, which must not jump when someone
 * adjusts the system clock.
 */
final class SystemClock implements Clock {

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }
}

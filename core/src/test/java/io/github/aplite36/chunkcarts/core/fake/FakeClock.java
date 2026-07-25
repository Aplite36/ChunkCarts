package io.github.aplite36.chunkcarts.core.fake;

import io.github.aplite36.chunkcarts.core.platform.Clock;

import java.time.Duration;

/**
 * A clock the test drives by hand. Every time-dependent rule in the spec is tested through this
 * — no test may sleep, and no production path may read the system clock.
 *
 * <p>Wall-clock and monotonic time advance together by default, which is the honest common case.
 * {@link #advanceNanosOnly} and {@link #advanceMillisOnly} exist for the two places where they
 * must diverge: scheduler drift with no wall-clock movement, and a restart, where wall-clock
 * jumps forward while the monotonic source resets.
 */
public final class FakeClock implements Clock {

    private long millis;
    private long nanos;

    public FakeClock() {
        this(1_700_000_000_000L);
    }

    public FakeClock(long startMillis) {
        this.millis = startMillis;
        this.nanos = 0L;
    }

    @Override
    public long nowMillis() {
        return millis;
    }

    @Override
    public long nanoTime() {
        return nanos;
    }

    public FakeClock advance(Duration d) {
        millis += d.toMillis();
        nanos += d.toNanos();
        return this;
    }

    public FakeClock advanceTicks(int ticks) {
        return advance(Duration.ofMillis(50L * ticks));
    }

    /** Monotonic time moves, wall-clock does not. For drift measurement in isolation. */
    public FakeClock advanceNanosOnly(Duration d) {
        nanos += d.toNanos();
        return this;
    }

    /** Wall-clock jumps, monotonic does not. Models downtime across a restart. */
    public FakeClock advanceMillisOnly(Duration d) {
        millis += d.toMillis();
        return this;
    }
}

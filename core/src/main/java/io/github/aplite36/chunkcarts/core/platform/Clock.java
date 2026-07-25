package io.github.aplite36.chunkcarts.core.platform;

/**
 * All time enters {@code core} through here. Never call {@code System.currentTimeMillis()} or
 * {@code System.nanoTime()} directly in policy code — every time-dependent rule has to be
 * testable without sleeping.
 *
 * <p>Two clocks, deliberately, because the spec needs both and they are not interchangeable:
 * wall-clock millis for anything that must survive a restart (§4.1 cooldown, §3.4 pause
 * tolerance), and a monotonic nano source for scheduler drift (§4.2), which must not jump when
 * the system clock is adjusted.
 */
public interface Clock {

    /** Epoch millis. The unit for every persisted or restart-surviving timestamp. */
    long nowMillis();

    /** Monotonic nanoseconds. Only meaningful as a difference between two samples. */
    long nanoTime();
}

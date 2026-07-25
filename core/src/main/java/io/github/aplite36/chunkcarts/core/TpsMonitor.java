package io.github.aplite36.chunkcarts.core;

/**
 * TPS derived from scheduler drift (SPEC §4.2), not from a platform API.
 *
 * <p>The poll task is scheduled every {@code poll-interval-ticks}, so the expected wall-clock gap
 * between runs is {@code interval × 50ms}. Sampling the monotonic clock between consecutive runs
 * gives {@code expected / actual} as a load ratio, which is one implementation that is correct on
 * every platform — including Folia, where there is no global TPS to ask for and this measures the
 * region that actually matters.
 *
 * <p>Three details, all of which exist because the raw ratio is unusable on its own: an EMA so a
 * single GC pause does not block departures, hysteresis so a server sitting at the threshold does
 * not flap, and a startup grace so the first minute after boot — world load, chunk gen, other
 * plugins initialising — does not deny every departure.
 */
final class TpsMonitor {

    static final double VANILLA_TPS = 20.0;
    static final long MILLIS_PER_TICK = 50L;

    /** Samples in the EMA window, and equally the startup grace: no verdict until it is full. */
    static final int WINDOW = 20;

    private static final double ALPHA = 2.0 / (WINDOW + 1);

    /** Resume only above {@code min-tps + this}, or the log fills with transitions. */
    static final double HYSTERESIS = 1.0;

    private final double minTps;

    private long lastSampleNanos = Long.MIN_VALUE;
    private int samples;
    private double ema = VANILLA_TPS;
    private boolean blocked;

    TpsMonitor(double minTps) {
        this.minTps = minTps;
    }

    /**
     * Records one poll. The first call only establishes a baseline — a drift measurement needs two
     * points, and there is no honest reading to give before the second one.
     */
    void sample(long nanoTime, int pollIntervalTicks) {
        long previous = lastSampleNanos;
        lastSampleNanos = nanoTime;
        if (previous == Long.MIN_VALUE) {
            return;
        }
        long actual = nanoTime - previous;
        if (actual <= 0) {
            return;
        }
        long expected = pollIntervalTicks * MILLIS_PER_TICK * 1_000_000L;
        // A poll that arrives early does not mean the server is running above 20 TPS.
        double reading = Math.min(VANILLA_TPS, VANILLA_TPS * (double) expected / actual);
        ema = samples == 0 ? reading : ema + ALPHA * (reading - ema);
        samples++;
        updateGate();
    }

    /** Whether departures are currently allowed. Transitions happen here, not per denial. */
    boolean allowsDeparture() {
        return !blocked;
    }

    double effectiveTps() {
        return ema;
    }

    boolean windowFull() {
        return samples >= WINDOW;
    }

    private void updateGate() {
        if (!windowFull()) {
            blocked = false;
        } else if (blocked) {
            blocked = ema < minTps + HYSTERESIS;
        } else {
            blocked = ema < minTps;
        }
    }
}

package io.github.aplite36.chunkcarts.metrics;

/**
 * Remembers the last thing the config said about {@code metrics}, so that a moment when nothing can
 * answer is not mistaken for the owner saying no.
 *
 * <p>This exists because of how bStats treats a false: {@code MetricsBase} calls
 * {@code scheduler.shutdown()} the first time its enabled check fails, and a
 * {@code ScheduledExecutorService} cannot be restarted. Reporting is started once per server
 * lifetime, so one spurious false ends it until the server restarts. That is exactly right when the
 * owner has opted out and exactly wrong for anything transient.
 *
 * <p>The transient case is real: {@code /chunkcarts reload} runs the mod's teardown and rebuild,
 * and between the two there is no engine to ask. The window is short against a thirty-minute submit
 * interval, but it is a silent, permanent failure when it lands.
 *
 * <p>Read from the bStats daemon thread and written from the server thread, hence the volatile.
 */
public final class ReportingSwitch {

    /**
     * True until told otherwise, because the first submit can land before the engine has finished
     * starting. Defaulting to false would move the same bug to a different window rather than fix
     * it — and the owner has not opted out of anything at that point.
     */
    private volatile boolean lastKnown = true;

    /** Records what the config currently says and returns it. */
    public boolean observe(boolean wanted) {
        lastKnown = wanted;
        return wanted;
    }

    /**
     * The answer to use when there is no engine to ask.
     *
     * <p>Deliberately not false. Nothing about a teardown-and-rebuild says the owner changed their
     * mind, and treating it as though it did is unrecoverable.
     */
    public boolean lastKnown() {
        return lastKnown;
    }
}

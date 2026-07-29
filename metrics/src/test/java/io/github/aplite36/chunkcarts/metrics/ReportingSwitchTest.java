package io.github.aplite36.chunkcarts.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule this class exists for: a momentary gap must never be reported as an opt-out.
 *
 * <p>bStats shuts its scheduler down the first time the enabled check returns false, and a
 * {@code ScheduledExecutorService} cannot be restarted — so a single spurious false ends reporting
 * for the lifetime of the server. {@code /chunkcarts reload} tears the engine down and rebuilds it,
 * and a submit landing in that window used to see no engine and read it as "the owner said no".
 */
class ReportingSwitchTest {

    @Test
    void reportsWantedBeforeAnythingHasBeenObserved() {
        // A submit can land before the engine finishes starting. Defaulting to false there would
        // kill reporting permanently on a slow start, which is the same bug in a different window.
        assertTrue(new ReportingSwitch().lastKnown());
    }

    @Test
    void observingPassesTheValueStraightThrough() {
        ReportingSwitch reporting = new ReportingSwitch();
        assertTrue(reporting.observe(true));
        assertFalse(reporting.observe(false));
    }

    @Test
    void aGapAfterOptingInStaysOptedIn() {
        ReportingSwitch reporting = new ReportingSwitch();
        reporting.observe(true);
        assertTrue(reporting.lastKnown(), "a reload window must not read as an opt-out");
    }

    @Test
    void aGapAfterOptingOutStaysOptedOut() {
        // The opt-out has to survive the gap too, or a reload would quietly resume reporting for
        // an owner who turned it off.
        ReportingSwitch reporting = new ReportingSwitch();
        reporting.observe(false);
        assertFalse(reporting.lastKnown());
    }

    @Test
    void theLatestObservationWins() {
        ReportingSwitch reporting = new ReportingSwitch();
        reporting.observe(true);
        reporting.observe(false);
        assertFalse(reporting.lastKnown());
        reporting.observe(true);
        assertTrue(reporting.lastKnown());
    }
}

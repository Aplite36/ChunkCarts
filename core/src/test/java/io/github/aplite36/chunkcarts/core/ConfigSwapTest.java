package io.github.aplite36.chunkcarts.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code /chunkcarts reload} does when the config on disk is unusable.
 *
 * <p>The order is the whole subject. Tearing the runtime down before finding out whether a
 * replacement can be built means one mistyped key leaves the mod dead until the server is
 * restarted — with no path back to the configuration that was working a moment earlier.
 */
@DisplayName("reloading the config")
class ConfigSwapTest {

    private final List<String> steps = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    private ConfigSwap.Prepared good() {
        steps.add("read");
        return new ConfigSwap.Prepared(Config.defaults().build(), List.of());
    }

    @Test
    @DisplayName("a good config is read first, and only then is the old runtime torn down")
    void aGoodConfigIsReadFirstAndOnlyThenIsTheOldRuntimeTornDown() {
        ConfigSwap.Outcome outcome = ConfigSwap.swap(
                this::good,
                () -> steps.add("teardown"),
                prepared -> steps.add("rebuild"),
                (message, error) -> failures.add(message));

        assertEquals(ConfigSwap.Outcome.RELOADED, outcome);
        assertEquals(List.of("read", "teardown", "rebuild"), steps,
                "reading last would mean the mod is already down when the file turns out to be bad");
        assertTrue(failures.isEmpty());
    }

    @Test
    @DisplayName("a config that cannot be read leaves the running one alone")
    void aConfigThatCannotBeReadLeavesTheRunningOneAlone() {
        ConfigSwap.Outcome outcome = ConfigSwap.swap(
                () -> {
                    steps.add("read");
                    throw new ClassCastException("class java.lang.Boolean cannot be cast to class "
                            + "java.lang.String");
                },
                () -> steps.add("teardown"),
                prepared -> steps.add("rebuild"),
                (message, error) -> failures.add(message));

        assertEquals(ConfigSwap.Outcome.KEPT, outcome,
                "the admin mistyped a key; they expect to be told, not to lose the mod");
        assertEquals(List.of("read"), steps,
                "nothing is torn down, so every ticket the old runtime holds stays held by it");
        assertEquals(1, failures.size(), "and it is reported, because a silent no-op reads as success");
        assertTrue(failures.get(0).contains("still running"),
                "the message has to say the old settings survived, or the admin restarts for nothing");
    }

    @Test
    @DisplayName("the failure is reported with the throwable that caused it")
    void theFailureIsReportedWithTheThrowableThatCausedIt() {
        RuntimeException cause = new IllegalArgumentException("negative duration: '-5m'");
        Throwable[] seen = {null};

        ConfigSwap.swap(
                () -> {
                    throw cause;
                },
                () -> steps.add("teardown"),
                prepared -> steps.add("rebuild"),
                (message, error) -> seen[0] = error);

        assertSame(cause, seen[0], "the reason has to reach the console or nothing can be fixed");
    }

    @Test
    @DisplayName("a rebuild that fails after teardown is named, not hidden")
    void aRebuildThatFailsAfterTeardownIsNamedNotHidden() {
        // Only reading the config can be rehearsed; constructing a runtime against a live server
        // cannot. So this case cannot be prevented — it can only be reported honestly.
        ConfigSwap.Outcome outcome = ConfigSwap.swap(
                this::good,
                () -> steps.add("teardown"),
                prepared -> {
                    throw new IllegalStateException("no such world");
                },
                (message, error) -> failures.add(message));

        assertEquals(ConfigSwap.Outcome.BROKEN, outcome,
                "distinct from KEPT: here the mod really is down and the admin has to know");
        assertEquals(List.of("read", "teardown"), steps);
        assertTrue(failures.get(0).contains("NOT running"));
    }

    @Test
    @DisplayName("preparing validates the config rather than only parsing it")
    void preparingValidatesTheConfigRatherThanOnlyParsingIt() {
        // resolve() is where the clamps and floors run. Doing it here, while the old runtime is
        // still up, is what makes the ordering worth anything.
        ConfigSwap.Prepared prepared = ConfigSwap.prepare(Map.of(
                "enforcement", "PERMISSIVE",
                "region-radius", 1));

        assertNotNull(prepared.config());
        assertEquals(EnforcementMode.PERMISSIVE, prepared.config().enforcement());
    }

    @Test
    @DisplayName("preparing carries the reader's warnings back for the caller to show")
    void preparingCarriesTheReadersWarningsBackForTheCallerToShow() {
        ConfigSwap.Prepared prepared = ConfigSwap.prepare(Map.of("progres-slack-chunks", 8));

        assertFalse(prepared.warnings().isEmpty(),
                "an unrecognised key is the reader's business, and the caller has to be able to say so");
    }
}

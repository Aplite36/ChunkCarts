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
        ConfigSwap.Result outcome = ConfigSwap.swap(
                this::good,
                () -> steps.add("teardown"),
                prepared -> { steps.add("rebuild"); return List.of(); },
                (message, error) -> failures.add(message));

        assertEquals(ConfigSwap.Outcome.RELOADED, outcome.outcome());
        assertEquals(List.of("read", "teardown", "rebuild"), steps,
                "reading last would mean the mod is already down when the file turns out to be bad");
        assertTrue(failures.isEmpty());
    }

    @Test
    @DisplayName("a config that cannot be read leaves the running one alone")
    void aConfigThatCannotBeReadLeavesTheRunningOneAlone() {
        ConfigSwap.Result outcome = ConfigSwap.swap(
                () -> {
                    steps.add("read");
                    throw new ClassCastException("class java.lang.Boolean cannot be cast to class "
                            + "java.lang.String");
                },
                () -> steps.add("teardown"),
                prepared -> { steps.add("rebuild"); return List.of(); },
                (message, error) -> failures.add(message));

        assertEquals(ConfigSwap.Outcome.KEPT, outcome.outcome(),
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
                prepared -> { steps.add("rebuild"); return List.of(); },
                (message, error) -> seen[0] = error);

        assertSame(cause, seen[0], "the reason has to reach the console or nothing can be fixed");
    }

    @Test
    @DisplayName("a rebuild that fails after teardown is named, not hidden")
    void aRebuildThatFailsAfterTeardownIsNamedNotHidden() {
        // Only reading the config can be rehearsed; constructing a runtime against a live server
        // cannot. So this case cannot be prevented — it can only be reported honestly.
        ConfigSwap.Result outcome = ConfigSwap.swap(
                this::good,
                () -> steps.add("teardown"),
                prepared -> {
                    throw new IllegalStateException("no such world");
                },
                (message, error) -> failures.add(message));

        assertEquals(ConfigSwap.Outcome.BROKEN, outcome.outcome(),
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

    @Test
    @DisplayName("a reload carries back both the reader's warnings and the engine's clamps")
    void aReloadCarriesBackBothTheReadersWarningsAndTheEnginesClamps() {
        // Two sources, and reload could reach neither. ConfigReader.Result.warnings() was consumed
        // inside start() and never stored; startupWarnings() does not exist until the engine has
        // been constructed, which happens inside the rebuild.
        ConfigSwap.Result result = ConfigSwap.swap(
                () -> new ConfigSwap.Prepared(Config.defaults().build(),
                        List.of("Unrecognised config key 'progres-slack-chunks'; ignoring.")),
                () -> { },
                prepared -> List.of("region-radius is 5, above the STRICT maximum of 2. Clamped."),
                (message, error) -> failures.add(message));

        assertEquals(2, result.warnings().size(), "parse warnings first, then the clamps");
        assertTrue(result.warnings().get(0).contains("progres-slack-chunks"));
        assertTrue(result.warnings().get(1).contains("region-radius"));
    }

    @Test
    @DisplayName("nothing to report is an empty report, not an empty line")
    void nothingToReportIsAnEmptyReportNotAnEmptyLine() {
        assertEquals(List.of(), ConfigSwap.report(List.of()),
                "the caller says so on the success line instead; silence and success otherwise "
                        + "look identical, which is half of what this is for");
    }

    @Test
    @DisplayName("warnings are indented under the count")
    void warningsAreIndentedUnderTheCount() {
        assertEquals(
                List.of("  min-speed is 0.0, below the STRICT minimum of 0.01. Raised."),
                ConfigSwap.report(List.of("min-speed is 0.0, below the STRICT minimum of 0.01. Raised.")));
    }

    @Test
    @DisplayName("a spew of warnings is capped, and says how many it did not show")
    void aSpewOfWarningsIsCappedAndSaysHowManyItDidNotShow() {
        // A config full of mistyped keys would otherwise fill a chat window. The console copy is
        // complete either way, so the tail points there rather than truncating silently.
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < ConfigSwap.WARNINGS_SHOWN + 5; i++) {
            many.add("Unrecognised config key 'typo" + i + "'; ignoring.");
        }

        List<String> lines = ConfigSwap.report(many);

        assertEquals(ConfigSwap.WARNINGS_SHOWN + 1, lines.size());
        assertTrue(lines.get(lines.size() - 1).contains("and 5 more"), lines.toString());
        assertTrue(lines.get(lines.size() - 1).contains("console"),
                "a cap that does not say where the rest went is just truncation");
    }

    @Test
    @DisplayName("exactly the cap is shown without a tail")
    void exactlyTheCapIsShownWithoutATail() {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < ConfigSwap.WARNINGS_SHOWN; i++) {
            many.add("warning " + i);
        }

        List<String> lines = ConfigSwap.report(many);

        assertEquals(ConfigSwap.WARNINGS_SHOWN, lines.size(), "an off-by-one here reads as a lost warning");
    }
}

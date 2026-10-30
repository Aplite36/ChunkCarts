package io.github.aplite36.chunkcarts.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config schema. The parser lives in {@code core} so it is tested once here rather than
 * four times over in adapters that would quietly diverge.
 *
 * <p>The through-line of this file: <strong>a broken key costs you that key, not the plugin.</strong>
 * A server that fails to load ChunkCarts because of one typo is a server running with no chunk
 * safety at all, which is the outcome this whole project exists to avoid.
 */
@DisplayName("Config reader")
class ConfigReaderTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Config read(Map<String, Object> yaml) {
        return ConfigReader.read(yaml).builder().build();
    }

    /**
     * A map with keys of any type, cast the way {@code ConfigFile.load} casts a parsed document.
     * That unchecked cast is the whole reason a non-string key reaches this class at all.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> raw(Object... kv) {
        Map<Object, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return (Map<String, Object>) (Map<?, ?>) m;
    }

    @Test
    @DisplayName("an empty file yields the shipped defaults")
    void emptyFileYieldsDefaults() {
        // Someone who deletes the file, or comments the whole thing out, gets the safe settings
        // rather than a null-riddled config.
        Config config = read(map());

        assertEquals(Config.defaults().build(), config);
        assertTrue(ConfigReader.read(map()).warnings().isEmpty());
    }

    @Test
    @DisplayName("every scalar key is read")
    void everyScalarKeyIsRead() {
        Config config = read(map(
                "config-version", 1,
                "enforcement", "PERMISSIVE",
                "departure-requirement", "PLAYER_ONLINE",
                "journey-cooldown", "4m",
                "journey-budget-chunks", 300,
                "progress-slack-chunks", 20,
                "region-radius", 2,
                "max-concurrent-journeys", 8,
                "poll-interval-ticks", 5,
                "min-speed", 0.2,
                "min-tps", 15.5,
                "pause-tolerance", "20s",
                "arrival-hold-ticks", 40,
                "unload-hold", "90s",
                "metrics", false));

        assertEquals(EnforcementMode.PERMISSIVE, config.enforcement());
        assertEquals(DepartureRequirement.PLAYER_ONLINE, config.departureRequirement());
        assertEquals(Duration.ofMinutes(4), config.journeyCooldown());
        assertEquals(300, config.journeyBudgetChunks());
        assertEquals(20, config.progressSlackChunks());
        assertEquals(2, config.regionRadius());
        assertEquals(8, config.maxConcurrentJourneys());
        assertEquals(5, config.pollIntervalTicks());
        assertEquals(0.2, config.minSpeed());
        assertEquals(15.5, config.minTps());
        assertEquals(Duration.ofSeconds(20), config.pauseTolerance());
        assertEquals(40, config.arrivalHoldTicks());
        assertEquals(Duration.ofSeconds(90), config.unloadHold());
        assertFalse(config.metrics());
    }

    @Test
    @DisplayName("the cart-types section selects types, and omitted types stay off")
    void cartTypesSectionSelectsTypes() {
        Config config = read(map("cart-types", map(
                "normal", true,
                "chest", false,
                "hopper", true,
                "furnace", false)));

        assertTrue(config.allowedCartTypes().contains(CartType.NORMAL));
        assertTrue(config.allowedCartTypes().contains(CartType.HOPPER));
        assertFalse(config.allowedCartTypes().contains(CartType.CHEST));
        assertFalse(config.allowedCartTypes().contains(CartType.FURNACE));
    }

    @Test
    @DisplayName("a malformed cart-types value disables the type and says so, not 'using the default'")
    void malformedCartTypeValueIsDisabledNotDefaulted() {
        ConfigReader.Result result = ConfigReader.read(map("cart-types", map(
                "hopper", "true")));
        Config config = result.builder().build();

        assertFalse(config.allowedCartTypes().contains(CartType.HOPPER));
        assertTrue(result.warnings().contains(
                "cart-types.hopper must be true or false; the cart type is disabled."));
    }

    @Test
    @DisplayName("tnt and command_block set to true are refused, not obeyed")
    void forbiddenCartTypesAreRefused() {
        // The file is allowed to say this; the plugin is not allowed to believe it. Gate 2 checks
        // NEVER_PERMITTED too, so this is belt and braces on the one setting where a mistake means
        // primed TNT arriving in unattended terrain.
        ConfigReader.Result result = ConfigReader.read(map("cart-types", map(
                "tnt", true,
                "command_block", true)));
        Config config = result.builder().build().resolve().config();

        assertFalse(config.isCartTypeAllowed(CartType.TNT));
        assertFalse(config.isCartTypeAllowed(CartType.COMMAND_BLOCK));
    }

    @Test
    @DisplayName("the worlds section reads mode and list")
    void worldsSectionReadsModeAndList() {
        Config config = read(map("worlds", map(
                "mode", "ALLOWLIST",
                "list", List.of("world", "world_nether"))));

        assertEquals(WorldsMode.ALLOWLIST, config.worldsMode());
        assertTrue(config.isWorldEnabled(new WorldId("world")));
        assertFalse(config.isWorldEnabled(new WorldId("creative")));
    }

    @Test
    @DisplayName("the stranded section reads both flags")
    void strandedSectionReadsBothFlags() {
        Config config = read(map("stranded", map(
                "log", false,
                "notify-last-interactor", true)));

        assertFalse(config.strandedLog());
        assertTrue(config.notifyLastInteractor());
    }

    @Test
    @DisplayName("durations accept h, m, s and a bare number of seconds")
    void durationsAcceptSuffixes() {
        assertEquals(Duration.ofHours(2), ConfigReader.parseDuration("2h"));
        assertEquals(Duration.ofMinutes(10), ConfigReader.parseDuration("10m"));
        assertEquals(Duration.ofSeconds(15), ConfigReader.parseDuration("15s"));
        assertEquals(Duration.ZERO, ConfigReader.parseDuration("0s"));
        assertEquals(Duration.ofSeconds(45), ConfigReader.parseDuration("45"));
        assertEquals(Duration.ofSeconds(15), ConfigReader.parseDuration(" 15S "));
    }

    @Test
    @DisplayName("an unparseable duration warns and keeps the default")
    void unparseableDurationWarnsAndKeepsDefault() {
        ConfigReader.Result result = ConfigReader.read(map("pause-tolerance", "fifteen seconds"));

        assertEquals(Config.defaults().build().pauseTolerance(),
                result.builder().build().pauseTolerance());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("pause-tolerance")),
                "the warning has to name the key, or nobody can find what to fix");
        assertThrows(IllegalArgumentException.class, () -> ConfigReader.parseDuration("soon"));
    }

    @Test
    @DisplayName("an unknown enum value warns and keeps the default")
    void unknownEnumValueWarnsAndKeepsDefault() {
        // Falling back to STRICT is the only safe direction here: a typo in `enforcement` must
        // never be the thing that switches the invariants off.
        ConfigReader.Result result = ConfigReader.read(map("enforcement", "LENIENT"));

        assertEquals(EnforcementMode.STRICT, result.builder().build().enforcement());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("enforcement")));
    }

    @Test
    @DisplayName("a value of the wrong type warns and keeps the default")
    void wrongTypeWarnsAndKeepsDefault() {
        ConfigReader.Result result = ConfigReader.read(map(
                "journey-budget-chunks", "lots",
                "metrics", "yes please"));

        Config config = result.builder().build();
        assertEquals(256, config.journeyBudgetChunks());
        assertTrue(config.metrics());
        assertEquals(2, result.warnings().size(), "one warning per unusable key");
    }

    @Test
    @DisplayName("a whole number too large for an int warns and keeps the default")
    void outOfRangeIntegerWarnsAndKeepsDefault() {
        // Every YAML parser hands back a Long for an integer this size, and Number.intValue()
        // wraps rather than failing: 3000000000 lands as -1294967296. resolve() only clamps this
        // key from above, so a negative budget reaches the engine and kills every journey on its
        // first poll — the admin asked for a very large budget and got no travel at all.
        ConfigReader.Result result = ConfigReader.read(map("journey-budget-chunks", 3_000_000_000L));

        assertEquals(Config.defaults().build().journeyBudgetChunks(),
                result.builder().build().journeyBudgetChunks(),
                "an unusable value keeps the default; it is never wrapped into a different one");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("journey-budget-chunks")),
                "and it has to say so, or the budget silently becomes its own opposite");
    }

    @Test
    @DisplayName("a key YAML reads as a boolean or a number warns instead of throwing")
    void nonStringKeyWarnsInsteadOfThrowing() {
        // The YAML 1.1 resolver applies to keys as well as values, so a bare `off:` key is a
        // Boolean and `1:` is an Integer before this class ever sees them. Iterating those as
        // String threw out of read(), which on the mod loaders happens during server startup.
        Map<String, Object> yaml = raw(
                Boolean.FALSE, "surprise",
                42, "surprise",
                "region-radius", 2,
                "cart-types", raw(Boolean.TRUE, "surprise", "normal", true));

        ConfigReader.Result result = assertDoesNotThrow(() -> ConfigReader.read(yaml));

        assertEquals(2, result.builder().build().regionRadius(),
                "the keys that were readable are still read");
        assertTrue(result.builder().build().allowedCartTypes().contains(CartType.NORMAL));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("'false'")),
                "the bad key is reported the way it looks in the file");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("'42'")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("cart-types.true")),
                "a section's keys are no safer than the top level's");
    }

    @Test
    @DisplayName("a duration too large to be measured warns and keeps the default")
    void overlargeDurationWarnsAndKeepsDefault() {
        // Duration.ofHours succeeds far past what toMillis() can represent, and every setting
        // here is read through toMillis() at use — journey-cooldown from inside the departure
        // gate, on every attempt. Left to construct, it throws there instead of warning here.
        assertThrows(IllegalArgumentException.class,
                () -> ConfigReader.parseDuration("999999999999999h"));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigReader.parseDuration("99999999999999999"));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigReader.parseDuration(Long.MAX_VALUE + "h"),
                "the largest values overflow inside ofHours itself, before toMillis is reached");

        ConfigReader.Result result =
                ConfigReader.read(map("journey-cooldown", "999999999999999h"));

        assertEquals(Config.defaults().build().journeyCooldown(),
                result.builder().build().journeyCooldown());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("journey-cooldown")));
        assertDoesNotThrow(() -> result.builder().build().journeyCooldown().toMillis(),
                "whatever survives this reader has to be usable where it is used");
    }

    @Test
    @DisplayName("a config-version from the future warns rather than guessing")
    void futureConfigVersionWarns() {
        ConfigReader.Result result = ConfigReader.read(map("config-version", 99));

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("config-version")),
                "a file written by a newer build may mean keys by the same name differently");
    }

    @Test
    @DisplayName("an unrecognised key warns rather than being silently ignored")
    void unrecognisedKeyWarns() {
        // Almost always a typo or a key from an old draft. Silence here means someone edits a
        // setting that does nothing and concludes the plugin ignores its own config.
        ConfigReader.Result result = ConfigReader.read(map("progres-slack-chunks", 40));

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("progres-slack-chunks")));
    }
}

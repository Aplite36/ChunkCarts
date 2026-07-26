package io.github.aplite36.chunkcarts.core;

import java.util.List;
import java.util.Map;

/**
 * Turns the parsed contents of {@code config.yml} into a {@link Config.Builder} (SPEC §6).
 *
 * <p>Lives in {@code core} rather than in each adapter so the schema is unit-tested once and the
 * three loader adapters cannot drift from the Bukkit one. It takes a plain nested {@code Map},
 * which is what every YAML library produces, so {@code core} keeps its zero-dependency rule and
 * the platform only has to hand over the tree it already parsed.
 *
 * <p><strong>A bad value is a warning, never a failure.</strong> One mistyped key must not stop
 * the plugin loading — the alternative is a server that boots with no ChunkCarts at all and a
 * stack trace nobody reads, which is strictly worse than one key falling back to its default.
 * Clamping is a separate concern and happens later, in {@link Config#resolve()}.
 */
public final class ConfigReader {

    /** The schema version this build understands. Bumped only when a key is renamed or removed. */
    public static final int SCHEMA_VERSION = 1;

    private ConfigReader() {
    }

    /**
     * @param builder  the shipped defaults with every recognised key applied over the top
     * @param warnings one line per key that could not be used, in file order
     */
    public record Result(Config.Builder builder, List<String> warnings) {}

    public static Result read(Map<String, Object> yaml) {
        throw new UnsupportedOperationException("step 4");
    }

    /**
     * Parses {@code 10m}, {@code 15s}, {@code 1h}, or a bare number of seconds.
     *
     * @throws IllegalArgumentException if the text is not a duration
     */
    public static java.time.Duration parseDuration(String text) {
        throw new UnsupportedOperationException("step 4");
    }
}

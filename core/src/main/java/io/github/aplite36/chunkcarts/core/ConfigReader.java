package io.github.aplite36.chunkcarts.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Turns the parsed contents of {@code config.yml} into a {@link Config.Builder}.
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

    private static final String CART_TYPES = "cart-types";
    private static final String WORLDS = "worlds";
    private static final String STRANDED = "stranded";

    /** Every key the reader consumes, so anything else in the file can be reported as unknown. */
    private static final Set<String> KNOWN = Set.of(
            "config-version", "enforcement", "departure-requirement", "journey-cooldown",
            "journey-budget-chunks", "progress-slack-chunks", "region-radius",
            "max-concurrent-journeys", "poll-interval-ticks", "min-speed", "min-tps",
            "pause-tolerance", "arrival-hold-ticks", "unload-hold", "metrics",
            CART_TYPES, WORLDS, STRANDED);

    /** The config key for each cart type, or null for one that has no key at all. */
    private static final Map<CartType, String> CART_TYPE_KEYS = Map.of(
            CartType.NORMAL, "normal",
            CartType.CHEST, "chest",
            CartType.HOPPER, "hopper",
            CartType.FURNACE, "furnace",
            CartType.TNT, "tnt",
            CartType.COMMAND_BLOCK, "command_block");

    private ConfigReader() {
    }

    /**
     * @param builder  the shipped defaults with every recognised key applied over the top
     * @param warnings one line per key that could not be used, in file order
     */
    public record Result(Config.Builder builder, List<String> warnings) {}

    public static Result read(Map<String, Object> yaml) {
        Config.Builder builder = Config.defaults();
        List<String> warnings = new ArrayList<>();
        Reader r = new Reader(yaml, warnings);

        r.integer("config-version", version -> {
            if (version > SCHEMA_VERSION) {
                warnings.add("config-version " + version + " is newer than this build understands ("
                        + SCHEMA_VERSION + "). Reading it anyway; keys may have changed meaning.");
            }
        });

        r.enumValue("enforcement", EnforcementMode.class, builder::enforcement);
        r.enumValue("departure-requirement", DepartureRequirement.class, builder::departureRequirement);
        r.duration("journey-cooldown", builder::journeyCooldown);
        r.integer("journey-budget-chunks", builder::journeyBudgetChunks);
        r.integer("progress-slack-chunks", builder::progressSlackChunks);
        r.integer("region-radius", builder::regionRadius);
        r.integer("max-concurrent-journeys", builder::maxConcurrentJourneys);
        r.integer("poll-interval-ticks", builder::pollIntervalTicks);
        r.decimal("min-speed", builder::minSpeed);
        r.decimal("min-tps", builder::minTps);
        r.duration("pause-tolerance", builder::pauseTolerance);
        r.integer("arrival-hold-ticks", builder::arrivalHoldTicks);
        r.duration("unload-hold", builder::unloadHold);
        r.bool("metrics", builder::metrics);

        readCartTypes(r.section(CART_TYPES), warnings, builder);
        readWorlds(r.section(WORLDS), warnings, builder);
        readStranded(r.section(STRANDED), warnings, builder);

        for (String key : yaml.keySet()) {
            if (!KNOWN.contains(key)) {
                warnings.add("Unrecognised config key '" + key + "'; ignoring. Check the spelling "
                        + "against config.yml — a setting that does nothing is worse than an error.");
            }
        }
        return new Result(builder, List.copyOf(warnings));
    }

    private static void readCartTypes(Map<String, Object> section, List<String> warnings,
                                      Config.Builder builder) {
        if (section == null) {
            return;
        }
        Reader r = new Reader(section, warnings, CART_TYPES + ".");
        Set<CartType> allowed = EnumSet.noneOf(CartType.class);
        // Absent means off: an admin who lists three types and omits furnace meant three types.
        // The defaults only apply when the whole section is missing, which is the case above.
        CART_TYPE_KEYS.forEach((type, key) -> r.bool(key, on -> {
            if (on) {
                allowed.add(type);
            }
        }));
        builder.allowedCartTypes(allowed);
        warnUnknown(section, CART_TYPE_KEYS.values(), CART_TYPES, warnings);
    }

    private static void readWorlds(Map<String, Object> section, List<String> warnings,
                                   Config.Builder builder) {
        if (section == null) {
            return;
        }
        Reader r = new Reader(section, warnings, WORLDS + ".");
        WorldsMode[] mode = {null};
        r.enumValue("mode", WorldsMode.class, m -> mode[0] = m);
        Set<String> list = new LinkedHashSet<>();
        Object raw = section.get("list");
        if (raw instanceof List<?> entries) {
            for (Object entry : entries) {
                list.add(String.valueOf(entry));
            }
        } else if (raw != null) {
            warnings.add("worlds.list must be a list of world names; ignoring.");
        }
        builder.worlds(mode[0] != null ? mode[0] : WorldsMode.BLOCKLIST, list);
        warnUnknown(section, List.of("mode", "list"), WORLDS, warnings);
    }

    private static void readStranded(Map<String, Object> section, List<String> warnings,
                                     Config.Builder builder) {
        if (section == null) {
            return;
        }
        Reader r = new Reader(section, warnings, STRANDED + ".");
        r.bool("log", builder::strandedLog);
        r.bool("notify-last-interactor", builder::notifyLastInteractor);
        warnUnknown(section, List.of("log", "notify-last-interactor"), STRANDED, warnings);
    }

    private static void warnUnknown(Map<String, Object> section, Iterable<String> known,
                                    String path, List<String> warnings) {
        Set<String> recognised = new LinkedHashSet<>();
        known.forEach(recognised::add);
        for (String key : section.keySet()) {
            if (!recognised.contains(key)) {
                warnings.add("Unrecognised config key '" + path + "." + key + "'; ignoring.");
            }
        }
    }

    /**
     * Parses {@code 10m}, {@code 15s}, {@code 1h}, or a bare number of seconds.
     *
     * @throws IllegalArgumentException if the text is not a duration
     */
    public static Duration parseDuration(String text) {
        String value = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("empty duration");
        }
        char unit = value.charAt(value.length() - 1);
        String digits = Character.isDigit(unit) ? value : value.substring(0, value.length() - 1);
        long amount;
        try {
            amount = Long.parseLong(digits.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a duration: '" + text + "'", e);
        }
        if (amount < 0) {
            throw new IllegalArgumentException("negative duration: '" + text + "'");
        }
        return switch (unit) {
            case 'h' -> Duration.ofHours(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 's' -> Duration.ofSeconds(amount);
            default -> Character.isDigit(unit)
                    ? Duration.ofSeconds(amount)   // a bare number means seconds
                    : throwUnit(text, unit);
        };
    }

    private static Duration throwUnit(String text, char unit) {
        throw new IllegalArgumentException(
                "unknown duration unit '" + unit + "' in '" + text + "'; use h, m or s");
    }

    /**
     * Pulls typed values out of one map level, warning by key path instead of throwing.
     *
     * <p>Every accessor is "call the consumer only if the value is present and usable", which is
     * what keeps the defaults intact for anything unreadable without a null check at each site.
     */
    private record Reader(Map<String, Object> map, List<String> warnings, String path) {

        Reader(Map<String, Object> map, List<String> warnings) {
            this(map, warnings, "");
        }

        void integer(String key, Consumer<Integer> sink) {
            number(key, "a whole number", n -> sink.accept(n.intValue()));
        }

        void decimal(String key, Consumer<Double> sink) {
            number(key, "a number", n -> sink.accept(n.doubleValue()));
        }

        private void number(String key, String expected, Consumer<Number> sink) {
            Object raw = map.get(key);
            if (raw == null) {
                return;
            }
            if (raw instanceof Number n) {
                sink.accept(n);
            } else {
                warn(key, expected);
            }
        }

        void bool(String key, Consumer<Boolean> sink) {
            Object raw = map.get(key);
            if (raw == null) {
                return;
            }
            // Deliberately not accepting "yes"/"on": YAML already has a boolean type, and being
            // lenient here means a value the file's own parser read as a string silently works
            // on one platform and not another.
            if (raw instanceof Boolean b) {
                sink.accept(b);
            } else {
                warn(key, "true or false");
            }
        }

        void duration(String key, Consumer<Duration> sink) {
            Object raw = map.get(key);
            if (raw == null) {
                return;
            }
            try {
                sink.accept(parseDuration(String.valueOf(raw)));
            } catch (IllegalArgumentException e) {
                warn(key, "a duration like 15s, 10m or 1h");
            }
        }

        <E extends Enum<E>> void enumValue(String key, Class<E> type, Consumer<E> sink) {
            Object raw = map.get(key);
            if (raw == null) {
                return;
            }
            try {
                sink.accept(Enum.valueOf(type, String.valueOf(raw).trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                warn(key, "one of " + String.join(", ", names(type)));
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> section(String key) {
            Object raw = map.get(key);
            if (raw == null) {
                return null;
            }
            if (raw instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
            warn(key, "a section");
            return null;
        }

        private static <E extends Enum<E>> List<String> names(Class<E> type) {
            return java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
        }

        private void warn(String key, String expected) {
            warnings.add(path + key + " must be " + expected + "; using the default instead.");
        }
    }
}

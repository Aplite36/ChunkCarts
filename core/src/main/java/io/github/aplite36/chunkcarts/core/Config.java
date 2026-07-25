package io.github.aplite36.chunkcarts.core;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The parsed config (SPEC §6). Lives in {@code core} so the clamp table is unit-tested rather
 * than trusted.
 *
 * <p>This type is plain data and its builder really works — tests need to construct inputs.
 * The <em>policy</em> is {@link #resolve()}, which applies the §3.5 clamps and floors, and that
 * is unimplemented until step 3.
 */
public record Config(
        EnforcementMode enforcement,
        DepartureRequirement departureRequirement,
        Duration journeyCooldown,
        int journeyBudgetChunks,
        int progressSlackChunks,
        int regionRadius,
        int maxConcurrentJourneys,
        int pollIntervalTicks,
        double minSpeed,
        double minTps,
        Duration pauseTolerance,
        int arrivalHoldTicks,
        Duration unloadHold,
        Set<CartType> allowedCartTypes,
        WorldsMode worldsMode,
        Set<String> worldList,
        boolean strandedLog,
        boolean notifyLastInteractor,
        boolean metrics) {

    // ---- SPEC §3.5 ceilings in STRICT. Named so tests assert against the table, not magic numbers.
    public static final int STRICT_MAX_PROGRESS_SLACK_CHUNKS = 64;
    public static final int STRICT_MAX_JOURNEY_BUDGET_CHUNKS = 1024;
    public static final int STRICT_MAX_REGION_RADIUS = 2;
    public static final int STRICT_MAX_CONCURRENT_JOURNEYS = 64;
    public static final Duration STRICT_MAX_PAUSE_TOLERANCE = Duration.ofSeconds(30);
    public static final int STRICT_MAX_ARRIVAL_HOLD_TICKS = 200;
    public static final int STRICT_MAX_POLL_INTERVAL_TICKS = 20;

    // ---- SPEC §3.5 floors. The unusual direction; both close specific back doors.
    public static final double STRICT_MIN_SPEED_FLOOR = 0.01;
    public static final Duration STRICT_MIN_COOLDOWN_UNDER_NONE = Duration.ofSeconds(60);

    /** Cart types that can never be enabled, whatever the config says (SPEC §4 gate 2). */
    public static final Set<CartType> NEVER_PERMITTED =
            Set.of(CartType.TNT, CartType.COMMAND_BLOCK, CartType.SPAWNER);

    /** The shipped defaults, matching the annotated {@code config.yml}. */
    public static Builder defaults() {
        return new Builder();
    }

    /**
     * Applies the SPEC §3.5 clamps and floors and reports what was changed.
     *
     * <p>Clamping matters because without it permissive behaviour exists implicitly via extreme
     * thresholds and the enforcement flag becomes theatre.
     */
    public Resolution resolve() {
        throw new UnsupportedOperationException("step 3");
    }

    /**
     * @param config   the config after clamping
     * @param warnings human-readable warnings, one per adjustment or refusal, in emission order
     */
    public record Resolution(Config config, List<String> warnings) {}

    /** Mutable builder seeded with the shipped defaults. */
    public static final class Builder {
        private EnforcementMode enforcement = EnforcementMode.STRICT;
        private DepartureRequirement departureRequirement = DepartureRequirement.PLAYER_SEEN;
        private Duration journeyCooldown = Duration.ofMinutes(10);
        private int journeyBudgetChunks = 256;
        private int progressSlackChunks = 16;
        private int regionRadius = 1;
        private int maxConcurrentJourneys = 16;
        private int pollIntervalTicks = 10;
        private double minSpeed = 0.05;
        private double minTps = 18.0;
        private Duration pauseTolerance = Duration.ofSeconds(15);
        private int arrivalHoldTicks = 60;
        private Duration unloadHold = Duration.ZERO;
        private Set<CartType> allowedCartTypes =
                EnumSet.of(CartType.NORMAL, CartType.CHEST, CartType.HOPPER, CartType.FURNACE);
        private WorldsMode worldsMode = WorldsMode.BLOCKLIST;
        private Set<String> worldList = Set.of();
        private boolean strandedLog = true;
        private boolean notifyLastInteractor = false;
        private boolean metrics = true;

        public Builder enforcement(EnforcementMode v) { this.enforcement = v; return this; }
        public Builder departureRequirement(DepartureRequirement v) { this.departureRequirement = v; return this; }
        public Builder journeyCooldown(Duration v) { this.journeyCooldown = v; return this; }
        public Builder journeyBudgetChunks(int v) { this.journeyBudgetChunks = v; return this; }
        public Builder progressSlackChunks(int v) { this.progressSlackChunks = v; return this; }
        public Builder regionRadius(int v) { this.regionRadius = v; return this; }
        public Builder maxConcurrentJourneys(int v) { this.maxConcurrentJourneys = v; return this; }
        public Builder pollIntervalTicks(int v) { this.pollIntervalTicks = v; return this; }
        public Builder minSpeed(double v) { this.minSpeed = v; return this; }
        public Builder minTps(double v) { this.minTps = v; return this; }
        public Builder pauseTolerance(Duration v) { this.pauseTolerance = v; return this; }
        public Builder arrivalHoldTicks(int v) { this.arrivalHoldTicks = v; return this; }
        public Builder unloadHold(Duration v) { this.unloadHold = v; return this; }
        public Builder allowedCartTypes(Set<CartType> v) { this.allowedCartTypes = v; return this; }
        public Builder worlds(WorldsMode mode, Set<String> list) {
            this.worldsMode = mode;
            this.worldList = list;
            return this;
        }
        public Builder strandedLog(boolean v) { this.strandedLog = v; return this; }
        public Builder notifyLastInteractor(boolean v) { this.notifyLastInteractor = v; return this; }
        public Builder metrics(boolean v) { this.metrics = v; return this; }

        public Config build() {
            return new Config(enforcement, departureRequirement, journeyCooldown,
                    journeyBudgetChunks, progressSlackChunks, regionRadius, maxConcurrentJourneys,
                    pollIntervalTicks, minSpeed, minTps, pauseTolerance, arrivalHoldTicks,
                    unloadHold, Set.copyOf(allowedCartTypes), worldsMode, Set.copyOf(worldList),
                    strandedLog, notifyLastInteractor, metrics);
        }
    }
}

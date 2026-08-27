package io.github.aplite36.chunkcarts.metrics;

import io.github.aplite36.chunkcarts.metrics.bstats.MetricsBase;
import io.github.aplite36.chunkcarts.metrics.bstats.charts.SimplePie;
import io.github.aplite36.chunkcarts.metrics.bstats.config.MetricsConfig;
import io.github.aplite36.chunkcarts.metrics.bstats.json.JsonObjectBuilder;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Anonymous usage reporting to bStats, identical on all four platforms.
 *
 * <p>What is sent is decided here and nowhere else, in three places: {@link #appendPlatformData}
 * and {@link #appendServiceData} produce the payload's own fields, and {@link #customCharts} adds
 * the custom-chart values on top. The README's Metrics section promises a specific field set, and
 * {@code ChunkCartsMetricsTest} pins all three paths against {@link #DISCLOSED_PLATFORM_FIELDS} and
 * the custom-chart ids, so adding a field or a chart means changing the README and the test in the
 * same commit.
 *
 * <p>Two opt-outs, both honoured. The server owner's global bStats switch lives in bStats' own
 * config file and is read into {@link BStatsSettings#enabled()}; ChunkCarts' own {@code metrics}
 * key is {@link MetricsPlatform#reportingWanted()} and is re-read on every submit, so
 * {@code /chunkcarts reload} takes effect without a restart. Removing either would get the project
 * banned from bStats, and rightly.
 */
public final class ChunkCartsMetrics {

    /** The registered bStats service id for ChunkCarts. */
    public static final int SERVICE_ID = 32934;

    /**
     * bStats has no Fabric, NeoForge or Forge module — the request for one has been open since
     * 2019 — so a mod reports under the Bukkit platform and fills in the Bukkit field names. This
     * is the established convention rather than a workaround of ours; C2ME and others do the same.
     * The fields stay honest: a Fabric server reports itself as Fabric in {@code bukkitName}, which
     * is what makes the server-software breakdown worth having across four platforms at once.
     */
    private static final String BSTATS_PLATFORM = "bukkit";

    private final MetricsBase base;

    private ChunkCartsMetrics(MetricsBase base) {
        this.base = base;
    }

    /**
     * Reads bStats' own config file, creating it with a generated server UUID on first run.
     *
     * <p>For the platforms that are not Bukkit. {@code bStatsDir} is conventionally a {@code bStats}
     * directory beside the config directory, so the UUID is shared with every other mod that
     * follows the convention; a private UUID would make bStats count one server several times.
     *
     * @throws IOException if the file can neither be read nor created — the caller should treat
     *                     that as "no metrics" and carry on, never as a startup failure
     */
    public static BStatsSettings settingsFromFile(File bStatsDir) throws IOException {
        MetricsConfig config = new MetricsConfig(new File(bStatsDir, "config.txt"), true);
        return new BStatsSettings(
                config.getServerUUID(),
                config.isEnabled(),
                config.isLogErrorsEnabled(),
                config.isLogSentDataEnabled(),
                config.isLogResponseStatusTextEnabled(),
                !config.didExistBefore());
    }

    /**
     * Starts reporting, or starts nothing but returns a usable handle if either opt-out is set.
     *
     * <p>Call once per server lifetime, not once per {@code /chunkcarts reload}: each call starts a
     * daemon thread on a submit schedule, so rebuilding the runtime around it would stack up
     * reporters and multiply the traffic. The reload path is covered by
     * {@link MetricsPlatform#reportingWanted()} being polled rather than captured.
     */
    public static ChunkCartsMetrics start(BStatsSettings settings, MetricsPlatform platform) {
        if (settings.firstRun()) {
            platform.info("ChunkCarts reports anonymous usage statistics to bStats "
                    + "(https://bstats.org). Nothing about players, worlds or addresses is sent. "
                    + "Opt out with `metrics: false` in the ChunkCarts config, or globally in "
                    + "bStats' own config file.");
        }

        MetricsBase base = new MetricsBase(
                BSTATS_PLATFORM,
                settings.serverUuid(),
                SERVICE_ID,
                settings.enabled(),
                builder -> appendPlatformData(builder, platform),
                builder -> appendServiceData(builder, platform),
                platform::submitOnServerThread,
                platform::reportingWanted,
                platform::warn,
                platform::info,
                settings.logErrors(),
                settings.logSentData(),
                settings.logResponseStatusText(),
                // The relocation check, left switched on. It passes because these classes are
                // vendored into our own package rather than resolved from org.bstats — which is
                // precisely the condition it is checking for, so it stays meaningful.
                false);

        customCharts(platform).forEach(base::addCustomChart);
        return new ChunkCartsMetrics(base);
    }

    /**
     * The custom-chart values: the third path into the payload, alongside {@link
     * #appendPlatformData} and {@link #appendServiceData}. Built as a list rather than called
     * straight into {@code addCustomChart} so {@code ChunkCartsMetricsTest} can pin the chart ids
     * the same way it pins the other two paths' field names.
     *
     * <p>{@code platform} duplicates {@code bukkitName} rather than adding new information: bStats'
     * own Server Software chart only recognises Bukkit-family names, so every Fabric, NeoForge and
     * Forge server renders as "Unknown" there. This chart is ours, reads all four platforms, and is
     * the one to look at instead.
     */
    static List<SimplePie> customCharts(MetricsPlatform platform) {
        return List.of(
                new SimplePie("enforcement", platform::enforcement),
                new SimplePie("departure_requirement", platform::departureRequirement),
                new SimplePie("platform", platform::serverSoftware));
    }

    /** Stops the submit schedule. Safe to call whether or not reporting ever started. */
    public void shutdown() {
        base.shutdown();
    }

    /**
     * The exact keys {@link #appendPlatformData} writes, matching the "Metrics" section of the
     * README field for field. {@code ChunkCartsMetricsTest} asserts the payload against this
     * constant instead of a copy of the list, so a field can only be added here by also adding it
     * there and to the README.
     */
    static final Set<String> DISCLOSED_PLATFORM_FIELDS = Set.of(
            "playerAmount", "onlineMode", "bukkitVersion", "bukkitName",
            "javaVersion", "osName", "osArch", "osVersion", "coreCount");

    /**
     * The per-server fields, under the names the Bukkit platform uses.
     *
     * <p>{@code bukkitVersion} is assembled as {@code <software> (MC: <version>)} rather than sent
     * as a bare version string, because that is the shape Bukkit's own {@code getVersion()} returns
     * and therefore the shape bStats' game-version chart is built to read. Sending {@code 1.21.11}
     * on its own would be truthful and might still chart correctly, but there is no way to confirm
     * that from outside bStats, and matching the established format costs nothing.
     *
     * <p>The rest ({@code onlineMode} through {@code coreCount}) are the ordinary bStats
     * environment fields every plugin on the server already reports: online-mode flag, Java
     * version, host OS name/architecture/version, and CPU core count. None of it is player data,
     * world data, an address or a coordinate.
     */
    static void appendPlatformData(JsonObjectBuilder builder, MetricsPlatform platform) {
        builder.appendField("playerAmount", platform.playerCount());
        builder.appendField("onlineMode", platform.onlineMode() ? 1 : 0);
        builder.appendField("bukkitVersion",
                platform.serverSoftware() + " (MC: " + platform.minecraftVersion() + ")");
        builder.appendField("bukkitName", platform.serverSoftware());
        builder.appendField("javaVersion", System.getProperty("java.version"));
        builder.appendField("osName", System.getProperty("os.name"));
        builder.appendField("osArch", System.getProperty("os.arch"));
        builder.appendField("osVersion", System.getProperty("os.version"));
        builder.appendField("coreCount", Runtime.getRuntime().availableProcessors());
    }

    /** The per-mod fields. */
    static void appendServiceData(JsonObjectBuilder builder, MetricsPlatform platform) {
        builder.appendField("pluginVersion", platform.modVersion());
    }
}

package io.github.aplite36.chunkcarts.bukkit;

import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import io.github.aplite36.chunkcarts.metrics.BStatsSettings;
import io.github.aplite36.chunkcarts.metrics.MetricsPlatform;
import io.github.aplite36.chunkcarts.metrics.ReportingSwitch;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Answers the metrics reporter's questions using the Bukkit API, and reads the server owner's
 * bStats settings from the file every other plugin on the server uses.
 *
 * <p>That file is {@code plugins/bStats/config.yml} and it is deliberately not ChunkCarts'
 * own — the {@code serverUuid} in it is shared, and inventing a private one would make bStats count
 * this server once for ChunkCarts and once for everything else installed.
 */
final class BukkitMetricsPlatform implements MetricsPlatform {

    private final ChunkCartsPlugin plugin;
    private final ReportingSwitch reporting = new ReportingSwitch();

    BukkitMetricsPlatform(ChunkCartsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Reads {@code plugins/bStats/config.yml}, creating it on first run.
     *
     * <p>Written out by hand rather than delegated to the vendored {@code MetricsConfig}, which
     * reads a different file in a different format: {@code key=value} text with a {@code server-uuid}
     * key, against this file's YAML with a {@code serverUuid} key. Pointing one at the other would
     * silently generate a second UUID on every boot.
     */
    static BStatsSettings readSettings(File pluginsDir) {
        File configFile = new File(new File(pluginsDir, "bStats"), "config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        boolean firstRun = !config.isSet("serverUuid");
        if (firstRun) {
            config.addDefault("enabled", true);
            config.addDefault("serverUuid", UUID.randomUUID().toString());
            config.addDefault("logFailedRequests", false);
            config.addDefault("logSentData", false);
            config.addDefault("logResponseStatusText", false);
            config.options().copyDefaults(true);
            try {
                config.save(configFile);
            } catch (IOException ignored) {
                // A read-only plugins directory is not a reason to fail startup. The UUID above is
                // still used for this run; it just will not persist.
            }
        }

        return new BStatsSettings(
                config.getString("serverUuid"),
                config.getBoolean("enabled", true),
                config.getBoolean("logFailedRequests", false),
                config.getBoolean("logSentData", false),
                config.getBoolean("logResponseStatusText", false),
                firstRun);
    }

    @Override
    public String serverSoftware() {
        return Bukkit.getName();
    }

    @Override
    public String minecraftVersion() {
        return Bukkit.getMinecraftVersion();
    }

    @Override
    public int playerCount() {
        return Bukkit.getOnlinePlayers().size();
    }

    @Override
    public boolean onlineMode() {
        return Bukkit.getOnlineMode();
    }

    @Override
    public String modVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public String enforcement() {
        ChunkCartsEngine engine = plugin.engine();
        return engine == null ? "unknown" : engine.config().enforcement().name();
    }

    @Override
    public String departureRequirement() {
        ChunkCartsEngine engine = plugin.engine();
        return engine == null ? "unknown" : engine.config().departureRequirement().name();
    }

    /**
     * The {@code metrics} key as it stands right now, so {@code /chunkcarts reload} takes effect.
     *
     * <p>Two different kinds of "no" meet here and only one of them is real. A disabled plugin is a
     * genuine stop, and returning false shuts the reporter down, which is what should happen. A null
     * engine is the moment a reload spends between teardown and rebuild — nobody has opted out of
     * anything, so the last known answer stands. See {@link ReportingSwitch} for why the difference
     * is unrecoverable rather than cosmetic.
     */
    @Override
    public boolean reportingWanted() {
        if (!plugin.isEnabled()) {
            return false;
        }
        ChunkCartsEngine engine = plugin.engine();
        return engine == null
                ? reporting.lastKnown()
                : reporting.observe(engine.config().metrics());
    }

    @Override
    public void submitOnServerThread(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void warn(String message, Throwable error) {
        plugin.getLogger().log(Level.WARNING, message, error);
    }

    @Override
    public void info(String message) {
        plugin.getLogger().info(message);
    }
}

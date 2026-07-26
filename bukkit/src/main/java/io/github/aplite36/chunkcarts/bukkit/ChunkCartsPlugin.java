package io.github.aplite36.chunkcarts.bukkit;

import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import io.github.aplite36.chunkcarts.core.Config;
import io.github.aplite36.chunkcarts.core.ConfigReader;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Minecart;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point and wiring (SPEC §12 step 4). Owns nothing policy-shaped: every rule lives in
 * {@code core}, and this class exists to hand it a clock, a world, a ticket backend and a list of
 * carts.
 *
 * <p>The runtime is rebuilt wholesale on {@code /chunkcarts reload} rather than mutated, because
 * a half-reloaded config — new clamps applied to journeys started under the old ones — is a state
 * nothing is tested against. Rebuilding releases every ticket and re-reads each journey from cart
 * NBT, which is the source of truth anyway (SPEC §7).
 */
public final class ChunkCartsPlugin extends JavaPlugin {

    /** Plugins that also hold chunks for minecarts. Both cannot be right (SPEC §8). */
    private static final List<String> RIVALS = List.of(
            "CartLoad", "CartLoader", "CartLoaders", "MinecartsLoadChunks",
            "ChunkEnforcer", "KeepChunks");

    private NamespacedKey stateKey;
    private ChunkCartsEngine engine;
    private CartTracker tracker;
    private PluginTicketBackend tickets;
    private BukkitTaskScheduler scheduler;
    private ChunkCartsListener listener;
    private boolean debug;

    @Override
    public void onEnable() {
        stateKey = new NamespacedKey(this, "journey");
        saveDefaultConfig();
        warnAboutRivals();
        start();

        ChunkCartsCommand command = new ChunkCartsCommand(this);
        var registered = getCommand("chunkcarts");
        if (registered != null) {
            registered.setExecutor(command);
            registered.setTabCompleter(command);
        }

        getLogger().info("ChunkCarts " + getPluginMeta().getVersion() + " enabled — "
                + engine.config().enforcement() + ", tracking " + tracker.trackedCount()
                + " cart(s).");
    }

    @Override
    public void onDisable() {
        stop();
    }

    ChunkCartsEngine engine() {
        return engine;
    }

    int heldChunkCount() {
        return tickets.heldCount();
    }

    boolean toggleDebug() {
        debug = !debug;
        tracker.setDebug(debug);
        tickets.setDebug(debug);
        return debug;
    }

    /** Tears the runtime down and builds it again from the file on disk (SPEC §10). */
    void reloadRuntime() {
        stop();
        reloadConfig();
        start();
        tracker.setDebug(debug);
        tickets.setDebug(debug);
    }

    private void start() {
        ConfigReader.Result parsed = ConfigReader.read(toMap(getConfig()));
        parsed.warnings().forEach(getLogger()::warning);

        Config config = parsed.builder().build();
        BukkitWorldQuery worlds = new BukkitWorldQuery(getServer());
        tickets = new PluginTicketBackend(this, worlds, getLogger());
        engine = new ChunkCartsEngine(config, worlds, tickets, new SystemClock());
        engine.startupWarnings().forEach(getLogger()::warning);
        engine.setPlayersOnline(!getServer().getOnlinePlayers().isEmpty());

        tracker = new CartTracker(engine, getServer(), stateKey, getLogger());
        listener = new ChunkCartsListener(engine, tracker);
        getServer().getPluginManager().registerEvents(listener, this);

        // Carts already sitting in loaded chunks. On a first boot there are none, but on a reload
        // the world is already populated and EntitiesLoadEvent has long since fired.
        for (World world : getServer().getWorlds()) {
            for (Minecart cart : world.getEntitiesByClass(Minecart.class)) {
                tracker.track(cart);
            }
        }

        scheduler = new BukkitTaskScheduler(this);
        scheduler.repeating("chunkcarts-travel-loop", engine.config().pollIntervalTicks(),
                tracker::poll);
    }

    private void stop() {
        if (scheduler != null) {
            scheduler.cancelAll();
        }
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
        if (engine != null) {
            // Releases every ticket and sweeps for anything we lost track of. Without this, held
            // chunks leak until the next restart (SPEC §8).
            engine.shutdown();
        }
    }

    private void warnAboutRivals() {
        for (String rival : RIVALS) {
            if (getServer().getPluginManager().getPlugin(rival) != null) {
                getLogger().warning(rival + " is installed alongside ChunkCarts. Behaviour is "
                        + "undefined and ChunkCarts' safety guarantee is void: another plugin will "
                        + "happily hold chunks ChunkCarts has released.");
            }
        }
    }

    /**
     * Converts Bukkit's configuration tree into the plain nested map {@code core} reads, so the
     * schema lives in one tested place rather than once per platform (SPEC §6).
     */
    private static Map<String, Object> toMap(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            out.put(key, value instanceof ConfigurationSection child ? toMap(child) : value);
        }
        return out;
    }
}

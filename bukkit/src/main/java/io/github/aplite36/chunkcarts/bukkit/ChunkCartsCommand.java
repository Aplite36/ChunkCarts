package io.github.aplite36.chunkcarts.bukkit;

import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import io.github.aplite36.chunkcarts.core.JourneySnapshot;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

/**
 * {@code /chunkcarts list|reload|debug} (SPEC §10).
 *
 * <p>{@code list} matters more than it looks: it is how an admin checks that the plugin is not
 * doing anything it should not be. A cluster is one row showing its cart count, so the row count
 * reconciles with {@code max-concurrent-journeys} rather than mysteriously exceeding it.
 */
final class ChunkCartsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("list", "reload", "debug");

    private final ChunkCartsPlugin plugin;

    ChunkCartsCommand(ChunkCartsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> list(sender);
            case "reload" -> reload(sender);
            case "debug" -> sender.sendMessage("[ChunkCarts] Verbose logging "
                    + (plugin.toggleDebug() ? "ON" : "OFF") + ".");
            default -> sender.sendMessage("[ChunkCarts] Usage: /" + label + " <list|reload|debug>");
        }
        return true;
    }

    private void list(CommandSender sender) {
        ChunkCartsEngine engine = plugin.engine();
        List<JourneySnapshot> journeys = engine.journeys();
        if (journeys.isEmpty()) {
            sender.sendMessage("[ChunkCarts] No active journeys. "
                    + plugin.heldChunkCount() + " chunks held, "
                    + String.format(Locale.ROOT, "%.1f", engine.effectiveTps()) + " TPS"
                    + clampedInterval(engine) + ".");
            return;
        }
        sender.sendMessage("[ChunkCarts] " + journeys.size() + "/"
                + engine.config().maxConcurrentJourneys() + " journeys, "
                + plugin.heldChunkCount() + " chunks held ("
                + plugin.heldChunkLevels() + "), "
                + String.format(Locale.ROOT, "%.1f", engine.effectiveTps()) + " TPS"
                + clampedInterval(engine) + ":");
        for (JourneySnapshot journey : journeys) {
            sender.sendMessage(String.format(Locale.ROOT,
                    "  %-10s %d cart(s)  %s from %d,%d  distance %d  slack %d  budget %d",
                    journey.state(), journey.cartCount(), journey.origin().world().value(),
                    journey.origin().cx(), journey.origin().cz(), journey.distanceRecord(),
                    journey.slackUsed(), journey.budgetRemaining()));
        }
    }

    /**
     * Named only while the §5 clamp is actually holding the loop below the configured interval.
     * An admin who set 10 and sees the loop running at 2 should be able to find out why here,
     * rather than reading it as the plugin ignoring the config.
     */
    private static String clampedInterval(ChunkCartsEngine engine) {
        int effective = engine.effectivePollIntervalTicks();
        return effective == engine.config().pollIntervalTicks() ? ""
                : ", polling every " + effective + " tick(s) — clamped on cart velocity";
    }

    private void reload(CommandSender sender) {
        // Every ticket is released and every journey re-read from cart NBT, which is the source of
        // truth (SPEC §7). Carts in flight carry on; they do not need a fresh token, because
        // resuming a restored journey is not a departure.
        plugin.reloadRuntime();
        sender.sendMessage("[ChunkCarts] Config reloaded. Enforcement: "
                + plugin.engine().config().enforcement() + ".");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
    }
}

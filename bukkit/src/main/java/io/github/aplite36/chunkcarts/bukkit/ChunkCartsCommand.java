package io.github.aplite36.chunkcarts.bukkit;

import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import io.github.aplite36.chunkcarts.core.CommandHelp;
import io.github.aplite36.chunkcarts.core.ConfigSwap;
import io.github.aplite36.chunkcarts.core.JourneySnapshot;
import io.github.aplite36.chunkcarts.core.ListTeleport;
import io.github.aplite36.chunkcarts.core.WhyReport;
import io.github.aplite36.chunkcarts.core.platform.CartView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /chunkcarts list|reload|debug|why|help}.
 *
 * <p>{@code list} matters more than it looks: it is how an admin checks that the plugin is not
 * doing anything it should not be. A cluster is one row showing its cart count, so the row count
 * reconciles with {@code max-concurrent-journeys} rather than mysteriously exceeding it.
 */
final class ChunkCartsCommand implements CommandExecutor, TabCompleter {

    /** Shared with the loader command and with help itself, so the three cannot disagree. */
    private static final List<String> SUBCOMMANDS = CommandHelp.SUBCOMMANDS;

    private final ChunkCartsPlugin plugin;

    ChunkCartsCommand(ChunkCartsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> list(sender, page(args));
            case "reload" -> reload(sender);
            case "debug" -> sender.sendMessage("[ChunkCarts] Verbose logging "
                    + (plugin.toggleDebug() ? "ON" : "OFF") + ".");
            case "why" -> why(sender);
            case "help" -> help(sender, label);
            // An unrecognised word gets the full help rather than a usage string. The usage string
            // was a second list of subcommands to forget to update, which is exactly what happened
            // to it.
            default -> {
                sender.sendMessage("[ChunkCarts] Unknown subcommand '" + sub + "'.");
                help(sender, label);
            }
        }
        return true;
    }

    /**
     * {@code /chunkcarts why} — what the cart the sender is looking at is doing, or what is
     * stopping it.
     *
     * <p>The platform half is here and the answer is assembled in {@code core}, so this and the
     * loader command cannot report differently. Console has no eyes, so it is refused rather than
     * answered about an arbitrary cart.
     */
    private void why(CommandSender sender) {
        ChunkCartsEngine engine = plugin.engine();
        if (engine == null) {
            sender.sendMessage("[ChunkCarts] Not running.");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[ChunkCarts] Run this in game, looking at a cart — the console has "
                    + "nowhere to look from. /chunkcarts list is the console view.");
            return;
        }
        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection();
        List<WhyReport.Candidate> carts = plugin.cartsNear(player, WhyReport.RANGE);

        Optional<CartView> view = WhyReport
                .target(eye.getX(), eye.getY(), eye.getZ(), look.getX(), look.getY(), look.getZ(),
                        carts)
                .flatMap(plugin::viewOf);
        if (view.isEmpty()) {
            sender.sendMessage("[ChunkCarts] No minecart within " + (int) WhyReport.RANGE
                    + " blocks that ChunkCarts is tracking.");
            return;
        }
        WhyReport.lines(view.get(), engine)
                .forEach(line -> sender.sendMessage("[ChunkCarts] " + line));
    }

    /** {@code /chunkcarts list 2}. A word that is not a number is page one, not an error. */
    private static int page(String[] args) {
        if (args.length < 2) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(args[1]));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void help(CommandSender sender, String label) {
        CommandHelp.lines(label).forEach(line -> sender.sendMessage("[ChunkCarts] " + line));
    }

    /**
     * Rows per page. A chat window shows about ten lines, and the header is the one carrying the
     * totals — at the STRICT cap of 64 journeys an unpaged list scrolls it away first.
     */
    private static final int PAGE_SIZE = 8;

    private void list(CommandSender sender, int page) {
        ChunkCartsEngine engine = plugin.engine();
        // Matches the loader command, which has always had this. It used not to matter here only
        // because Bukkit's stop() never nulled the field, so a plugin left inert by a failed reload
        // read as healthy instead of saying so.
        if (engine == null) {
            sender.sendMessage("[ChunkCarts] Not running.");
            return;
        }
        List<JourneySnapshot> journeys = engine.journeys();
        int held = plugin.heldChunkCount();

        if (journeys.isEmpty()) {
            // The tally used to appear only while carts were moving, which is the opposite of
            // useful: chunks held with no journey holding them is the leak shape, and it is
            // visible precisely when the list is otherwise empty.
            sendHeader(sender, "No active journeys. " + held + " chunks held",
                    held > 0 ? " (" + plugin.heldChunkLevels() + ")" : "",
                    ", " + tps(engine) + " TPS" + clampedInterval(engine) + ".");
            return;
        }

        int pages = (journeys.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int shown = Math.min(Math.max(page, 1), pages);
        sendHeader(sender, journeys.size() + "/" + engine.config().maxConcurrentJourneys()
                        + " journeys, " + held + " chunks held",
                " (" + plugin.heldChunkLevels() + ")",
                ", " + tps(engine) + " TPS" + clampedInterval(engine)
                        + (pages > 1 ? "  — page " + shown + "/" + pages : "") + ":");

        int from = (shown - 1) * PAGE_SIZE;
        for (JourneySnapshot journey : journeys.subList(from,
                Math.min(from + PAGE_SIZE, journeys.size()))) {
            sendRow(sender, journey);
        }
        if (shown < pages) {
            sender.sendMessage("[ChunkCarts]   ... /chunkcarts list " + (shown + 1)
                    + " for the next " + Math.min(PAGE_SIZE, journeys.size() - from - PAGE_SIZE)
                    + ".");
        }
    }

    /**
     * One journey. {@code near} is where the cluster is now and {@code from} is where it set off —
     * the row used to carry only the second, which can be thousands of blocks behind the cart and
     * gives an admin no way to go and look at a journey they are worried about.
     */
    private static String row(JourneySnapshot journey) {
        String near = journey.approximateChunk()
                .map(chunk -> String.format(Locale.ROOT, "near %s %d,%d",
                        chunk.world().value(), chunk.cx() * 16, chunk.cz() * 16))
                .orElse("holding nothing");
        return String.format(Locale.ROOT,
                "  %-10s %d cart(s)  %s  from %s %d,%d  distance %d  slack %d  budget %d",
                journey.state(), journey.cartCount(), near,
                journey.origin().world().value(), journey.origin().cx() * 16,
                journey.origin().cz() * 16, journey.distanceRecord(),
                journey.slackUsed(), journey.budgetRemaining());
    }

    /**
     * The row, clickable for a player who could have typed the teleport themselves. Everyone else,
     * the console included, gets exactly the string they always did.
     *
     * <p>The two vanilla permissions rather than {@code chunkcarts.admin}: the click runs
     * {@code execute} and {@code tp} as the player, so offering it to someone without them would
     * only hand them an error. The world is looked up for its dimension key because the world id
     * on this platform is the folder name, which {@code execute in} does not take; a world that
     * has since been unloaded gets no click rather than a broken one.
     */
    private static void sendRow(CommandSender sender, JourneySnapshot journey) {
        String text = row(journey);
        Optional<Component> clickable = journey.approximateChunk()
                .filter(chunk -> sender instanceof Player
                        && sender.hasPermission("minecraft.command.execute")
                        && sender.hasPermission("minecraft.command.teleport"))
                .flatMap(chunk -> Optional.ofNullable(Bukkit.getWorld(chunk.world().value()))
                        .map(World::getKey)
                        .map(key -> Component.text(text)
                                .clickEvent(ClickEvent.runCommand(
                                        ListTeleport.command(key.asString(), chunk)))
                                .hoverEvent(HoverEvent.showText(
                                        Component.text(ListTeleport.hover(chunk))))));
        if (clickable.isPresent()) {
            sender.sendMessage(clickable.get());
        } else {
            sender.sendMessage(text);
        }
    }

    private static String tps(ChunkCartsEngine engine) {
        return String.format(Locale.ROOT, "%.1f", engine.effectiveTps());
    }

    /**
     * Named only while the velocity clamp is actually holding the loop below the configured
     * interval. An admin who set 10 and sees the loop running at 2 should be able to find out
     * why here, rather than reading it as the plugin ignoring the config.
     */
    private static String clampedInterval(ChunkCartsEngine engine) {
        int effective = engine.effectivePollIntervalTicks();
        return effective == engine.config().pollIntervalTicks() ? ""
                : ", polling every " + effective + " tick(s) — clamped on cart velocity";
    }

    /**
     * The header, with the load-level tally coloured red unless every held chunk is
     * {@code ENTITY_TICKING}.
     *
     * <p>This is the one line in the command that reports something no unit test can reach: that
     * the chunks the plugin holds are actually ticking. Anything else means carts sit motionless in
     * loaded chunks. It rendered as plain text, so the healthy case and the broken case were the
     * same shape and nobody reading {@code (TICKING x9)} noticed it was not
     * {@code (ENTITY_TICKING x9)}.
     */
    private void sendHeader(CommandSender sender, String before, String tally, String after) {
        Component text = Component.text("[ChunkCarts] " + before);
        if (!tally.isEmpty()) {
            text = text.append(Component.text(tally, plugin.heldChunksAllTicking()
                    ? NamedTextColor.WHITE
                    : NamedTextColor.RED));
        }
        sender.sendMessage(text.append(Component.text(after)));
    }

    private void reload(CommandSender sender) {
        // Every ticket is released and every journey re-read from cart NBT, which is the source of
        // truth. Carts in flight carry on; they do not need a fresh token, because
        // resuming a restored journey is not a departure.
        ConfigSwap.Result result = plugin.reloadRuntime();
        ConfigSwap.Outcome outcome = result.outcome();
        ChunkCartsEngine engine = plugin.engine();
        switch (outcome) {
            // The warnings are why someone runs reload in the first place: each one names the key,
            // the value they wrote and what it was changed to. They all went to the console, which
            // is the one place the person who just ran the command is not looking. The count is
            // stated even at zero, because otherwise silence and success read the same.
            case RELOADED -> {
                sender.sendMessage("[ChunkCarts] Config reloaded. Enforcement: "
                        + engine.config().enforcement() + ". "
                        + (result.warnings().isEmpty()
                                ? "Nothing to report."
                                : result.warnings().size() + " warning(s):"));
                ConfigSwap.report(result.warnings())
                        .forEach(line -> sender.sendMessage("[ChunkCarts] " + line));
            }
            case KEPT -> sender.sendMessage(engine == null
                    ? "[ChunkCarts] Not running, so there was nothing to reload."
                    : "[ChunkCarts] Config NOT reloaded — the file on disk could not be read. Still "
                            + "running on the settings already in force ("
                            + engine.config().enforcement() + "). The reason is in the console.");
            case BROKEN -> sender.sendMessage("[ChunkCarts] Config read but the runtime could not "
                    + "be rebuilt. ChunkCarts is NOT running — see the console, then restart the "
                    + "server.");
        }
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

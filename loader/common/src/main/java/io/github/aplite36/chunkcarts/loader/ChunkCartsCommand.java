package io.github.aplite36.chunkcarts.loader;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import io.github.aplite36.chunkcarts.core.CommandHelp;
import io.github.aplite36.chunkcarts.core.ConfigSwap;
import io.github.aplite36.chunkcarts.core.JourneySnapshot;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;
//?}

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * {@code /chunkcarts list|reload|debug|help}.
 *
 * <p>{@code list} matters more than it looks: it is how an admin checks that the mod is not doing
 * anything it should not be. A cluster is one row showing its cart count, so the row count
 * reconciles with {@code max-concurrent-journeys} rather than mysteriously exceeding it.
 */
public final class ChunkCartsCommand {

    /**
     * The design calls for the single permission {@code chunkcarts.admin}. From 1.21.11 the game can
     * express exactly that — permissions became named atoms rather than only numeric levels — and
     * before that there is nothing to express it with, so the check is gamemaster level alone.
     *
     * <p>Even where the atom exists, the level fallback is not optional.
     * {@code LevelBasedPermissionSet}, which is what a server without a permission mod uses, answers
     * {@code false} to every atom it does not know — so an atom check alone would lock out the
     * operators of a plain vanilla server, including whoever installed the mod. Gamemaster is the
     * same bar the vanilla commands of this weight use, on both sides of the seam.
     */
    //? if >=1.21.11 {
    private static final Permission ADMIN =
            Permission.Atom.create(Identifier.fromNamespaceAndPath(ChunkCarts.MOD_ID, "admin"));

    private static final Predicate<CommandSourceStack> IS_ADMIN = source ->
            source.permissions().hasPermission(ADMIN)
                    || source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    //?} else
    /*private static final Predicate<CommandSourceStack> IS_ADMIN = source -> source.hasPermission(2);*/

    private final ChunkCartsMod mod;

    public ChunkCartsCommand(ChunkCartsMod mod) {
        this.mod = mod;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(build("chunkcarts"));
        // No /cc alias: it collides with chat-clear commands on a great many servers.
        dispatcher.register(build("ccarts"));
    }

    private LiteralArgumentBuilder<CommandSourceStack> build(String name) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name)
                .requires(IS_ADMIN)
                .executes(ctx -> list(ctx.getSource(), 1))
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> list(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource())))
                .then(Commands.literal("debug").executes(ctx -> debug(ctx.getSource())))
                // Brigadier answers an unregistered word with "Unknown or incomplete command" and a
                // caret, so unlike Paper there is no default branch to fall into. help has to be a
                // literal like the rest.
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource(), name)));

        // The tree is written out rather than generated from CommandHelp, because a command tree
        // is easier to read declaratively and this is the shape every other mod's looks like. The
        // cost of writing it twice is that the two can drift, which is the whole of #30 — so they
        // are compared here instead, and a subcommand registered without a help line (or the
        // reverse) fails at registration rather than at whatever point someone runs help.
        Set<String> registered = root.getArguments().stream()
                .map(CommandNode::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        if (!registered.equals(new TreeSet<>(CommandHelp.SUBCOMMANDS))) {
            throw new IllegalStateException("/" + name + " registers " + registered
                    + " but CommandHelp lists " + new TreeSet<>(CommandHelp.SUBCOMMANDS)
                    + "; the two have to match");
        }
        return root;
    }

    private int help(CommandSourceStack source, String label) {
        CommandHelp.lines(label).forEach(line -> send(source, line));
        return 1;
    }

    private int debug(CommandSourceStack source) {
        boolean on = mod.toggleDebug();
        send(source, "Verbose logging " + (on ? "ON" : "OFF") + ".");
        return 1;
    }

    /**
     * Rows per page. A chat window shows about ten lines, and the header is the one carrying the
     * totals — at the STRICT cap of 64 journeys an unpaged list scrolls it away first.
     */
    private static final int PAGE_SIZE = 8;

    private int list(CommandSourceStack source, int page) {
        ChunkCartsEngine engine = mod.engine();
        if (engine == null) {
            send(source, "Not running.");
            return 0;
        }
        List<JourneySnapshot> journeys = engine.journeys();
        int held = mod.heldChunkCount();

        if (journeys.isEmpty()) {
            // The tally used to appear only while carts were moving, which is the opposite of
            // useful: chunks held with no journey holding them is the leak shape, and it is
            // visible precisely when the list is otherwise empty.
            sendHeader(source, "No active journeys. " + held + " chunks held",
                    held > 0 ? " (" + mod.heldChunkLevels() + ")" : "",
                    ", " + tps(engine) + " TPS" + clampedInterval(engine) + ".");
            return 1;
        }

        int pages = (journeys.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int shown = Math.min(Math.max(page, 1), pages);
        sendHeader(source, journeys.size() + "/" + engine.config().maxConcurrentJourneys()
                        + " journeys, " + held + " chunks held",
                " (" + mod.heldChunkLevels() + ")",
                ", " + tps(engine) + " TPS" + clampedInterval(engine)
                        + (pages > 1 ? "  — page " + shown + "/" + pages : "") + ":");

        int from = (shown - 1) * PAGE_SIZE;
        for (JourneySnapshot journey : journeys.subList(from, Math.min(from + PAGE_SIZE, journeys.size()))) {
            send(source, row(journey));
        }
        if (shown < pages) {
            send(source, "  ... /chunkcarts list " + (shown + 1) + " for the next "
                    + Math.min(PAGE_SIZE, journeys.size() - from - PAGE_SIZE) + ".");
        }
        return journeys.size();
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

    private static String tps(ChunkCartsEngine engine) {
        return String.format(Locale.ROOT, "%.1f", engine.effectiveTps());
    }

    /**
     * Named only while the velocity clamp is actually holding the loop below the configured
     * interval. An admin who set 10 and sees the loop running at 2 should be able to find out why
     * here, rather than reading it as the mod ignoring the config.
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
     * the chunks the mod holds are actually ticking. Anything else means carts sit motionless in
     * loaded chunks. It rendered as plain text, so the healthy case and the broken case were the
     * same shape and nobody reading {@code (TICKING x9)} noticed it was not
     * {@code (ENTITY_TICKING x9)}.
     */
    private void sendHeader(CommandSourceStack source, String before, String tally, String after) {
        MutableComponent text = Component.literal("[ChunkCarts] " + before);
        if (!tally.isEmpty()) {
            text = text.append(Component.literal(tally).withStyle(mod.heldChunksAllTicking()
                    ? ChatFormatting.WHITE
                    : ChatFormatting.RED));
        }
        MutableComponent line = text.append(Component.literal(after));
        source.sendSuccess(() -> line, false);
    }

    private int reload(CommandSourceStack source) {
        // Every ticket is released and every journey re-read from the cart's own data, which is the
        // source of truth. Carts in flight carry on; they do not need a fresh token,
        // because resuming a restored journey is not a departure.
        //
        // Reading mod.engine() unconditionally used to be an NPE on a failed reload, which left the
        // engine null: the first reload reported the real error and every reload after it reported
        // the NPE instead, hiding it.
        ConfigSwap.Outcome outcome = mod.reloadRuntime();
        ChunkCartsEngine engine = mod.engine();
        switch (outcome) {
            case RELOADED -> send(source, "Config reloaded. Enforcement: "
                    + engine.config().enforcement() + ".");
            case KEPT -> send(source, engine == null
                    ? "Not running, so there was nothing to reload."
                    : "Config NOT reloaded — the file on disk could not be read. Still running on "
                            + "the settings already in force (" + engine.config().enforcement()
                            + "). The reason is in the console.");
            case BROKEN -> send(source, "Config read but the runtime could not be rebuilt. "
                    + "ChunkCarts is NOT running — see the console, then restart the server.");
        }
        return outcome == ConfigSwap.Outcome.RELOADED ? 1 : 0;
    }

    private static void send(CommandSourceStack source, String message) {
        Supplier<Component> text = () -> Component.literal("[ChunkCarts] " + message);
        source.sendSuccess(text, false);
    }
}

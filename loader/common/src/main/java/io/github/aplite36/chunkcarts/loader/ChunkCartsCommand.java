package io.github.aplite36.chunkcarts.loader;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import io.github.aplite36.chunkcarts.core.ConfigSwap;
import io.github.aplite36.chunkcarts.core.JourneySnapshot;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;
//?}

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@code /chunkcarts list|reload|debug}.
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
        return Commands.literal(name)
                .requires(IS_ADMIN)
                .executes(ctx -> list(ctx.getSource()))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource())))
                .then(Commands.literal("debug").executes(ctx -> debug(ctx.getSource())));
    }

    private int debug(CommandSourceStack source) {
        boolean on = mod.toggleDebug();
        send(source, "Verbose logging " + (on ? "ON" : "OFF") + ".");
        return 1;
    }

    private int list(CommandSourceStack source) {
        ChunkCartsEngine engine = mod.engine();
        if (engine == null) {
            send(source, "Not running.");
            return 0;
        }
        List<JourneySnapshot> journeys = engine.journeys();
        if (journeys.isEmpty()) {
            send(source, "No active journeys. " + mod.heldChunkCount() + " chunks held, "
                    + String.format(Locale.ROOT, "%.1f", engine.effectiveTps()) + " TPS"
                    + clampedInterval(engine) + ".");
            return 1;
        }
        send(source, journeys.size() + "/" + engine.config().maxConcurrentJourneys()
                + " journeys, " + mod.heldChunkCount() + " chunks held ("
                + mod.heldChunkLevels() + "), "
                + String.format(Locale.ROOT, "%.1f", engine.effectiveTps()) + " TPS"
                + clampedInterval(engine) + ":");
        for (JourneySnapshot journey : journeys) {
            send(source, String.format(Locale.ROOT,
                    "  %-10s %d cart(s)  %s from %d,%d  distance %d  slack %d  budget %d",
                    journey.state(), journey.cartCount(), journey.origin().world().value(),
                    journey.origin().cx(), journey.origin().cz(), journey.distanceRecord(),
                    journey.slackUsed(), journey.budgetRemaining()));
        }
        return journeys.size();
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

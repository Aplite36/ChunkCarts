package io.github.aplite36.chunkcarts.bukkit;

import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import io.github.aplite36.chunkcarts.core.ChunkPos;
import io.github.aplite36.chunkcarts.core.CommandHelp;
import io.github.aplite36.chunkcarts.core.Config;
import io.github.aplite36.chunkcarts.core.ConfigSwap;
import io.github.aplite36.chunkcarts.core.JourneySnapshot;
import io.github.aplite36.chunkcarts.core.JourneyState;
import io.github.aplite36.chunkcarts.core.WhyReport;
import io.github.aplite36.chunkcarts.core.WorldId;
import io.github.aplite36.chunkcarts.core.platform.CartView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What {@code /chunkcarts} on Paper says back. The engine and the plugin are mocks; the text they
 * are asked to produce comes from the real command and the real {@code core} helpers.
 */
class ChunkCartsCommandTest {

    private static final Config CONFIG = Config.defaults().build();
    private static final WorldId OVERWORLD = new WorldId("world");

    private final ChunkCartsPlugin plugin = mock(ChunkCartsPlugin.class);
    private final ChunkCartsEngine engine = mock(ChunkCartsEngine.class);
    private final ChunkCartsCommand command = new ChunkCartsCommand(plugin);
    private final Command bukkitCommand = mock(Command.class);

    @BeforeEach
    void runningEngine() {
        when(plugin.engine()).thenReturn(engine);
        when(engine.config()).thenReturn(CONFIG);
        when(engine.effectiveTps()).thenReturn(20.0);
        when(engine.effectivePollIntervalTicks()).thenReturn(CONFIG.pollIntervalTicks());
        when(plugin.heldChunkLevels()).thenReturn("ENTITY_TICKING x9");
        when(plugin.heldChunksAllTicking()).thenReturn(true);
    }

    private Chat run(String label, String... args) {
        Chat chat = Chat.console();
        assertTrue(command.onCommand(chat.sender, bukkitCommand, label, args));
        return chat;
    }

    private static List<String> prefixed(List<String> lines) {
        return lines.stream().map(line -> "[ChunkCarts] " + line).toList();
    }

    @Nested
    class Help {

        @Test
        void helpPrintsCommandHelpUnderTheLabelTyped() {
            assertEquals(prefixed(CommandHelp.lines("ccarts")), run("ccarts", "help").lines);
        }

        @Test
        void anUnknownWordNamesItAndThenPrintsTheFullHelp() {
            List<String> expected = new ArrayList<>();
            expected.add("[ChunkCarts] Unknown subcommand 'banana'.");
            expected.addAll(prefixed(CommandHelp.lines("chunkcarts")));

            assertEquals(expected, run("chunkcarts", "BaNaNa").lines);
        }

        @Test
        void tabCompletionOffersEverySubcommandHelpKnowsAbout() {
            assertEquals(CommandHelp.SUBCOMMANDS,
                    command.onTabComplete(Chat.console().sender, bukkitCommand, "chunkcarts",
                            new String[] {""}));
            assertEquals(List.of("reload"),
                    command.onTabComplete(Chat.console().sender, bukkitCommand, "chunkcarts",
                            new String[] {"re"}));
        }
    }

    @Nested
    class ListPaging {

        private void journeys(int count) {
            when(engine.journeys()).thenReturn(IntStream.range(0, count)
                    .mapToObj(i -> new JourneySnapshot(UUID.randomUUID(), JourneyState.TRAVELLING,
                            UUID.randomUUID(), Set.of(UUID.randomUUID()), new ChunkPos(OVERWORLD, i, 0),
                            i, 0, 100, 0, Set.of(new ChunkPos(OVERWORLD, i, 1))))
                    .toList());
            when(plugin.heldChunkCount()).thenReturn(count * 9);
        }

        /** Rows are everything between the header and the "next page" footer, if there is one. */
        private static int rows(Chat chat) {
            boolean footer = chat.lines.get(chat.lines.size() - 1).contains("/chunkcarts list ");
            return chat.lines.size() - 1 - (footer ? 1 : 0);
        }

        @Test
        void noArgumentsIsTheListAndAnEmptyListStillReportsHeldChunks() {
            journeys(0);
            when(plugin.heldChunkCount()).thenReturn(3);

            Chat chat = run("chunkcarts");

            assertEquals(List.of("[ChunkCarts] No active journeys. 3 chunks held "
                    + "(ENTITY_TICKING x9), 20.0 TPS."), chat.lines);
        }

        @Test
        void aShortListIsOnePageWithNoFooter() {
            journeys(5);

            Chat chat = run("chunkcarts", "list");

            assertEquals("[ChunkCarts] 5/" + CONFIG.maxConcurrentJourneys()
                    + " journeys, 45 chunks held (ENTITY_TICKING x9), 20.0 TPS:", chat.lines.get(0));
            assertEquals(5, rows(chat));
            assertEquals(6, chat.lines.size());
        }

        @Test
        void theFirstPageShowsEightRowsAndPointsAtTheNext() {
            journeys(20);

            Chat chat = run("chunkcarts", "list");

            assertTrue(chat.lines.get(0).endsWith("— page 1/3:"), chat.lines.get(0));
            assertEquals(8, rows(chat));
            assertEquals("[ChunkCarts]   ... /chunkcarts list 2 for the next 8.",
                    chat.lines.get(chat.lines.size() - 1));
        }

        @Test
        void theSecondToLastPageCountsWhatIsLeftOnTheLast() {
            journeys(20);

            Chat chat = run("chunkcarts", "list", "2");

            assertTrue(chat.lines.get(0).endsWith("— page 2/3:"), chat.lines.get(0));
            assertEquals("[ChunkCarts]   ... /chunkcarts list 3 for the next 4.",
                    chat.lines.get(chat.lines.size() - 1));
        }

        @Test
        void theLastPageHasTheRemainderAndNoFooter() {
            journeys(20);

            Chat chat = run("chunkcarts", "list", "3");

            assertTrue(chat.lines.get(0).endsWith("— page 3/3:"), chat.lines.get(0));
            assertEquals(4, rows(chat));
            assertEquals(5, chat.lines.size());
        }

        @Test
        void aPagePastTheEndShowsTheLastPage() {
            journeys(20);

            assertEquals(run("chunkcarts", "list", "3").lines.size(),
                    run("chunkcarts", "list", "99").lines.size());
            assertTrue(run("chunkcarts", "list", "99").lines.get(0).endsWith("— page 3/3:"));
        }

        @Test
        void aPageThatIsNotANumberIsPageOne() {
            journeys(20);

            assertTrue(run("chunkcarts", "list", "two").lines.get(0).endsWith("— page 1/3:"));
            assertTrue(run("chunkcarts", "list", "-4").lines.get(0).endsWith("— page 1/3:"));
        }

        @Test
        void theTallyIsRedUnlessEveryHeldChunkIsEntityTicking() {
            journeys(1);

            Chat healthy = run("chunkcarts", "list");
            when(plugin.heldChunksAllTicking()).thenReturn(false);
            Chat broken = run("chunkcarts", "list");

            assertEquals(NamedTextColor.WHITE,
                    Chat.colourOf(healthy.components.get(0), "ENTITY_TICKING x9"));
            assertEquals(NamedTextColor.RED,
                    Chat.colourOf(broken.components.get(0), "ENTITY_TICKING x9"));
        }

        @Test
        void aClampedPollIntervalIsNamedInTheHeader() {
            journeys(1);
            when(engine.effectivePollIntervalTicks()).thenReturn(2);

            assertTrue(run("chunkcarts", "list").lines.get(0)
                    .contains(", polling every 2 tick(s) — clamped on cart velocity"));
        }

        @Test
        void aStoppedEngineSaysSoInsteadOfListing() {
            when(plugin.engine()).thenReturn(null);

            assertEquals(List.of("[ChunkCarts] Not running."), run("chunkcarts", "list").lines);
        }
    }

    @Nested
    class Reload {

        @Test
        void aCleanReloadSaysThereIsNothingToReport() {
            when(plugin.reloadRuntime())
                    .thenReturn(new ConfigSwap.Result(ConfigSwap.Outcome.RELOADED, List.of()));

            assertEquals(List.of("[ChunkCarts] Config reloaded. Enforcement: STRICT. "
                    + "Nothing to report."), run("chunkcarts", "reload").lines);
        }

        @Test
        void warningsGoToWhoeverRanTheCommandUnderACount() {
            List<String> warnings = List.of("poll-interval-ticks 0 raised to 1",
                    "unknown key 'colour'");
            when(plugin.reloadRuntime())
                    .thenReturn(new ConfigSwap.Result(ConfigSwap.Outcome.RELOADED, warnings));

            assertEquals(List.of(
                    "[ChunkCarts] Config reloaded. Enforcement: STRICT. 2 warning(s):",
                    "[ChunkCarts]   poll-interval-ticks 0 raised to 1",
                    "[ChunkCarts]   unknown key 'colour'"), run("chunkcarts", "reload").lines);
        }

        @Test
        void manyWarningsAreCutOffWithAPointerToTheConsole() {
            List<String> warnings = IntStream.range(0, ConfigSwap.WARNINGS_SHOWN + 3)
                    .mapToObj(i -> "warning " + i).toList();
            when(plugin.reloadRuntime())
                    .thenReturn(new ConfigSwap.Result(ConfigSwap.Outcome.RELOADED, warnings));

            List<String> lines = run("chunkcarts", "reload").lines;

            assertEquals(1 + ConfigSwap.WARNINGS_SHOWN + 1, lines.size());
            assertEquals("[ChunkCarts]   ... and 3 more; the full list is in the console.",
                    lines.get(lines.size() - 1));
        }

        @Test
        void anUnreadableFileKeepsTheRunningSettingsAndSaysSo() {
            when(plugin.reloadRuntime())
                    .thenReturn(new ConfigSwap.Result(ConfigSwap.Outcome.KEPT, List.of()));

            List<String> lines = run("chunkcarts", "reload").lines;

            assertEquals(1, lines.size());
            assertTrue(lines.get(0).startsWith("[ChunkCarts] Config NOT reloaded"), lines.get(0));
            assertTrue(lines.get(0).contains("(STRICT)"), lines.get(0));
        }

        @Test
        void keptWithNothingRunningDoesNotDereferenceTheMissingEngine() {
            when(plugin.engine()).thenReturn(null);
            when(plugin.reloadRuntime())
                    .thenReturn(new ConfigSwap.Result(ConfigSwap.Outcome.KEPT, List.of()));

            assertEquals(List.of("[ChunkCarts] Not running, so there was nothing to reload."),
                    run("chunkcarts", "reload").lines);
        }

        @Test
        void aBrokenRuntimeSaysChunkCartsIsNotRunning() {
            when(plugin.engine()).thenReturn(null);
            when(plugin.reloadRuntime())
                    .thenReturn(new ConfigSwap.Result(ConfigSwap.Outcome.BROKEN, List.of()));

            List<String> lines = run("chunkcarts", "reload").lines;

            assertEquals(1, lines.size());
            assertTrue(lines.get(0).contains("ChunkCarts is NOT running"), lines.get(0));
        }
    }

    @Nested
    class ClickableRows {

        private static final String ROW = "  TRAVELLING 1 cart(s)  near world 0,16  from world 0,0  "
                + "distance 0  slack 0  budget 100";

        private final Player player = mock(Player.class);
        private final Server server = mock(Server.class);
        private final World world = mock(World.class);

        @BeforeEach
        void oneJourneyInTheOverworld() {
            when(engine.journeys()).thenReturn(List.of(new JourneySnapshot(UUID.randomUUID(),
                    JourneyState.TRAVELLING, UUID.randomUUID(), Set.of(UUID.randomUUID()),
                    new ChunkPos(OVERWORLD, 0, 0), 0, 0, 100, 0,
                    Set.of(new ChunkPos(OVERWORLD, 0, 1)))));
            when(plugin.heldChunkCount()).thenReturn(1);
            when(plugin.getServer()).thenReturn(server);
            when(server.getWorld("world")).thenReturn(world);
            when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
            when(player.hasPermission("minecraft.command.execute")).thenReturn(true);
            when(player.hasPermission("minecraft.command.teleport")).thenReturn(true);
        }

        private Chat listAsPlayer() {
            Chat chat = Chat.player(player);
            assertTrue(command.onCommand(chat.sender, bukkitCommand, "chunkcarts",
                    new String[] {"list"}));
            return chat;
        }

        @Test
        void theConsoleGetsThePlainRow() {
            Chat chat = run("chunkcarts", "list");

            assertEquals(ROW, chat.lines.get(1));
            assertEquals(1, chat.components.size(), "only the header is a component");
        }

        @Test
        void aPlayerWhoCanTeleportGetsTheSameTextWithAClickToTheNearPosition() {
            Chat chat = listAsPlayer();
            Component row = chat.components.get(1);

            assertEquals(ROW, chat.lines.get(1));
            assertEquals(ClickEvent.runCommand(
                            "/execute in minecraft:overworld run tp @s 0 ~ 16"),
                    row.clickEvent());
            assertEquals(HoverEvent.Action.SHOW_TEXT, row.hoverEvent().action());
            assertTrue(PlainTextComponentSerializer.plainText()
                    .serialize((Component) row.hoverEvent().value()).contains("world 0,16"));
        }

        @Test
        void aPlayerWithoutTheTeleportPermissionGetsThePlainRow() {
            when(player.hasPermission("minecraft.command.teleport")).thenReturn(false);
            Chat chat = listAsPlayer();

            assertEquals(ROW, chat.lines.get(1));
            assertEquals(1, chat.components.size());
        }

        @Test
        void aPlayerWithoutExecuteGetsThePlainRow() {
            when(player.hasPermission("minecraft.command.execute")).thenReturn(false);

            assertEquals(1, listAsPlayer().components.size());
        }

        @Test
        void anUnloadedWorldGetsNoClickRatherThanABrokenOne() {
            when(server.getWorld("world")).thenReturn(null);
            Chat chat = listAsPlayer();

            assertEquals(ROW, chat.lines.get(1));
            assertEquals(1, chat.components.size());
        }
    }

    @Nested
    class Why {

        private final Player player = mock(Player.class);

        @BeforeEach
        void standingAtTheOriginLookingNorth() {
            when(player.getEyeLocation()).thenReturn(new Location(null, 0.5, 65.6, 0.5, 180f, 0f));
        }

        private Chat runAsPlayer() {
            Chat chat = Chat.player(player);
            assertTrue(command.onCommand(chat.sender, bukkitCommand, "chunkcarts",
                    new String[] {"why"}));
            return chat;
        }

        @Test
        void theConsoleIsRefusedRatherThanAnsweredAboutSomeCart() {
            List<String> lines = run("chunkcarts", "why").lines;

            assertEquals(1, lines.size());
            assertTrue(lines.get(0).startsWith("[ChunkCarts] Run this in game"), lines.get(0));
        }

        @Test
        void aStoppedEngineSaysSo() {
            when(plugin.engine()).thenReturn(null);

            assertEquals(List.of("[ChunkCarts] Not running."), runAsPlayer().lines);
        }

        @Test
        void noTrackedCartInRangeSaysHowFarItLooked() {
            when(plugin.cartsNear(any(), anyDouble())).thenReturn(List.of());

            assertEquals(List.of("[ChunkCarts] No minecart within 8 blocks that ChunkCarts is "
                    + "tracking."), runAsPlayer().lines);
        }

        @Test
        void theCartInFrontIsReportedByCore() {
            UUID id = UUID.randomUUID();
            CartView cart = mock(CartView.class);
            when(cart.id()).thenReturn(id);
            when(cart.world()).thenReturn(OVERWORLD);
            when(cart.x()).thenReturn(0.5);
            when(cart.y()).thenReturn(65.0);
            when(cart.z()).thenReturn(-3.5);
            when(plugin.cartsNear(any(), anyDouble()))
                    .thenReturn(List.of(new WhyReport.Candidate(id, 0.5, 65.0, -3.5)));
            when(plugin.viewOf(id)).thenReturn(Optional.of(cart));

            assertEquals(prefixed(WhyReport.lines(cart, engine)), runAsPlayer().lines);
        }
    }
}

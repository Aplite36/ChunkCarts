package io.github.aplite36.chunkcarts.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The subcommand list the four platforms share.
 *
 * <p>This cannot reach either dispatcher — Paper's is a {@code switch} in {@code bukkit} and the
 * loaders' is a Brigadier tree in {@code loader/common}, neither of which has a test source set.
 * What it pins is the content: that the list is well formed, that {@code help} documents itself,
 * and that the text follows the alias the admin actually typed. The loader side compares its
 * registered literals against {@link CommandHelp#SUBCOMMANDS} at registration and throws on a
 * mismatch, which is where that half of the coupling is enforced.
 */
@DisplayName("command help")
class CommandHelpTest {

    @Test
    @DisplayName("every subcommand has a summary, and no name is listed twice")
    void everySubcommandHasASummaryAndNoNameIsListedTwice() {
        assertFalse(CommandHelp.LINES.isEmpty());
        for (CommandHelp.Line line : CommandHelp.LINES) {
            assertFalse(line.name().isBlank(), "a subcommand with no name");
            assertFalse(line.summary().isBlank(),
                    "naming the subcommands was already what the old usage string did; the point of "
                            + "help is saying what each is for: " + line.name());
        }
        assertEquals(CommandHelp.SUBCOMMANDS.size(), Set.copyOf(CommandHelp.SUBCOMMANDS).size(),
                "duplicate subcommand name");
    }

    @Test
    @DisplayName("help documents itself")
    void helpDocumentsItself() {
        // A command whose own help does not mention itself is how people end up guessing, which is
        // the situation #30 is about.
        assertTrue(CommandHelp.SUBCOMMANDS.contains("help"));
    }

    @Test
    @DisplayName("the three original subcommands are still listed")
    void theThreeOriginalSubcommandsAreStillListed() {
        assertTrue(CommandHelp.SUBCOMMANDS.containsAll(List.of("list", "reload", "debug")),
                "dropping one silently would make help wrong in the direction it exists to fix");
    }

    @Test
    @DisplayName("the text uses the alias that was actually typed")
    void theTextUsesTheAliasThatWasActuallyTyped() {
        // /ccarts is a registered alias on every platform. An admin who uses it should not be shown
        // help for a command they did not type.
        List<String> lines = CommandHelp.lines("ccarts");
        assertEquals(CommandHelp.LINES.size(), lines.size());
        for (String line : lines) {
            assertTrue(line.startsWith("/ccarts "), line);
        }
    }

    @Test
    @DisplayName("the subcommand column is aligned, so the summaries read as a column")
    void theSubcommandColumnIsAligned() {
        List<String> lines = CommandHelp.lines("chunkcarts");
        Set<Integer> summaryStarts = new java.util.LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            summaryStarts.add(lines.get(i).indexOf(CommandHelp.LINES.get(i).summary()));
        }
        assertEquals(1, summaryStarts.size(),
                "every summary starts at the same column, or the list is harder to scan than the "
                        + "usage string it replaces: " + lines);
    }
}

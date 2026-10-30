package io.github.aplite36.chunkcarts.core;

import java.util.List;
import java.util.stream.Collectors;

/**
 * What {@code /chunkcarts} can do, in one place, so the four platforms cannot answer differently.
 *
 * <p>The command is implemented twice — once for Paper and once, shared, for the three mod loaders
 * — and the two had drifted. Paper routed anything it did not recognise into a usage line, so
 * {@code /chunkcarts help} happened to work and {@code /chunkcarts banana} did too. Brigadier does
 * not: on the loaders both got "Unknown or incomplete command" with a caret under the word. The
 * same guess taught you the commands on one platform and scolded you on the other three.
 *
 * <p>Only the text lives here, not the dispatch. {@code core} has no business knowing how a
 * platform registers a subcommand, but "what are the subcommands and what is each one for" is a
 * fact about the mod rather than about Paper or Brigadier, and it is a fact that has to be the same
 * on all four. Keeping it here also means the list has one place to be updated when a subcommand is
 * added, which is the failure #30 predicts for a usage string typed into a {@code default} branch.
 */
public final class CommandHelp {

    /** A subcommand, and what an admin would run it to find out. */
    public record Line(String name, String summary) {}

    /**
     * Every subcommand, in the order help prints them. {@code help} is in the list on purpose: a
     * command whose own help does not mention itself is how people end up guessing.
     */
    public static final List<Line> LINES = List.of(
            new Line("list", "what is travelling, what is held, and the current TPS"),
            new Line("reload", "re-read the config from disk"),
            new Line("debug", "toggle verbose logging to the console"),
            new Line("help", "this list"));

    /** Just the names, for tab completion and for whatever a platform does with an unknown word. */
    public static final List<String> SUBCOMMANDS = LINES.stream().map(Line::name).toList();

    private static final int NAME_WIDTH =
            LINES.stream().mapToInt(line -> line.name().length()).max().orElse(0);

    private CommandHelp() {
    }

    /**
     * The help text, one string per line, with no platform decoration — each adapter adds its own
     * {@code [ChunkCarts]} prefix the same way it does for every other message.
     *
     * @param label the alias the command was actually invoked under, so the help shows what the
     *              admin typed rather than the canonical name they may not use
     */
    public static List<String> lines(String label) {
        return LINES.stream()
                .map(line -> "/" + label + " " + pad(line.name()) + "  " + line.summary())
                .collect(Collectors.toList());
    }

    private static String pad(String name) {
        return name + " ".repeat(NAME_WIDTH - name.length());
    }
}

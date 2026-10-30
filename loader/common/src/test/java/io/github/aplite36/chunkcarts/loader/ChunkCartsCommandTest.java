package io.github.aplite36.chunkcarts.loader;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import io.github.aplite36.chunkcarts.core.CommandHelp;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Brigadier tree shared by the three loaders, and its check against {@link CommandHelp}.
 *
 * <p>The check exists so a subcommand registered without a help line, or the reverse, fails at
 * registration (#30). These tests pin both halves: the real tree passes it, and a tree that has
 * drifted either way does not.
 */
class ChunkCartsCommandTest {

    /**
     * {@code Commands} reaches the built-in registries in its static initialiser, and those refuse
     * to load before the game has bootstrapped. This is the same pair of calls the server makes
     * at startup, minus everything that needs a world.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Set<String> children(CommandNode<CommandSourceStack> node) {
        return node.getChildren().stream()
                .map(CommandNode::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void bothRootsRegisterExactlyTheSubcommandsHelpLists() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();

        new ChunkCartsCommand(null).register(dispatcher);

        Set<String> expected = new TreeSet<>(CommandHelp.SUBCOMMANDS);
        assertEquals(Set.of("chunkcarts", "ccarts"), children(dispatcher.getRoot()));
        assertEquals(expected, children(dispatcher.getRoot().getChild("chunkcarts")));
        assertEquals(expected, children(dispatcher.getRoot().getChild("ccarts")));
    }

    @Test
    void listTakesAnOptionalPage() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        new ChunkCartsCommand(null).register(dispatcher);

        CommandNode<CommandSourceStack> list =
                dispatcher.getRoot().getChild("chunkcarts").getChild("list");

        assertTrue(list.getCommand() != null, "bare list must run");
        assertEquals(Set.of("page"), children(list));
    }

    private static LiteralArgumentBuilder<Object> tree(List<String> subcommands) {
        LiteralArgumentBuilder<Object> root = LiteralArgumentBuilder.literal("chunkcarts");
        subcommands.forEach(name -> root.then(LiteralArgumentBuilder.literal(name)));
        return root;
    }

    @Test
    void aTreeMatchingHelpPasses() {
        assertDoesNotThrow(() ->
                ChunkCartsCommand.requireMatchesHelp("chunkcarts", tree(CommandHelp.SUBCOMMANDS)));
    }

    @Test
    void aSubcommandWithNoHelpLineIsRefused() {
        List<String> extra = new ArrayList<>(CommandHelp.SUBCOMMANDS);
        extra.add("purge");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ChunkCartsCommand.requireMatchesHelp("chunkcarts", tree(extra)));
        assertTrue(e.getMessage().contains("purge"), e.getMessage());
    }

    @Test
    void aHelpLineWithNoSubcommandIsRefused() {
        List<String> missing = CommandHelp.SUBCOMMANDS.stream()
                .filter(name -> !name.equals("why"))
                .toList();

        assertThrows(IllegalStateException.class,
                () -> ChunkCartsCommand.requireMatchesHelp("ccarts", tree(missing)));
    }
}

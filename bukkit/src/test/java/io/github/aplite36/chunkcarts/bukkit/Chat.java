package io.github.aplite36.chunkcarts.bukkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * A command sender that writes down what it was sent, as plain text in order.
 *
 * <p>The command sends both strings and Adventure components (the {@code list} header is one), so
 * both overloads are captured and flattened to the text a player would read. The components are
 * kept too, for the one test that cares about colour.
 */
final class Chat {

    final List<String> lines = new ArrayList<>();
    final List<Component> components = new ArrayList<>();
    CommandSender sender;

    static Chat console() {
        Chat chat = new Chat();
        chat.sender = chat.capture(mock(CommandSender.class));
        return chat;
    }

    static Chat player(Player player) {
        Chat chat = new Chat();
        chat.sender = chat.capture(player);
        return chat;
    }

    private <T extends CommandSender> T capture(T sender) {
        doAnswer(call -> lines.add(call.getArgument(0))).when(sender).sendMessage(anyString());
        doAnswer(call -> {
            Component component = call.getArgument(0);
            components.add(component);
            return lines.add(PlainTextComponentSerializer.plainText().serialize(component));
        }).when(sender).sendMessage(any(Component.class));
        return sender;
    }

    /** The colour of the first child of a component whose text contains {@code fragment}. */
    static TextColor colourOf(Component component, String fragment) {
        for (Component child : component.children()) {
            if (child instanceof TextComponent text && text.content().contains(fragment)) {
                return text.color();
            }
        }
        throw new AssertionError("no child containing '" + fragment + "' in " + component);
    }
}

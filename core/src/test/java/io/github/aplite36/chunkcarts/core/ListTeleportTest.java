package io.github.aplite36.chunkcarts.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strings behind a clickable {@code list} row. Whether the click is offered at all, and to whom,
 * is decided in the adapters, which have no test source set; this pins what it does when it is.
 */
@DisplayName("list row teleport")
class ListTeleportTest {

    private static final ChunkPos NETHER = new ChunkPos(new WorldId("minecraft:the_nether"), -3, 7);

    @Test
    @DisplayName("teleports to the block coordinates the row prints, in the row's dimension")
    void teleportsToThePrintedPosition() {
        assertEquals("/execute in minecraft:the_nether run tp @s -48 ~ 112",
                ListTeleport.command("minecraft:the_nether", NETHER));
    }

    @Test
    @DisplayName("takes the dimension key it is given, not the world id")
    void usesTheGivenDimensionKey() {
        // Paper's world id is a folder name, which execute in does not accept.
        ChunkPos paper = new ChunkPos(new WorldId("world_nether"), 1, 2);
        assertEquals("/execute in minecraft:the_nether run tp @s 16 ~ 32",
                ListTeleport.command("minecraft:the_nether", paper));
    }

    @Test
    @DisplayName("the hover names the world and position the way the row does")
    void hoverMatchesTheRow() {
        String hover = ListTeleport.hover(NETHER);
        assertTrue(hover.contains("minecraft:the_nether -48,112"), hover);
    }

    @Test
    @DisplayName("the command never has a locale's digit grouping in it")
    void numbersAreLocaleIndependent() {
        ChunkPos far = new ChunkPos(new WorldId("minecraft:overworld"), 100_000, -100_000);
        assertEquals("/execute in minecraft:overworld run tp @s 1600000 ~ -1600000",
                ListTeleport.command("minecraft:overworld", far));
    }
}

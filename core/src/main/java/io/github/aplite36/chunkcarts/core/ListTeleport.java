package io.github.aplite36.chunkcarts.core;

import java.util.Locale;

/**
 * The click-to-teleport on a {@code /chunkcarts list} row: the command it runs and the hover text
 * that says so.
 *
 * <p>Only the strings live here. Attaching them is platform work — Paper builds an Adventure click
 * event, the loaders a vanilla {@code Style} — and so is deciding who gets them, so the row text
 * itself stays the plain string both adapters already build and the console keeps seeing exactly
 * that.
 *
 * <p>The target is the row's {@code near} position, the same numbers the row prints, so clicking
 * goes where the admin just read. {@code execute in} because a journey can be in another dimension
 * from the admin; the height is {@code ~}, the admin's own, because the record has no y and a
 * guessed one is a fair way to put someone inside a hill. Admins who use this are mostly flying.
 */
public final class ListTeleport {

    private ListTeleport() {
    }

    /**
     * @param dimension the dimension key {@code execute in} takes, such as
     *                  {@code minecraft:overworld}. On the loaders that is the {@link WorldId}
     *                  itself; on Paper the world id is the folder name and the adapter looks up
     *                  the key
     * @param chunk     the row's {@link JourneySnapshot#approximateChunk()}
     */
    public static String command(String dimension, ChunkPos chunk) {
        // The leading slash: before 1.21.5 a run_command without one is sent as a chat message.
        // From 1.21.5 the client strips it if present, so the one string is right everywhere.
        return String.format(Locale.ROOT, "/execute in %s run tp @s %d ~ %d",
                dimension, chunk.cx() * 16, chunk.cz() * 16);
    }

    /** The hover text, naming the world as the row does rather than by its dimension key. */
    public static String hover(ChunkPos chunk) {
        return String.format(Locale.ROOT, "Click to teleport to %s %d,%d (at your current height)",
                chunk.world().value(), chunk.cx() * 16, chunk.cz() * 16);
    }
}

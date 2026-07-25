package io.github.aplite36.chunkcarts.core;

/**
 * The minecart variants ChunkCarts recognises.
 *
 * <p>{@link #TNT}, {@link #COMMAND_BLOCK} and {@link #SPAWNER} may never depart (SPEC §4 gate 2).
 * TNT and COMMAND_BLOCK have config keys that cannot be enabled; SPAWNER has no key at all.
 */
public enum CartType {
    NORMAL,
    CHEST,
    HOPPER,
    FURNACE,
    TNT,
    COMMAND_BLOCK,
    SPAWNER
}

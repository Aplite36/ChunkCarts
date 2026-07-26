package io.github.aplite36.chunkcarts.loader;

// The minecart classes kept their names and moved to a subpackage of their own in 1.21.11.
//? if >=1.21.11 {
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
//?} else
/*import net.minecraft.world.entity.vehicle.AbstractMinecart;*/

import java.util.Optional;

/**
 * Where a cart's journey state lives between polls (SPEC §7).
 *
 * <p>The one part of {@link LevelCartView} that is not plain Minecraft. Every loader has an
 * attach-arbitrary-data-to-an-entity mechanism and no two of them are the same type, but all of
 * them serialise into the entity's own NBT — which is the property §7 actually depends on: state
 * dies with the entity, travels with it between dimensions, and rolls back with the world if an
 * admin restores a backup.
 *
 * <p>The bytes carry a schema version in byte zero and are otherwise {@code core}'s business. An
 * implementation must not interpret them, and must return empty rather than throwing for a cart
 * that has never travelled — which is almost every cart on a server.
 */
public interface JourneyStore {

    Optional<byte[]> read(AbstractMinecart cart);

    /** Writes the state, replacing whatever was there. A {@code null} clears it. */
    void write(AbstractMinecart cart, byte[] state);
}

package io.github.aplite36.chunkcarts.fabric;

import com.mojang.serialization.Codec;
import io.github.aplite36.chunkcarts.loader.ChunkCarts;
import io.github.aplite36.chunkcarts.loader.JourneyStore;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else
/*import net.minecraft.resources.ResourceLocation;*/
//? if >=1.21.11 {
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
//?} else
/*import net.minecraft.world.entity.vehicle.AbstractMinecart;*/

import java.util.Base64;
import java.util.Optional;

/**
 * {@link JourneyStore} on Fabric's data attachments (SPEC §7).
 *
 * <p>Attachments are the direct analogue of Bukkit's persistent data container: they serialise into
 * the entity's NBT, which is the shared-fate property §7 depends on.
 *
 * <p>Raw bytes with a schema version in byte zero, base64-encoded because the value has to go
 * through a {@link Codec} and a string is the one representation that is stable across every NBT
 * and JSON backend Mojang has used. The format inside the bytes is {@code core}'s business.
 *
 * <p>Constructed once from the entry point: registering the same attachment id twice throws, so
 * this must not be rebuilt by {@code /chunkcarts reload} along with the rest of the runtime.
 */
final class FabricJourneyStore implements JourneyStore {

    private final AttachmentType<byte[]> journeyState = AttachmentRegistry.createPersistent(
            //? if >=1.21.11 {
            Identifier.fromNamespaceAndPath(ChunkCarts.MOD_ID, "journey"),
            //?} else
            /*ResourceLocation.fromNamespaceAndPath(ChunkCarts.MOD_ID, "journey"),*/
            Codec.STRING.xmap(Base64.getDecoder()::decode, Base64.getEncoder()::encodeToString));

    /**
     * The cast is not defensive: Fabric API mixes {@link AttachmentTarget} into {@code Entity} at
     * runtime, which the compiler cannot see.
     */
    @Override
    public Optional<byte[]> read(AbstractMinecart cart) {
        return Optional.ofNullable(((AttachmentTarget) cart).getAttached(journeyState));
    }

    @Override
    public void write(AbstractMinecart cart, byte[] state) {
        ((AttachmentTarget) cart).setAttached(journeyState, state);
    }
}

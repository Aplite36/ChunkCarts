package io.github.aplite36.chunkcarts.fabric;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
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
 * {@link JourneyStore} on Fabric's data attachments.
 *
 * <p>Attachments are the direct analogue of Bukkit's persistent data container: they serialise into
 * the entity's NBT, which is the shared-fate property this store relies on.
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
            Codec.STRING.comapFlatMap(
                    FabricJourneyStore::decodeBase64, Base64.getEncoder()::encodeToString));

    /**
     * {@code comapFlatMap} rather than {@code xmap}, because {@code xmap} does not capture
     * exceptions into the {@link DataResult} — only {@code comapFlatMap} and {@code flatXmap} do.
     * With {@code xmap} the {@link IllegalArgumentException} that {@code Base64.Decoder} throws on
     * a payload that is not base64 propagates out of attachment deserialisation instead of becoming
     * a decode error the platform reports and skips.
     *
     * <p>An error here is the same answer {@code JourneyCodec.decode} gives for bytes it cannot
     * parse: discard rather than guess. The cart comes back with no attachment, which is DORMANT.
     *
     * <p>The message carries the length and not the payload — a corrupt attachment is exactly the
     * case where the string could be arbitrarily long.
     */
    private static DataResult<byte[]> decodeBase64(String encoded) {
        try {
            return DataResult.success(Base64.getDecoder().decode(encoded));
        } catch (IllegalArgumentException e) {
            return DataResult.error(
                    () -> "chunkcarts journey attachment is not base64 (" + encoded.length()
                            + " chars); discarding");
        }
    }

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

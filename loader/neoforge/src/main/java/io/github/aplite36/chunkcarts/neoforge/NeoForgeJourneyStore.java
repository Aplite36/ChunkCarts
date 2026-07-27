package io.github.aplite36.chunkcarts.neoforge;

import com.mojang.serialization.Codec;
import io.github.aplite36.chunkcarts.loader.ChunkCarts;
import io.github.aplite36.chunkcarts.loader.JourneyStore;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
//? if >=1.21.11 {
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
//?} else
/*import net.minecraft.world.entity.vehicle.AbstractMinecart;*/

import java.util.Base64;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link JourneyStore} on NeoForge's data attachments.
 *
 * <p>The same mechanism as the Fabric implementation and the same wire format — base64 in the
 * entity's NBT — deliberately, so a world moved between the two loaders keeps its journeys. What
 * differs is the plumbing: NeoForge attachments are a registry, so the type is registered through a
 * {@link DeferredRegister} on the mod event bus rather than created eagerly.
 *
 * <p>The bytes go through a {@link Codec} for the same reason as on Fabric: a string is the one
 * representation stable across every NBT and JSON backend Mojang has used. The format inside them
 * is {@code core}'s business.
 */
final class NeoForgeJourneyStore implements JourneyStore {

    /**
     * The default for a cart that has never travelled, which is almost every cart on a server. The
     * {@code hasData} guard in {@link #read} means it is never handed out, but the builder requires a
     * default, and an empty array is the answer that cannot be mistaken for a journey.
     */
    private static final byte[] NONE = new byte[0];

    private final DeferredRegister<AttachmentType<?>> attachments =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, ChunkCarts.MOD_ID);

    private final Supplier<AttachmentType<byte[]>> journeyState =
            attachments.register("journey", NeoForgeJourneyStore::createType);

    NeoForgeJourneyStore(IEventBus modEventBus) {
        attachments.register(modEventBus);
    }

    /**
     * The serialiser argument changed from a {@link Codec} to a {@code MapCodec} in NeoForge 21.6,
     * for MC 1.21.6 — a seam of NeoForge's own that lines up with none of the Fabric ones.
     */
    private static AttachmentType<byte[]> createType() {
        Codec<byte[]> base = Codec.STRING.xmap(
                Base64.getDecoder()::decode, Base64.getEncoder()::encodeToString);
        return AttachmentType.<byte[]>builder(() -> NONE)
                //? if >=1.21.6 {
                .serialize(base.fieldOf("journey"))
                //?} else
                /*.serialize(base)*/
                .build();
    }

    /**
     * {@code hasData} then {@code getData}, rather than the shorter {@code getExistingDataOrNull}.
     *
     * <p>Not a style choice. That method is missing from NeoForge 21.0, 21.3 and 21.4 but present in
     * 21.1 and everything from 21.5 on, because 21.1 is the LTS line and got it backported — so its
     * availability follows which NeoForge branches are maintained, not the game version, and there
     * is no {@code >=} predicate that describes it. These two calls exist on all fifteen targets.
     *
     * <p>What matters either way is not calling bare {@code getData}, which materialises the default
     * on first read: that would mark every cart on the server as carrying journey data and write an
     * empty attachment into its NBT.
     */
    @Override
    public Optional<byte[]> read(AbstractMinecart cart) {
        return cart.hasData(journeyState.get())
                ? Optional.of(cart.getData(journeyState.get()))
                : Optional.empty();
    }

    @Override
    public void write(AbstractMinecart cart, byte[] state) {
        if (state == null) {
            cart.removeData(journeyState.get());
        } else {
            cart.setData(journeyState.get(), state);
        }
    }
}

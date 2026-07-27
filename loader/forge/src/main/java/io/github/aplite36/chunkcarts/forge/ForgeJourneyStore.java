package io.github.aplite36.chunkcarts.forge;

import io.github.aplite36.chunkcarts.loader.ChunkCarts;
import io.github.aplite36.chunkcarts.loader.JourneyStore;
import net.minecraft.nbt.CompoundTag;
//? if >=1.21.11 {
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
//?} else
/*import net.minecraft.world.entity.vehicle.AbstractMinecart;*/

import java.util.Base64;
import java.util.Optional;

/**
 * {@link JourneyStore} on Forge's entity persistent data.
 *
 * <p>Forge has <em>no</em> attachment API — verified against the 1.21.11 sources, where
 * {@code net.minecraftforge.attachment} does not exist — so this is where the NeoForge port stops
 * being mechanical. Three routes were available and this is the smallest of them:
 *
 * <ul>
 *   <li>{@code Entity#getPersistentData()}, a Forge patch that adds a {@link CompoundTag} written
 *       under {@code ForgeData} and read back by vanilla's own entity serialisation. Confirmed from
 *       Forge's actual patch to {@code Entity.java}, not from documentation.
 *   <li>Capabilities ({@code ICapabilitySerializable} plus {@code AttachCapabilitiesEvent} plus
 *       {@code RegisterCapabilitiesEvent}): roughly sixty lines and a registration event to store
 *       one byte array, and it writes a {@code ForgeCaps} tag just as unconditionally.
 *   <li>A mod-owned map keyed by UUID, which is ruled out — state has to die with the entity and
 *       roll back with the world.
 * </ul>
 *
 * <p>The payload is the same base64 the Fabric and NeoForge stores write, under the same
 * {@code chunkcarts:journey} key, so a world carried between loaders keeps its journeys. The
 * <em>container</em> necessarily differs — {@code ForgeData} here against
 * {@code neoforge:attachments} there — because that part is the platform's, not ours.
 *
 * <p>One cost, accepted knowingly: {@code getPersistentData()} materialises an empty tag on first
 * call, so a cart this mod has merely looked at gains an empty {@code ForgeData:{}} in its NBT.
 * Forge writes it with {@code storeNullable}, so only a genuinely absent tag is skipped and there
 * is no non-creating accessor to read through. That is a dozen bytes per cart, and the capability
 * route writes its own container just as eagerly, so it buys nothing back.
 */
final class ForgeJourneyStore implements JourneyStore {

    /** Namespaced to match the other two loaders exactly; see the class comment. */
    private static final String KEY = ChunkCarts.MOD_ID + ":journey";

    @Override
    public Optional<byte[]> read(AbstractMinecart cart) {
        CompoundTag data = cart.getPersistentData();
        // CompoundTag's getters went Optional in 1.21.5 — measured across all eleven cached
        // targets, not assumed. On the newer branch an absent key is already an empty Optional,
        // which is exactly the "has never travelled" answer needed here.
        //? if >=1.21.5 {
        return data.getString(KEY).map(Base64.getDecoder()::decode);
        //?} else {
        /*return data.contains(KEY)
                ? Optional.of(Base64.getDecoder().decode(data.getString(KEY)))
                : Optional.empty();
        *///?}
    }

    @Override
    public void write(AbstractMinecart cart, byte[] state) {
        CompoundTag data = cart.getPersistentData();
        if (state == null) {
            // Returns the removed Tag from 1.21.11 and void before it. Ignoring the result is what
            // lets one line cover both.
            data.remove(KEY);
        } else {
            data.putString(KEY, Base64.getEncoder().encodeToString(state));
        }
    }
}

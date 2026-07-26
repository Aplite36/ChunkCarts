package io.github.aplite36.chunkcarts.fabric;

import io.github.aplite36.chunkcarts.core.CartType;
import io.github.aplite36.chunkcarts.core.WorldId;
import io.github.aplite36.chunkcarts.core.platform.CartView;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.vehicle.minecart.MinecartCommandBlock;
import net.minecraft.world.entity.vehicle.minecart.MinecartFurnace;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.entity.vehicle.minecart.MinecartSpawner;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * A live view of one minecart (SPEC §1.1). Reads through to the entity on every call rather than
 * caching, because a view is created fresh each poll and a stale position is the one error that
 * would make every downstream rule wrong at once.
 */
final class FabricCartView implements CartView {

    private final AbstractMinecart cart;
    private final UUID lastInteractor;

    FabricCartView(AbstractMinecart cart, UUID lastInteractor) {
        this.cart = cart;
        this.lastInteractor = lastInteractor;
    }

    AbstractMinecart entity() {
        return cart;
    }

    @Override
    public UUID id() {
        return cart.getUUID();
    }

    @Override
    public WorldId world() {
        return worldIdOf(cart.level());
    }

    /**
     * The dimension id, {@code minecraft:overworld} and friends — not a folder name.
     *
     * <p>This is a real difference from the Bukkit module, where a world is named by its folder and
     * {@code worlds.list} reads {@code world_nether}. There is no shared answer: Bukkit worlds are
     * server-owned folders and loader dimensions are registry ids, and inventing a translation would
     * mean guessing which one an admin meant. The config comment says which form each platform
     * wants; a wrong entry is inert rather than dangerous, because an unmatched name simply fails to
     * match under either {@code worlds.mode}.
     */
    static WorldId worldIdOf(net.minecraft.world.level.Level level) {
        return new WorldId(level.dimension().identifier().toString());
    }

    @Override
    public double x() {
        return cart.position().x;
    }

    @Override
    public double y() {
        return cart.position().y;
    }

    @Override
    public double z() {
        return cart.position().z;
    }

    /**
     * Ground speed in blocks/tick, which is <em>not</em> the length of the motion vector.
     *
     * <p>Measured on Paper 1.21.11 and true here for the same reason: a cart on a continuously
     * powered rail line reports a motion vector of 1.5 while covering about 0.43 blocks a tick. The
     * vector is the boost the rails applied, before the game clamps movement to the cart's maximum
     * speed — intent, not displacement. Reading it raw makes an ordinary booster track look like a
     * speed plugin, which over-reacts everywhere: §5 would halve the poll interval on a stock server
     * for no gain, and §3.4 would call a stopped cart moving.
     *
     * <p>{@link net.minecraft.world.entity.vehicle.minecart.MinecartBehavior#getMaxSpeed} is the
     * clamp the game itself applies, so the smaller of the two is the honest answer. Speed-modifying
     * mods raise it, which is exactly when §5 should fire.
     */
    @Override
    public double velocity() {
        Vec3 motion = cart.getDeltaMovement();
        if (!(cart.level() instanceof ServerLevel level)) {
            return motion.length();
        }
        return Math.min(motion.length(), cart.getBehavior().getMaxSpeed(level));
    }

    @Override
    public CartType type() {
        return typeOf(cart);
    }

    /**
     * Class checks rather than {@code EntityType}, so a version that adds a cart variant fails by
     * reporting NORMAL rather than by throwing on an unknown registry entry. Order matters only in
     * that every branch is a distinct class.
     */
    static CartType typeOf(AbstractMinecart cart) {
        if (cart instanceof MinecartChest) {
            return CartType.CHEST;
        }
        if (cart instanceof MinecartHopper) {
            return CartType.HOPPER;
        }
        if (cart instanceof MinecartFurnace) {
            return CartType.FURNACE;
        }
        if (cart instanceof MinecartTNT) {
            return CartType.TNT;
        }
        if (cart instanceof MinecartCommandBlock) {
            return CartType.COMMAND_BLOCK;
        }
        if (cart instanceof MinecartSpawner) {
            return CartType.SPAWNER;
        }
        return CartType.NORMAL;
    }

    @Override
    public int itemCount() {
        if (!(cart instanceof Container container)) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            total += container.getItem(slot).getCount();
        }
        return total;
    }

    /**
     * The journey state, read from the entity's own persistent data (SPEC §7).
     *
     * <p>Fabric's data attachments are the direct analogue of Bukkit's persistent data container:
     * they serialise into the entity's NBT, so state dies with the entity, travels with it between
     * dimensions, and rolls back together with the world if an admin restores a backup. That
     * shared-fate property is the whole reason §7 puts the source of truth on the entity.
     *
     * <p>The cast is not defensive: Fabric API mixes {@link AttachmentTarget} into {@code Entity} at
     * runtime, which the compiler cannot see.
     */
    @Override
    public Optional<byte[]> readState() {
        return Optional.ofNullable(
                ((AttachmentTarget) cart).getAttached(ChunkCartsFabric.journeyState()));
    }

    @Override
    public void writeState(byte[] state) {
        ((AttachmentTarget) cart).setAttached(ChunkCartsFabric.journeyState(), state);
    }

    @Override
    public Optional<UUID> lastInteractor() {
        return Optional.ofNullable(lastInteractor);
    }
}

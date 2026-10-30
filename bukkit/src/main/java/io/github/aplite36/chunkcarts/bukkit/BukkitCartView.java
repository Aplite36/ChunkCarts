package io.github.aplite36.chunkcarts.bukkit;

import io.github.aplite36.chunkcarts.core.CartType;
import io.github.aplite36.chunkcarts.core.WorldId;
import io.github.aplite36.chunkcarts.core.platform.CartView;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.entity.minecart.SpawnerMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Optional;
import java.util.UUID;

/**
 * A live view of one minecart. Reads through to the entity on every call rather than
 * caching, because a view is created fresh each poll and a stale position is the one error that
 * would make every downstream rule wrong at once.
 */
final class BukkitCartView implements CartView {

    private final Minecart cart;
    private final NamespacedKey stateKey;

    BukkitCartView(Minecart cart, NamespacedKey stateKey) {
        this.cart = cart;
        this.stateKey = stateKey;
    }

    Minecart entity() {
        return cart;
    }

    @Override
    public UUID id() {
        return cart.getUniqueId();
    }

    @Override
    public WorldId world() {
        return new WorldId(cart.getWorld().getName());
    }

    @Override
    public double x() {
        return cart.getLocation().getX();
    }

    @Override
    public double y() {
        return cart.getLocation().getY();
    }

    @Override
    public double z() {
        return cart.getLocation().getZ();
    }

    /**
     * Ground speed in blocks/tick, which is <em>not</em> the length of the motion vector.
     *
     * <p>Measured on Paper 1.21.11: a cart on a continuously powered rail line reports a motion
     * vector of 1.5 while covering about 0.43 blocks a tick. The vector is the boost the rails
     * applied, before the game clamps movement to the cart's max speed — intent, not displacement.
     * Reading it raw makes an ordinary booster track look like a speed plugin, which under-reports
     * nothing but over-reacts everywhere: the velocity clamp would halve the poll interval on a
     * stock server for no gain, and pause tolerance would call a stopped cart moving.
     *
     * <p>{@code getMaxSpeed()} is the clamp the game itself applies, so the smaller of the two is
     * the honest answer. Speed-modifying plugins raise it, which is exactly when the velocity
     * clamp should fire.
     */
    @Override
    public double velocity() {
        return Math.min(cart.getVelocity().length(), cart.getMaxSpeed());
    }

    @Override
    public CartType type() {
        return typeOf(cart);
    }

    /**
     * Interface checks rather than {@code EntityType}, so a version that adds a cart variant fails
     * by reporting NORMAL rather than by throwing on an unknown enum constant. Order matters only
     * in that every branch is a distinct interface.
     */
    static CartType typeOf(Minecart cart) {
        if (cart instanceof StorageMinecart) {
            return CartType.CHEST;
        }
        if (cart instanceof HopperMinecart) {
            return CartType.HOPPER;
        }
        if (cart instanceof PoweredMinecart) {
            return CartType.FURNACE;
        }
        if (cart instanceof ExplosiveMinecart) {
            return CartType.TNT;
        }
        if (cart instanceof CommandMinecart) {
            return CartType.COMMAND_BLOCK;
        }
        if (cart instanceof SpawnerMinecart) {
            return CartType.SPAWNER;
        }
        return CartType.NORMAL;
    }

    @Override
    public int itemCount() {
        if (!(cart instanceof InventoryHolder holder)) {
            return 0;
        }
        Inventory inventory = holder.getInventory();
        int total = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    @Override
    public Optional<byte[]> readState() {
        return Optional.ofNullable(
                cart.getPersistentDataContainer().get(stateKey, PersistentDataType.BYTE_ARRAY));
    }

    @Override
    public void writeState(byte[] state) {
        cart.getPersistentDataContainer().set(stateKey, PersistentDataType.BYTE_ARRAY, state);
    }

}

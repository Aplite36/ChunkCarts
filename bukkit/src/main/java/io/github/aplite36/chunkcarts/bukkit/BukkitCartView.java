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
 * A live view of one minecart (SPEC §1.1). Reads through to the entity on every call rather than
 * caching, because a view is created fresh each poll and a stale position is the one error that
 * would make every downstream rule wrong at once.
 */
final class BukkitCartView implements CartView {

    private final Minecart cart;
    private final NamespacedKey stateKey;
    private final UUID lastInteractor;

    BukkitCartView(Minecart cart, NamespacedKey stateKey, UUID lastInteractor) {
        this.cart = cart;
        this.stateKey = stateKey;
        this.lastInteractor = lastInteractor;
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

    @Override
    public double velocity() {
        return cart.getVelocity().length();
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

    @Override
    public Optional<UUID> lastInteractor() {
        return Optional.ofNullable(lastInteractor);
    }
}

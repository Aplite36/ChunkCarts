package io.github.aplite36.chunkcarts.core.platform;

import io.github.aplite36.chunkcarts.core.CartType;
import io.github.aplite36.chunkcarts.core.WorldId;

import java.util.Optional;
import java.util.UUID;

/**
 * A live view of one minecart. Implementations wrap a platform entity; {@code core} only ever
 * sees this.
 *
 * <p>{@link #readState()} and {@link #writeState(byte[])} are the persistence hook. The entity's
 * own NBT/PDC is the source of truth (SPEC §7) — the in-memory index is a cache — so these are
 * deliberately raw bytes with the schema version in byte zero, and {@code core} owns the format.
 */
public interface CartView {

    UUID id();

    WorldId world();

    double x();

    double y();

    double z();

    /** Speed in blocks per tick. Compared against {@code min-speed}. */
    double velocity();

    CartType type();

    /**
     * Total item count currently inside this cart, or 0 for a type that holds nothing.
     *
     * <p>Required by the §8 unload-hold completion test, which holds while the cluster's total is
     * strictly <em>decreasing</em>. Not listed in §1.1's platform surface — the omission only
     * surfaces when you try to write the arrival-hold tests, since "are items still draining"
     * cannot be answered from position and velocity alone.
     */
    int itemCount();

    /**
     * The persisted journey state, or empty if the cart carries none. First byte is the schema
     * version; an unrecognised version means discard rather than migrate (SPEC §7).
     */
    Optional<byte[]> readState();

    void writeState(byte[] state);

    /**
     * The last player to interact with this cart, used for the cooldown waiver (SPEC §4.1) and
     * optional stranding notification (SPEC §8).
     *
     * <p>Interaction means a right-click, an inventory open, or placement. Hopper insertion,
     * dispenser dispatch, and a player merely standing nearby are not interaction — that
     * distinction is what stops an AFK player beside a dispenser from cycling a cart forever.
     */
    Optional<UUID> lastInteractor();
}

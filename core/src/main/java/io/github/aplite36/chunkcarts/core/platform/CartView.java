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
 * own NBT/PDC is the source of truth — the in-memory index is a cache — so these are
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
     * <p>Required by the unload-hold completion test, which holds while the cluster's total is
     * strictly <em>decreasing</em>. Not listed among the rest of the platform surface — the
     * omission only surfaces when you try to write the arrival-hold tests, since "are items still
     * draining" cannot be answered from position and velocity alone.
     */
    int itemCount();

    /**
     * The persisted journey state, or empty if the cart carries none. First byte is the schema
     * version; an unrecognised version means discard rather than migrate.
     */
    Optional<byte[]> readState();

    void writeState(byte[] state);

    // No lastInteractor() here. It was implemented on both platforms and called by nothing:
    // notify-last-interactor is served by each CartTracker's own map, read directly where the
    // message is sent. Every view built for a poll paid a map lookup to populate a field no one
    // read, and the doc on it described a cooldown rule that CartEvent, not this, decides.
}

package io.github.aplite36.chunkcarts.core;

import java.util.Optional;

/**
 * Serialises {@link JourneyRecord} to the raw bytes an entity carries (SPEC §7).
 *
 * <p>The first byte is the schema version. An unrecognised version means <strong>discard, not
 * migrate</strong>: carts written by an old version will be sitting in unloaded chunks all over
 * the world, and you cannot migrate what you cannot parse.
 */
public final class JourneyCodec {

    /** Written as byte zero of every record. */
    public static final byte SCHEMA_VERSION = 1;

    public byte[] encode(JourneyRecord record) {
        throw new UnsupportedOperationException("step 3");
    }

    /**
     * @return the decoded record, or empty if the schema version is unrecognised or the payload
     *         is malformed — the caller then returns the cart to DORMANT and logs once
     */
    public Optional<JourneyRecord> decode(byte[] bytes) {
        throw new UnsupportedOperationException("step 3");
    }
}

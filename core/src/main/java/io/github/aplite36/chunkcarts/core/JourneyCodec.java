package io.github.aplite36.chunkcarts.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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

    /** A cluster can plausibly be a long train; it cannot plausibly be this. Guards a corrupt length. */
    private static final int MAX_MEMBERS = 4096;

    public byte[] encode(JourneyRecord record) {
        Objects.requireNonNull(record, "record");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(SCHEMA_VERSION);
            out.writeBoolean(record.token());
            out.writeByte(record.state().ordinal());
            out.writeUTF(record.origin().world().value());
            out.writeInt(record.origin().cx());
            out.writeInt(record.origin().cz());
            out.writeInt(record.distanceRecord());
            out.writeInt(record.slackUsed());
            out.writeInt(record.budgetRemaining());
            out.writeLong(record.lastJourneyStartedAt());
            out.writeLong(record.lastInteractionAt());
            out.writeInt(record.dimensionTransitionsUsed());
            writeUuid(out, record.clusterId());
            out.writeInt(record.members().size());
            for (UUID member : record.members()) {
                writeUuid(out, member);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("encoding to a byte array cannot fail", e);
        }
        return bytes.toByteArray();
    }

    /**
     * @return the decoded record, or empty if the schema version is unrecognised or the payload
     *         is malformed — the caller then returns the cart to DORMANT and logs once
     */
    public Optional<JourneyRecord> decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes[0] != SCHEMA_VERSION) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            in.readByte();  // schema version, already checked
            boolean token = in.readBoolean();
            JourneyState state = byOrdinal(in.readByte(), JourneyState.values());
            ChunkPos origin = new ChunkPos(new WorldId(in.readUTF()), in.readInt(), in.readInt());
            int distanceRecord = in.readInt();
            int slackUsed = in.readInt();
            int budgetRemaining = in.readInt();
            long lastJourneyStartedAt = in.readLong();
            long lastInteractionAt = in.readLong();
            int dimensionTransitionsUsed = in.readInt();
            UUID clusterId = readUuid(in);
            int memberCount = in.readInt();
            if (memberCount < 0 || memberCount > MAX_MEMBERS) {
                return Optional.empty();
            }
            Set<UUID> members = new LinkedHashSet<>();
            for (int i = 0; i < memberCount; i++) {
                members.add(readUuid(in));
            }
            return Optional.of(new JourneyRecord(token, state, origin, distanceRecord, slackUsed,
                    budgetRemaining, lastJourneyStartedAt, lastInteractionAt,
                    dimensionTransitionsUsed, clusterId, Set.copyOf(members)));
        } catch (IOException | RuntimeException e) {
            // Truncated, corrupt, or written by something that is not this codec. Same answer as
            // an unknown schema version: discard rather than guess.
            return Optional.empty();
        }
    }

    private static <E extends Enum<E>> E byOrdinal(byte ordinal, E[] values) {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("no constant with ordinal " + ordinal);
        }
        return values[ordinal];
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }
}

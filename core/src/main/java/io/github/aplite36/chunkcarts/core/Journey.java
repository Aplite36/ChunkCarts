package io.github.aplite36.chunkcarts.core;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One journey's mutable state. A cluster is one journey, so this is per-cluster and
 * never per-cart: one origin, one budget, one region, one slot against the concurrency cap.
 *
 * <p>Package-private and deliberately a plain mutable holder. {@link JourneySnapshot} is the
 * read-only view that leaves the package; {@link JourneyRecord} is the persisted form.
 */
final class Journey {

    /** Doubles as the cluster id — a cluster and a journey are the same thing. */
    final UUID id;

    /** The cart that satisfied the departure gates. It owns origin, record, slack and budget. */
    UUID lead;

    /** Fixed at departure, never recomputed per poll. Only the drift leash removes members. */
    final Set<UUID> members = new LinkedHashSet<>();

    JourneyState state;
    ChunkPos origin;
    int distanceRecord;
    int slackUsed;
    int budgetRemaining;
    int dimensionTransitionsUsed;

    /** The lead's chunk at the last poll, for detecting chunk progress and chunks entered. */
    ChunkPos lastChunk;

    /**
     * Epoch millis of the last chunk change. <strong>Never persisted</strong> and reset to now on
     * restore — it measures standing still while we were watching, and downtime is not that.
     */
    long lastProgressAt;

    /**
     * Epoch millis of the last write to the entity. Persisted state is the source of truth, so this
     * bounds how stale it may get between transitions — not itself persisted.
     */
    long lastFlushAt;

    /** Chunks currently held for this journey. */
    final Set<ChunkPos> held = new LinkedHashSet<>();

    /**
     * Every chunk this journey has been charged for. The budget pays for a chunk once: a route
     * that re-enters ground it already held has not made us load anything new.
     */
    final Set<ChunkPos> charged = new LinkedHashSet<>();

    // ---- arrival hold -----------------------------------------------------------------------

    long holdStartedAt;

    /** Cluster item total at the last poll, for the strictly-decreasing completion test. */
    int lastItemTotal;

    /** Epoch millis of the last net decrease. Inbound items must not push this forward. */
    long lastDecreaseAt;

    Journey(UUID id, UUID lead, ChunkPos origin, int budgetRemaining, long now) {
        this.id = id;
        this.lead = lead;
        this.origin = origin;
        this.budgetRemaining = budgetRemaining;
        this.state = JourneyState.TRAVELLING;
        this.lastChunk = origin;
        this.lastProgressAt = now;
        this.lastFlushAt = now;
    }

    JourneySnapshot snapshot() {
        return new JourneySnapshot(id, state, lead, Set.copyOf(members), origin, distanceRecord,
                slackUsed, budgetRemaining, dimensionTransitionsUsed, Set.copyOf(held));
    }

    JourneyRecord toRecord(boolean token, long lastJourneyStartedAt, long lastInteractionAt) {
        return new JourneyRecord(token, state, origin, distanceRecord, slackUsed, budgetRemaining,
                lastJourneyStartedAt, lastInteractionAt, dimensionTransitionsUsed, id,
                Set.copyOf(members));
    }
}

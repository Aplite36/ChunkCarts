package io.github.aplite36.chunkcarts.core;

import java.util.Set;
import java.util.UUID;

/**
 * The journey state persisted on the entity (SPEC §7). PDC/NBT is the source of truth; the
 * in-memory index is a cache for iteration.
 *
 * <p><strong>Note what is deliberately absent: {@code lastProgressAt}.</strong> It is wall-clock,
 * so after any downtime {@code now - lastProgressAt} exceeds {@code pause-tolerance} and the first
 * poll after boot would terminate every restored journey as ARRIVED or STALLED. It must be reset
 * to {@code now} on restore and must not round-trip (SPEC §7.1).
 *
 * <p>{@code lastJourneyStartedAt} and {@code lastInteractionAt} are the asymmetric case: also
 * wall-clock, but they <em>must</em> persist unchanged, because downtime legitimately counts
 * toward a cooldown elapsing.
 */
public record JourneyRecord(
        boolean token,
        JourneyState state,
        ChunkPos origin,
        int distanceRecord,
        int slackUsed,
        int budgetRemaining,
        long lastJourneyStartedAt,
        long lastInteractionAt,
        int dimensionTransitionsUsed,
        UUID clusterId,
        Set<UUID> members) {}

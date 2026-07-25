package io.github.aplite36.chunkcarts.core;

import java.util.Set;
import java.util.UUID;

/**
 * A read-only view of one journey, for {@code /chunkcarts list} (SPEC §10) and for tests.
 *
 * <p>A cluster is <strong>one</strong> journey and therefore one snapshot, showing its cart count,
 * so the row count reconciles with {@code max-concurrent-journeys}.
 *
 * @param leadCart the cart that satisfied the departure gates; it owns origin, record, slack
 *                 and budget for the whole cluster (SPEC §8)
 * @param members  every cart in the cluster, including the lead. Fixed at departure and not
 *                 recomputed per poll
 * @param heldChunks the chunks this journey currently holds tickets for
 */
public record JourneySnapshot(
        UUID journeyId,
        JourneyState state,
        UUID leadCart,
        Set<UUID> members,
        ChunkPos origin,
        int distanceRecord,
        int slackUsed,
        int budgetRemaining,
        int dimensionTransitionsUsed,
        Set<ChunkPos> heldChunks) {

    public int cartCount() {
        return members.size();
    }
}

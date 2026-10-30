package io.github.aplite36.chunkcarts.core;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A read-only view of one journey, for {@code /chunkcarts list} and for tests.
 *
 * <p>A cluster is <strong>one</strong> journey and therefore one snapshot, showing its cart count,
 * so the row count reconciles with {@code max-concurrent-journeys}.
 *
 * @param leadCart the cart that satisfied the departure gates; it owns origin, record, slack
 *                 and budget for the whole cluster
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

    /**
     * Roughly where the cluster is now, as a chunk, or empty if it holds nothing.
     *
     * <p>{@code origin} is where the journey <em>began</em> and can be thousands of blocks behind
     * the cart by the time an admin reads it, which makes a journey they want to go and look at
     * unfindable. There is no current position on this record and adding one would mean a per-poll
     * write for a value only the command reads — but the held region is centred on the carts, so
     * its centre is the carts to within {@code region-radius}, which is at most two chunks in
     * STRICT.
     *
     * <p>The mean is taken over the chunks in the majority world. A journey mid-dimension-transition
     * holds chunks in two, and averaging across them would name a point in neither.
     */
    public Optional<ChunkPos> approximateChunk() {
        if (heldChunks.isEmpty()) {
            return Optional.empty();
        }
        WorldId world = heldChunks.stream()
                .collect(Collectors.groupingBy(ChunkPos::world, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.<WorldId, Long>comparingByValue()
                        .thenComparing(entry -> entry.getKey().value(), Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElseThrow();
        List<ChunkPos> here = heldChunks.stream().filter(chunk -> chunk.world().equals(world)).toList();
        long cx = 0;
        long cz = 0;
        for (ChunkPos chunk : here) {
            cx += chunk.cx();
            cz += chunk.cz();
        }
        return Optional.of(new ChunkPos(world,
                Math.toIntExact(Math.round((double) cx / here.size())),
                Math.toIntExact(Math.round((double) cz / here.size()))));
    }
}

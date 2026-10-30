package io.github.aplite36.chunkcarts.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where {@code /chunkcarts list} says a journey is.
 *
 * <p>Every row used to report {@code origin} and nothing else, which is where the journey
 * <em>began</em> — possibly thousands of blocks behind the cart by the time an admin reads it.
 * There is no current position on the snapshot and adding one would mean a per-poll write for a
 * value only the command reads, so the held region stands in for it: the region is centred on the
 * carts, so its centre is the carts to within {@code region-radius}.
 */
@DisplayName("where a journey is now")
class JourneySnapshotTest {

    private static final WorldId OVERWORLD = new WorldId("overworld");
    private static final WorldId NETHER = new WorldId("the_nether");

    private static JourneySnapshot holding(ChunkPos... held) {
        return new JourneySnapshot(
                UUID.randomUUID(), JourneyState.TRAVELLING, UUID.randomUUID(),
                Set.of(UUID.randomUUID()), new ChunkPos(OVERWORLD, 0, 0),
                12, 0, 400, 0, new LinkedHashSet<>(Arrays.asList(held)));
    }

    @Test
    @DisplayName("a journey holding nothing has nowhere to point at")
    void aJourneyHoldingNothingHasNowhereToPointAt() {
        assertEquals(Optional.empty(), holding().approximateChunk(),
                "the row says so rather than inventing a location");
    }

    @Test
    @DisplayName("the centre of the held region is where the carts are")
    void theCentreOfTheHeldRegionIsWhereTheCartsAre() {
        // The 3x3 a cart at chunk 10,20 holds at region-radius 1.
        ChunkPos[] region = new ChunkPos[9];
        int i = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                region[i++] = new ChunkPos(OVERWORLD, 10 + dx, 20 + dz);
            }
        }

        assertEquals(Optional.of(new ChunkPos(OVERWORLD, 10, 20)),
                holding(region).approximateChunk());
    }

    @Test
    @DisplayName("a single held chunk is the answer on its own")
    void aSingleHeldChunkIsTheAnswerOnItsOwn() {
        assertEquals(Optional.of(new ChunkPos(OVERWORLD, -40, 7)),
                holding(new ChunkPos(OVERWORLD, -40, 7)).approximateChunk());
    }

    @Test
    @DisplayName("mid-dimension-transition it names the world it is mostly in")
    void midDimensionTransitionItNamesTheWorldItIsMostlyIn() {
        // A journey crossing a portal holds chunks in two worlds for a moment. Averaging across
        // both would name a point in neither, and a coordinate in the wrong dimension is worse
        // than no coordinate — an admin flies to it and finds empty track.
        JourneySnapshot journey = holding(
                new ChunkPos(OVERWORLD, 100, 100),
                new ChunkPos(OVERWORLD, 102, 100),
                new ChunkPos(NETHER, -8, -8));

        assertEquals(Optional.of(new ChunkPos(OVERWORLD, 101, 100)), journey.approximateChunk(),
                "the majority world, and a mean taken only over its own chunks");
    }

    @Test
    @DisplayName("an even split picks the same world every time")
    void anEvenSplitPicksTheSameWorldEveryTime() {
        // Two worlds, one chunk each. Whichever it picks, it has to pick it consistently: a row
        // that alternated between dimensions poll to poll would read as the cart teleporting.
        JourneySnapshot journey = holding(
                new ChunkPos(NETHER, -8, -8),
                new ChunkPos(OVERWORLD, 100, 100));

        Optional<ChunkPos> first = journey.approximateChunk();
        assertTrue(first.isPresent());
        for (int i = 0; i < 20; i++) {
            assertEquals(first, journey.approximateChunk(), "unstable tie-break");
        }
    }
}

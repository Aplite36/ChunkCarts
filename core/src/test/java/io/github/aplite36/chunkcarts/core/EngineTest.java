package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import io.github.aplite36.chunkcarts.core.fake.FakeChunkTicketBackend;
import io.github.aplite36.chunkcarts.core.fake.FakeClock;
import io.github.aplite36.chunkcarts.core.fake.FakeWorldQuery;
import io.github.aplite36.chunkcarts.core.platform.CartView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared fixture for the suite. No server, no game, milliseconds.
 *
 * <p>The engine is built lazily so a test can adjust {@link #cfg} first — several tests are
 * about config behaviour at construction time, and a pre-built engine would freeze the defaults
 * before the test could speak.
 */
abstract class EngineTest {

    static final WorldId OVERWORLD = new WorldId("world");
    static final WorldId NETHER = new WorldId("world_nether");

    final FakeClock clock = new FakeClock();
    final FakeWorldQuery world = new FakeWorldQuery();
    final FakeChunkTicketBackend tickets = new FakeChunkTicketBackend();
    final JourneyCodec codec = new JourneyCodec();

    Config.Builder cfg = Config.defaults();

    private ChunkCartsEngine engine;
    private Config built;

    ChunkCartsEngine engine() {
        if (engine == null) {
            built = cfg.build();
            engine = new ChunkCartsEngine(built, world, tickets, clock);
        }
        return engine;
    }

    Config config() {
        engine();
        return built;
    }

    // ---- token plumbing -----------------------------------------------------------------------

    /**
     * Grants a token the only legitimate way: real player presence in the cart's chunk, then an
     * observation. Never by asserting on chunks the engine itself holds.
     */
    void grantToken(FakeCart cart) {
        world.withPlayerLoaded(cart.world(), cart.chunkX(), cart.chunkZ());
        engine().observe(cart);
    }

    // ---- departure ----------------------------------------------------------------------------

    DepartureOutcome depart(FakeCart cart) {
        return engine().tryDepart(cart);
    }

    /** Grants a token and departs. The common opening move for a journey-behaviour test. */
    UUID departWithToken(FakeCart cart) {
        grantToken(cart);
        return assertDeparted(depart(cart));
    }

    static UUID assertDeparted(DepartureOutcome outcome) {
        var departed = assertInstanceOf(DepartureOutcome.Departed.class, outcome,
                "expected the gates to pass");
        return departed.journeyId();
    }

    static void assertDenied(DepartureOutcome outcome, DenialReason expected) {
        var denied = assertInstanceOf(DepartureOutcome.Denied.class, outcome,
                "expected a denial, not a departure");
        assertEquals(expected, denied.reason());
    }

    // ---- the travel loop ----------------------------------------------------------------------

    void poll(FakeCart... carts) {
        engine().poll(List.of(carts));
    }

    /** Advances one poll interval and polls — the rhythm the scheduler would produce. */
    void tickPoll(FakeCart... carts) {
        clock.advanceTicks(config().pollIntervalTicks());
        poll(carts);
    }

    /** Moves the cart one chunk at a time along +X, polling at each chunk. */
    void travelStraight(FakeCart cart, int chunks) {
        for (int i = 0; i < chunks; i++) {
            cart.atChunk(cart.chunkX() + 1, cart.chunkZ());
            tickPoll(cart);
        }
    }

    /**
     * Walks the cart around the perimeter of a square circuit, polling at each chunk.
     *
     * <p>This is the closed-track case the whole product claim rests on: the cart reaches its
     * furthest point on the first lap and can never beat it again.
     */
    void travelLoop(FakeCart cart, int side, int laps) {
        List<int[]> path = squarePath(cart.chunkX(), cart.chunkZ(), side);
        for (int lap = 0; lap < laps; lap++) {
            for (int[] step : path) {
                cart.atChunk(step[0], step[1]);
                tickPoll(cart);
            }
        }
    }

    /** Perimeter chunk coordinates of a square with its corner at the origin, in travel order. */
    static List<int[]> squarePath(int ox, int oz, int side) {
        List<int[]> path = new ArrayList<>();
        for (int i = 1; i <= side; i++) path.add(new int[] {ox + i, oz});
        for (int i = 1; i <= side; i++) path.add(new int[] {ox + side, oz + i});
        for (int i = 1; i <= side; i++) path.add(new int[] {ox + side - i, oz + side});
        for (int i = 1; i <= side; i++) path.add(new int[] {ox, oz + side - i});
        return path;
    }

    // ---- removal ------------------------------------------------------------------------------

    /**
     * Destroys a cart, telling the engine which carts the server still has. The survivors matter:
     * destroying a cluster's lead ends the journey for the rest of it, and ending a journey means
     * writing state back to the carts left behind.
     */
    void removeCart(FakeCart removed, FakeCart... remaining) {
        removeCart(RemovalCause.DESTROYED, removed, remaining);
    }

    void removeCart(RemovalCause cause, FakeCart removed, FakeCart... remaining) {
        Map<UUID, CartView> views = new HashMap<>();
        for (FakeCart cart : remaining) {
            views.put(cart.id(), cart);
        }
        engine().onCartRemoved(removed.id(), cause, views::get);
    }

    // ---- assertions on journey state ----------------------------------------------------------

    JourneySnapshot journeyOf(FakeCart cart) {
        return engine().journeyOf(cart.id())
                .orElseThrow(() -> new AssertionError("expected " + cart.id() + " to have a journey"));
    }

    void assertNoJourney(FakeCart cart) {
        assertTrue(engine().journeyOf(cart.id()).isEmpty(),
                "expected no active journey for " + cart.id());
    }

    void assertTerminated(FakeCart cart, TerminationReason expected) {
        assertEquals(expected, engine().lastTermination(cart.id()).orElse(null),
                "wrong termination reason");
    }

    void assertState(FakeCart cart, JourneyState expected) {
        assertEquals(expected, journeyOf(cart).state());
    }

    /**
     * What is actually on the entity right now, which is the source of truth persistence cares
     * about.
     */
    JourneyRecord persisted(FakeCart cart) {
        return cart.readState().flatMap(codec::decode)
                .orElseThrow(() -> new AssertionError("expected persisted state on " + cart.id()));
    }

    /** A second engine over the same carts: what a restart or {@code /chunkcarts reload} does. */
    ChunkCartsEngine restarted() {
        return new ChunkCartsEngine(config(), world, tickets, clock);
    }
}

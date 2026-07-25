package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.platform.CartView;
import io.github.aplite36.chunkcarts.core.platform.ChunkTicketBackend;
import io.github.aplite36.chunkcarts.core.platform.Clock;
import io.github.aplite36.chunkcarts.core.platform.WorldQuery;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The policy engine. Everything the §3 invariants promise is enforced here, and nothing here
 * touches Minecraft.
 *
 * <p>Unimplemented as of step 2: the §9 suite is committed first, so that any later change to an
 * assertion shows up as a change to a test rather than hiding inside an implementation diff.
 */
public final class ChunkCartsEngine {

    private final Config config;
    private final WorldQuery world;
    private final ChunkTicketBackend tickets;
    private final Clock clock;

    public ChunkCartsEngine(Config config, WorldQuery world, ChunkTicketBackend tickets, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.world = Objects.requireNonNull(world, "world");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Warnings emitted once at startup — PERMISSIVE mode, ignored {@code unload-hold} (SPEC §6). */
    public List<String> startupWarnings() {
        throw new UnsupportedOperationException("step 3");
    }

    /**
     * Observes a cart that is not travelling, granting a token if it sits in a player-loaded
     * chunk (SPEC §3.3).
     *
     * <p>Tokens must never be granted by chunks the plugin itself is holding — self-granting
     * would make the scheme circular and every rule downstream decorative.
     */
    public void observe(CartView cart) {
        throw new UnsupportedOperationException("step 3");
    }

    public boolean hasToken(UUID cartId) {
        throw new UnsupportedOperationException("step 3");
    }

    /**
     * Records something that happened to a cart. Only some events count as interaction for the
     * cooldown waiver — see {@link CartEvent}.
     */
    public void record(UUID cartId, CartEvent event) {
        throw new UnsupportedOperationException("step 3");
    }

    /** Convenience for a solitary cart: no cluster candidates other than itself. */
    public DepartureOutcome tryDepart(CartView cart) {
        return tryDepart(cart, List.of(cart));
    }

    /**
     * Evaluates the SPEC §4 gates and departs if all pass.
     *
     * @param contacts carts in contact with the lead, which become the cluster. Membership is
     *                 fixed here and not recomputed per poll (SPEC §8)
     */
    public DepartureOutcome tryDepart(CartView lead, List<CartView> contacts) {
        throw new UnsupportedOperationException("step 3");
    }

    /**
     * One turn of the travel loop (SPEC §5). Also the drift sample for derived TPS (SPEC §4.2),
     * so it must be called on the scheduler's interval rather than ad hoc.
     */
    public void poll(List<CartView> carts) {
        throw new UnsupportedOperationException("step 3");
    }

    /** Every journey holding chunks, including PAUSED and HOLDING ones. */
    public List<JourneySnapshot> journeys() {
        throw new UnsupportedOperationException("step 3");
    }

    /** The journey this cart belongs to, whether it is the lead or a cluster member. */
    public Optional<JourneySnapshot> journeyOf(UUID cartId) {
        throw new UnsupportedOperationException("step 3");
    }

    /** Why this cart's most recent journey ended, if one has. */
    public Optional<TerminationReason> lastTermination(UUID cartId) {
        throw new UnsupportedOperationException("step 3");
    }

    /** Journeys occupying a concurrency slot. PAUSED and HOLDING count (SPEC §8). */
    public int activeJourneyCount() {
        throw new UnsupportedOperationException("step 3");
    }

    /** TPS derived from scheduler drift, EMA-smoothed (SPEC §4.2). Never a platform TPS call. */
    public double effectiveTps() {
        throw new UnsupportedOperationException("step 3");
    }

    /**
     * Re-registers a cart from its persisted state on chunk load (SPEC §7), giving it a fresh
     * pause window (SPEC §7.1) while leaving its cooldown and interaction timestamps alone.
     */
    public void restore(CartView cart) {
        throw new UnsupportedOperationException("step 3");
    }

    /** Releases anything held on behalf of a cart that no longer exists. */
    public void onCartRemoved(UUID cartId) {
        throw new UnsupportedOperationException("step 3");
    }

    /** Releases every ticket and flushes state. Called on disable, or chunks leak until restart. */
    public void shutdown() {
        throw new UnsupportedOperationException("step 3");
    }
}

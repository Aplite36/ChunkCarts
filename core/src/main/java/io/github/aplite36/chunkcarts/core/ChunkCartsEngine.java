package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.platform.CartView;
import io.github.aplite36.chunkcarts.core.platform.ChunkTicketBackend;
import io.github.aplite36.chunkcarts.core.platform.Clock;
import io.github.aplite36.chunkcarts.core.platform.WorldQuery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The policy engine. Everything the invariants promise is enforced here, and nothing here
 * touches Minecraft.
 */
public final class ChunkCartsEngine {

    /**
     * How long a cluster's item total may sit without a net decrease before the unload hold ends.
     * The test is <em>decrease</em>, not movement: a hopper cart under a farm's output
     * has items pouring in forever, and a movement-based test would hold its chunk forever with it.
     */
    static final Duration UNLOAD_COMPLETION_QUIET_PERIOD = Duration.ofSeconds(10);

    /**
     * How often a journey that is neither transitioning nor setting records still reaches the
     * entity. PDC is the source of truth and the in-memory index is the cache, so the
     * cost of a crash is bounded by this: a few seconds of slack and budget, never the whole
     * journey. Writing per poll instead would be a PDC write per cart twice a second forever.
     */
    static final Duration STATE_FLUSH_INTERVAL = Duration.ofSeconds(5);

    /**
     * The floor under the velocity clamp. At one tick the travel loop runs as often as the
     * server ticks, and anything faster than that region-radius cannot save.
     */
    static final int MIN_POLL_INTERVAL_TICKS = 1;

    /**
     * How long a longer interval must stay warranted before the loop actually slows back down.
     * Velocity wobbles constantly — around corners, up inclines, over a booster — and following it
     * upward at once would reschedule the task every few polls and hand the TPS monitor a stream of
     * samples whose expected interval kept moving underneath them.
     */
    static final Duration INTERVAL_GROWTH_SETTLE = Duration.ofSeconds(5);

    /**
     * The share of a region's outward margin a cart may cover between two polls. A cart sitting at
     * the edge of its own chunk has {@code regionRadius × 16} blocks of held terrain ahead of it;
     * spending half of that per poll leaves a whole poll of margin for the one after.
     */
    private static final double MARGIN_SHARE_PER_POLL = 0.5;

    private static final long NEVER = Long.MIN_VALUE;

    private final Config config;
    private final List<String> startupWarnings;
    private final WorldQuery world;
    private final ChunkTicketBackend tickets;
    private final Clock clock;
    private final JourneyCodec codec = new JourneyCodec();
    private final TpsMonitor tps;

    /** Per-cart state that outlives any one journey: the token, and the two wall-clock stamps. */
    private final Map<UUID, CartState> carts = new HashMap<>();

    /** Active journeys by id, which is also the cluster id. Insertion-ordered for {@code list}. */
    private final Map<UUID, Journey> journeys = new LinkedHashMap<>();

    private final Map<UUID, UUID> journeyByCart = new HashMap<>();
    private final Map<UUID, TerminationReason> lastTermination = new HashMap<>();

    /**
     * Whether any player is online, for {@code departure-requirement: PLAYER_ONLINE}. Pushed in by
     * the adapter rather than pulled: the platform surface is capped at seven operations and
     * warns against growing it, and a join/quit event is something every platform already has.
     */
    private boolean playersOnline = true;

    /** The interval the travel loop should be running at right now. */
    private int pollIntervalTicks;

    /** When a longer interval first became warranted, for {@link #INTERVAL_GROWTH_SETTLE}. */
    private long growEligibleSince = NEVER;

    public ChunkCartsEngine(Config config, WorldQuery world, ChunkTicketBackend tickets, Clock clock) {
        Objects.requireNonNull(config, "config");
        Config.Resolution resolution = config.resolve();
        this.config = resolution.config();
        this.startupWarnings = resolution.warnings();
        this.world = Objects.requireNonNull(world, "world");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tps = new TpsMonitor(this.config.minTps());
        this.pollIntervalTicks = this.config.pollIntervalTicks();
    }

    /** The config actually in force, after the clamps and floors. */
    public Config config() {
        return config;
    }

    /** Warnings emitted once at startup — PERMISSIVE mode, ignored {@code unload-hold}. */
    public List<String> startupWarnings() {
        return startupWarnings;
    }

    /** Told by the adapter on join and quit. Only {@code PLAYER_ONLINE} reads it. */
    public void setPlayersOnline(boolean online) {
        this.playersOnline = online;
    }

    /**
     * Observes a cart that is not travelling, granting a token if it sits in a player-loaded
     * chunk.
     *
     * <p>Tokens must never be granted by chunks the plugin itself is holding — self-granting
     * would make the scheme circular and every rule downstream decorative. That guarantee lives
     * in {@link WorldQuery#isPlayerLoaded}, which answers from player positions and never from
     * chunk load state.
     */
    public void observe(CartView cart) {
        Objects.requireNonNull(cart, "cart");
        if (journeyByCart.containsKey(cart.id())) {
            return;  // not DORMANT; a travelling cart cannot be earning its next token
        }
        if (world.isPlayerLoaded(cart.world(), chunkX(cart), chunkZ(cart))) {
            cartState(cart.id()).token = true;
        }
    }

    public boolean hasToken(UUID cartId) {
        CartState state = carts.get(cartId);
        return state != null && state.token;
    }

    /**
     * Records something that happened to a cart. Only some events count as interaction for the
     * cooldown waiver — see {@link CartEvent}.
     */
    public void record(UUID cartId, CartEvent event) {
        if (event.isInteraction()) {
            CartState state = cartState(cartId);
            state.lastInteractionAt = clock.nowMillis();
            state.cooldownWaiver = true;
        }
    }

    /** Convenience for a solitary cart: no cluster candidates other than itself. */
    public DepartureOutcome tryDepart(CartView cart) {
        return tryDepart(cart, List.of(cart));
    }

    /**
     * Evaluates the departure gates and departs if all pass.
     *
     * @param contacts carts in contact with the lead, which become the cluster. Membership is
     *                 fixed here and not recomputed per poll
     */
    public DepartureOutcome tryDepart(CartView lead, List<CartView> contacts) {
        Objects.requireNonNull(lead, "lead");
        DenialReason denial = gate(lead);
        if (denial != null) {
            return new DepartureOutcome.Denied(denial);  // no token is spent on a denial
        }

        long now = clock.nowMillis();
        Journey journey = new Journey(UUID.randomUUID(), lead.id(), chunkOf(lead),
                config.journeyBudgetChunks(), now);

        // Membership is fixed here. Recomputing it per poll would turn a cart that breaks contact
        // at a station into its own journey, and holding no token it would terminate mid-route.
        List<CartView> cluster = new ArrayList<>();
        journey.members.add(lead.id());
        cluster.add(lead);
        for (CartView contact : contacts) {
            if (journey.members.add(contact.id())) {
                cluster.add(contact);
            }
        }
        for (CartView member : cluster) {
            journeyByCart.put(member.id(), journey.id);
            CartState state = cartState(member.id());
            state.token = false;                 // consumed on the transition to TRAVELLING
            state.lastJourneyStartedAt = now;    // the cooldown runs from start, not end
            state.cooldownWaiver = false;        // one interaction, one dispatch
            lastTermination.remove(member.id());
        }
        journeys.put(journey.id, journey);

        // An ungenerated chunk under the departure region holds nothing and is not a denial: the
        // first poll terminates it as UNGENERATED_CHUNK, which is where that check belongs.
        syncRegion(journey, cluster, true);
        flush(journey, cluster);

        // Before the first poll rather than after it. Polls are where the clamp normally lives, but
        // the gap this cluster is about to cross is the one between now and the next poll, and a
        // fast cart would spend that gap at whatever interval the last poll left behind.
        shrinkTo(targetPollInterval(fastest(cluster)));
        return new DepartureOutcome.Departed(journey.id);
    }

    /**
     * One turn of the travel loop. Also the drift sample for derived TPS,
     * so it must be called on the scheduler's interval rather than ad hoc.
     */
    public void poll(List<CartView> carts) {
        // Sampled against the interval that was in force over the gap just elapsed, which is what
        // the field still holds: the clamp below only moves it once this poll is done.
        tps.sample(clock.nanoTime(), pollIntervalTicks);

        Map<UUID, CartView> views = new HashMap<>();
        for (CartView cart : carts) {
            views.put(cart.id(), cart);
        }
        for (Journey journey : List.copyOf(journeys.values())) {
            CartView lead = views.get(journey.lead);
            if (lead == null) {
                continue;  // its chunk is not loaded, or the caller polls a subset
            }
            if (journey.state == JourneyState.HOLDING) {
                serviceHold(journey, views);
            } else {
                travel(journey, lead, views);
            }
        }

        updatePollInterval(carts);
    }

    /** Every journey holding chunks, including PAUSED and HOLDING ones. */
    public List<JourneySnapshot> journeys() {
        return journeys.values().stream().map(Journey::snapshot).toList();
    }

    /** The journey this cart belongs to, whether it is the lead or a cluster member. */
    public Optional<JourneySnapshot> journeyOf(UUID cartId) {
        UUID journeyId = journeyByCart.get(cartId);
        return journeyId == null ? Optional.empty()
                : Optional.ofNullable(journeys.get(journeyId)).map(Journey::snapshot);
    }

    /** Why this cart's most recent journey ended, if one has. */
    public Optional<TerminationReason> lastTermination(UUID cartId) {
        return Optional.ofNullable(lastTermination.get(cartId));
    }

    /** Journeys occupying a concurrency slot. PAUSED and HOLDING count. */
    public int activeJourneyCount() {
        return journeys.size();
    }

    /** TPS derived from scheduler drift, EMA-smoothed. Never a platform TPS call. */
    public double effectiveTps() {
        return tps.effectiveTps();
    }

    /**
     * The interval the travel loop should currently be running at: the configured value,
     * clamped downward on observed velocity. The adapter reads this after each cycle and
     * reschedules when it changes.
     *
     * <p>This is decided here rather than in the adapter because TPS is measured as the ratio of
     * the expected gap between polls to the actual one, and the expected gap <em>is</em> this
     * number. An adapter that quietly rescheduled itself faster would read as a server running at a
     * fraction of its speed, and would answer a fast cart by denying every departure on the server.
     */
    public int effectivePollIntervalTicks() {
        return pollIntervalTicks;
    }

    /**
     * Re-registers a cart from its persisted state on chunk load, giving it a fresh
     * pause window while leaving its cooldown and interaction timestamps alone.
     */
    public void restore(CartView cart) {
        Optional<byte[]> raw = cart.readState();
        if (raw.isEmpty()) {
            return;
        }
        Optional<JourneyRecord> decoded = codec.decode(raw.get());
        if (decoded.isEmpty()) {
            // Unknown schema version or corrupt payload: discard, do not migrate. The cart goes
            // back to DORMANT and needs a fresh token like any other.
            CartState state = cartState(cart.id());
            state.token = false;
            flushDormant(cart);
            return;
        }

        JourneyRecord record = decoded.get();
        CartState state = cartState(cart.id());
        state.token = record.token();
        // Both of these are wall-clock and both survive the restart unchanged: downtime
        // legitimately counts toward a cooldown elapsing.
        state.lastJourneyStartedAt = record.lastJourneyStartedAt();
        state.lastInteractionAt = record.lastInteractionAt();
        // The waiver is a flag in memory but two timestamps on disk. An interaction later than the
        // last departure is one that has not been spent yet, so it survives the restart with them.
        state.cooldownWaiver = record.lastInteractionAt() > record.lastJourneyStartedAt();

        if (record.state() != JourneyState.TRAVELLING && record.state() != JourneyState.PAUSED) {
            return;
        }

        long now = clock.nowMillis();
        Journey journey = journeys.get(record.clusterId());
        if (journey == null) {
            journey = new Journey(record.clusterId(), cart.id(), record.origin(),
                    record.budgetRemaining(), now);
            journey.state = record.state();
            journey.distanceRecord = record.distanceRecord();
            journey.slackUsed = record.slackUsed();
            journey.dimensionTransitionsUsed = record.dimensionTransitionsUsed();
            journey.lastChunk = chunkOf(cart);
            journeys.put(journey.id, journey);
        }
        journey.members.add(cart.id());
        journeyByCart.put(cart.id(), journey.id);
        // The restart reset. lastProgressAt measures how long a cart has stood still *while we
        // were watching*; three days of downtime is not that, and without this every restored
        // journey dies on the first poll after boot.
        journey.lastProgressAt = now;
        lastTermination.remove(cart.id());

        // Add-only: a cluster re-registers one cart at a time as its chunks load, and a full sync
        // from one member would release the chunks its siblings are holding. The first poll that
        // sees the whole cluster squares the region up. Re-holding what was already held is not
        // new spend either — the budget paid for these chunks before the restart.
        for (ChunkPos chunk : regionAround(cart)) {
            journey.charged.add(chunk);
            if (journey.held.add(chunk)) {
                tickets.hold(chunk.world(), chunk.cx(), chunk.cz());
            }
        }
    }

    /** Releases anything held on behalf of a cart that no longer exists. */
    public void onCartRemoved(UUID cartId) {
        UUID journeyId = journeyByCart.get(cartId);
        if (journeyId != null) {
            Journey journey = journeys.get(journeyId);
            if (journey != null) {
                releaseAll(journey);
                for (UUID member : journey.members) {
                    journeyByCart.remove(member);
                }
                journeys.remove(journey.id);
            }
            journeyByCart.remove(cartId);
        }
        carts.remove(cartId);
    }

    /** Releases every ticket and flushes state. Called on disable, or chunks leak until restart. */
    public void shutdown() {
        tickets.releaseAll();
        journeys.clear();
        journeyByCart.clear();
        // The per-cart registry survives: cooldowns and interaction stamps are wall-clock and must
        // not be forgotten by a reload.
    }

    // ---- departure gates, cheapest first -------------------------------------------------------

    private DenialReason gate(CartView lead) {
        if (!config.isWorldEnabled(lead.world())) {
            return DenialReason.WORLD_DISABLED;
        }
        if (!config.isCartTypeAllowed(lead.type())) {
            return DenialReason.CART_TYPE_FORBIDDEN;
        }
        if (!world.isOnRail(lead)) {
            return DenialReason.NOT_ON_RAIL;
        }
        if (lead.velocity() < config.minSpeed()) {
            return DenialReason.BELOW_MIN_SPEED;
        }
        if (!cooldownSatisfied(lead.id())) {
            return DenialReason.COOLDOWN;
        }
        if (config.departureRequirement() == DepartureRequirement.PLAYER_ONLINE && !playersOnline) {
            return DenialReason.DEPARTURE_REQUIREMENT;
        }
        if (!hasDepartureToken(lead.id())) {
            return DenialReason.NO_TOKEN;
        }
        if (!tps.allowsDeparture()) {
            return DenialReason.TPS_BACKOFF;  // refuse to start; never interrupt one in flight
        }
        if (journeys.size() >= config.maxConcurrentJourneys()) {
            return DenialReason.CONCURRENCY_CAP;  // deny, do not queue
        }
        return null;
    }

    private boolean cooldownSatisfied(UUID cartId) {
        CartState state = carts.get(cartId);
        if (state == null || state.lastJourneyStartedAt == CartState.NEVER) {
            return true;
        }
        // One interaction buys exactly one waived dispatch, and departing spends it. An AFK player
        // beside a dispenser grants tokens through proximity but never interacts, so never earns
        // this. Held as a flag rather than as `lastInteractionAt > lastJourneyStartedAt`, which
        // cannot tell one waived dispatch from unlimited ones when both land in the same
        // millisecond — a right-click and the dispatch it triggers routinely do.
        if (state.cooldownWaiver) {
            return true;
        }
        return clock.nowMillis() - state.lastJourneyStartedAt >= config.journeyCooldown().toMillis();
    }

    private boolean hasDepartureToken(UUID cartId) {
        if (journeyByCart.containsKey(cartId)) {
            return false;  // already travelling; it spent its token to get there
        }
        // Under NONE the cooldown is the only limit, which is why it gains a floor in STRICT.
        // PLAYER_ONLINE is answered by its own gate above.
        return config.departureRequirement() != DepartureRequirement.PLAYER_SEEN
                || hasToken(cartId);
    }

    // ---- the travel loop ------------------------------------------------------------------------

    private void travel(Journey journey, CartView lead, Map<UUID, CartView> views) {
        long now = clock.nowMillis();
        ChunkPos leadChunk = chunkOf(lead);
        JourneyState stateBefore = journey.state;

        dropDrifters(journey, lead, leadChunk, views);

        // Re-checks that still apply in flight. World before dimension: a cart crossing into an
        // excluded world has hit the world gate, not the transition limit.
        if (!config.isWorldEnabled(lead.world())) {
            terminate(journey, TerminationReason.WORLD_DISABLED, views);
            return;
        }
        if (!world.isOnRail(lead)) {
            terminate(journey, TerminationReason.LEFT_RAILS, views);
            return;
        }

        boolean transited = false;
        if (!lead.world().equals(journey.origin.world())) {
            if (journey.dimensionTransitionsUsed >= 1) {
                // Each transit resets the origin, so unlimited transits would loop the progress
                // rule forever.
                terminate(journey, TerminationReason.DIMENSION_LIMIT, views);
                return;
            }
            journey.dimensionTransitionsUsed++;
            journey.origin = leadChunk;
            journey.distanceRecord = 0;
            journey.slackUsed = 0;
            transited = true;
        }

        if (!syncRegion(journey, membersPresent(journey, views), true)) {
            // Refused to generate terrain, which also stops carts being used as worldgen bots.
            terminate(journey, TerminationReason.UNGENERATED_CHUNK, views);
            return;
        }
        if (strict() && journey.budgetRemaining <= 0) {
            terminate(journey, TerminationReason.BUDGET_EXHAUSTED, views);
            return;
        }

        boolean progressed = transited || !leadChunk.equals(journey.lastChunk);
        boolean newRecord = false;

        // The progress rule. A closed track of any diameter sets its record on the first lap
        // and can never beat it, so it always dies within the slack allowance.
        if (!transited) {
            int distance = leadChunk.chebyshevTo(journey.origin);
            if (distance > journey.distanceRecord) {
                journey.distanceRecord = distance;
                journey.slackUsed = 0;
                newRecord = true;
            } else {
                journey.slackUsed += leadChunk.chebyshevTo(journey.lastChunk);
            }
            if (strict() && journey.slackUsed > config.progressSlackChunks()) {
                terminate(journey, TerminationReason.SLACK_EXHAUSTED, views);
                return;
            }
        }

        // Steps 5 and 6 are one mechanism: a cart is terminated for standing still only once the
        // tolerance has elapsed, which is what lets sorting stations and signal stops work.
        if (progressed) {
            journey.lastProgressAt = now;
            journey.lastChunk = leadChunk;
            journey.state = JourneyState.TRAVELLING;
        } else if (now - journey.lastProgressAt >= config.pauseTolerance().toMillis()) {
            // One tolerance, two labels. The action is identical; only the log line differs.
            terminate(journey, lead.velocity() < config.minSpeed()
                    ? TerminationReason.ARRIVED : TerminationReason.STALLED, views);
        } else {
            // A cart between chunk boundaries is still travelling. PAUSED is for one that has
            // actually stopped — at a sorter, a signal, or on an incline.
            journey.state = lead.velocity() < config.minSpeed()
                    ? JourneyState.PAUSED : JourneyState.TRAVELLING;
        }

        // Reached only by a journey that survived this poll: the terminating paths above have
        // already flushed or released. A new record or a state change is a persistence transition
        // and goes out at once; everything else — slack, budget — rides the timer.
        boolean transition = transited || newRecord || journey.state != stateBefore;
        if (transition || now - journey.lastFlushAt >= STATE_FLUSH_INTERVAL.toMillis()) {
            flush(journey, membersPresent(journey, views));
        }
    }

    // ---- the velocity clamp on the poll interval -----------------------------------------------

    /**
     * Brings the poll interval in line with the fastest cart in flight.
     *
     * <p>Vanilla carts cap at 0.4 blocks/tick, so nothing here ever fires on a vanilla server: at
     * {@code region-radius: 1} a cart would have to exceed 0.8 blocks/tick before ten ticks were
     * too long a gap. Speed-modifying plugins exist though, and a cart that crosses its whole held
     * region between two polls arrives in terrain nothing is holding and strands itself there.
     */
    private void updatePollInterval(List<CartView> carts) {
        int target = targetPollInterval(fastestInFlight(carts));
        if (target < pollIntervalTicks) {
            shrinkTo(target);  // the next poll is the one that strands; there is nothing to wait for
        } else if (target > pollIntervalTicks) {
            long now = clock.nowMillis();
            if (growEligibleSince == NEVER) {
                growEligibleSince = now;
            } else if (now - growEligibleSince >= INTERVAL_GROWTH_SETTLE.toMillis()) {
                pollIntervalTicks = target;
                growEligibleSince = NEVER;
            }
        } else {
            growEligibleSince = NEVER;
        }
    }

    private void shrinkTo(int target) {
        if (target < pollIntervalTicks) {
            pollIntervalTicks = target;
            growEligibleSince = NEVER;
        }
    }

    /**
     * Never above the configured interval: the clamp only moves downward, and the admin set a
     * resolution.
     */
    private int targetPollInterval(double fastest) {
        if (fastest <= 0.0) {
            return config.pollIntervalTicks();
        }
        double margin = config.regionRadius() * 16.0 * MARGIN_SHARE_PER_POLL;
        int ticks = (int) Math.floor(margin / fastest);
        return Math.max(MIN_POLL_INTERVAL_TICKS, Math.min(config.pollIntervalTicks(), ticks));
    }

    private double fastestInFlight(List<CartView> carts) {
        double fastest = 0.0;
        for (CartView cart : carts) {
            if (journeyByCart.containsKey(cart.id())) {
                fastest = Math.max(fastest, cart.velocity());
            }
        }
        return fastest;
    }

    /**
     * The fastest of a group, regardless of whether it is travelling. Only used at departure, where
     * the cluster is travelling by definition — a parked cart on a fast track must not be allowed
     * to hold the whole server's travel loop at its own interval.
     */
    private static double fastest(List<CartView> carts) {
        double fastest = 0.0;
        for (CartView cart : carts) {
            fastest = Math.max(fastest, cart.velocity());
        }
        return fastest;
    }

    /**
     * The leash on static membership. Without it five carts could fan out to five places
     * and hold 5 × 9 chunks against one budget and one slot.
     */
    private void dropDrifters(Journey journey, CartView lead, ChunkPos leadChunk,
                              Map<UUID, CartView> views) {
        for (UUID memberId : List.copyOf(journey.members)) {
            if (memberId.equals(journey.lead)) {
                continue;
            }
            CartView member = views.get(memberId);
            if (member == null) {
                continue;
            }
            boolean drifted = !member.world().equals(lead.world())
                    || chunkOf(member).chebyshevTo(leadChunk) > config.regionRadius();
            if (!drifted) {
                continue;
            }
            journey.members.remove(memberId);
            journeyByCart.remove(memberId);
            // Terminated individually and immediately, so the chunks it dragged along are released
            // rather than left held by the cluster it left. A shunt onto a siding stays well inside
            // the leash; this is the cart that ended up a hundred blocks away.
            lastTermination.put(memberId, TerminationReason.DROPPED);
            flushDormant(member);
        }
    }

    /**
     * Brings the held region in line with where the cluster is: hold what should be held, release
     * what should not, charge the budget for anything new.
     *
     * <p>Chunks a player is already loading are skipped entirely. A journey through populated
     * terrain spends nothing because nothing was spent — otherwise routes near spawn fail for no
     * reason.
     *
     * @return false if a chunk about to be held is ungenerated, in which case nothing was changed
     */
    private boolean syncRegion(Journey journey, List<CartView> members, boolean charge) {
        Set<ChunkPos> desired = new LinkedHashSet<>();
        for (CartView member : members) {
            desired.addAll(regionAround(member));
        }

        for (ChunkPos chunk : desired) {
            if (!world.isGenerated(chunk.world(), chunk.cx(), chunk.cz())) {
                return false;
            }
        }

        for (ChunkPos chunk : List.copyOf(journey.held)) {
            if (!desired.contains(chunk)) {
                tickets.release(chunk.world(), chunk.cx(), chunk.cz());
                journey.held.remove(chunk);
            }
        }
        for (ChunkPos chunk : desired) {
            if (journey.held.contains(chunk)) {
                continue;
            }
            // All eight neighbours are held, so the next chunk is already held whichever way the
            // cart turns; only the async timing matters. Prefetch so a boundary crossing never
            // triggers a synchronous load.
            world.prefetch(chunk.world(), chunk.cx(), chunk.cz());
            tickets.hold(chunk.world(), chunk.cx(), chunk.cz());
            journey.held.add(chunk);
            if (journey.charged.add(chunk) && charge) {
                journey.budgetRemaining = Math.max(0, journey.budgetRemaining - 1);
            }
        }
        return true;
    }

    /**
     * The square of {@code region-radius} around one cart, minus anything a player is already
     * loading. Chunks a player holds are not ours to hold, to charge for, or to grant tokens from.
     */
    private Set<ChunkPos> regionAround(CartView cart) {
        Set<ChunkPos> region = new LinkedHashSet<>();
        int radius = config.regionRadius();
        int cx = chunkX(cart);
        int cz = chunkZ(cart);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!world.isPlayerLoaded(cart.world(), cx + dx, cz + dz)) {
                    region.add(new ChunkPos(cart.world(), cx + dx, cz + dz));
                }
            }
        }
        return region;
    }

    // ---- arrival hold -----------------------------------------------------------------------

    private void serviceHold(Journey journey, Map<UUID, CartView> views) {
        long now = clock.nowMillis();
        long settle = config.arrivalHoldTicks() * TpsMonitor.MILLIS_PER_TICK;
        // resolve() has already zeroed unload-hold in STRICT: it is ignored there, not clamped.
        long unload = config.unloadHold().toMillis();
        long elapsed = now - journey.holdStartedAt;

        int total = clusterItemTotal(journey, views);
        boolean complete;
        if (elapsed >= Math.max(settle, unload)) {
            complete = true;
        } else if (elapsed >= settle && unload > 0) {
            // Hold while the cluster's total is strictly decreasing. Not "while items are moving":
            // a cart filling from a farm, or a recirculating hopper line, moves items forever
            // without the total ever falling.
            complete = total == 0
                    || now - journey.lastDecreaseAt >= UNLOAD_COMPLETION_QUIET_PERIOD.toMillis();
        } else {
            complete = false;  // settle time is unconditional, in every mode
        }

        if (total < journey.lastItemTotal) {
            journey.lastDecreaseAt = now;
        }
        journey.lastItemTotal = total;

        if (complete) {
            releaseAll(journey);
            forget(journey, views);
        }
    }

    private int clusterItemTotal(Journey journey, Map<UUID, CartView> views) {
        int total = 0;
        for (UUID memberId : journey.members) {
            CartView member = views.get(memberId);
            if (member != null) {
                total += member.itemCount();
            }
        }
        return total;
    }

    // ---- termination -------------------------------------------------------------------------

    private void terminate(Journey journey, TerminationReason reason, Map<UUID, CartView> views) {
        for (UUID member : journey.members) {
            lastTermination.put(member, reason);
        }

        if (reason == TerminationReason.ARRIVED) {
            // The one reason that holds first: settle time for a detector rail to fire and shunt
            // the cart, plus the unload hold where PERMISSIVE allows it. One hold per cluster.
            journey.state = JourneyState.HOLDING;
            journey.holdStartedAt = clock.nowMillis();
            journey.lastDecreaseAt = journey.holdStartedAt;
            journey.lastItemTotal = clusterItemTotal(journey, views);
            flush(journey, membersPresent(journey, views));
            return;
        }

        journey.state = JourneyState.STRANDED;
        releaseAll(journey);
        forget(journey, views);
    }

    private void releaseAll(Journey journey) {
        for (ChunkPos chunk : journey.held) {
            tickets.release(chunk.world(), chunk.cx(), chunk.cz());
        }
        journey.held.clear();
    }

    /** Drops the journey from the index and returns its carts to DORMANT. */
    private void forget(Journey journey, Map<UUID, CartView> views) {
        journey.state = JourneyState.DORMANT;
        for (UUID member : journey.members) {
            journeyByCart.remove(member);
            CartView view = views.get(member);
            if (view != null) {
                flushDormant(view);
            }
        }
        journeys.remove(journey.id);
    }

    // ---- persistence -------------------------------------------------------------------------

    /**
     * Writes journey state to the entity. PDC/NBT is the source of truth and the in-memory index is
     * a cache, so this happens on the persisted transitions — departure, a new record,
     * termination — and otherwise on {@link #STATE_FLUSH_INTERVAL}, never per poll.
     */
    private void flush(Journey journey, List<CartView> members) {
        journey.lastFlushAt = clock.nowMillis();
        for (CartView member : members) {
            CartState state = cartState(member.id());
            member.writeState(codec.encode(journey.toRecord(state.token,
                    state.lastJourneyStartedAt, state.lastInteractionAt)));
        }
    }

    private void flushDormant(CartView cart) {
        CartState state = cartState(cart.id());
        cart.writeState(codec.encode(new JourneyRecord(state.token, JourneyState.DORMANT,
                chunkOf(cart), 0, 0, 0, state.lastJourneyStartedAt, state.lastInteractionAt,
                0, cart.id(), Set.of())));
    }

    // ---- small helpers ------------------------------------------------------------------------

    private boolean strict() {
        return config.enforcement() == EnforcementMode.STRICT;
    }

    private CartState cartState(UUID cartId) {
        return carts.computeIfAbsent(cartId, id -> new CartState());
    }

    private List<CartView> membersPresent(Journey journey, Map<UUID, CartView> views) {
        List<CartView> present = new ArrayList<>();
        for (UUID member : journey.members) {
            CartView view = views.get(member);
            if (view != null) {
                present.add(view);
            }
        }
        return present;
    }

    private static ChunkPos chunkOf(CartView cart) {
        return new ChunkPos(cart.world(), chunkX(cart), chunkZ(cart));
    }

    private static int chunkX(CartView cart) {
        return (int) Math.floor(cart.x() / 16.0);
    }

    private static int chunkZ(CartView cart) {
        return (int) Math.floor(cart.z() / 16.0);
    }

    /** Per-cart state that outlives any single journey. */
    private static final class CartState {
        static final long NEVER = Long.MIN_VALUE;

        boolean token;
        long lastJourneyStartedAt = NEVER;
        long lastInteractionAt = NEVER;

        /** An unspent interaction, worth exactly one waived dispatch. */
        boolean cooldownWaiver;
    }
}

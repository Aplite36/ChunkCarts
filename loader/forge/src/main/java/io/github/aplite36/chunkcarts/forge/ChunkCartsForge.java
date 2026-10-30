package io.github.aplite36.chunkcarts.forge;

import io.github.aplite36.chunkcarts.core.CartEvent;
import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import io.github.aplite36.chunkcarts.core.Config;
import io.github.aplite36.chunkcarts.core.ConfigSwap;
import io.github.aplite36.chunkcarts.core.WhyReport;
import io.github.aplite36.chunkcarts.core.platform.CartView;
import io.github.aplite36.chunkcarts.loader.CartTracker;
import io.github.aplite36.chunkcarts.loader.ChunkCarts;
import io.github.aplite36.chunkcarts.loader.ChunkCartsCommand;
import io.github.aplite36.chunkcarts.loader.ChunkCartsMod;
import io.github.aplite36.chunkcarts.loader.ConfigFile;
import io.github.aplite36.chunkcarts.loader.JourneyStore;
import io.github.aplite36.chunkcarts.loader.JourneyTicket;
import io.github.aplite36.chunkcarts.loader.LevelTicketBackend;
import io.github.aplite36.chunkcarts.loader.LevelWorldQuery;
import io.github.aplite36.chunkcarts.loader.LoaderMetricsPlatform;
import io.github.aplite36.chunkcarts.loader.SystemClock;
import io.github.aplite36.chunkcarts.loader.TickScheduler;
import io.github.aplite36.chunkcarts.metrics.ChunkCartsMetrics;
//? if >=1.21.5 {
import net.minecraft.core.registries.Registries;
//?}
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
//? if >=1.21.11 {
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
//?} else
/*import net.minecraft.world.entity.vehicle.AbstractMinecart;*/
import net.minecraft.world.item.MinecartItem;
//? if <1.21.6 {
/*import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
*///?}
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Entry point and wiring. The Forge counterpart of the NeoForge entry point.
 *
 * <p>Deliberately kept in the same order and with the same method names as its NeoForge twin, so
 * the two can be read side by side and the divergence is visible rather than buried. The
 * divergence is larger than the NeoForge port's was, and none of it is cosmetic:
 *
 * <ul>
 *   <li><strong>There is no {@code IEventBus}.</strong> Forge 1.21.6+ ships EventBus 7, which
 *       replaced one bus carrying every event with a static bus <em>per event class</em>. So a
 *       listener is {@code EntityJoinLevelEvent.BUS.addListener(consumer)} rather than
 *       {@code EVENT_BUS.addListener(EntityJoinLevelEvent.class, consumer)}.
 *       {@code MinecraftForge.EVENT_BUS} still exists but is only a migration shim, and is not used
 *       here.
 *   <li><strong>The mod bus is a {@code BusGroup}, not a bus.</strong> Mod-loading events are
 *       reached with {@code RegisterEvent.getBus(group)}, the group coming from the injected
 *       context.
 *   <li><strong>Nothing is injected but the context.</strong> Forge tries exactly two constructor
 *       shapes — no-arg, or one {@link FMLJavaModLoadingContext} — confirmed from
 *       {@code FMLModContainer}'s bytecode. NeoForge hands over the mod event bus directly.
 *   <li><strong>No attachments.</strong> See {@link ForgeJourneyStore}; the store needs no
 *       registration at all here, which is why nothing is passed to its constructor.
 * </ul>
 *
 * <p>Owns nothing policy-shaped. Every rule lives in {@code core}, and this class exists to hand it
 * a clock, a world, a ticket backend and a list of carts.
 *
 * <p>The runtime is rebuilt wholesale on {@code /chunkcarts reload} rather than mutated, because a
 * half-reloaded config — new clamps applied to journeys started under the old ones — is a state
 * nothing is tested against. Rebuilding releases every ticket and re-reads each journey from the
 * carts themselves, which is the source of truth anyway.
 */
@Mod(ChunkCarts.MOD_ID)
public final class ChunkCartsForge implements ChunkCartsMod {

    private static final String MOD_ID = ChunkCarts.MOD_ID;

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final String TRAVEL_LOOP = "chunkcarts-travel-loop";

    /** The ticket backend's per-tick sweep. Named so a fault in it is attributable. */
    private static final String TICKET_SWEEP = "chunkcarts-ticket-sweep";

    /**
     * Mods that also hold chunks <em>for minecarts</em>. Both cannot be right.
     *
     * <p>Deliberately not a list of every chunk-loading mod. A player who deliberately force-loads
     * their base is not making ChunkCarts' guarantee false; a second mod that loads chunks because a
     * cart is moving is, because it will keep holding what ChunkCarts released. Warning on the
     * popular general-purpose ones would be noise on most modpacks.
     */
    private static final List<String> RIVALS = List.of(
            "cartload", "cartloader", "cartloaders", "minecartsloadchunks",
            "chunkenforcer", "keepchunks");

    private MinecraftServer server;
    // Volatile: written on the server thread, read on the bStats daemon thread when the
    // reporter polls whether reporting is still wanted.
    private volatile ChunkCartsEngine engine;
    private CartTracker tracker;
    private LevelTicketBackend tickets;
    private TickScheduler scheduler;
    private LevelWorldQuery worlds;
    private final JourneyStore journeys = new ForgeJourneyStore();
    private ChunkCartsMetrics metrics;
    private boolean debug;

    /** Who used a minecart item where, so the cart that appears next can be attributed to them. */
    private PendingPlacement pendingPlacement;

    private record PendingPlacement(UUID player, double x, double y, double z, long gameTime) {}

    /**
     * The two EventBus generations, side by side.
     *
     * <p>The seam is Forge 1.21.6 and it was measured, not guessed: every target's own
     * {@code userdev} config.json names its EventBus build, and they run 6.2.8 → 6.2.33 up to
     * 1.21.5 and 7.0-beta.10 → 7.0.5 from 1.21.6 on. Note that this does <em>not</em> line up with
     * the vanilla seam below it: 1.21.5 needs {@code RegisterEvent}, because the ticket registry
     * exists and is frozen there, but still has to reach it over EventBus 6. That one target is the
     * reason for three branches rather than two.
     *
     * <p>On EventBus 6 every listener names its event class explicitly. The shorter
     * {@code addListener(this::onEntityLoad)} overload exists, but it infers the event type from the
     * consumer's generic signature and a method reference does not carry one, so it fails at
     * registration time rather than at compile time — a silent-wiring failure, on the platform
     * where it is hardest to notice.
     */
    public ChunkCartsForge(FMLJavaModLoadingContext context) {
        ChunkCartsCommand command = new ChunkCartsCommand(this);
        registerTicketType(context);
        wireGameEvents(command);
        warnAboutRivals();
    }

    /**
     * Puts the chunk ticket into the registry, in whichever window this version has for it.
     *
     * <p>Three branches for two independent seams that do not line up. From 1.21.5 the ticket type
     * goes into a vanilla registry and Forge freezes those before the server starts, so registration
     * has to happen inside the window {@code RegisterEvent} opens, on the mod bus rather than the
     * game bus. Before 1.21.5 there is no ticket registry to be frozen out of — the type is a plain
     * object — so it is simply built here. Crossing that is the EventBus seam at 1.21.6, which
     * changes <em>how</em> a mod-bus listener is added. 1.21.5 is the one target that needs the new
     * registry over the old bus.
     */
    private static void registerTicketType(FMLJavaModLoadingContext context) {
        //? if >=1.21.6 {
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event -> {
            if (event.getRegistryKey().equals(Registries.TICKET_TYPE)) {
                JourneyTicket.register();
            }
        });
        //?} elif >=1.21.5 {
        /*context.getModEventBus().addListener(EventPriority.NORMAL, false, RegisterEvent.class,
                event -> {
                    if (event.getRegistryKey().equals(Registries.TICKET_TYPE)) {
                        JourneyTicket.register();
                    }
                });
        *///?} else
        /*JourneyTicket.register();*/
    }

    /**
     * Subscribes to the game events, on whichever bus this version has.
     *
     * <p>The seam is Forge 1.21.6, and it was measured rather than guessed: every target's own
     * {@code userdev} config.json names the EventBus build it ships, and they run 6.2.8 → 6.2.33 up
     * to 1.21.5 and 7.0-beta.10 → 7.0.5 from 1.21.6 on. EventBus 7 replaced one bus carrying every
     * event with a static bus per event class, so there is no object to hold and no event class to
     * pass — the bus already knows its own type.
     *
     * <p>On EventBus 6 each listener names its event class explicitly. The shorter
     * {@code addListener(this::onEntityLoad)} overload does exist, but it infers the event type from
     * the consumer's generic signature, and a method reference carries none — so it fails when the
     * listener is registered rather than when it is compiled. That is a silent-wiring failure, on
     * the branch where it is hardest to notice, and naming the class avoids it.
     */
    private void wireGameEvents(ChunkCartsCommand command) {
        //? if >=1.21.6 {
        ServerStartedEvent.BUS.addListener(event -> start(event.getServer()));
        ServerStoppingEvent.BUS.addListener(event -> serverStopping());

        EntityJoinLevelEvent.BUS.addListener(this::onEntityLoad);
        EntityLeaveLevelEvent.BUS.addListener(this::onEntityUnload);
        PlayerInteractEvent.RightClickBlock.BUS.addListener(this::onUseBlock);

        RegisterCommandsEvent.BUS.addListener(event -> command.register(event.getDispatcher()));
        //?} else {
        /*IEventBus gameBus = MinecraftForge.EVENT_BUS;

        gameBus.addListener(EventPriority.NORMAL, false, ServerStartedEvent.class,
                event -> start(event.getServer()));
        gameBus.addListener(EventPriority.NORMAL, false, ServerStoppingEvent.class,
                event -> serverStopping());

        gameBus.addListener(EventPriority.NORMAL, false, EntityJoinLevelEvent.class,
                this::onEntityLoad);
        gameBus.addListener(EventPriority.NORMAL, false, EntityLeaveLevelEvent.class,
                this::onEntityUnload);
        gameBus.addListener(EventPriority.NORMAL, false, PlayerInteractEvent.RightClickBlock.class,
                this::onUseBlock);

        gameBus.addListener(EventPriority.NORMAL, false, RegisterCommandsEvent.class,
                event -> command.register(event.getDispatcher()));
        *///?}

        wireServerTick();
        wireEntityInteract();
    }

    /**
     * The server-tick subscription, which needs a third seam of its own.
     *
     * <p>The EventBus rewrite at 1.21.6 and the shape of this event are <em>not</em> the same change,
     * which cost a build to find out. On 1.21.6, 1.21.7 and 1.21.8 the event is a sealed class with
     * {@code getServer()}, exactly as it was under EventBus 6; it became a record exposing
     * {@code server()} at 1.21.9. Read off the 1.21.8 sources after those three targets failed on
     * {@code cannot find symbol: method server()} while 1.21.9 and 1.21.11 compiled — the kind of gap
     * only building every candidate finds.
     */
    private void wireServerTick() {
        //? if >=1.21.9 {
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> onServerTick(event.server()));
        //?} elif >=1.21.6 {
        /*TickEvent.ServerTickEvent.Post.BUS.addListener(event -> onServerTick(event.getServer()));
        *///?} else {
        /*MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                TickEvent.ServerTickEvent.Post.class, event -> onServerTick(event.getServer()));
        *///?}
    }

    /**
     * The right-click-on-an-entity subscription, which crosses both seams at once and so gets a
     * method of its own rather than a third copy of everything above.
     *
     * <p>26.1 deleted {@code PlayerInteractEvent.EntityInteract} outright, leaving only
     * {@code EntityInteractSpecific} — the variant that also knows which part of the entity was
     * clicked. Verified by reading the 26.1, 26.1.1, 26.1.2 and 26.2 sources; 1.21.11 and earlier
     * still have both. The two fire at slightly different points of the same click, the Specific one
     * first, and for the purpose here — noting that a player touched this cart — either is correct.
     */
    private void wireEntityInteract() {
        //? if >=26.1 {
        PlayerInteractEvent.EntityInteractSpecific.BUS.addListener(this::onUseEntity);
        //?} elif >=1.21.6 {
        /*PlayerInteractEvent.EntityInteract.BUS.addListener(this::onUseEntity);
        *///?} else {
        /*MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                PlayerInteractEvent.EntityInteract.class, this::onUseEntity);
        *///?}
    }

    /**
     * The mod list, which stopped being a singleton in 26.1 and became a static holder — so
     * {@code ModList.get()} does not exist there. Wrapped once rather than branched at both call
     * sites, because the method names either side of the change are identical and only the receiver
     * moved.
     */
    private static Optional<? extends ModContainer> modContainer(String id) {
        //? if >=26.1 {
        return ModList.getModContainerById(id);
        //?} else
        /*return ModList.get().getModContainerById(id);*/
    }

    private static void warnAboutRivals() {
        for (String rival : RIVALS) {
            modContainer(rival).ifPresent(container -> LOGGER.warn(
                    "{} is installed alongside ChunkCarts. Behaviour is undefined and ChunkCarts' "
                            + "safety guarantee is void: another mod will happily hold chunks "
                            + "ChunkCarts has released.",
                    container.getModInfo().getDisplayName()));
        }
    }

    @Override
    public ChunkCartsEngine engine() {
        return engine;
    }

    @Override
    public int heldChunkCount() {
        return tickets == null ? 0 : tickets.heldCount();
    }

    /**
     * A tally of the load levels of the chunks currently held, as {@code ENTITY_TICKING x9}.
     *
     * <p>Worth a place in {@code list} rather than hiding behind debug: it is the only in-game
     * evidence for the claim no unit test can reach, that held chunks actually tick. On a platform
     * where ticket registration fails silently that is the whole verification.
     */
    @Override
    public String heldChunkLevels() {
        if (tickets == null) {
            return "";
        }
        return tickets.heldLevels().entrySet().stream()
                .map(e -> e.getKey() + " x" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    @Override
    public List<WhyReport.Candidate> cartsNear(ServerPlayer player, double range) {
        return tracker == null ? List.of() : tracker.candidatesNear(player, range);
    }

    @Override
    public Optional<CartView> viewOf(UUID cartId) {
        return tracker == null ? Optional.empty() : tracker.viewOf(cartId);
    }

    @Override
    public boolean heldChunksAllTicking() {
        return tickets == null
                || tickets.heldLevels().keySet().stream()
                        .allMatch(status -> status == FullChunkStatus.ENTITY_TICKING);
    }

    @Override
    public boolean toggleDebug() {
        debug = !debug;
        if (tracker != null) {
            tracker.setDebug(debug);
        }
        if (tickets != null) {
            tickets.setDebug(debug);
        }
        return debug;
    }

    /**
     * Rebuilds the runtime from the file on disk, reading it before anything is torn down.
     *
     * <p>{@link ConfigSwap} holds the ordering and the reasoning; all four adapters go through it so
     * they cannot disagree about what a failed reload leaves behind.
     */
    @Override
    public ConfigSwap.Result reloadRuntime() {
        MinecraftServer running = server;
        if (running == null) {
            return new ConfigSwap.Result(ConfigSwap.Outcome.KEPT, List.of());
        }
        ConfigSwap.Result result = ConfigSwap.swap(
                this::readConfig,
                this::stop,
                prepared -> {
                    start(running, prepared);
                    // Not available before this point: resolve() runs inside the engine's
                    // constructor, so the clamp warnings do not exist until it does.
                    return engine.startupWarnings();
                },
                (message, error) -> LOGGER.error(message, error));
        if (result.outcome() == ConfigSwap.Outcome.RELOADED) {
            tracker.setDebug(debug);
            tickets.setDebug(debug);
        }
        return result;
    }

    /** Reads and validates the config on disk. Touches nothing that is running. */
    private ConfigSwap.Prepared readConfig() {
        return ConfigSwap.prepare(ConfigFile.load(LOGGER, FMLPaths.CONFIGDIR.get()));
    }

    private void start(MinecraftServer startedServer) {
        start(startedServer, ConfigSwap.startup(this::readConfig, LOGGER::error));
    }

    private void start(MinecraftServer startedServer, ConfigSwap.Prepared prepared) {
        server = startedServer;

        prepared.warnings().forEach(LOGGER::warn);

        Config config = prepared.config();
        worlds = new LevelWorldQuery(startedServer);
        tickets = new LevelTicketBackend(worlds, LOGGER);
        engine = new ChunkCartsEngine(config, worlds, tickets, new SystemClock());
        engine.startupWarnings().forEach(LOGGER::warn);
        engine.setPlayersOnline(!startedServer.getPlayerList().getPlayers().isEmpty());

        tracker = new CartTracker(engine, startedServer, LOGGER, journeys);

        // Carts already sitting in loaded levels. On a first boot there are none, but on a reload
        // the world is already populated and the join event has long since fired for all of them.
        for (ServerLevel level : startedServer.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof AbstractMinecart cart) {
                    tracker.track(cart);
                }
            }
        }

        scheduler = new TickScheduler(LOGGER);
        scheduler.repeating(TRAVEL_LOOP, engine.effectivePollIntervalTicks(), this::runTravelLoop);

        startMetrics(startedServer);

        LOGGER.info("ChunkCarts {} enabled — {}, tracking {} cart(s).",
                modVersion(), config.enforcement(), tracker.trackedCount());
    }

    /**
     * Started at most once per server lifetime, though {@code start} itself runs again on every
     * {@code /chunkcarts reload}: each reporter owns a submit schedule, so restarting it per reload
     * would stack them up. The {@code metrics} key is still honoured live — the reporter polls it.
     *
     * <p>Nothing about metrics may stop the mod working, so every failure here is a warning.
     */
    private void startMetrics(MinecraftServer startedServer) {
        if (metrics != null) {
            return;
        }
        try {
            metrics = ChunkCartsMetrics.start(
                    ChunkCartsMetrics.settingsFromFile(
                            FMLPaths.CONFIGDIR.get().resolve("bStats").toFile()),
                    new LoaderMetricsPlatform(startedServer, () -> engine, "Forge", modVersion(),
                            LOGGER));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not start metrics reporting; carrying on without it: {}", e.toString());
        }
    }

    /** Server shutdown, as distinct from {@code /chunkcarts reload}, which also calls {@link #stop}. */
    private void serverStopping() {
        if (metrics != null) {
            metrics.shutdown();
            metrics = null;
        }
        stop();
        server = null;
    }

    /**
     * One cycle of the travel loop, plus the velocity clamp the engine asked for.
     *
     * <p>The interval is read after the cycle, not before: departures happen at the end of a cycle,
     * and a cart fast enough to need a shorter interval usually announces itself by departing. The
     * engine is the only thing that may choose this number, because the same number is what its TPS
     * monitor expects the gap between polls to be.
     */
    private void runTravelLoop() {
        tracker.poll();
        int wanted = engine.effectivePollIntervalTicks();
        if (wanted != scheduler.intervalOf(TRAVEL_LOOP)) {
            if (debug) {
                LOGGER.info("Travel loop interval now {} tick(s) — clamped from {} on observed cart "
                        + "velocity.", wanted, engine.config().pollIntervalTicks());
            }
            scheduler.reschedule(TRAVEL_LOOP, wanted);
        }
    }

    /**
     * Stops the runtime. Also runs on a runtime a failed reload only partly built, so every field
     * may be null, and the fields are cleared even if shutting down throws: a non-null engine is
     * what tells {@code /chunkcarts list} the mod is running.
     *
     * <p>{@code server} is left alone. It is the server that is running, not part of the runtime,
     * and {@link #reloadRuntime()} needs it to rebuild after a failed reload.
     */
    private void stop() {
        try {
            if (scheduler != null) {
                scheduler.cancelAll();
            }
            if (engine != null) {
                // Releases every ticket and sweeps for anything we lost track of. Without this,
                // held chunks stay up until their ticket expires.
                engine.shutdown();
            }
        } finally {
            engine = null;
            tracker = null;
            tickets = null;
            scheduler = null;
            worlds = null;
        }
    }

    private void onServerTick(MinecraftServer ticking) {
        if (scheduler == null) {
            return;
        }
        // Cheaper and more reliable than tracking join and quit events: the adapter pushes
        // this flag, and the player list is the thing the flag is about. Reading it once per tick
        // costs a size check and cannot drift out of step with reality the way an event pair can.
        engine.setPlayersOnline(!ticking.getPlayerList().getPlayers().isEmpty());
        // Both run on the loader's tick event, where an escaping throw is a crash report rather
        // than a logged mistake, so neither is allowed to escape. See TickLoop.
        scheduler.guarded(TICKET_SWEEP, tickets::tick);
        scheduler.tick(ticking);
    }

    /**
     * Registers carts as they appear. One event covers what Bukkit needs three for: a
     * chunk loading, a dispenser firing, and a player placing a cart all arrive here, so a
     * dispenser-fed station — the archetypal ChunkCarts setup — needs no special case.
     *
     * <p>A cart that was travelling before a restart resumes from its own persistent data rather
     * than from anything the mod kept.
     *
     * <p>Fires on the client too, so the side check is not optional: a client-side cart has no
     * journey and tracking it would double-count.
     */
    private void onEntityLoad(EntityJoinLevelEvent event) {
        if (tracker == null || event.getLevel().isClientSide()
                || !(event.getEntity() instanceof AbstractMinecart cart)) {
            return;
        }
        tracker.track(cart);
        claimPlacement(cart);
    }

    private void onEntityUnload(EntityLeaveLevelEvent event) {
        if (tracker != null && !event.getLevel().isClientSide()
                && event.getEntity() instanceof AbstractMinecart cart) {
            tracker.untrack(cart.getUUID());
        }
    }

    /**
     * A right-click on a cart. For a container cart that click <em>is</em> the inventory open — the
     * two are one action here, unlike Bukkit where a separate event fires — so the container case is
     * reported as {@code INVENTORY_OPEN} and everything else as {@code RIGHT_CLICK}.
     *
     * <p>Never cancels. ChunkCarts changes no game behaviour, and this event is cancellable, so
     * touching it would swallow the click. Taking a {@code Consumer} rather than one of EventBus 7's
     * {@code Predicate} overloads is how that is enforced here: a listener that cannot return a
     * verdict cannot accidentally give one.
     */
    //? if >=26.1 {
    private void onUseEntity(PlayerInteractEvent.EntityInteractSpecific event) {
    //?} else
    /*private void onUseEntity(PlayerInteractEvent.EntityInteract event) {*/
        if (tracker == null || event.getLevel().isClientSide()
                || !(event.getTarget() instanceof AbstractMinecart cart)) {
            return;
        }
        tracker.recordInteractor(cart.getUUID(), event.getEntity().getUUID());
        engine.record(cart.getUUID(),
                cart instanceof Container ? CartEvent.INVENTORY_OPEN : CartEvent.RIGHT_CLICK);
    }

    /**
     * A player using a minecart item on a block. The cart does not exist yet, so this only notes who
     * did it and where; {@link #claimPlacement} attributes the entity that appears next.
     *
     * <p>Deliberately narrow. A hopper pushing items into a cart is not interaction, and treating it
     * as one is exactly how an automated loader would waive its own cooldown forever.
     */
    private void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()
                || !(event.getItemStack().getItem() instanceof MinecartItem)) {
            return;
        }
        pendingPlacement = new PendingPlacement(event.getEntity().getUUID(),
                event.getHitVec().getLocation().x,
                event.getHitVec().getLocation().y,
                event.getHitVec().getLocation().z,
                event.getLevel().getGameTime());
    }

    /**
     * Attributes a freshly loaded cart to the player who just placed it, if that is what happened.
     *
     * <p>Same tick and within a couple of blocks. Both bounds matter: a cart arriving in a chunk
     * that loaded later is not the one anybody placed, and a note left lying around would credit the
     * wrong player with an interaction — which waives a cooldown.
     */
    private void claimPlacement(AbstractMinecart cart) {
        PendingPlacement placement = pendingPlacement;
        if (placement == null) {
            return;
        }
        pendingPlacement = null;
        if (cart.level().getGameTime() != placement.gameTime()
                || cart.position().distanceToSqr(placement.x(), placement.y(), placement.z()) > 4.0) {
            return;
        }
        tracker.recordInteractor(cart.getUUID(), placement.player());
        engine.record(cart.getUUID(), CartEvent.PLACEMENT);
    }

    private static String modVersion() {
        return modContainer(MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("(unknown)");
    }
}

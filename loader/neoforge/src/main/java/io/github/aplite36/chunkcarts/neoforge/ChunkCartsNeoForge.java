package io.github.aplite36.chunkcarts.neoforge;

import io.github.aplite36.chunkcarts.core.CartEvent;
import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import io.github.aplite36.chunkcarts.core.Config;
import io.github.aplite36.chunkcarts.core.ConfigSwap;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
//? if >=1.21.11 {
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
//?} else
/*import net.minecraft.world.entity.vehicle.AbstractMinecart;*/
import net.minecraft.world.item.MinecartItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
//? if >=1.21.5 {
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.RegisterEvent;
//?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Entry point and wiring. The NeoForge counterpart of the Fabric entry point, and
 * deliberately its mirror image: the two differ only where the platforms do, so a change to one is
 * visibly a change to the other.
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
public final class ChunkCartsNeoForge implements ChunkCartsMod {

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
    private final JourneyStore journeys;
    private ChunkCartsMetrics metrics;
    private boolean debug;

    /** Who used a minecart item where, so the cart that appears next can be attributed to them. */
    private PendingPlacement pendingPlacement;

    private record PendingPlacement(UUID player, double x, double y, double z, long gameTime) {}

    /**
     * The mod event bus is injected; the game event bus is the static {@link NeoForge#EVENT_BUS}.
     * The split is load-bearing rather than cosmetic — registration happens on the first and only
     * during mod loading, gameplay on the second — and it is the main structural difference from
     * Fabric, where one {@code onInitialize} registers everything.
     */
    public ChunkCartsNeoForge(IEventBus modEventBus) {
        journeys = new NeoForgeJourneyStore(modEventBus);

        // From 1.21.5 the ticket type goes into a vanilla registry, and NeoForge freezes those
        // before the server starts — RegisterEvent is the window in which the registry is
        // unfrozen, so the shared registration runs from there rather than from here. This is the
        // one place the two loaders genuinely disagree: Fabric leaves the registry writable and
        // registers inline. Before 1.21.5 there is no ticket registry to be frozen out of, the
        // type being a plain object, so it is built here on both.
        //? if >=1.21.5 {
        modEventBus.addListener(RegisterEvent.class, event -> {
            if (event.getRegistryKey().equals(Registries.TICKET_TYPE)) {
                JourneyTicket.register();
            }
        });
        //?} else
        /*JourneyTicket.register();*/

        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> start(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> serverStopping());
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class,
                event -> onServerTick(event.getServer()));

        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, this::onEntityLoad);
        NeoForge.EVENT_BUS.addListener(EntityLeaveLevelEvent.class, this::onEntityUnload);
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.EntityInteract.class, this::onUseEntity);
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.RightClickBlock.class, this::onUseBlock);

        ChunkCartsCommand command = new ChunkCartsCommand(this);
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class,
                event -> command.register(event.getDispatcher()));

        warnAboutRivals();
    }

    private static void warnAboutRivals() {
        for (String rival : RIVALS) {
            ModList.get().getModContainerById(rival).ifPresent(container -> LOGGER.warn(
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
     * evidence for the claim no unit test can reach, that held chunks actually tick.
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
    public ConfigSwap.Outcome reloadRuntime() {
        MinecraftServer running = server;
        if (running == null) {
            return ConfigSwap.Outcome.KEPT;
        }
        ConfigSwap.Outcome outcome = ConfigSwap.swap(
                this::readConfig,
                this::stop,
                prepared -> start(running, prepared),
                (message, error) -> LOGGER.error(message, error));
        if (outcome == ConfigSwap.Outcome.RELOADED) {
            tracker.setDebug(debug);
            tickets.setDebug(debug);
        }
        return outcome;
    }

    /** Reads and validates the config on disk. Touches nothing that is running. */
    private ConfigSwap.Prepared readConfig() {
        return ConfigSwap.prepare(ConfigFile.load(LOGGER, FMLPaths.CONFIGDIR.get()));
    }

    private void start(MinecraftServer startedServer) {
        start(startedServer, readConfig());
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
                    new LoaderMetricsPlatform(startedServer, () -> engine, "NeoForge", modVersion(),
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

    private void stop() {
        if (scheduler != null) {
            scheduler.cancelAll();
        }
        if (engine != null) {
            // Releases every ticket and sweeps for anything we lost track of. Without this, held
            // chunks stay up until their ticket expires.
            engine.shutdown();
        }
        engine = null;
        tracker = null;
        tickets = null;
        scheduler = null;
        worlds = null;
        server = null;
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
     * <p>A cart that was travelling before a restart resumes from its own attached state rather than
     * from anything the mod kept.
     *
     * <p>Unlike Fabric's server-only entity events, this one fires on the client too, so the side
     * check is not optional: a client-side cart has no journey and tracking it would double-count.
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
     * touching it would swallow the click.
     */
    private void onUseEntity(PlayerInteractEvent.EntityInteract event) {
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
        return ModList.get().getModContainerById(MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("(unknown)");
    }
}

package io.github.aplite36.chunkcarts.fabric;

import com.mojang.serialization.Codec;
import io.github.aplite36.chunkcarts.core.CartEvent;
import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import io.github.aplite36.chunkcarts.core.Config;
import io.github.aplite36.chunkcarts.core.ConfigReader;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else
/*import net.minecraft.resources.ResourceLocation;*/
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
//? if >=1.21.11 {
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
//?} else
/*import net.minecraft.world.entity.vehicle.AbstractMinecart;*/
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Entry point and wiring (SPEC §12 step 7). Owns nothing policy-shaped: every rule lives in
 * {@code core}, and this class exists to hand it a clock, a world, a ticket backend and a list of
 * carts.
 *
 * <p>The runtime is rebuilt wholesale on {@code /chunkcarts reload} rather than mutated, because a
 * half-reloaded config — new clamps applied to journeys started under the old ones — is a state
 * nothing is tested against. Rebuilding releases every ticket and re-reads each journey from the
 * carts themselves, which is the source of truth anyway (SPEC §7).
 */
public final class ChunkCartsFabric implements ModInitializer {

    public static final String MOD_ID = "chunkcarts";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final String TRAVEL_LOOP = "chunkcarts-travel-loop";

    /**
     * Mods that also hold chunks <em>for minecarts</em>. Both cannot be right (SPEC §8).
     *
     * <p>Deliberately not a list of every chunk-loading mod. A player who deliberately force-loads
     * their base is not making ChunkCarts' guarantee false; a second mod that loads chunks because a
     * cart is moving is, because it will keep holding what ChunkCarts released. Warning on the
     * popular general-purpose ones would be noise on most modpacks.
     */
    private static final List<String> RIVALS = List.of(
            "cartload", "cartloader", "cartloaders", "minecartsloadchunks",
            "chunkenforcer", "keepchunks");

    /**
     * The chunk ticket, registered once and shared by every hold.
     *
     * <p>Flags {@code FLAG_LOADING | FLAG_SIMULATION | FLAG_KEEP_DIMENSION_ACTIVE}, which is what
     * vanilla's ender pearl ticket uses and what a travelling cart needs: the chunk loads, it
     * simulates, and a dimension nobody is standing in keeps ticking so a cart crossing the nether
     * alone does not silently stop. Notably absent is {@code FLAG_PERSIST} — nothing about a held
     * chunk should survive into the save file — and {@code FLAG_CAN_EXPIRE_IF_UNLOADED}, which is
     * about tickets that give up before their chunk arrives.
     *
     * <p>The 100-tick timeout is SPEC §1.1's, and it is the crash story: the travel loop re-adds the
     * ticket every poll, so a server that dies mid-journey drops every held chunk within five
     * seconds instead of leaking it until someone notices.
     *
     * <p>Registering it in {@code BuiltInRegistries.TICKET_TYPE} is not required for a ticket that
     * never persists, but it costs a line and it is what makes the type print as
     * {@code chunkcarts:journey} in a debug dump instead of as an anonymous record.
     */
    //? if >=1.21.5 {
    private static TicketType journeyTicket;
    //?} else
    /*private static TicketType<ChunkPos> journeyTicket;*/

    /**
     * The journey state, attached to the cart entity (SPEC §7). Raw bytes with a schema version in
     * byte zero, base64-encoded because the value has to go through a {@link Codec} and a string is
     * the one representation that is stable across every NBT and JSON backend Mojang has used. The
     * format inside the bytes is {@code core}'s business, not this module's.
     */
    private static AttachmentType<byte[]> journeyState;

    private MinecraftServer server;
    private ChunkCartsEngine engine;
    private CartTracker tracker;
    private LevelTicketBackend tickets;
    private TickScheduler scheduler;
    private FabricWorldQuery worlds;
    private boolean debug;

    /** Who used a minecart item where, so the cart that appears next can be attributed to them. */
    private PendingPlacement pendingPlacement;

    private record PendingPlacement(UUID player, double x, double y, double z, long gameTime) {}

    //? if >=1.21.5 {
    static TicketType journeyTicket() {
        return journeyTicket;
    }
    //?} else {
    /*static TicketType<ChunkPos> journeyTicket() {
        return journeyTicket;
    }
    *///?}

    static AttachmentType<byte[]> journeyState() {
        return journeyState;
    }

    @Override
    public void onInitialize() {
        // Four generations of the same ticket, oldest last. What changes is only how the type is
        // described — flags, then a persist boolean plus a three-valued enum, then a comparator and
        // a timeout — and whether it lives in a registry at all. What does not change is the level
        // arithmetic: TicketStorage and, before it, DistanceManager both build the ticket at
        // ChunkLevel.byStatus(FULL) minus the radius, checked against the bytecode on 1.21, 1.21.4,
        // 1.21.5 and 1.21.8. So TICKET_RADIUS means entity-ticking on every one of them.
        //
        // Two capabilities genuinely do not exist further back, both minor: before 1.21.9 there is
        // no keep-dimension-active flag, so a cart crossing a dimension nobody is in relies on that
        // dimension ticking anyway; and before 1.21.5 the type is not registered, so it prints
        // anonymously in a debug dump.
        // On the oldest branch the chunk position doubles as the ticket's identity value, which is
        // what vanilla's own per-chunk tickets do there and is what makes a re-add refresh rather
        // than stack.
        //? if >=1.21.11 {
        journeyTicket = Registry.register(
                BuiltInRegistries.TICKET_TYPE,
                Identifier.fromNamespaceAndPath(MOD_ID, "journey"),
                new TicketType(100L, TicketType.FLAG_LOADING
                        | TicketType.FLAG_SIMULATION
                        | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));
        //?} elif >=1.21.9 {
        /*journeyTicket = Registry.register(
                BuiltInRegistries.TICKET_TYPE,
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "journey"),
                new TicketType(100L, TicketType.FLAG_LOADING
                        | TicketType.FLAG_SIMULATION
                        | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));
        *///?} elif >=1.21.5 {
        /*journeyTicket = Registry.register(
                BuiltInRegistries.TICKET_TYPE,
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "journey"),
                new TicketType(100L, false, TicketType.TicketUse.LOADING_AND_SIMULATION));
        *///?} else {
        /*journeyTicket = TicketType.create(
                MOD_ID + "_journey", Comparator.comparingLong(ChunkPos::toLong), 100);
        *///?}

        journeyState = AttachmentRegistry.createPersistent(
                //? if >=1.21.11 {
                Identifier.fromNamespaceAndPath(MOD_ID, "journey"),
                //?} else
                /*ResourceLocation.fromNamespaceAndPath(MOD_ID, "journey"),*/
                Codec.STRING.xmap(Base64.getDecoder()::decode, Base64.getEncoder()::encodeToString));

        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(startedServer -> stop());
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

        ServerEntityEvents.ENTITY_LOAD.register(this::onEntityLoad);
        ServerEntityEvents.ENTITY_UNLOAD.register(this::onEntityUnload);
        UseEntityCallback.EVENT.register(this::onUseEntity);
        UseBlockCallback.EVENT.register(this::onUseBlock);

        ChunkCartsCommand command = new ChunkCartsCommand(this);
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registry, environment) -> command.register(dispatcher));

        warnAboutRivals();
    }

    private static void warnAboutRivals() {
        for (String rival : RIVALS) {
            FabricLoader.getInstance().getModContainer(rival).ifPresent(container -> LOGGER.warn(
                    "{} is installed alongside ChunkCarts. Behaviour is undefined and ChunkCarts' "
                            + "safety guarantee is void: another mod will happily hold chunks "
                            + "ChunkCarts has released.",
                    container.getMetadata().getName()));
        }
    }

    ChunkCartsEngine engine() {
        return engine;
    }

    int heldChunkCount() {
        return tickets == null ? 0 : tickets.heldCount();
    }

    /**
     * A tally of the load levels of the chunks currently held, as {@code ENTITY_TICKING x9}.
     *
     * <p>Worth a place in {@code list} rather than hiding behind debug: it is the only in-game
     * evidence for the claim no unit test can reach, that held chunks actually tick.
     */
    String heldChunkLevels() {
        if (tickets == null) {
            return "";
        }
        return tickets.heldLevels().entrySet().stream()
                .map(e -> e.getKey() + " x" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    boolean toggleDebug() {
        debug = !debug;
        if (tracker != null) {
            tracker.setDebug(debug);
        }
        if (tickets != null) {
            tickets.setDebug(debug);
        }
        return debug;
    }

    /** Tears the runtime down and builds it again from the file on disk (SPEC §10). */
    void reloadRuntime() {
        MinecraftServer running = server;
        stop();
        if (running != null) {
            start(running);
            tracker.setDebug(debug);
            tickets.setDebug(debug);
        }
    }

    private void start(MinecraftServer startedServer) {
        server = startedServer;

        ConfigReader.Result parsed = ConfigReader.read(ConfigFile.load(LOGGER));
        parsed.warnings().forEach(LOGGER::warn);

        Config config = parsed.builder().build();
        worlds = new FabricWorldQuery(startedServer);
        tickets = new LevelTicketBackend(worlds, LOGGER);
        engine = new ChunkCartsEngine(config, worlds, tickets, new SystemClock());
        engine.startupWarnings().forEach(LOGGER::warn);
        engine.setPlayersOnline(!startedServer.getPlayerList().getPlayers().isEmpty());

        tracker = new CartTracker(engine, startedServer, LOGGER);

        // Carts already sitting in loaded levels. On a first boot there are none, but on a reload
        // the world is already populated and ENTITY_LOAD has long since fired for all of them.
        for (ServerLevel level : startedServer.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof AbstractMinecart cart) {
                    tracker.track(cart);
                }
            }
        }

        scheduler = new TickScheduler();
        scheduler.repeating(TRAVEL_LOOP, engine.effectivePollIntervalTicks(), this::runTravelLoop);

        LOGGER.info("ChunkCarts {} enabled — {}, tracking {} cart(s).",
                modVersion(), config.enforcement(), tracker.trackedCount());
    }

    /**
     * One cycle of the travel loop, plus the §5 velocity clamp the engine asked for.
     *
     * <p>The interval is read after the cycle, not before: departures happen at the end of a cycle,
     * and a cart fast enough to need a shorter interval usually announces itself by departing. The
     * engine is the only thing that may choose this number, because the same number is what its TPS
     * monitor expects the gap between polls to be (SPEC §4.2).
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
            // chunks stay up until their ticket expires (SPEC §8).
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
        // Cheaper and more reliable than tracking join and quit events: §1.1 has the adapter push
        // this flag, and the player list is the thing the flag is about. Reading it once per tick
        // costs a size check and cannot drift out of step with reality the way an event pair can.
        engine.setPlayersOnline(!ticking.getPlayerList().getPlayers().isEmpty());
        tickets.tick();
        scheduler.tick(ticking);
    }

    /**
     * Registers carts as they appear (SPEC §7). One event covers what Bukkit needs three for: a
     * chunk loading, a dispenser firing, and a player placing a cart all arrive here, so a
     * dispenser-fed station — the archetypal ChunkCarts setup — needs no special case.
     *
     * <p>A cart that was travelling before a restart resumes from its own attached state rather than
     * from anything the mod kept.
     */
    private void onEntityLoad(Entity entity, ServerLevel level) {
        if (tracker == null || !(entity instanceof AbstractMinecart cart)) {
            return;
        }
        tracker.track(cart);
        claimPlacement(cart);
    }

    private void onEntityUnload(Entity entity, ServerLevel level) {
        if (tracker != null && entity instanceof AbstractMinecart cart) {
            tracker.untrack(cart.getUUID());
        }
    }

    /**
     * A right-click on a cart. For a container cart that click <em>is</em> the inventory open — the
     * two are one action here, unlike Bukkit where a separate event fires — so the container case is
     * reported as {@code INVENTORY_OPEN} and everything else as {@code RIGHT_CLICK}.
     *
     * <p>Always returns {@code PASS}. ChunkCarts changes no game behaviour: it never cancels an
     * interaction, and a listener that returned anything else here would swallow the click.
     */
    private InteractionResult onUseEntity(net.minecraft.world.entity.player.Player player,
                                          net.minecraft.world.level.Level level,
                                          net.minecraft.world.InteractionHand hand,
                                          Entity target,
                                          net.minecraft.world.phys.EntityHitResult hit) {
        if (tracker == null || level.isClientSide() || !(target instanceof AbstractMinecart cart)) {
            return InteractionResult.PASS;
        }
        tracker.recordInteractor(cart.getUUID(), player.getUUID());
        engine.record(cart.getUUID(),
                cart instanceof Container ? CartEvent.INVENTORY_OPEN : CartEvent.RIGHT_CLICK);
        return InteractionResult.PASS;
    }

    /**
     * A player using a minecart item on a block. The cart does not exist yet, so this only notes who
     * did it and where; {@link #claimPlacement} attributes the entity that appears next.
     *
     * <p>Deliberately narrow. A hopper pushing items into a cart is not interaction, and treating it
     * as one is exactly how an automated loader would waive its own cooldown forever (SPEC §4.1).
     */
    private InteractionResult onUseBlock(net.minecraft.world.entity.player.Player player,
                                         net.minecraft.world.level.Level level,
                                         net.minecraft.world.InteractionHand hand,
                                         net.minecraft.world.phys.BlockHitResult hit) {
        if (level.isClientSide() || !(player.getItemInHand(hand).getItem() instanceof MinecartItem)) {
            return InteractionResult.PASS;
        }
        pendingPlacement = new PendingPlacement(player.getUUID(),
                hit.getLocation().x, hit.getLocation().y, hit.getLocation().z,
                level.getGameTime());
        return InteractionResult.PASS;
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
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("(unknown)");
    }
}

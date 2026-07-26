package io.github.aplite36.chunkcarts.loader;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else
/*import net.minecraft.resources.ResourceLocation;*/
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;

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
 *
 * <p>Shared by every loader because it is pure Minecraft — NeoForge and Forge add no ticket API of
 * their own that this needs. It is also the highest-risk code in the project (§1.1: registration
 * fails silently), so it exists exactly once and is verified in-game per platform rather than
 * re-derived per adapter.
 */
public final class JourneyTicket {

    /** Kept as a local so the version branches below stay byte-identical across every adapter. */
    private static final String MOD_ID = ChunkCarts.MOD_ID;

    //? if >=1.21.5 {
    private static TicketType journeyTicket;
    //?} else
    /*private static TicketType<ChunkPos> journeyTicket;*/

    private JourneyTicket() {
    }

    //? if >=1.21.5 {
    public static TicketType get() {
        return journeyTicket;
    }
    //?} else {
    /*public static TicketType<ChunkPos> get() {
        return journeyTicket;
    }
    *///?}

    /** Called once from the adapter's entry point, before any chunk is held. */
    public static void register() {
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
    }
}

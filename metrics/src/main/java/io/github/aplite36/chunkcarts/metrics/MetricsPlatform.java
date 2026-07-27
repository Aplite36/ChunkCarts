package io.github.aplite36.chunkcarts.metrics;

/**
 * Everything the metrics reporter needs from the running server, so that the field set it sends is
 * written once rather than once per loader.
 *
 * <p>The point of this interface is that the *shape* of the report cannot drift between platforms.
 * Four adapters each assembling their own JSON is four chances to send a field the README does not
 * disclose; here the adapters answer questions and {@link ChunkCartsMetrics} decides what is sent.
 *
 * <p>Every method is polled at submit time, not read once at startup, so a {@code /chunkcarts
 * reload} that flips {@code metrics} takes effect without a restart.
 */
public interface MetricsPlatform {

    /** Server software as an admin would name it: {@code Paper}, {@code Fabric}, {@code Forge}. */
    String serverSoftware();

    /** The Minecraft version, plain, as {@code 1.21.11}. */
    String minecraftVersion();

    /** Players online right now. */
    int playerCount();

    /** Whether the server authenticates against Mojang. */
    boolean onlineMode();

    /** The ChunkCarts version, read from the platform's own mod or plugin metadata. */
    String modVersion();

    /** The enforcement mode in force, as {@code STRICT} or {@code PERMISSIVE}. */
    String enforcement();

    /** The departure requirement in force, as {@code PLAYER_SEEN}. */
    String departureRequirement();

    /**
     * Whether the {@code metrics} config key is currently true.
     *
     * <p>Separate from the {@code enabled} flag in bStats' own config file: that one is the server
     * owner's global opt-out and is honoured at construction, this one is ours and is honoured on
     * every submit. Either being false stops reporting.
     */
    boolean reportingWanted();

    /**
     * Runs the data collection on whichever thread may safely read the player list.
     *
     * <p>Bukkit's scheduler and {@code MinecraftServer} both satisfy this. Collection is cheap —
     * a list size and two enum names — so this never does real work on the main thread; the upload
     * itself happens on bStats' own daemon thread afterwards.
     */
    void submitOnServerThread(Runnable task);

    void warn(String message, Throwable error);

    void info(String message);
}

package io.github.aplite36.chunkcarts.loader;

import io.github.aplite36.chunkcarts.core.ChunkCartsEngine;
import io.github.aplite36.chunkcarts.metrics.MetricsPlatform;
import io.github.aplite36.chunkcarts.metrics.ReportingSwitch;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * Answers the metrics reporter's questions from {@link MinecraftServer}, for all three mod loaders
 * at once.
 *
 * <p>No preprocessor directives, and that is a checked fact rather than an assumption: {@code
 * getServerVersion()}, {@code getServerModName()}, {@code usesAuthentication()},
 * {@code getPlayerList()} and the inherited {@code execute(Runnable)} all have identical signatures
 * in the mapped jars from 1.21 through 26.3, so one copy spans every target. Only the loader's name, its config directory and the mod version
 * differ, and those are constructor arguments supplied by each entry point.
 */
public final class LoaderMetricsPlatform implements MetricsPlatform {

    private final MinecraftServer server;
    private final Supplier<ChunkCartsEngine> engine;
    private final String serverSoftware;
    private final String modVersion;
    private final Logger logger;
    private final ReportingSwitch reporting = new ReportingSwitch();

    /**
     * @param engine a supplier rather than the engine itself, because {@code /chunkcarts reload}
     *               replaces the engine and briefly leaves none at all
     */
    public LoaderMetricsPlatform(MinecraftServer server, Supplier<ChunkCartsEngine> engine,
                                 String serverSoftware, String modVersion, Logger logger) {
        this.server = server;
        this.engine = engine;
        this.serverSoftware = serverSoftware;
        this.modVersion = modVersion;
        this.logger = logger;
    }

    @Override
    public String serverSoftware() {
        return serverSoftware;
    }

    /**
     * The brand the server sends clients, which each loader patches to name itself and a hybrid
     * patches again. Read on every submit, like every other answer here.
     */
    @Override
    public String serverBrand() {
        return server.getServerModName();
    }

    @Override
    public String minecraftVersion() {
        return server.getServerVersion();
    }

    @Override
    public int playerCount() {
        return server.getPlayerList().getPlayers().size();
    }

    @Override
    public boolean onlineMode() {
        return server.usesAuthentication();
    }

    @Override
    public String modVersion() {
        return modVersion;
    }

    @Override
    public String enforcement() {
        ChunkCartsEngine running = engine.get();
        return running == null ? "unknown" : running.config().enforcement().name();
    }

    @Override
    public String departureRequirement() {
        ChunkCartsEngine running = engine.get();
        return running == null ? "unknown" : running.config().departureRequirement().name();
    }

    /**
     * The {@code metrics} key as it stands right now, so {@code /chunkcarts reload} takes effect.
     *
     * <p>The engine is null for the moment a reload spends between teardown and rebuild. That is not
     * an opt-out, and reporting it as one would shut the reporter down for good — see
     * {@link ReportingSwitch}.
     */
    @Override
    public boolean reportingWanted() {
        ChunkCartsEngine running = engine.get();
        return running == null
                ? reporting.lastKnown()
                : reporting.observe(running.config().metrics());
    }

    /**
     * {@code MinecraftServer} is an {@link java.util.concurrent.Executor}, so this is the server
     * thread. {@code execute} rather than {@code executeBlocking}: a submit that arrives during
     * shutdown should be dropped, not waited on.
     */
    @Override
    public void submitOnServerThread(Runnable task) {
        server.execute(task);
    }

    @Override
    public void warn(String message, Throwable error) {
        logger.warn(message, error);
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }
}

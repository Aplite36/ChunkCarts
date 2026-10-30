package io.github.aplite36.chunkcarts.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Replaces a running runtime with one built from the config on disk, in the one order that is safe:
 * read and validate first, tear down only once the replacement is known to be buildable.
 *
 * <p>Every adapter rebuilds its runtime wholesale on {@code /chunkcarts reload} rather than mutating
 * it, because a half-reloaded config — new clamps applied to journeys started under the old ones —
 * is a state nothing is tested against. That decision is right and is not what this class changes.
 * What it changes is the order: tearing down first means an unreadable config file leaves nothing
 * running and no way back to the configuration that was working a moment earlier, recoverable only
 * by restarting the server. An admin who mistypes a key expects to be told so, not to lose the mod.
 *
 * <p>It lives in {@code core} because the sequence was written out four times — Paper, Fabric,
 * NeoForge and Forge — and the four had drifted into disagreeing about what a failed reload even
 * leaves behind. One copy, with {@link Outcome} naming the three states a caller has to be able to
 * tell apart, is what stops them drifting again.
 */
public final class ConfigSwap {

    /** A config read from disk and validated, with whatever the reader warned about on the way. */
    public record Prepared(Config config, List<String> warnings) {}

    /** What a reload did. The three cases need three different things said to whoever asked. */
    public enum Outcome {

        /** The new config is in force. */
        RELOADED,

        /**
         * The file on disk could not be turned into a working config, so nothing was torn down and
         * the settings already in force are still running. This is the case the ordering exists for.
         */
        KEPT,

        /**
         * The config read cleanly and the rebuild then failed anyway, so the old runtime is gone and
         * no new one replaced it. Nothing can prevent this outright — only reading the config can be
         * rehearsed, not constructing a runtime against a live server — so it is named rather than
         * hidden, because the mod is not running and the admin has to know.
         */
        BROKEN
    }

    private ConfigSwap() {
    }

    /**
     * Reads a raw config map into a validated {@link Config}, doing everything that can fail before
     * the caller commits to it.
     *
     * <p>{@link Config#resolve()} is run and its result discarded on purpose: the engine resolves
     * again when it is constructed, and resolving is pure, so the only reason to do it here is to
     * find out whether it can be done at all while the old runtime is still up.
     */
    public static Prepared prepare(Map<String, Object> raw) {
        ConfigReader.Result parsed = ConfigReader.read(raw);
        Config config = parsed.builder().build();
        config.resolve();
        return new Prepared(config, parsed.warnings());
    }

    /**
     * Runs a reload.
     *
     * @param read     produces a validated config, or throws. Must not touch the running runtime —
     *                 the whole point is that this can fail harmlessly
     * @param teardown stops the running runtime. Not called at all if {@code read} throws
     * @param rebuild  builds the new runtime from what {@code read} returned
     * @param onFailure given a message and the throwable behind it, for the adapter's own logger
     */
    public static Outcome swap(Supplier<Prepared> read, Runnable teardown, Consumer<Prepared> rebuild,
                               BiConsumer<String, Throwable> onFailure) {
        Objects.requireNonNull(read, "read");
        Objects.requireNonNull(teardown, "teardown");
        Objects.requireNonNull(rebuild, "rebuild");
        Objects.requireNonNull(onFailure, "onFailure");

        Prepared prepared;
        try {
            prepared = read.get();
        } catch (RuntimeException error) {
            onFailure.accept("Config reload failed while reading the file, so nothing was changed. "
                    + "ChunkCarts is still running on the settings it already had.", error);
            return Outcome.KEPT;
        }

        teardown.run();
        try {
            rebuild.accept(prepared);
        } catch (RuntimeException error) {
            onFailure.accept("Config reload read the file but could not rebuild the runtime. "
                    + "ChunkCarts is NOT running. Restart the server once the cause is fixed.", error);
            return Outcome.BROKEN;
        }
        return Outcome.RELOADED;
    }
}

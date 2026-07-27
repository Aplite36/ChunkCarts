package io.github.aplite36.chunkcarts.loader;

import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads {@code config/chunkcarts.yml}, writing the annotated default on first run.
 *
 * <p>The schema itself lives in {@code core}'s {@code ConfigReader}, which takes a plain nested map;
 * this class only has to produce that map. Doing it with SnakeYAML rather than a hand-written reader
 * is deliberate: the Bukkit module gets SnakeYAML for free through the server, and an admin who
 * copies a working {@code config.yml} from a Paper server to a Fabric one should get the same
 * parse — including the odd corners of YAML that a subset parser silently gets wrong, like
 * {@code on}/{@code off} booleans and a {@code #} inside a quoted value.
 *
 * <p>{@link SafeConstructor} rather than the default: a config file must not be able to name a Java
 * class to instantiate.
 */
public final class ConfigFile {

    private ConfigFile() {
    }

    /**
     * <p>The directory is the loader's, because each one puts it somewhere different; the file name
     * inside it is not, so {@code chunkcarts.yml} is the same path relative to the config folder on
     * every platform.
     */
    static Path path(Path configDir) {
        return configDir.resolve(ChunkCarts.MOD_ID + ".yml");
    }

    /**
     * The config as a nested map, or an empty map if there is nothing readable.
     *
     * <p>An unreadable file yields defaults rather than a refusal to start, and says so loudly. The
     * defaults are the safe ones — {@code STRICT}, {@code PLAYER_SEEN} — so falling back to them
     * cannot quietly turn the safety guarantee off, which is the only failure mode here that would
     * matter.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> load(Logger log, Path configDir) {
        Path path = path(configDir);
        if (!Files.exists(path)) {
            writeDefault(path, log);
        }
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            log.warn("{} is not a YAML mapping — using defaults.", path);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read {} ({}) — using defaults. Fix the file and run "
                    + "/chunkcarts reload.", path, e.getMessage());
        }
        return new LinkedHashMap<>();
    }

    /**
     * Copies the packaged default out verbatim, comments and all.
     *
     * <p>The comments are half the documentation — {@code enforcement} and {@code unload-hold} are
     * only comprehensible with the paragraph above them — so this is a byte copy of the same file
     * the Bukkit module ships, not a serialisation of the parsed defaults.
     */
    private static void writeDefault(Path path, Logger log) {
        try (InputStream packaged = ConfigFile.class.getResourceAsStream("/config.yml")) {
            if (packaged == null) {
                log.warn("No packaged config.yml in the mod jar — starting on built-in defaults.");
                return;
            }
            Files.createDirectories(path.getParent());
            Files.copy(packaged, path);
            log.info("Wrote default config to {}.", path);
        } catch (IOException e) {
            log.warn("Could not write {} ({}) — starting on built-in defaults.", path, e.getMessage());
        }
    }
}

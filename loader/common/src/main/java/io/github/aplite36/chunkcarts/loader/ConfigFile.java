package io.github.aplite36.chunkcarts.loader;

import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
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
     * The config as a nested map, or an empty map if there is no file to read.
     *
     * <p>A file that exists and cannot be used throws instead of yielding defaults. Swallowing it
     * here meant {@code /chunkcarts reload} saw an empty map, read it as a valid all-defaults
     * config and replaced the running settings with it, reporting success. Throwing lets the reload
     * keep what was running; at startup, where there is nothing running to keep,
     * {@code ConfigSwap.startup} falls back to the defaults instead, and says so.
     *
     * @throws UncheckedIOException     if the file exists and cannot be read
     * @throws IllegalArgumentException if it is not valid YAML, or not a mapping at the top level
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
        Object parsed;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        } catch (YAMLException e) {
            throw new IllegalArgumentException(path + " is not valid YAML: " + e.getMessage(), e);
        }
        if (parsed == null) {
            // An empty file, or one that is all comments: nothing set, so every key is a default.
            return new LinkedHashMap<>();
        }
        if (parsed instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException(path + " is not a YAML mapping of keys to values.");
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

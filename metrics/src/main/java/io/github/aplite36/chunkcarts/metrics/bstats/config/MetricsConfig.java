/*
 * Vendored from bStats (https://github.com/Bastian/bstats-metrics), unmodified apart from the
 * package name. Vendored rather than shaded because MetricsBase refuses to run from a package
 * starting with `org.bstats`, and relocating a dependency would mean a shading plugin on all
 * forty-five loader targets.
 *
 * MIT License
 *
 * Copyright (c) 2021 Bastian Oppermann
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.aplite36.chunkcarts.metrics.bstats.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A simple config for bStats.
 *
 * This class is not used by every platform.
 */
public class MetricsConfig {

    private final File file;
    private final boolean defaultEnabled;

    private String serverUUID;
    private boolean enabled;
    private boolean logErrors;
    private boolean logSentData;
    private boolean logResponseStatusText;

    private boolean didExistBefore = true;

    public MetricsConfig(File file, boolean defaultEnabled) throws IOException {
        this.file = file;
        this.defaultEnabled = defaultEnabled;

        setupConfig();
    }

    public String getServerUUID() {
        return serverUUID;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isLogErrorsEnabled() {
        return logErrors;
    }

    public boolean isLogSentDataEnabled() {
        return logSentData;
    }

    public boolean isLogResponseStatusTextEnabled() {
        return logResponseStatusText;
    }

    /**
     * Checks whether the config file did exist before or not.
     *
     * @return If the config did exist before.
     */
    public boolean didExistBefore() {
        return didExistBefore;
    }

    /**
     * Creates the config file if it does not exist and read its content.
     */
    private void setupConfig() throws IOException {
        if (!file.exists()) {
            didExistBefore = false; // Looks like it's the first time we create it (or someone deleted it).
            writeConfig();
        }
        readConfig();
        if (serverUUID == null) {
            // Found a malformed config file with no UUID. Let's recreate it.
            writeConfig();
            readConfig();
        }
    }

    /**
     * Creates a config file with teh default content.
     */
    private void writeConfig() throws IOException {
        List<String> configContent = new ArrayList<>();
        configContent.add("# bStats (https://bStats.org) collects some basic information for plugin authors, like");
        configContent.add("# how many people use their plugin and their total player count. It's recommended to keep");
        configContent.add("# bStats enabled, but if you're not comfortable with this, you can turn this setting off.");
        configContent.add("# There is no performance penalty associated with having metrics enabled, and data sent to");
        configContent.add("# bStats is fully anonymous.");
        configContent.add("# Learn more here: https://bstats.org/docs/server-owners");
        configContent.add("enabled=" + defaultEnabled);
        configContent.add("server-uuid=" + UUID.randomUUID().toString());
        configContent.add("log-errors=false");
        configContent.add("log-sent-data=false");
        configContent.add("log-response-status-text=false");
        writeFile(file, configContent);
    }

    /**
     * Reads the content of the config file.
     */
    private void readConfig() throws IOException {
        List<String> lines = readFile(file);
        if (lines == null) {
            throw new AssertionError("Content of newly created file is null");
        }

        enabled = getConfigValue("enabled", lines).map("true"::equals).orElse(true);
        serverUUID = getConfigValue("server-uuid", lines).orElse(null);
        logErrors = getConfigValue("log-errors", lines).map("true"::equals).orElse(false);
        logSentData =  getConfigValue("log-sent-data", lines).map("true"::equals).orElse(false);
        logResponseStatusText =  getConfigValue("log-response-status-text", lines).map("true"::equals).orElse(false);
    }

    /**
     * Gets a config setting from the given list of lines of the file.
     *
     * @param key The key for the setting.
     * @param lines The lines of the file.
     * @return The value of the setting.
     */
    private Optional<String> getConfigValue(String key, List<String> lines) {
        return lines.stream()
                .filter(line -> line.startsWith(key + "="))
                .map(line -> line.replaceFirst(Pattern.quote(key + "="), ""))
                .findFirst();
    }

    /**
     * Reads the text content of the given file.
     *
     * @param file The file to read.
     * @return The lines of the given file.
     */
    private List<String> readFile(File file) throws IOException {
        if (!file.exists()) {
            return null;
        }
        try (
                FileReader fileReader = new FileReader(file);
                BufferedReader bufferedReader = new BufferedReader(fileReader)
        ) {
            return bufferedReader.lines().collect(Collectors.toList());
        }
    }

    /**
     * Writes the given lines to the given file.
     *
     * @param file The file to write to.
     * @param lines The lines to write.
     */
    private void writeFile(File file, List<String> lines) throws IOException {
        if (!file.exists()) {
            File parentFile = file.getParentFile();
            if (parentFile != null) {
                parentFile.mkdirs();
            }
            file.createNewFile();
        }
        try (
                FileWriter fileWriter = new FileWriter(file);
                BufferedWriter bufferedWriter = new BufferedWriter(fileWriter)
        ) {
            for (String line : lines) {
                bufferedWriter.write(line);
                bufferedWriter.newLine();
            }
        }
    }

}

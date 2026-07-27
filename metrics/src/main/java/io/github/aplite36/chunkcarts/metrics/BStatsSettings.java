package io.github.aplite36.chunkcarts.metrics;

/**
 * The server owner's bStats settings, as read from bStats' own config file.
 *
 * <p>Read by the adapter rather than by this module, because the file is not the same file on every
 * platform and the difference is not cosmetic. Paper plugins keep YAML at
 * {@code plugins/bStats/config.yml} with a {@code serverUuid} key; every other platform keeps
 * {@code key=value} text at {@code <parent>/bStats/config.txt} with a {@code server-uuid} key. Both
 * are conventions other projects already follow, and following them is what makes the server UUID
 * *shared*: generate a private one and bStats counts one server as two.
 *
 * @param serverUuid            the shared per-server identifier
 * @param enabled               the owner's global opt-out; false means never report
 * @param logErrors             log failed submissions
 * @param logSentData           log the payload of every submission
 * @param logResponseStatusText log bStats' reply
 * @param firstRun              the config file did not exist and has just been created, so the
 *                              owner has not yet been told bStats is running
 */
public record BStatsSettings(
        String serverUuid,
        boolean enabled,
        boolean logErrors,
        boolean logSentData,
        boolean logResponseStatusText,
        boolean firstRun) {
}

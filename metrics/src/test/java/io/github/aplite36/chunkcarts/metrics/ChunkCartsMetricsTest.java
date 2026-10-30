package io.github.aplite36.chunkcarts.metrics;

import io.github.aplite36.chunkcarts.metrics.bstats.charts.SimplePie;
import io.github.aplite36.chunkcarts.metrics.bstats.json.JsonObjectBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The privacy claim, as a test.
 *
 * <p>The README promises a specific list of what leaves a server. That promise is only worth
 * anything if adding a field or a chart to the payload breaks the build, so these tests pin the
 * payload, and {@link ChunkCartsMetrics#DISCLOSED_PLATFORM_FIELDS}, against a literal copy of the
 * README's list, and pin the custom-chart ids alongside it, covering all three paths the class doc
 * for {@link ChunkCartsMetrics} describes. A new field or chart fails the build until this test's
 * literal lists are edited, which is the prompt to edit the README in the same commit.
 */
class ChunkCartsMetricsTest {

    /**
     * The per-server fields the README's "Metrics" section names, restated here on purpose: if the
     * test read {@link ChunkCartsMetrics#DISCLOSED_PLATFORM_FIELDS} instead, a field added to the
     * payload and the constant together would pass without anyone touching the README.
     */
    private static final Set<String> DISCLOSED_IN_README = Set.of(
            "playerAmount", "onlineMode", "bukkitVersion", "bukkitName",
            "javaVersion", "osName", "osArch", "osVersion", "coreCount");

    /** Answers with values that are obviously test data, so a leak into the payload is visible. */
    private static final class FakePlatform implements MetricsPlatform {

        boolean wanted = true;

        @Override public String serverSoftware() { return "Fabric"; }
        @Override public String minecraftVersion() { return "1.21.11"; }
        @Override public int playerCount() { return 3; }
        @Override public boolean onlineMode() { return true; }
        @Override public String modVersion() { return "9.9.9-test"; }
        @Override public String enforcement() { return "STRICT"; }
        @Override public String departureRequirement() { return "PLAYER_SEEN"; }
        @Override public boolean reportingWanted() { return wanted; }
        @Override public void submitOnServerThread(Runnable task) { task.run(); }
        @Override public void warn(String message, Throwable error) { }
        @Override public void info(String message) { }
    }

    private static Set<String> keysOf(String json) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("\"([^\"]+)\":").matcher(json);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private static String platformJson(MetricsPlatform platform) {
        JsonObjectBuilder builder = new JsonObjectBuilder();
        ChunkCartsMetrics.appendPlatformData(builder, platform);
        return builder.build().toString();
    }

    /** Every {@link ChunkCartsMetrics#customCharts} value, keyed by chart id. */
    private static Map<String, String> customChartValues(MetricsPlatform platform) {
        Pattern field = Pattern.compile("\"chartId\":\"([^\"]+)\".*\"value\":\"([^\"]*)\"");
        Map<String, String> values = new LinkedHashMap<>();
        for (SimplePie chart : ChunkCartsMetrics.customCharts(platform)) {
            String json = chart.getRequestJsonObject((message, error) -> { }, false).toString();
            Matcher matcher = field.matcher(json);
            assertTrue(matcher.find(), json);
            values.put(matcher.group(1), matcher.group(2));
        }
        return values;
    }

    @Test
    @DisplayName("the per-server payload contains exactly the disclosed fields")
    void platformPayloadFieldsAreExactlyTheDisclosedOnes() {
        assertEquals(DISCLOSED_IN_README, keysOf(platformJson(new FakePlatform())));
    }

    @Test
    @DisplayName("the disclosed-fields constant matches the README's list")
    void disclosedFieldsConstantMatchesTheReadme() {
        assertEquals(DISCLOSED_IN_README, ChunkCartsMetrics.DISCLOSED_PLATFORM_FIELDS);
    }

    @Test
    @DisplayName("the custom charts are exactly the disclosed ones, including the platform chart")
    void customChartsAreExactlyTheDisclosedOnes() {
        assertEquals(
                Set.of("enforcement", "departure_requirement", "platform"),
                customChartValues(new FakePlatform()).keySet());
    }

    @Test
    @DisplayName("the platform chart reports the same server software as the disclosed field")
    void platformChartMatchesServerSoftware() {
        FakePlatform platform = new FakePlatform();
        assertEquals(platform.serverSoftware(), customChartValues(platform).get("platform"));
    }

    @Test
    @DisplayName("the per-mod payload carries the version and nothing else")
    void servicePayloadCarriesOnlyTheVersion() {
        JsonObjectBuilder builder = new JsonObjectBuilder();
        ChunkCartsMetrics.appendServiceData(builder, new FakePlatform());
        String json = builder.build().toString();

        assertEquals(new LinkedHashSet<>(List.of("pluginVersion")), keysOf(json));
        assertTrue(json.contains("9.9.9-test"), json);
    }

    @Test
    @DisplayName("the game version is sent in the shape the game-version chart reads")
    void gameVersionUsesTheBukkitVersionShape() {
        assertTrue(platformJson(new FakePlatform()).contains("Fabric (MC: 1.21.11)"),
                "bukkitVersion must keep the `<software> (MC: <version>)` shape");
    }

    @Test
    @DisplayName("no world, player or address data reaches the payload")
    void payloadCarriesNothingIdentifying() {
        String json = platformJson(new FakePlatform()).toLowerCase();
        for (String forbidden : List.of("uuid", "player_name", "playername", "world", "ip",
                "address", "coord", "location", "seed", "motd", "port")) {
            assertFalse(json.contains(forbidden),
                    "payload must not mention `" + forbidden + "`: " + json);
        }
    }

    @Test
    @DisplayName("the config key is read on every submit, not captured at startup")
    void reportingWantedIsPolled() {
        FakePlatform platform = new FakePlatform();
        assertTrue(platform.reportingWanted());

        // The reload path depends on this: MetricsBase holds the supplier, not its value, so
        // flipping `metrics` in the config stops reporting without a restart.
        platform.wanted = false;
        assertFalse(platform.reportingWanted());
    }
}

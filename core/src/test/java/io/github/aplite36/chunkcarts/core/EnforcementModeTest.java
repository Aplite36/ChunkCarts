package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.fake.FakeCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clamps and enforcement.
 *
 * <p>Without clamps, permissive behaviour exists implicitly via extreme thresholds and the
 * enforcement flag becomes theatre. That is the whole point of this file.
 */
@DisplayName("Clamps and enforcement")
class EnforcementModeTest extends EngineTest {

    private Config.Resolution resolveStrict(Config.Builder builder) {
        return builder.enforcement(EnforcementMode.STRICT).build().resolve();
    }

    @Test
    @DisplayName("PERMISSIVE mode bypasses the progress rule")
    void permissiveModeBypassesProgressRule() {
        cfg.enforcement(EnforcementMode.PERMISSIVE).progressSlackChunks(16);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);

        travelLoop(cart, 4, 10);

        assertNotEquals(TerminationReason.SLACK_EXHAUSTED,
                engine().lastTermination(cart.id()).orElse(null),
                "in PERMISSIVE, minecarts CAN be used as chunk loaders — that is the contract");
    }

    @Test
    @DisplayName("PERMISSIVE mode still requires a departure token")
    void permissiveModeStillRequiresDepartureToken() {
        cfg.enforcement(EnforcementMode.PERMISSIVE);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);

        assertDenied(depart(cart), DenialReason.NO_TOKEN);
    }

    @Test
    @DisplayName("PERMISSIVE mode still enforces the cooldown")
    void permissiveModeStillEnforcesCooldown() {
        cfg.enforcement(EnforcementMode.PERMISSIVE).journeyCooldown(Duration.ofMinutes(10));
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0);
        departWithToken(cart);
        travelStraight(cart, 2);
        engine().shutdown();

        grantToken(cart);
        assertDenied(depart(cart), DenialReason.COOLDOWN);
    }

    @Test
    @DisplayName("PERMISSIVE mode still enforces the cart-type list")
    void permissiveModeStillEnforcesCartTypeList() {
        cfg.enforcement(EnforcementMode.PERMISSIVE);
        FakeCart cart = FakeCart.at(OVERWORLD, 0, 0).type(CartType.SPAWNER);
        grantToken(cart);

        assertDenied(depart(cart), DenialReason.CART_TYPE_FORBIDDEN);
    }

    @Test
    @DisplayName("STRICT clamps excessive slack")
    void strictModeClampsExcessiveSlack() {
        var resolved = resolveStrict(Config.defaults().progressSlackChunks(5000));

        assertEquals(Config.STRICT_MAX_PROGRESS_SLACK_CHUNKS, resolved.config().progressSlackChunks());
        assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("progress-slack-chunks")),
                "clamping must warn, or the operator never learns their setting was overruled");
    }

    @Test
    @DisplayName("STRICT clamps excessive budget")
    void strictModeClampsExcessiveBudget() {
        var resolved = resolveStrict(Config.defaults().journeyBudgetChunks(999_999));

        assertEquals(Config.STRICT_MAX_JOURNEY_BUDGET_CHUNKS, resolved.config().journeyBudgetChunks());
        assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("journey-budget-chunks")));
    }

    @Test
    @DisplayName("STRICT clamps an excessive arrival hold")
    void strictModeClampsExcessiveArrivalHold() {
        var resolved = resolveStrict(Config.defaults().arrivalHoldTicks(20_000));

        assertEquals(Config.STRICT_MAX_ARRIVAL_HOLD_TICKS, resolved.config().arrivalHoldTicks());
        assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("arrival-hold-ticks")));
    }

    @Test
    @DisplayName("STRICT clamps an excessive pause tolerance")
    void strictModeClampsExcessivePauseTolerance() {
        // A long tolerance re-opens the stationary-cart hole.
        var resolved = resolveStrict(Config.defaults().pauseTolerance(Duration.ofMinutes(30)));

        assertEquals(Config.STRICT_MAX_PAUSE_TOLERANCE, resolved.config().pauseTolerance());
        assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("pause-tolerance")));
    }

    @Test
    @DisplayName("STRICT raises a zero min-speed to the floor")
    void strictModeRaisesZeroMinSpeedToFloor() {
        // At 0, a stationary cart on a rail satisfies the departure gate, departs, and holds its
        // region while never entering a new chunk.
        var resolved = resolveStrict(Config.defaults().minSpeed(0.0));

        assertEquals(Config.STRICT_MIN_SPEED_FLOOR, resolved.config().minSpeed(), 1e-9);
        assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("min-speed")));
    }

    @Test
    @DisplayName("STRICT enforces the cooldown floor under departure-requirement NONE")
    void strictModeEnforcesCooldownFloorUnderNoneRequirement() {
        // The progress rule stops loops; only the cooldown stops repetition. With neither, a
        // dispenser can launch carts down a straight line continuously and keep a corridor loaded.
        var resolved = resolveStrict(Config.defaults()
                .departureRequirement(DepartureRequirement.NONE)
                .journeyCooldown(Duration.ZERO));

        assertEquals(Config.STRICT_MIN_COOLDOWN_UNDER_NONE, resolved.config().journeyCooldown());
        assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("journey-cooldown")));
    }

    @Test
    @DisplayName("the cooldown floor does not apply under PLAYER_SEEN")
    void cooldownFloorNotAppliedUnderPlayerSeen() {
        // Under PLAYER_SEEN the token requirement already bounds repetition, so the floor would be
        // gratuitous friction.
        var resolved = resolveStrict(Config.defaults()
                .departureRequirement(DepartureRequirement.PLAYER_SEEN)
                .journeyCooldown(Duration.ofSeconds(5)));

        assertEquals(Duration.ofSeconds(5), resolved.config().journeyCooldown());
        assertTrue(resolved.warnings().stream().noneMatch(w -> w.contains("journey-cooldown")));
    }

    @Test
    @DisplayName("STRICT clamps an absurd journey cooldown")
    void strictModeClampsAbsurdJourneyCooldown() {
        // The one duration with no ceiling. A cooldown of centuries is not a rate limit, it is a
        // typo that retires every cart on the server.
        var resolved = resolveStrict(Config.defaults().journeyCooldown(Duration.ofDays(3650)));

        assertEquals(Config.STRICT_MAX_JOURNEY_COOLDOWN, resolved.config().journeyCooldown());
        assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("journey-cooldown")));
    }

    @Test
    @DisplayName("a region-radius of 0 is raised to the absolute floor in both modes")
    void zeroRegionRadiusIsRaisedToFloorInBothModes() {
        // At 0 a cart holds its own chunk and nothing else, so it stops at the first boundary it
        // reaches; the drift leash tears apart any group straddling one; and the velocity clamp
        // reads the zero margin as "poll as fast as possible" and pins the loop to a tick, while
        // /chunkcarts list blames cart speed. Structural, so PERMISSIVE cannot opt out of it.
        for (EnforcementMode mode : EnforcementMode.values()) {
            var resolved = Config.defaults().enforcement(mode).regionRadius(0).build().resolve();

            assertEquals(Config.MIN_REGION_RADIUS, resolved.config().regionRadius(),
                    "region-radius 0 in " + mode);
            assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("region-radius")),
                    "and it warns in " + mode + ", the way the poll-interval floor does");
        }
    }

    @Test
    @DisplayName("counts that must be positive get a floor in both modes")
    void nonPositiveCountsAreRaisedToTheirFloorInBothModes() {
        // The other half of the truncation bug: nothing bounded these from below, so a wrapped or
        // simply mistyped value went through. A budget of 0 or less ends every journey on its
        // first check, 0 concurrent journeys refuses every departure on the server, and 0 slack
        // calls the first curve in the track a loop. None of those is a permission to grant.
        for (EnforcementMode mode : EnforcementMode.values()) {
            var resolved = Config.defaults().enforcement(mode)
                    .journeyBudgetChunks(-1)
                    .progressSlackChunks(0)
                    .maxConcurrentJourneys(0)
                    .build().resolve();

            assertEquals(Config.MIN_JOURNEY_BUDGET_CHUNKS, resolved.config().journeyBudgetChunks(),
                    "journey-budget-chunks in " + mode);
            assertEquals(Config.MIN_PROGRESS_SLACK_CHUNKS, resolved.config().progressSlackChunks(),
                    "progress-slack-chunks in " + mode);
            assertEquals(Config.MIN_CONCURRENT_JOURNEYS, resolved.config().maxConcurrentJourneys(),
                    "max-concurrent-journeys in " + mode);
            for (String key : List.of("journey-budget-chunks", "progress-slack-chunks",
                    "max-concurrent-journeys")) {
                assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains(key)),
                        key + " must warn in " + mode);
            }
        }
    }

    @Test
    @DisplayName("PERMISSIVE mode emits a startup warning")
    void permissiveModeEmitsStartupWarning() {
        cfg.enforcement(EnforcementMode.PERMISSIVE);

        var warnings = engine().startupWarnings();

        assertTrue(warnings.stream().anyMatch(w -> w.contains("PERMISSIVE")),
                "on every startup, not just the first");
        assertTrue(warnings.stream().anyMatch(w -> w.toLowerCase().contains("chunk load")),
                "the warning must say plainly what the mode allows");
    }
}

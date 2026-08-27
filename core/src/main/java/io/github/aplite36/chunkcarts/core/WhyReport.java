package io.github.aplite36.chunkcarts.core;

import io.github.aplite36.chunkcarts.core.platform.CartView;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The answer to {@code /chunkcarts why}: what one cart is doing, or what is stopping it.
 *
 * <p>The engine already computes the answers to the two most common support questions this mod
 * will ever get, and used to throw both away. {@code lastTermination} was read only by
 * {@code CartTracker.reportEnd}, which writes a console line if {@code stranded.log} is on — so a
 * player standing next to a stranded cart could find out nothing and an admin had to grep the log
 * with a timestamp they did not have. The denial reason was worse: {@code gate()} produces one on
 * every departure attempt and {@code tryDepartures} discarded it unless debug was on, so a station
 * that had quietly stopped dispatching looked perfectly healthy in {@code list}.
 *
 * <p>The whole report is assembled here rather than in the two command implementations, for the
 * same reason {@link CommandHelp} is: it is a fact about the mod, it has to read the same on all
 * four platforms, and here it can be tested. Both adapters do the platform half — working out which
 * cart the sender means — and pass in the {@link CartView} they already know how to build.
 *
 * <p>This is a pure read. It reports decisions the engine has already made and changes nothing,
 * which is why it does not run into {@code ChunkCartsMod}'s read-mostly rule the way a
 * stop-this-journey command correctly would.
 */
public final class WhyReport {

    private WhyReport() {
    }

    /** How far {@code why} looks for a cart. Beyond this an admin means a different cart. */
    public static final double RANGE = 8.0;

    /**
     * Cosine of the widest angle from the sender's line of sight that still counts as looking at
     * something. About 32 degrees — wide enough that a cart does not have to be centred in the
     * crosshair, narrow enough that a cart behind you is never the answer.
     */
    private static final double LOOKING_AT = 0.85;

    /** One candidate cart, as the adapter sees it. */
    public record Candidate(UUID id, double x, double y, double z) {}

    /**
     * Which cart the sender means: the one they are looking at, or failing that the nearest.
     *
     * <p>An entity ray-trace would be the exact answer, and the API for one differs across the
     * 1.21 to 26.2 span this mod covers. An angle test against the look vector needs only an eye
     * position and a direction, which every version has spelled the same way, and gives the same
     * answer for the case that matters — one cart among several in a rail yard. Falling back to
     * the nearest cart means the command still answers when someone runs it standing over a cart
     * rather than looking down the track at it.
     *
     * @param carts every cart within {@link #RANGE}; the adapter has already done that query
     */
    public static Optional<UUID> target(double eyeX, double eyeY, double eyeZ,
                                        double lookX, double lookY, double lookZ,
                                        List<Candidate> carts) {
        Candidate lookedAt = null;
        double lookedAtDistance = Double.MAX_VALUE;
        Candidate nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Candidate cart : carts) {
            double dx = cart.x() - eyeX;
            double dy = cart.y() - eyeY;
            double dz = cart.z() - eyeZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > RANGE) {
                continue;
            }
            if (distance < nearestDistance) {
                nearest = cart;
                nearestDistance = distance;
            }
            // A cart the sender is standing inside has no meaningful direction to compare.
            double alignment = distance < 1.0e-6 ? 1.0
                    : (dx * lookX + dy * lookY + dz * lookZ) / distance;
            if (alignment >= LOOKING_AT && distance < lookedAtDistance) {
                lookedAt = cart;
                lookedAtDistance = distance;
            }
        }
        Candidate chosen = lookedAt != null ? lookedAt : nearest;
        return Optional.ofNullable(chosen).map(Candidate::id);
    }

    /** The report, one string per line, with no platform decoration. */
    public static List<String> lines(CartView cart, ChunkCartsEngine engine) {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "%s at %s %d,%d,%d",
                cart.type(), cart.world().value(),
                (int) cart.x(), (int) cart.y(), (int) cart.z()));

        Optional<JourneySnapshot> journey = engine.journeyOf(cart.id());
        if (journey.isPresent()) {
            JourneySnapshot snapshot = journey.get();
            lines.add(String.format(Locale.ROOT,
                    "  %s  journey %s  %d cart(s)  distance %d  slack %d  budget %d",
                    snapshot.state(), shortId(snapshot.journeyId()), snapshot.cartCount(),
                    snapshot.distanceRecord(), snapshot.slackUsed(), snapshot.budgetRemaining()));
            return List.copyOf(lines);
        }

        lines.add(engine.lastTermination(cart.id())
                .map(reason -> "  Not travelling. Last journey ended: " + reason)
                .orElse("  Not travelling. No journey has ended for this cart."));

        engine.lastDenial(cart.id()).ifPresent(reason -> lines.add(denialLine(cart, engine, reason)));
        return List.copyOf(lines);
    }

    private static String denialLine(CartView cart, ChunkCartsEngine engine, DenialReason reason) {
        String detail = reason == DenialReason.COOLDOWN
                ? engine.cooldownRemaining(cart.id()).map(left -> " (" + human(left) + " remaining)")
                        .orElse("")
                : "";
        return "  Will not depart: " + reason + detail;
    }

    /** The first block of a UUID. Enough to tell two journeys apart in a chat window. */
    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    /** {@code 7m 12s}. Whole seconds — this is a diagnostic, not a stopwatch. */
    static String human(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        StringBuilder out = new StringBuilder();
        if (hours > 0) {
            out.append(hours).append("h ");
        }
        if (hours > 0 || minutes > 0) {
            out.append(minutes).append("m ");
        }
        return out.append(rest).append('s').toString();
    }
}

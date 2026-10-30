package io.github.aplite36.chunkcarts.core.fake;

import io.github.aplite36.chunkcarts.core.CartType;
import io.github.aplite36.chunkcarts.core.platform.CartView;
import io.github.aplite36.chunkcarts.core.WorldId;

import java.util.Optional;
import java.util.UUID;

/**
 * A minecart the test moves around by hand.
 *
 * <p>Positions are set in <em>chunk</em> coordinates via {@link #atChunk} for readability, since
 * every rule in the spec is expressed in chunks. Block coordinates are derived as the chunk centre.
 */
public final class FakeCart implements CartView {

    private final UUID id = UUID.randomUUID();
    private WorldId world;
    private double x;
    private double y = 64.0;
    private double z;
    private double velocity = 0.4;
    private CartType type = CartType.NORMAL;
    private int itemCount;
    private byte[] state;
    private int writes;

    public FakeCart(WorldId world) {
        this.world = world;
    }

    public static FakeCart at(WorldId world, int cx, int cz) {
        return new FakeCart(world).atChunk(cx, cz);
    }

    @Override public UUID id() { return id; }
    @Override public WorldId world() { return world; }
    @Override public double x() { return x; }
    @Override public double y() { return y; }
    @Override public double z() { return z; }
    @Override public double velocity() { return velocity; }
    @Override public CartType type() { return type; }
    @Override public int itemCount() { return itemCount; }
    @Override public Optional<byte[]> readState() { return Optional.ofNullable(state); }
    @Override public void writeState(byte[] s) { this.state = s; this.writes++; }

    /** Places the cart at the centre of the given chunk. */
    public FakeCart atChunk(int cx, int cz) {
        this.x = cx * 16 + 8;
        this.z = cz * 16 + 8;
        return this;
    }

    public FakeCart atBlock(double bx, double bz) {
        this.x = bx;
        this.z = bz;
        return this;
    }

    /**
     * How many times state has been written. A PDC write per poll is forbidden, and a flush
     * that is merely <em>often enough</em> to satisfy the freshness tests would pass them by
     * writing constantly — so the freshness rules need this to be pinned from the other side too.
     */
    public int writeCount() {
        return writes;
    }

    public int chunkX() {
        return (int) Math.floor(x / 16.0);
    }

    public int chunkZ() {
        return (int) Math.floor(z / 16.0);
    }

    public FakeCart velocity(double v) { this.velocity = v; return this; }
    public FakeCart type(CartType t) { this.type = t; return this; }
    public FakeCart itemCount(int n) { this.itemCount = n; return this; }
    public FakeCart world(WorldId w) { this.world = w; return this; }

    /** Raw state injection, for testing what happens to an unparseable payload. */
    public FakeCart rawState(byte[] bytes) { this.state = bytes; return this; }
}

package io.github.aplite36.chunkcarts.core.fake;

import io.github.aplite36.chunkcarts.core.ChunkPos;
import io.github.aplite36.chunkcarts.core.platform.ChunkTicketBackend;
import io.github.aplite36.chunkcarts.core.WorldId;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Records what was held and released rather than doing anything.
 *
 * <p>It keeps a full history as well as the live set, because several tests are about the
 * <em>sequence</em> — that a termination released a region, that a budget was charged once per
 * chunk rather than once per poll — and a live set alone cannot distinguish those.
 */
public final class FakeChunkTicketBackend implements ChunkTicketBackend {

    /** One entry per call, in order. */
    public record Call(boolean held, ChunkPos chunk) {}

    private final Set<ChunkPos> live = new LinkedHashSet<>();
    private final List<Call> history = new ArrayList<>();
    private int releaseAllCount;

    @Override
    public void hold(WorldId world, int cx, int cz) {
        ChunkPos pos = new ChunkPos(world, cx, cz);
        live.add(pos);
        history.add(new Call(true, pos));
    }

    @Override
    public void release(WorldId world, int cx, int cz) {
        ChunkPos pos = new ChunkPos(world, cx, cz);
        live.remove(pos);
        history.add(new Call(false, pos));
    }

    @Override
    public void releaseAll() {
        releaseAllCount++;
        for (ChunkPos pos : new ArrayList<>(live)) {
            history.add(new Call(false, pos));
        }
        live.clear();
    }

    /** Chunks held right now. */
    public Set<ChunkPos> held() {
        return Set.copyOf(live);
    }

    public int heldCount() {
        return live.size();
    }

    public boolean isHolding(WorldId world, int cx, int cz) {
        return live.contains(new ChunkPos(world, cx, cz));
    }

    public List<Call> history() {
        return List.copyOf(history);
    }

    /** Distinct chunks ever held — the count a budget should have been charged for. */
    public long distinctEverHeld() {
        return history.stream().filter(Call::held).map(Call::chunk).distinct().count();
    }

    public int releaseAllCount() {
        return releaseAllCount;
    }
}

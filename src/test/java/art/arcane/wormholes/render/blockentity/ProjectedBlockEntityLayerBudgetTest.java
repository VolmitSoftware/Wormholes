package art.arcane.wormholes.render.blockentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.ProjectionCellKey;

final class ProjectedBlockEntityLayerBudgetTest {
    @Test
    void sendsAreCappedPerFlushAndResumeNextTick() {
        List<Long> sent = new ArrayList<Long>();
        ProjectedBlockEntityLayer layer = new ProjectedBlockEntityLayer((observer, x, y, z, sample) ->
            sent.add(Long.valueOf(ProjectionCellKey.pack(x, y, z))));
        Long2ObjectOpenHashMap<BlockEntitySample> desired = new Long2ObjectOpenHashMap<BlockEntitySample>();
        for (int index = 0; index < 100; index++) {
            desired.put(ProjectionCellKey.pack(index, 64, 0), sample("sign", index));
        }

        layer.update(desired, (x, y, z) -> null);
        assertEquals(100, layer.pendingCount());
        assertEquals(64, layer.flush(null, 64));
        assertEquals(64, sent.size());
        assertEquals(36, layer.pendingCount());
        assertEquals(36, layer.flush(null, 64));
        assertEquals(100, sent.size());
        assertEquals(0, layer.flush(null, 64));

        layer.update(desired, (x, y, z) -> null);
        assertEquals(0, layer.pendingCount(), "an unchanged sample is not resent");
    }

    @Test
    void changedSamplesResendAndRemovedCellsRestoreTheLocalBlockEntity() {
        List<String> sent = new ArrayList<String>();
        ProjectedBlockEntityLayer layer = new ProjectedBlockEntityLayer((observer, x, y, z, sample) ->
            sent.add(x + ":" + sample.typeKey() + ":" + sample.nbt()[0]));
        Long2ObjectOpenHashMap<BlockEntitySample> desired = new Long2ObjectOpenHashMap<BlockEntitySample>();
        desired.put(ProjectionCellKey.pack(1, 64, 0), sample("sign", 1));
        desired.put(ProjectionCellKey.pack(2, 64, 0), sample("banner", 2));
        layer.update(desired, (x, y, z) -> null);
        layer.flush(null, 64);
        sent.clear();

        desired.put(ProjectionCellKey.pack(1, 64, 0), sample("sign", 9));
        desired.remove(ProjectionCellKey.pack(2, 64, 0));
        layer.update(desired, (x, y, z) -> x == 2 ? sample("skull", 5) : null);
        assertEquals(2, layer.flush(null, 64));
        assertTrue(sent.contains("1:minecraft:sign:9"), sent.toString());
        assertTrue(sent.contains("2:minecraft:skull:5"), "the real local block entity is restored when a projected one leaves");

        sent.clear();
        desired.clear();
        layer.update(desired, (x, y, z) -> null);
        assertEquals(0, layer.flush(null, 64), "a removed cell without a local block entity needs no packet");
        layer.invalidateSent();
        desired.put(ProjectionCellKey.pack(1, 64, 0), sample("sign", 9));
        layer.update(desired, (x, y, z) -> null);
        assertEquals(1, layer.flush(null, 64), "a full resend pass repeats every sample");
    }

    private static BlockEntitySample sample(String type, int marker) {
        return new BlockEntitySample("minecraft:" + type, new byte[] {(byte) marker, 0, 0});
    }
}

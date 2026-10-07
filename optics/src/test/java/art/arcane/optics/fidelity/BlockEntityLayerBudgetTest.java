package art.arcane.optics.fidelity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import org.junit.jupiter.api.Test;

import art.arcane.optics.claim.RecordingProjectionOutput;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.spi.OpticsMetrics;

final class BlockEntityLayerBudgetTest {
    @Test
    void sendsAreCappedPerFlushAndResumeNextTick() {
        RecordingProjectionOutput<Object> output = new RecordingProjectionOutput<Object>();
        BlockEntityLayer<Object> layer = new BlockEntityLayer<Object>(OpticsMetrics.none());
        Long2ObjectOpenHashMap<BlockEntitySample> desired = new Long2ObjectOpenHashMap<BlockEntitySample>();
        for (int index = 0; index < 100; index++) {
            desired.put(CellKeys.pack(index, 64, 0), sample("sign", index));
        }

        layer.update(desired, (x, y, z) -> null);
        assertEquals(100, layer.pendingCount());
        assertEquals(64, layer.flush(null, 64, output));
        assertEquals(64, output.blockEntities.size());
        assertEquals(36, layer.pendingCount());
        assertEquals(36, layer.flush(null, 64, output));
        assertEquals(100, output.blockEntities.size());
        assertEquals(0, layer.flush(null, 64, output));

        layer.update(desired, (x, y, z) -> null);
        assertEquals(0, layer.pendingCount(), "an unchanged sample is not resent");
    }

    @Test
    void changedSamplesResendAndRemovedCellsRestoreTheLocalBlockEntity() {
        RecordingProjectionOutput<Object> output = new RecordingProjectionOutput<Object>();
        BlockEntityLayer<Object> layer = new BlockEntityLayer<Object>(OpticsMetrics.none());
        Long2ObjectOpenHashMap<BlockEntitySample> desired = new Long2ObjectOpenHashMap<BlockEntitySample>();
        desired.put(CellKeys.pack(1, 64, 0), sample("sign", 1));
        desired.put(CellKeys.pack(2, 64, 0), sample("banner", 2));
        layer.update(desired, (x, y, z) -> null);
        layer.flush(null, 64, output);
        output.blockEntities.clear();

        desired.put(CellKeys.pack(1, 64, 0), sample("sign", 9));
        desired.remove(CellKeys.pack(2, 64, 0));
        layer.update(desired, (x, y, z) -> x == 2 ? sample("skull", 5) : null);
        assertEquals(2, layer.flush(null, 64, output));
        assertTrue(describe(output).contains("1:minecraft:sign:9"), describe(output).toString());
        assertTrue(describe(output).contains("2:minecraft:skull:5"), "the real local block entity is restored when a projected one leaves");

        output.blockEntities.clear();
        desired.clear();
        layer.update(desired, (x, y, z) -> null);
        assertEquals(0, layer.flush(null, 64, output), "a removed cell without a local block entity needs no packet");
        layer.invalidateSent();
        desired.put(CellKeys.pack(1, 64, 0), sample("sign", 9));
        layer.update(desired, (x, y, z) -> null);
        assertEquals(1, layer.flush(null, 64, output), "a full resend pass repeats every sample");
    }

    @Test
    void queuedSampleIsCancelledWhenItsProjectionDisappearsBeforeBudgetAllowsDelivery() {
        RecordingProjectionOutput<Object> output = new RecordingProjectionOutput<Object>();
        BlockEntityLayer<Object> layer = new BlockEntityLayer<Object>(OpticsMetrics.none());
        Long2ObjectOpenHashMap<BlockEntitySample> desired = new Long2ObjectOpenHashMap<>();
        long key = CellKeys.pack(1, 64, 0);
        desired.put(key, sample("sign", 1));
        layer.update(desired, (x, y, z) -> null);
        desired.clear();
        layer.update(desired, (x, y, z) -> null);
        assertEquals(0, layer.flush(null, 64, output));
        assertTrue(output.blockEntities.isEmpty());
    }

    @Test
    void revertedQueuedChangeIsCancelledAndLocalRestorationSendsOnlyOnce() {
        RecordingProjectionOutput<Object> output = new RecordingProjectionOutput<Object>();
        BlockEntityLayer<Object> layer = new BlockEntityLayer<Object>(OpticsMetrics.none());
        Long2ObjectOpenHashMap<BlockEntitySample> desired = new Long2ObjectOpenHashMap<>();
        long key = CellKeys.pack(1, 64, 0);
        BlockEntitySample original = sample("sign", 1);
        desired.put(key, original);
        layer.update(desired, (x, y, z) -> null);
        layer.flush(null, 64, output);
        desired.put(key, sample("sign", 2));
        layer.update(desired, (x, y, z) -> null);
        desired.put(key, original);
        layer.update(desired, (x, y, z) -> null);
        assertEquals(0, layer.flush(null, 64, output));
        desired.clear();
        layer.update(desired, (x, y, z) -> sample("skull", 3));
        assertEquals(1, layer.flush(null, 64, output));
        layer.update(desired, (x, y, z) -> sample("skull", 3));
        assertEquals(0, layer.flush(null, 64, output));
        assertEquals(2, output.blockEntities.size());
    }

    private static List<String> describe(RecordingProjectionOutput<Object> output) {
        List<String> described = new ArrayList<String>();
        for (RecordingProjectionOutput.Emitted<Object, RecordingProjectionOutput.BlockEntitySend> sent : output.blockEntities) {
            BlockEntitySample sample = sent.value().sample();
            described.add(CellKeys.unpackX(sent.value().cellKey()) + ":" + sample.typeKey() + ":" + sample.nbt()[0]);
        }
        return described;
    }

    private static BlockEntitySample sample(String type, int marker) {
        return new BlockEntitySample("minecraft:" + type, new byte[] {(byte) marker, 0, 0});
    }
}

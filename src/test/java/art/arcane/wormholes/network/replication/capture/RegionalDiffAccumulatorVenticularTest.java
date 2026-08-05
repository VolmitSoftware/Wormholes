package art.arcane.wormholes.network.replication.capture;

import art.arcane.wormholes.network.replication.BlockChange;
import art.arcane.wormholes.network.replication.BlockChangeFeed;
import art.arcane.wormholes.network.replication.BlockEntityDiff;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.network.replication.LightDiff;
import art.arcane.wormholes.network.replication.ReplicationTestStream;
import art.arcane.wormholes.network.replication.TestNetworkSink;
import art.arcane.wormholes.network.view.ViewSlice;
import art.arcane.wormholes.portal.ProjectionRenderMode;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RegionalDiffAccumulatorVenticularTest {
    private static final String PEER = "peer-vd";
    private static final BlockData SOLID = fakeBlockData("minecraft:stone");
    private static final BlockData AIR = fakeBlockData("minecraft:air");

    @Test
    void placingIntoSolidRockMarksEveryDepthTwoAffectedCellOccluded(@TempDir Path dir) {
        Harness harness = new Harness(dir);
        harness.accumulator.recordBlockChange(harness.world, 8, 64, 8, SOLID, BlockChange.FLAG_NONE);
        harness.accumulator.drainChunk(harness.world, harness.chunkKey);

        Map<Integer, Byte> flags = harness.feed.flagsByPacked();
        assertEquals(25, flags.size(), "the changed cell plus its Manhattan-radius-two dependents must be emitted");
        for (Map.Entry<Integer, Byte> entry : flags.entrySet()) {
            assertEquals(BlockChange.FLAG_OCCLUDED, entry.getValue().byteValue(), "every fully buried cell must carry the occluded flag");
        }
        assertNotNull(flags.get(BlockChange.pack(8, 64, 8)));
    }

    @Test
    void breakingASolidCellReemitsEveryDepthTwoDependentAsExposed(@TempDir Path dir) {
        Harness harness = new Harness(dir);
        harness.accumulator.recordBlockChange(harness.world, 8, 64, 8, AIR, BlockChange.FLAG_NONE);
        harness.accumulator.drainChunk(harness.world, harness.chunkKey);

        Map<Integer, Byte> flags = harness.feed.flagsByPacked();
        assertEquals(25, flags.size(), "the broken cell plus its Manhattan-radius-two dependents must be re-emitted");
        assertEquals(BlockChange.FLAG_NONE, flags.get(BlockChange.pack(8, 64, 8)).byteValue(), "the broken (air) cell is never buried");
        for (Map.Entry<Integer, Byte> entry : flags.entrySet()) {
            assertEquals(BlockChange.FLAG_NONE, entry.getValue().byteValue(),
                "every cell whose depth-two status depends on the opening must be exposed");
        }
    }

    @Test
    void snapshotDiffOcclusionNeverReadsLiveWorldBlocks(@TempDir Path dir) {
        Harness harness = new Harness(dir);
        harness.accumulator.setCaptureOcclusionModel((world, x, y, z) -> {
            throw new AssertionError("snapshot comparison read a live block");
        }, data -> data == SOLID);

        RegionalDiffAccumulator.BlockCaptureRevision revision =
            harness.accumulator.captureBlockRevision(harness.world, harness.chunkKey);
        harness.accumulator.recordSnapshotBlockChange(harness.world, 8, 64, 8, SOLID, BlockChange.FLAG_NONE,
            (x, y, z) -> SOLID, -64, 320, revision);
        harness.accumulator.drainChunk(harness.world, harness.chunkKey);

        assertEquals(25, harness.feed.flagsByPacked().size());
    }

    private static final class Harness {
        private final World world;
        private final long chunkKey;
        private final RegionalDiffAccumulator accumulator;
        private final CapturingFeed feed;

        private Harness(Path dir) {
            TestNetworkSink sink = new TestNetworkSink(dir);
            ChunkReplicationManager replication = sink.getReplicationManager();
            this.world = fakeWorld(UUID.randomUUID());
            UUID portalId = UUID.randomUUID();
            this.chunkKey = ViewSlice.columnKey(0, 0);
            replication.subscribe(PEER, portalId, world,
                ReplicationTestStream.stream(portalId, world, chunkKey, ProjectionRenderMode.VENTICULAR));
            this.feed = new CapturingFeed();
            this.accumulator = new RegionalDiffAccumulator(replication, feed, CaptureSettings.defaults());
            this.accumulator.setCaptureOcclusionModel((w, x, y, z) -> SOLID, data -> data == SOLID);
        }
    }

    private static final class CapturingFeed implements BlockChangeFeed {
        private final List<BlockChange> blocks = new ArrayList<>();

        @Override
        public void onChunkDrain(World world, long chunkKey, List<BlockChange> drainedBlocks, List<LightDiff> drainedLights, List<BlockEntityDiff> drainedEntities) {
            blocks.addAll(drainedBlocks);
        }

        @Override
        public void onTickEnd() {
        }

        private Map<Integer, Byte> flagsByPacked() {
            Map<Integer, Byte> flags = new HashMap<>();
            for (BlockChange change : blocks) {
                flags.put(change.packedXyz(), change.flags());
            }
            return flags;
        }
    }

    private static World fakeWorld(UUID uid) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUID" -> uid;
            case "getMinHeight" -> -64;
            case "getMaxHeight" -> 320;
            case "equals" -> proxy == args[0];
            case "hashCode" -> uid.hashCode();
            case "toString" -> "FakeWorld[" + uid + "]";
            default -> defaultValue(method.getReturnType());
        });
    }

    private static BlockData fakeBlockData(String stateString) {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[]{BlockData.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getAsString" -> stateString;
            case "clone" -> proxy;
            case "equals" -> proxy == args[0];
            case "hashCode" -> stateString.hashCode();
            case "toString" -> "FakeBlockData[" + stateString + "]";
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return Boolean.FALSE;
        }
        if (returnType == int.class || returnType == long.class || returnType == short.class || returnType == byte.class) {
            return 0;
        }
        if (returnType == float.class || returnType == double.class) {
            return 0.0D;
        }
        return null;
    }
}

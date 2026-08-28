package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.PacketBlobs;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.Direction;

public final class ProjectedEntityOcclusionTest {
    @Test
    public void fullyCoveredMinecartSizedEnvelopeIsHidden() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = wall(view, 2, -4, 6, -4, 6);
        ProjectedEntityOcclusion occlusion = occlusion();
        begin(occlusion, view, blockers);

        assertTrue(occlusion.fullyHidden(new BoundingBox(
            4.25D, 1.0D, 0.25D,
            4.75D, 2.0D, 0.75D)));
    }

    @Test
    public void exposedEnvelopeCellKeepsTheWholeEntityVisible() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = wall(view, 2, -4, 6, -1, 1);
        ProjectedEntityOcclusion occlusion = occlusion();
        begin(occlusion, view, blockers);

        assertFalse(occlusion.fullyHidden(new BoundingBox(
            4.25D, 1.0D, 1.25D,
            4.75D, 2.0D, 1.75D)));
    }

    @Test
    public void projectedVisualsUseTheSameDestinationBlockProof() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = wall(view, 2, -4, 6, -4, 6);
        ProjectedEntityOcclusion occlusion = occlusion();
        begin(occlusion, view, blockers);

        assertTrue(occlusion.fullyHidden(visual(4.5D, 1.0D, 0.5D, 0.7D)));
    }

    @Test
    public void changedOrUnavailableGeometryFailsOpen() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = wall(view, 2, -4, 6, -4, 6);
        ProjectedEntityOcclusion occlusion = occlusion();
        occlusion.beginPass(view, 0.5D, 0.5D, 0.5D, Direction.W, blockers,
            0.5D, 1.5D, 0.5D, 0.0D);
        view.incrementRevision();
        occlusion.startBatch();

        assertFalse(occlusion.fullyHidden(new BoundingBox(
            4.25D, 1.0D, 0.25D,
            4.75D, 2.0D, 0.75D)));

        occlusion.beginPass(view, 0.5D, 0.5D, 0.5D, Direction.W, new LongOpenHashSet(),
            0.5D, 1.5D, 0.5D, 0.0D);
        occlusion.startBatch();
        assertFalse(occlusion.fullyHidden(visual(4.5D, 1.0D, 0.5D, 0.7D)));
    }

    private static void begin(ProjectedEntityOcclusion occlusion,
                              FakeWorldView view,
                              LongOpenHashSet blockers) {
        occlusion.beginPass(view, 0.5D, 0.5D, 0.5D, Direction.W, blockers,
            0.5D, 1.5D, 0.5D, 0.0D);
        occlusion.startBatch();
    }

    private static ProjectedEntityOcclusion occlusion() {
        ProjectorViewOcclusion blockOcclusion = new ProjectorViewOcclusion(
            data -> data != null && data.getMaterial() == Material.STONE);
        return new ProjectedEntityOcclusion(blockOcclusion);
    }

    private static LongOpenHashSet wall(FakeWorldView view,
                                        int x,
                                        int minY,
                                        int maxY,
                                        int minZ,
                                        int maxZ) {
        LongOpenHashSet blockers = new LongOpenHashSet();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                view.put(x, y, z, Material.STONE);
                blockers.add(ProjectionCellKey.pack(x, y, z));
            }
        }
        return blockers;
    }

    private static EntityVisual visual(double x, double y, double z, double height) {
        return EntityVisual.full(
            UUID.randomUUID(), "minecraft:minecart",
            x, y, z, height,
            0.0D, 0.0D, 1.0D,
            0.0F, 0.0F,
            0.0D, 0.0D, 0.0D,
            true,
            "", "", "",
            null, null,
            PacketBlobs.EMPTY, PacketBlobs.EMPTY, PacketBlobs.EMPTY,
            0);
    }

    private static BlockData blockData(Material material) {
        return (BlockData) Proxy.newProxyInstance(
            BlockData.class.getClassLoader(),
            new Class<?>[] {BlockData.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getMaterial" -> material;
                case "toString", "getAsString" -> material.name();
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                case "clone" -> proxy;
                default -> null;
            });
    }

    private static final class FakeWorldView implements ProjectionWorldView {
        private final Map<String, BlockData> blocks = new HashMap<String, BlockData>();
        private final BlockData air = blockData(Material.AIR);
        private long revision;

        private void put(int x, int y, int z, Material material) {
            blocks.put(key(x, y, z), blockData(material));
        }

        private void incrementRevision() {
            revision++;
        }

        @Override
        public World getWorld() {
            return null;
        }

        @Override
        public int getMinHeight() {
            return -64;
        }

        @Override
        public int getMaxHeight() {
            return 320;
        }

        @Override
        public BlockData sampleBlockData(int x, int y, int z) {
            return blocks.getOrDefault(key(x, y, z), air);
        }

        @Override
        public String sampleBiome(int x, int y, int z) {
            return null;
        }

        @Override
        public int getLight(int x, int y, int z) {
            return LIGHT_UNAVAILABLE;
        }

        @Override
        public int getSkyDarken() {
            return 0;
        }

        @Override
        public long getRevision() {
            return revision;
        }

        private static String key(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }
    }
}

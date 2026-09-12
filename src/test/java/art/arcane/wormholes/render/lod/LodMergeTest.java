package art.arcane.wormholes.render.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.ProjectorSample;
import art.arcane.wormholes.render.plate.PlateCell;
import art.arcane.wormholes.render.plate.ViewPlate;
import art.arcane.wormholes.render.plate.ViewPlateBuilder;
import art.arcane.wormholes.render.plate.ViewPlateKey;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;

final class LodMergeTest {
    private static final UUID PORTAL_ID = UUID.fromString("00000000-0000-0000-0000-00000000010d");

    @Test
    void oddSlabsPastTheDistanceReuseThePreviousSlabAndTheRestoreKeyStaysTheOriginalCell() {
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure);
        StripedView destination = new StripedView();
        LodPolicy lod = new LodPolicy(true, 1, 100);
        ViewPlate plate = ViewPlateBuilder.build(request(portal, structure, destination, lod, 8.0D));

        PlateCell slab0 = plate.cell(ProjectionCellKey.pack(0, 64, -1));
        PlateCell slab1 = plate.cell(ProjectionCellKey.pack(0, 64, -2));
        PlateCell slab2 = plate.cell(ProjectionCellKey.pack(0, 64, -3));
        PlateCell slab3 = plate.cell(ProjectionCellKey.pack(0, 64, -4));
        PlateCell slab4 = plate.cell(ProjectionCellKey.pack(0, 64, -5));
        assertNotSame(slab0.data(), slab1.data(), "slabs inside the distance keep their own samples");
        assertSame(slab1, slab2, "the first odd slab past the distance reuses the previous slab cell");
        assertNotSame(slab2, slab3, "even slabs are sampled again");
        assertSame(slab3, slab4);
        assertEquals(slab1.remoteKey(), slab2.remoteKey(), "a merged cell keeps the remote key it was copied from");
        assertTrue(plate.cellKeys().contains(ProjectionCellKey.pack(0, 64, -3)),
            "merged cells keep their own local key so the arbiter restores the real local block");
    }

    @Test
    void detailBlocksBeyondTheCutoffBecomeAirAndNoneKeepsEverything() {
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure);
        FlowerView destination = new FlowerView();
        ViewPlate cut = ViewPlateBuilder.build(request(portal, structure, destination, new LodPolicy(false, 100, 2), 8.0D));
        assertEquals(ProjectorSample.Kind.BLOCK, cut.cell(ProjectionCellKey.pack(0, 64, -1)).kind());
        assertEquals(ProjectorSample.Kind.BLOCK, cut.cell(ProjectionCellKey.pack(0, 64, -2)).kind());
        assertEquals(ProjectorSample.Kind.REMOTE_AIR, cut.cell(ProjectionCellKey.pack(0, 64, -4)).kind());
        assertEquals(Material.STONE, cut.cell(ProjectionCellKey.pack(0, 63, -4)).data().getMaterial(),
            "full blocks past the cutoff stay");

        ViewPlate none = ViewPlateBuilder.build(request(portal, structure, destination, LodPolicy.NONE, 8.0D));
        assertEquals(ProjectorSample.Kind.BLOCK, none.cell(ProjectionCellKey.pack(0, 64, -4)).kind());
        assertTrue(LodPolicy.NONE.isNone());
        assertFalse(new LodPolicy(true, 32, 48).isNone());
    }

    @Test
    void coarsenedWorkHalvesTheFarSlabsAndProfilesScaleTheDistances() {
        LodPolicy balanced = new LodPolicy(true, 32, 48);
        assertEquals(1000L, LodPolicy.NONE.coarsenedWork(1000L, 64.0D));
        assertEquals(1000L, balanced.coarsenedWork(1000L, 16.0D), "views shallower than the distance are untouched");
        assertEquals(750L, balanced.coarsenedWork(1000L, 64.0D));
        assertTrue(balanced.mergesSlab(33));
        assertFalse(balanced.mergesSlab(34));
        assertFalse(balanced.mergesSlab(32));
        assertTrue(LodPolicy.isDetail(Material.POPPY));
        assertTrue(LodPolicy.isDetail(Material.OAK_FENCE));
        assertTrue(LodPolicy.isDetail(Material.GLASS_PANE));
        assertTrue(LodPolicy.isDetail(Material.SHORT_GRASS));
        assertFalse(LodPolicy.isDetail(Material.STONE));
        assertEquals(0.5D, LodProfile.NEAR.distanceScale());
        assertEquals(1.5D, LodProfile.FAR.distanceScale());
    }

    private static ViewPlateBuilder.Request request(ILocalPortal portal, PortalStructure structure,
                                                    ProjectionWorldView destination, LodPolicy lod, double depth) {
        PortalFrame frame = portal.getFrame();
        return new ViewPlateBuilder.Request(new ViewPlateKey(PORTAL_ID, destination, true, 0), portal, destination, frame, frame,
            structure.getCenter().getX(), structure.getCenter().getY(), structure.getCenter().getZ(),
            structure.getCenter().getX(), structure.getCenter().getY(), structure.getCenter().getZ(),
            false, 0, depth, 0.0D, 0.0D, false, blockData(Material.AIR), lod, false, 1L, 1L, 0L,
            material -> material == Material.STONE);
    }

    private static PortalStructure structure() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("worldKey", "minecraft:overworld");
        values.put("x1", Integer.valueOf(0));
        values.put("x2", Integer.valueOf(0));
        values.put("y1", Integer.valueOf(63));
        values.put("y2", Integer.valueOf(64));
        values.put("z1", Integer.valueOf(0));
        values.put("z2", Integer.valueOf(0));
        PortalStructure structure = new PortalStructure();
        structure.setArea(new Cuboid(values));
        return structure;
    }

    private static ILocalPortal portal(PortalStructure structure) {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        org.bukkit.util.Vector origin = structure.getCenter().toVector();
        return (ILocalPortal) Proxy.newProxyInstance(ILocalPortal.class.getClassLoader(), new Class<?>[] {ILocalPortal.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getStructure" -> structure;
                case "getFrame" -> frame;
                case "getOrigin" -> origin;
                case "getId" -> PORTAL_ID;
                case "getWorld" -> null;
                case "getName", "toString" -> "lod-portal";
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                default -> primitiveDefault(method.getReturnType());
            });
    }

    private static Object primitiveDefault(Class<?> returnType) {
        if (returnType == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (returnType == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (returnType == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (returnType == Double.TYPE) {
            return Double.valueOf(0.0D);
        }
        return null;
    }

    static BlockData blockData(Material material) {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] {BlockData.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getMaterial" -> material;
                case "getAsString", "toString" -> material.getKey().toString();
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                case "clone" -> proxy;
                default -> primitiveDefault(method.getReturnType());
            });
    }

    /** Every slab along -Z has its own block instance so identity reveals copies. */
    private static final class StripedView implements ProjectionWorldView {
        private final Map<Integer, BlockData> perSlab = new HashMap<Integer, BlockData>();

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
            return perSlab.computeIfAbsent(Integer.valueOf(z), ignored -> blockData(Material.STONE));
        }

        @Override
        public String sampleBiome(int x, int y, int z) {
            return null;
        }

        @Override
        public int getLight(int x, int y, int z) {
            return ProjectionWorldView.LIGHT_UNAVAILABLE;
        }

        @Override
        public int getSkyDarken() {
            return 0;
        }
    }

    private static final class FlowerView implements ProjectionWorldView {
        private final BlockData poppy = blockData(Material.POPPY);
        private final BlockData stone = blockData(Material.STONE);

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
            return y == 64 ? poppy : stone;
        }

        @Override
        public String sampleBiome(int x, int y, int z) {
            return null;
        }

        @Override
        public int getLight(int x, int y, int z) {
            return ProjectionWorldView.LIGHT_UNAVAILABLE;
        }

        @Override
        public int getSkyDarken() {
            return 0;
        }
    }
}

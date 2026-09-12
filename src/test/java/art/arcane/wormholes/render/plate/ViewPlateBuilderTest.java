package art.arcane.wormholes.render.plate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.ProjectorSample;
import art.arcane.wormholes.render.lod.LodPolicy;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;

final class ViewPlateBuilderTest {
    private static final UUID PORTAL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    @Test
    void twoBuildsOfTheSameKeyAreIdenticalCellForCell() {
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, PortalFrame.canonical(Direction.S));
        FakeWorldView destination = new FakeWorldView(blockData(Material.STONE), 7L);
        destination.put(0, 64, -3, blockData(Material.GLASS));
        ViewPlateBuilder.Request request = request(portal, structure, destination, true);

        ViewPlate first = ViewPlateBuilder.build(request);
        ViewPlate second = ViewPlateBuilder.build(request);

        assertFalse(first.isEmpty());
        assertEquals(first.cellCount(), second.cellCount());
        assertEquals(7L, first.destinationRevision());
        assertEquals(request.transformRevision(), first.transformRevision());
        assertTrue(first.bytes() > 0L);
        LongOpenHashSet keys = new LongOpenHashSet(first.cellKeys());
        assertEquals(keys, new LongOpenHashSet(second.cellKeys()));
        for (long key : keys) {
            PlateCell left = first.cell(key);
            PlateCell right = second.cell(key);
            assertNotNull(left);
            assertEquals(left.kind(), right.kind());
            assertSame(left.data(), right.data());
            assertEquals(left.remoteKey(), right.remoteKey());
            assertTrue(ProjectionCellKey.unpackZ(key) < 0, "plate cells must lie behind the far side of the plane");
            assertTrue(ProjectionCellKey.unpackZ(key) >= -5, "plate depth must respect the requested depth");
        }
        boolean sawGlass = false;
        for (long key : keys) {
            if (first.cell(key).data().getMaterial() == Material.GLASS) {
                sawGlass = true;
            }
        }
        assertTrue(sawGlass, "the plate must carry the destination sample through the transform");
    }

    @Test
    void aChangedDestinationRevisionYieldsADifferentPlate() {
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, PortalFrame.canonical(Direction.S));
        FakeWorldView destination = new FakeWorldView(blockData(Material.STONE), 1L);
        ViewPlate before = ViewPlateBuilder.build(request(portal, structure, destination, true));

        destination.put(0, 64, -2, blockData(Material.GLASS));
        destination.revision = 2L;
        ViewPlate after = ViewPlateBuilder.build(request(portal, structure, destination, true));

        assertNotEquals(before.destinationRevision(), after.destinationRevision());
        long changedKey = ProjectionCellKey.pack(0, 64, -2);
        assertEquals(Material.STONE, before.cell(changedKey).data().getMaterial());
        assertEquals(Material.GLASS, after.cell(changedKey).data().getMaterial());
    }

    @Test
    void airSamplesUnavailableChunksAndOccludedCellsAreClassifiedLikeTheSampler() {
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, PortalFrame.canonical(Direction.S));
        FakeWorldView destination = new FakeWorldView(blockData(Material.AIR), 3L);
        destination.put(0, 64, -1, blockData(Material.STONE));
        destination.unknown(1, 64, -1);
        ViewPlate plate = ViewPlateBuilder.build(request(portal, structure, destination, true));

        assertEquals(ProjectorSample.Kind.REMOTE_AIR, plate.cell(ProjectionCellKey.pack(0, 65, -1)).kind());
        assertEquals(ProjectorSample.Kind.BLOCK, plate.cell(ProjectionCellKey.pack(0, 64, -1)).kind());
        assertNull(plate.cell(ProjectionCellKey.pack(1, 64, -1)), "unavailable samples stay absent so the projector falls back");
    }

    @Test
    void theBackSideBuildsBehindTheOppositeFace() {
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, PortalFrame.canonical(Direction.S));
        FakeWorldView destination = new FakeWorldView(blockData(Material.STONE), 1L);
        ViewPlate plate = ViewPlateBuilder.build(request(portal, structure, destination, false));

        assertFalse(plate.isEmpty());
        for (long key : plate.cellKeys()) {
            assertTrue(ProjectionCellKey.unpackZ(key) > 0, "back-side cells lie on the normal side of the plane");
        }
    }

    @Test
    void resumableJobsProduceTheSamePlateAsASynchronousBuild() {
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, PortalFrame.canonical(Direction.S));
        FakeWorldView destination = new FakeWorldView(blockData(Material.STONE), 9L);
        ViewPlateBuilder.Request request = request(portal, structure, destination, true);
        ViewPlateBuilder.Job job = ViewPlateBuilder.job(request);
        int steps = 0;
        while (!job.step(4)) {
            steps++;
            assertTrue(steps < 100_000);
        }
        ViewPlate stepped = job.result();
        ViewPlate direct = ViewPlateBuilder.build(request);
        assertTrue(steps > 1, "a tiny cell budget must take several steps");
        assertEquals(new LongOpenHashSet(direct.cellKeys()), new LongOpenHashSet(stepped.cellKeys()));
    }

    static ViewPlateBuilder.Request request(ILocalPortal portal, PortalStructure structure, ProjectionWorldView destination, boolean frontSide) {
        PortalFrame frame = portal.getFrame();
        ViewPlateKey key = new ViewPlateKey(PORTAL_ID, destination, frontSide, 0);
        return new ViewPlateBuilder.Request(key, portal, destination, frame, frame,
            structure.getCenter().getX(), structure.getCenter().getY(), structure.getCenter().getZ(),
            structure.getCenter().getX(), structure.getCenter().getY(), structure.getCenter().getZ(),
            false, 0, 4.0D, 2.0D, 0.75D, false, blockData(Material.AIR), LodPolicy.NONE, false,
            destination.getRevision(), 42L, 0L, material -> material == Material.STONE);
    }

    static PortalStructure structure() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("worldKey", "minecraft:overworld");
        values.put("x1", Integer.valueOf(0));
        values.put("x2", Integer.valueOf(0));
        values.put("y1", Integer.valueOf(64));
        values.put("y2", Integer.valueOf(65));
        values.put("z1", Integer.valueOf(0));
        values.put("z2", Integer.valueOf(0));
        PortalStructure structure = new PortalStructure();
        structure.setArea(new Cuboid(values));
        return structure;
    }

    static ILocalPortal portal(PortalStructure structure, PortalFrame frame) {
        org.bukkit.util.Vector origin = structure.getCenter().toVector();
        return (ILocalPortal) Proxy.newProxyInstance(
            ILocalPortal.class.getClassLoader(), new Class<?>[] {ILocalPortal.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getStructure" -> structure;
                case "getFrame" -> frame;
                case "getOrigin" -> origin;
                case "getId" -> PORTAL_ID;
                case "getName", "toString" -> "plate-portal";
                case "getWorld" -> null;
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                default -> primitiveDefault(method.getReturnType());
            });
    }

    static Object primitiveDefault(Class<?> returnType) {
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
        return (BlockData) Proxy.newProxyInstance(
            BlockData.class.getClassLoader(), new Class<?>[] {BlockData.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getMaterial" -> material;
                case "getAsString", "toString" -> material.getKey().toString();
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                case "clone" -> proxy;
                default -> primitiveDefault(method.getReturnType());
            });
    }

    static final class FakeWorldView implements ProjectionWorldView {
        private final Map<String, BlockData> blocks = new HashMap<String, BlockData>();
        private final Map<String, Boolean> unknown = new HashMap<String, Boolean>();
        private final BlockData defaultData;
        long revision;
        int reads;

        FakeWorldView(BlockData defaultData, long revision) {
            this.defaultData = defaultData;
            this.revision = revision;
        }

        void put(int x, int y, int z, BlockData data) {
            blocks.put(key(x, y, z), data);
        }

        void unknown(int x, int y, int z) {
            unknown.put(key(x, y, z), Boolean.TRUE);
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
            reads++;
            String key = key(x, y, z);
            if (unknown.containsKey(key)) {
                return null;
            }
            return blocks.getOrDefault(key, defaultData);
        }

        @Override
        public String sampleBiome(int x, int y, int z) {
            return "minecraft:plains";
        }

        @Override
        public int getLight(int x, int y, int z) {
            return ProjectionWorldView.LIGHT_UNAVAILABLE;
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

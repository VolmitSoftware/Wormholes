package art.arcane.wormholes.render.plate;

import art.arcane.wormholes.render.BukkitProjectorBlocks;
import art.arcane.wormholes.util.BukkitGeometry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;
import art.arcane.wormholes.render.ProjectorFrameTransform;
import art.arcane.wormholes.render.ProjectorSample;
import art.arcane.wormholes.render.ProjectorSampleMemo;
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
        ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView> request = request(portal, structure, destination, true);

        ViewPlate<BlockData> first = ViewPlateBuilder.build(request);
        ViewPlate<BlockData> second = ViewPlateBuilder.build(request);

        assertFalse(first.isEmpty());
        assertEquals(first.cellCount(), second.cellCount());
        assertEquals(7L, first.destinationRevision());
        assertEquals(request.transformRevision(), first.transformRevision());
        assertTrue(first.bytes() > 0L);
        LongOpenHashSet keys = new LongOpenHashSet(first.cellKeys());
        assertEquals(keys, new LongOpenHashSet(second.cellKeys()));
        for (long key : keys) {
            PlateCell<BlockData> left = first.cell(key);
            PlateCell<BlockData> right = second.cell(key);
            assertNotNull(left);
            assertEquals(left.kind(), right.kind());
            assertSame(left.data(), right.data());
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
        ViewPlate<BlockData> before = ViewPlateBuilder.build(request(portal, structure, destination, true));

        destination.put(0, 64, -2, blockData(Material.GLASS));
        destination.revision = 2L;
        ViewPlate<BlockData> after = ViewPlateBuilder.build(request(portal, structure, destination, true));

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
        ViewPlate<BlockData> plate = ViewPlateBuilder.build(request(portal, structure, destination, true));

        assertEquals(ProjectorSample.Kind.REMOTE_AIR, plate.cell(ProjectionCellKey.pack(0, 65, -1)).kind());
        assertEquals(ProjectorSample.Kind.BLOCK, plate.cell(ProjectionCellKey.pack(0, 64, -1)).kind());
        assertNull(plate.cell(ProjectionCellKey.pack(1, 64, -1)), "unavailable samples stay absent so the projector falls back");
    }

    @Test
    void blockEntityCellsOfAnIncompleteCaptureStayAbsentSoTheScanSamplesThemLive() {
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, PortalFrame.canonical(Direction.S));
        FakeWorldView destination = new FakeWorldView(blockData(Material.STONE), 3L);
        destination.put(0, 64, -2, blockData(Material.OAK_SIGN));
        destination.put(0, 64, -1, blockData(Material.GLASS));

        ViewPlate<BlockData> complete = ViewPlateBuilder.build(blockEntityRequest(portal, structure, destination));
        assertNotNull(complete.cell(ProjectionCellKey.pack(0, 64, -2)), "a complete capture keeps the sign cell on the plate");

        destination.blockEntitiesComplete = false;
        ViewPlate<BlockData> capped = ViewPlateBuilder.build(blockEntityRequest(portal, structure, destination));
        assertNull(capped.cell(ProjectionCellKey.pack(0, 64, -2)), "a sign whose block entity was not captured is sampled live");
        assertNotNull(capped.cell(ProjectionCellKey.pack(0, 64, -1)), "cells without block entities stay on the plate");
    }

    private static ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView> blockEntityRequest(ILocalPortal portal, PortalStructure structure,
                                                                                                      ProjectionWorldView destination) {
        PortalFrame frame = portal.getFrame();
        ViewPlateKey key = new ViewPlateKey(PORTAL_ID, destination, true, 0, 0L);
        return new ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView>(key, portal.getStructure(), destination, frame, frame,
            structure.getCenter().getX(), structure.getCenter().getY(), structure.getCenter().getZ(),
            structure.getCenter().getX(), structure.getCenter().getY(), structure.getCenter().getZ(),
            false, 0, 4.0D, 2.0D, 0.75D, false, blockData(Material.AIR), LodPolicy.NONE, true,
            destination.getRevision(), 42L, 0L, new BukkitProjectorBlocks(material -> material == Material.STONE));
    }

    @Test
    void theBackSideBuildsBehindTheOppositeFace() {
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, PortalFrame.canonical(Direction.S));
        FakeWorldView destination = new FakeWorldView(blockData(Material.STONE), 1L);
        ViewPlate<BlockData> plate = ViewPlateBuilder.build(request(portal, structure, destination, false));

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
        ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView> request = request(portal, structure, destination, true);
        ViewPlateBuilder.Job<BlockData, World> job = ViewPlateBuilder.job(request);
        int steps = 0;
        while (!job.step(4)) {
            steps++;
            assertTrue(steps < 100_000);
        }
        ViewPlate<BlockData> stepped = job.result();
        ViewPlate<BlockData> direct = ViewPlateBuilder.build(request);
        assertTrue(steps > 1, "a tiny cell budget must take several steps");
        assertEquals(new LongOpenHashSet(direct.cellKeys()), new LongOpenHashSet(stepped.cellKeys()));
    }

    @Test
    void denseCellsMatchTheSamplerClassificationForRandomBoxesAndContent() {
        BlockData air = blockData(Material.AIR);
        BlockData stone = blockData(Material.STONE);
        BlockData glass = blockData(Material.GLASS);
        BukkitProjectorBlocks blocks = new BukkitProjectorBlocks(material -> material == Material.STONE);
        int totalOccluded = 0;
        for (int seed = 0; seed < 48; seed++) {
            Random random = new Random(seed);
            PortalStructure structure = structure();
            ILocalPortal portal = portal(structure, PortalFrame.canonical(Direction.S));
            NoiseWorldView destination = new NoiseWorldView(seed, 4 + random.nextInt(8), stone, glass, air);
            boolean frontSide = random.nextBoolean();
            boolean buried = random.nextBoolean();
            boolean mirror = random.nextBoolean();
            int quarterTurns = mirror ? random.nextInt(4) : 0;
            double depth = 1 + random.nextInt(6);
            double lateral = random.nextInt(4);
            PortalFrame frame = portal.getFrame();
            double originX = structure.getCenter().getX();
            double originY = structure.getCenter().getY();
            double originZ = structure.getCenter().getZ();
            ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView> request = new ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView>(
                new ViewPlateKey(PORTAL_ID, destination, frontSide, quarterTurns, 0L), portal.getStructure(), destination,
                frame, mirror ? frame.flipNormal() : frame, originX, originY, originZ, originX, originY, originZ,
                mirror, quarterTurns, depth, lateral, 0.75D, buried, air, LodPolicy.NONE, false, 1L, 2L, 0L, blocks);
            ProjectorFrameTransform transform = new ProjectorFrameTransform();
            if (mirror) {
                transform.configureMirror(frame, quarterTurns, originX, originY, originZ, new double[3]);
            } else {
                transform.configure(frame.view(frontSide), frame.view(frontSide), originX, originY, originZ, originX, originY, originZ);
            }
            double[] remotePoint = new double[3];

            ViewPlate<BlockData> plate = ViewPlateBuilder.build(request);
            ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = new ProjectorSampleMemo<BlockData, Material, ProjectionWorldView>(blocks, () -> null);
            Set<PlateCell<BlockData>> palette = Collections.newSetFromMap(new IdentityHashMap<PlateCell<BlockData>, Boolean>());
            int present = 0;
            int occluded = 0;
            for (int x = -12; x <= 12; x++) {
                for (int y = 52; y <= 78; y++) {
                    for (int z = -12; z <= 12; z++) {
                        long key = ProjectionCellKey.pack(x, y, z);
                        PlateCell<BlockData> cell = plate.cell(key);
                        if (cell == null) {
                            continue;
                        }
                        transform.apply(x + 0.5D, y + 0.5D, z + 0.5D, remotePoint);
                        int rx = (int) Math.floor(remotePoint[0]);
                        int ry = (int) Math.floor(remotePoint[1]);
                        int rz = (int) Math.floor(remotePoint[2]);
                        BlockData remote = destination.sampleBlockData(rx, ry, rz);
                        present++;
                        palette.add(cell);
                        String label = "seed=" + seed + " mirror=" + mirror + " cell=" + x + "," + y + "," + z;
                        assertNotNull(remote, label + " unknown samples must stay absent");
                        if (remote.getMaterial() == Material.AIR) {
                            assertEquals(ProjectorSample.Kind.REMOTE_AIR, cell.kind(), label);
                            assertSame(air, cell.data(), label);
                            continue;
                        }
                        int occlusionDepth = buried ? memo.occlusionDepthInView(destination, rx, ry, rz, remote) : 0;
                        ProjectorSample.Kind expected = occlusionDepth == 1 ? ProjectorSample.Kind.BACKING_BLOCK
                            : occlusionDepth == 2 ? ProjectorSample.Kind.OCCLUDED : ProjectorSample.Kind.BLOCK;
                        assertEquals(expected, cell.kind(), label);
                        if (expected == ProjectorSample.Kind.OCCLUDED) {
                            occluded++;
                        }
                        assertSame(remote, cell.data(), label);
                        assertSame(remote, cell.sourceData(), label);
                    }
                }
            }
            assertEquals(present, plate.cellCount(), "seed=" + seed + " every plate cell lies inside the checked region");
            long predicted = ViewPlateBuilder.footprint(request).predictedBytes();
            assertEquals(predicted + (48L * palette.size()), plate.bytes(), "seed=" + seed + " the byte estimate is exact");
            assertTrue(palette.size() <= 5, "seed=" + seed + " cells share palette entries instead of one object per cell");
            totalOccluded += occluded;
        }
        assertTrue(totalOccluded > 0, "the random content must exercise fully buried cells");
    }

    @Test
    void patchingDirtyChunksMatchesAFreshBuildOfTheChangedDestination() {
        BlockData air = blockData(Material.AIR);
        BlockData stone = blockData(Material.STONE);
        BlockData glass = blockData(Material.GLASS);
        BukkitProjectorBlocks blocks = new BukkitProjectorBlocks(material -> material == Material.STONE);
        for (int seed = 0; seed < 16; seed++) {
            Random random = new Random(1000L + seed);
            PortalStructure structure = structureAt(14);
            ILocalPortal portal = portal(structure, PortalFrame.canonical(Direction.S));
            NoiseWorldView destination = new NoiseWorldView(seed, 5 + random.nextInt(7), stone, glass, air);
            boolean mirror = random.nextBoolean();
            int quarterTurns = mirror ? random.nextInt(4) : 0;
            LodPolicy lod = random.nextBoolean() ? LodPolicy.NONE : new LodPolicy(true, 4, 100);
            PortalFrame frame = portal.getFrame();
            double originX = structure.getCenter().getX();
            double originY = structure.getCenter().getY();
            double originZ = structure.getCenter().getZ();
            ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView> request = new ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView>(
                new ViewPlateKey(PORTAL_ID, destination, random.nextBoolean(), quarterTurns, 0L), portal.getStructure(), destination,
                frame, mirror ? frame.flipNormal() : frame, originX, originY, originZ, originX, originY, originZ,
                mirror, quarterTurns, 6 + random.nextInt(14), 4 + random.nextInt(16), 0.75D, true, air, lod, false, 1L, 2L, 0L, blocks);
            ViewPlate<BlockData> before = ViewPlateBuilder.build(request);

            LongOpenHashSet dirty = new LongOpenHashSet();
            for (int change = 0; change < 1 + random.nextInt(6); change++) {
                int x = -10 + random.nextInt(50);
                int y = 50 + random.nextInt(30);
                int z = -30 + random.nextInt(60);
                destination.override(x, y, z, random.nextBoolean() ? air : stone);
                dirty.add(ProjectionWorldChangeTracker.chunkKey(x >> 4, z >> 4));
            }
            ViewPlate<BlockData> fresh = ViewPlateBuilder.build(request);
            ViewPlateBuilder.Job<BlockData, World> patchJob = ViewPlateBuilder.patch(request, before, dirty);
            while (!patchJob.step(4096)) {
            }
            ViewPlate<BlockData> patched = patchJob.result();

            assertEquals(fresh.cellCount(), patched.cellCount(), "seed=" + seed);
            assertEquals(new LongOpenHashSet(fresh.cellKeys()), new LongOpenHashSet(patched.cellKeys()), "seed=" + seed);
            for (long key : fresh.cellKeys()) {
                PlateCell<BlockData> expected = fresh.cell(key);
                PlateCell<BlockData> actual = patched.cell(key);
                String label = "seed=" + seed + " cell=" + ProjectionCellKey.unpackX(key) + "," + ProjectionCellKey.unpackY(key) + "," + ProjectionCellKey.unpackZ(key);
                assertEquals(expected.kind(), actual.kind(), label);
                assertSame(expected.data(), actual.data(), label);
            }
        }
    }

    @Test
    void theFootprintCoversEveryRemoteChunkTheBuildReadsIncludingBuriedProbes() {
        PortalStructure structure = structureAt(14);
        ILocalPortal portal = portal(structure, PortalFrame.canonical(Direction.S));
        FakeWorldView destination = new FakeWorldView(blockData(Material.STONE), 1L);
        ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView> shallowRequest = footprintRequest(portal, structure, destination, false);
        ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView> buriedRequest = footprintRequest(portal, structure, destination, true);
        ViewPlateBuilder.Footprint shallow = ViewPlateBuilder.footprint(shallowRequest);
        ViewPlateBuilder.Footprint buried = ViewPlateBuilder.footprint(buriedRequest);
        ViewPlate<BlockData> plate = ViewPlateBuilder.build(shallowRequest);

        assertTrue(shallow.minChunkX() <= plate.minChunkX() && shallow.maxChunkX() >= plate.maxChunkX());
        assertTrue(shallow.minChunkZ() <= plate.minChunkZ() && shallow.maxChunkZ() >= plate.maxChunkZ());
        assertEquals(0, shallow.maxChunkX());
        assertEquals(1, buried.maxChunkX(), "buried probes two blocks past the box reach the neighbouring chunk");
        assertEquals(shallow.predictedBytes(), buried.predictedBytes());
        assertEquals(shallow.predictedBytes() + 48L, plate.bytes(), "one stone palette entry on top of the predicted grid");
    }

    private static ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView> footprintRequest(ILocalPortal portal, PortalStructure structure,
                                                                                                     ProjectionWorldView destination, boolean buried) {
        return new ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView>(new ViewPlateKey(PORTAL_ID, destination, true, 0, 0L),
            portal.getStructure(), destination, portal.getFrame(), portal.getFrame(),
            structure.getCenter().getX(), structure.getCenter().getY(), structure.getCenter().getZ(),
            structure.getCenter().getX(), structure.getCenter().getY(), structure.getCenter().getZ(),
            false, 0, 4.0D, 0.0D, 0.0D, buried, blockData(Material.AIR), LodPolicy.NONE, false, 1L, 42L, 0L,
            new BukkitProjectorBlocks(material -> false));
    }

    static PortalStructure structureAt(int x) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("worldKey", "minecraft:overworld");
        values.put("x1", Integer.valueOf(x));
        values.put("x2", Integer.valueOf(x));
        values.put("y1", Integer.valueOf(64));
        values.put("y2", Integer.valueOf(65));
        values.put("z1", Integer.valueOf(0));
        values.put("z2", Integer.valueOf(0));
        PortalStructure structure = new PortalStructure();
        structure.setArea(new Cuboid(values));
        return structure;
    }

    static ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView> request(ILocalPortal portal, PortalStructure structure, ProjectionWorldView destination, boolean frontSide) {
        PortalFrame frame = portal.getFrame();
        ViewPlateKey key = new ViewPlateKey(PORTAL_ID, destination, frontSide, 0, 0L);
        return new ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView>(key, portal.getStructure(), destination, frame, frame,
            structure.getCenter().getX(), structure.getCenter().getY(), structure.getCenter().getZ(),
            structure.getCenter().getX(), structure.getCenter().getY(), structure.getCenter().getZ(),
            false, 0, 4.0D, 2.0D, 0.75D, false, blockData(Material.AIR), LodPolicy.NONE, false,
            destination.getRevision(), 42L, 0L, new BukkitProjectorBlocks(material -> material == Material.STONE));
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
                case "getOrigin" -> BukkitGeometry.vector(origin);
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

    static final class NoiseWorldView implements ProjectionWorldView {
        private final Map<Long, BlockData> overrides = new HashMap<Long, BlockData>();
        private final int seed;
        private final int stoneShare;
        private final BlockData stone;
        private final BlockData glass;
        private final BlockData air;

        NoiseWorldView(int seed, int stoneShare, BlockData stone, BlockData glass, BlockData air) {
            this.seed = seed;
            this.stoneShare = stoneShare;
            this.stone = stone;
            this.glass = glass;
            this.air = air;
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

        void override(int x, int y, int z, BlockData data) {
            overrides.put(Long.valueOf(ProjectionCellKey.pack(x, y, z)), data);
        }

        @Override
        public BlockData sampleBlockData(int x, int y, int z) {
            BlockData override = overrides.get(Long.valueOf(ProjectionCellKey.pack(x, y, z)));
            if (override != null) {
                return override;
            }
            long hash = (x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L) ^ (seed * 2654435761L);
            hash ^= hash >>> 17;
            hash *= 0x2545F4914F6CDD1DL;
            int bucket = (int) Math.floorMod(hash >>> 11, 12L);
            if (bucket < stoneShare) {
                return stone;
            }
            if (bucket == 10) {
                return glass;
            }
            if (bucket == 11 && (seed & 1) == 0) {
                return null;
            }
            return air;
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
    }

    static final class FakeWorldView implements ProjectionWorldView {
        private final Map<String, BlockData> blocks = new HashMap<String, BlockData>();
        private final Map<String, Boolean> unknown = new HashMap<String, Boolean>();
        private final BlockData defaultData;
        long revision;
        int reads;
        boolean blockEntitiesComplete = true;

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

        @Override
        public boolean blockEntitiesComplete(int x, int z) {
            return blockEntitiesComplete;
        }

        private static String key(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }
    }
}

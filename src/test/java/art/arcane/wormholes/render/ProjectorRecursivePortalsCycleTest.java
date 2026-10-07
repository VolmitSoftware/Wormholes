package art.arcane.wormholes.render;

import art.arcane.wormholes.render.BukkitProjectorBlocks;
import org.bukkit.Material;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.recursion.RecursiveEndpoints;
import art.arcane.optics.scan.Sample;
import art.arcane.optics.scan.Sampler;

final class ProjectorRecursivePortalsCycleTest {
    private static final double EYE_X = 1.0D;
    private static final double EYE_Y = 65.0D;
    private static final double EYE_Z = -5.0D;
    private static final double SAMPLE_X = 1.0D;
    private static final double SAMPLE_Y = 65.0D;
    private static final double SAMPLE_Z = 6.0D;

    @Test
    void reachClassifiesSampleVolumesByTheCandidatesTheyCanHit() {
        FacingPair pair = new FacingPair(true);
        RecursiveEndpoints<World, ILocalPortal> portals = BukkitProjectorPortalAccess.create(pair::portals);
        RecursiveEndpoints<World, ILocalPortal>.Index index = portals.indexFor(pair.world, EYE_X, EYE_Y, EYE_Z, pair.back);
        List<RecursiveEndpoints<World, ILocalPortal>.Candidate> masks = new ArrayList<RecursiveEndpoints<World, ILocalPortal>.Candidate>();

        assertEquals(RecursiveEndpoints.Reach.RECURSIVE,
            index.reach(SAMPLE_X, SAMPLE_Y, SAMPLE_Z, SAMPLE_X, SAMPLE_Y, SAMPLE_Z, 3, masks));
        assertTrue(masks.isEmpty());
        assertEquals(RecursiveEndpoints.Reach.MASK,
            index.reach(SAMPLE_X, SAMPLE_Y, SAMPLE_Z, SAMPLE_X, SAMPLE_Y, SAMPLE_Z, 0, masks));
        assertEquals(1, masks.size());
        assertEquals(RecursiveEndpoints.Reach.NONE, index.reach(80.0D, 40.0D, -20.0D, 180.0D, 90.0D, 20.0D, 3, masks));
        assertEquals(RecursiveEndpoints.Reach.NONE, index.reach(-180.0D, 40.0D, -20.0D, -80.0D, 90.0D, 20.0D, 3, masks));
        assertEquals(RecursiveEndpoints.Reach.NONE, index.reach(-20.0D, 200.0D, -20.0D, 20.0D, 250.0D, 20.0D, 3, masks));
        assertEquals(RecursiveEndpoints.Reach.NONE, index.reach(-20.0D, 40.0D, -180.0D, 20.0D, 90.0D, -80.0D, 3, masks));
        assertTrue(masks.isEmpty());
        assertTrue(portals.reaches(pair.world, pair.back, SAMPLE_X, SAMPLE_Y, SAMPLE_Z, SAMPLE_X, SAMPLE_Y, SAMPLE_Z));
        assertFalse(portals.reaches(pair.world, pair.back, 80.0D, 40.0D, -20.0D, 180.0D, 90.0D, 20.0D));
    }

    @Test
    void portalsWithoutADestinationReachOnlyAsMasks() {
        FacingPair pair = new FacingPair(false);
        RecursiveEndpoints<World, ILocalPortal> portals = BukkitProjectorPortalAccess.create(pair::portals);
        RecursiveEndpoints<World, ILocalPortal>.Index index = portals.indexFor(pair.world, EYE_X, EYE_Y, EYE_Z, pair.back);
        List<RecursiveEndpoints<World, ILocalPortal>.Candidate> masks = new ArrayList<RecursiveEndpoints<World, ILocalPortal>.Candidate>();

        assertEquals(RecursiveEndpoints.Reach.MASK,
            index.reach(SAMPLE_X, SAMPLE_Y, SAMPLE_Z, SAMPLE_X, SAMPLE_Y, SAMPLE_Z, 3, masks));
        assertEquals(1, masks.size());
        assertTrue(masks.get(0).covers(SAMPLE_X, SAMPLE_Y, SAMPLE_Z));
        RecursiveEndpoints.Hit<World, ILocalPortal> hit = index.find(SAMPLE_X, SAMPLE_Y, SAMPLE_Z, 3);
        assertNotNull(hit);
        assertFalse(hit.traversable);
        assertFalse(hit.cycle);
        assertFalse(index.maskHit().traversable);
    }

    @Test
    void unreachableGeometrySharesOneEmptyIndex() {
        FacingPair pair = new FacingPair(true);
        RecursiveEndpoints<World, ILocalPortal> portals = BukkitProjectorPortalAccess.create(pair::portals);
        RecursiveEndpoints<World, ILocalPortal>.Index empty = portals.emptyIndex();

        assertFalse(portals.reaches(pair.world, pair.back, 400.0D, 40.0D, 400.0D, 480.0D, 90.0D, 480.0D));
        assertTrue(empty.isEmpty());
        assertTrue(empty.paths().isEmpty());
        assertNull(empty.find(SAMPLE_X, SAMPLE_Y, SAMPLE_Z, 3));
        assertSame(empty, portals.emptyIndex());
        portals.clear();
        assertSame(empty, portals.emptyIndex());
    }

    @Test
    void clippedLinesContainEveryCoveredPoint() {
        FacingPair pair = new FacingPair(false);
        RecursiveEndpoints<World, ILocalPortal> portals = BukkitProjectorPortalAccess.create(pair::portals);
        Random random = new Random(40_961L);
        double[] range = new double[2];
        int covered = 0;
        int clippedAway = 0;
        for (int trial = 0; trial < 600; trial++) {
            double eyeX = -6.0D + (random.nextDouble() * 14.0D);
            double eyeY = 58.0D + (random.nextDouble() * 14.0D);
            double eyeZ = (random.nextBoolean() ? -1.0D : 1.0D) * (0.2D + (random.nextDouble() * 9.0D));
            RecursiveEndpoints<World, ILocalPortal>.Index index = portals.indexFor(pair.world, eyeX, eyeY, eyeZ, pair.back);
            for (RecursiveEndpoints<World, ILocalPortal>.Candidate candidate : index.paths()) {
                double baseX = -8.0D + (random.nextDouble() * 18.0D);
                double baseY = 56.0D + (random.nextDouble() * 18.0D);
                double baseZ = -14.0D + (random.nextDouble() * 28.0D);
                int axis = random.nextInt(4);
                double directionX = axis == 0 ? 1.0D : axis == 3 ? random.nextGaussian() : 0.0D;
                double directionY = axis == 1 ? 1.0D : axis == 3 ? random.nextGaussian() : 0.0D;
                double directionZ = axis == 2 ? 1.0D : axis == 3 ? random.nextGaussian() : 0.0D;
                if (random.nextBoolean()) {
                    directionX = -directionX;
                    directionY = -directionY;
                    directionZ = -directionZ;
                }
                range[0] = -30.0D;
                range[1] = 30.0D;
                boolean clipped = candidate.clipLine(baseX, baseY, baseZ, directionX, directionY, directionZ, range);
                for (int step = -600; step <= 600; step++) {
                    double s = step * 0.05D;
                    if (!candidate.covers(baseX + (s * directionX), baseY + (s * directionY), baseZ + (s * directionZ))) {
                        if (clipped && (s < range[0] || s > range[1])) {
                            clippedAway++;
                        }
                        continue;
                    }
                    covered++;
                    assertTrue(clipped && s >= range[0] && s <= range[1],
                        "trial=" + trial + " s=" + s + " range=[" + range[0] + ", " + range[1] + "] clipped=" + clipped);
                }
            }
        }
        assertTrue(covered > 2_000, "the sweep must exercise covered points, saw " + covered);
        assertTrue(clippedAway > 2_000, "the clip must reject most of each line, saw " + clippedAway);
    }

    @Test
    void maskOnlyHitsLeaveTheDestinationMemoIntact() {
        FacingPair maskPair = new FacingPair(false);
        FacingPair linkedPair = new FacingPair(true);
        Sampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> masked = sampler(maskPair);
        Sampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> linked = sampler(linkedPair);

        Sample<BlockData, ProjectionWorldView> maskSample = masked.resolve(new StoneWorldView(maskPair.world),
            SAMPLE_X, SAMPLE_Y, SAMPLE_Z, EYE_X, EYE_Y, EYE_Z, null, 3, false, null, null);
        linked.resolve(new StoneWorldView(linkedPair.world), SAMPLE_X, SAMPLE_Y, SAMPLE_Z, EYE_X, EYE_Y, EYE_Z,
            linkedPair.back, 3, false, null, null);

        assertEquals(Sample.Kind.MASK_AIR, maskSample.kind);
        assertFalse(masked.recursiveSamplesCached(), "a mask is pure geometry and must not poison the destination memo");
        assertTrue(linked.recursiveSamplesCached(), "a nested sample still invalidates the destination memo");
    }

    @Test
    void aCandidateAlreadyOnThePathIsReportedAsACycle() {
        FacingPair pair = new FacingPair(true);
        RecursiveEndpoints<World, ILocalPortal> portals = BukkitProjectorPortalAccess.create(pair::portals);
        RecursiveEndpoints<World, ILocalPortal>.Index index = portals.indexFor(pair.world, EYE_X, EYE_Y, EYE_Z, pair.back);

        RecursiveEndpoints.Hit<World, ILocalPortal> fresh = index.find(SAMPLE_X, SAMPLE_Y, SAMPLE_Z, 3);
        RecursiveEndpoints.RecursionPath path = new RecursiveEndpoints.RecursionPath();
        path.push(pair.front.getId());
        RecursiveEndpoints.Hit<World, ILocalPortal> revisited = index.find(SAMPLE_X, SAMPLE_Y, SAMPLE_Z, 3, path);

        assertNotNull(fresh);
        assertTrue(fresh.traversable);
        assertFalse(fresh.cycle);
        assertEquals(pair.front.getId(), fresh.portalId);
        assertNotNull(revisited);
        assertFalse(revisited.traversable);
        assertTrue(revisited.cycle);
    }

    @Test
    void aSelfFacingPairMasksAtTheSecondNestingInsteadOfSpendingTheDepthBudget() {
        FacingPair pair = new FacingPair(true);
        RecursiveEndpoints<World, ILocalPortal> portals = BukkitProjectorPortalAccess.create(pair::portals);
        AtomicInteger nestedViewLookups = new AtomicInteger();
        ProjectionWorldView view = new StoneWorldView(pair.world);
        @SuppressWarnings("unchecked")
        Sampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView>[] sampler = (Sampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView>[]) new Sampler<?, ?, ?, ?, ?>[1];
        RenderTestSupport.withBukkitServer(() -> sampler[0] = BukkitProjectorBlocks.sampler(BukkitProjectorBlocks.memo(), portals,
            ignored -> {
                nestedViewLookups.incrementAndGet();
                return view;
            }));

        Sample<BlockData, ProjectionWorldView> sample = sampler[0].resolve(view, SAMPLE_X, SAMPLE_Y, SAMPLE_Z, EYE_X, EYE_Y, EYE_Z,
            pair.back, 3, false, null, null);

        assertEquals(Sample.Kind.MASK_AIR, sample.kind);
        assertEquals(1, nestedViewLookups.get(), "the second nesting must be masked, not sampled");
    }

    private static Sampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler(FacingPair pair) {
        RecursiveEndpoints<World, ILocalPortal> portals = BukkitProjectorPortalAccess.create(pair::portals);
        ProjectionWorldView view = new StoneWorldView(pair.world);
        @SuppressWarnings("unchecked")
        Sampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView>[] sampler = (Sampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView>[]) new Sampler<?, ?, ?, ?, ?>[1];
        RenderTestSupport.withBukkitServer(() -> sampler[0] = BukkitProjectorBlocks.sampler(BukkitProjectorBlocks.memo(), portals,
            ignored -> view));
        return sampler[0];
    }

    private static final class FacingPair {
        private final World world;
        private final ILocalPortal front;
        private final ILocalPortal back;

        private FacingPair(boolean linked) {
            world = RenderTestSupport.world("recursion", List.<Entity>of());
            Map<String, Object> frontState = portalState(Frame.canonical(Face.S), 0.0D);
            Map<String, Object> backState = portalState(Frame.canonical(Face.N), 4.0D);
            front = RenderTestSupport.portal(frontState);
            back = RenderTestSupport.portal(backState);
            if (linked) {
                frontState.put("tunnel", RenderTestSupport.tunnel(back));
                backState.put("tunnel", RenderTestSupport.tunnel(front));
            }
        }

        private Map<String, Object> portalState(Frame frame, double planeZ) {
            Map<String, Object> state = RenderTestSupport.portalState(world, new Vector(1.0D, 65.0D, planeZ), frame);
            state.put("structure", new ApertureStructure(new Box(0.0D, 2.0D, 64.0D, 66.0D, planeZ, planeZ + 1.0D)));
            state.put("view", new Box(-20.0D, 20.0D, 40.0D, 90.0D, -20.0D, 20.0D));
            state.put("supportsProjections", Boolean.TRUE);
            state.put("projecting", Boolean.TRUE);
            state.put("open", Boolean.TRUE);
            state.put("mirrorMode", Boolean.FALSE);
            return state;
        }

        private List<ILocalPortal> portals() {
            List<ILocalPortal> all = new ArrayList<ILocalPortal>(2);
            all.add(front);
            all.add(back);
            return all;
        }
    }

    private static final class ApertureStructure extends PortalStructure {
        private final Box area;

        private ApertureStructure(Box area) {
            this.area = area;
        }

        @Override
        public Box getArea() {
            return area;
        }

        @Override
        public boolean isFullCuboid() {
            return true;
        }
    }

    private static final class StoneWorldView implements ProjectionWorldView {
        private final World world;

        private StoneWorldView(World world) {
            this.world = world;
        }

        @Override
        public World getWorld() {
            return world;
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
            return null;
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

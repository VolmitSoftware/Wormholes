package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

final class ProjectorRecursivePortalsCycleTest {
    private static final double EYE_X = 1.0D;
    private static final double EYE_Y = 65.0D;
    private static final double EYE_Z = -5.0D;
    private static final double SAMPLE_X = 1.0D;
    private static final double SAMPLE_Y = 65.0D;
    private static final double SAMPLE_Z = 6.0D;

    @Test
    void aCandidateAlreadyOnThePathIsReportedAsACycle() {
        FacingPair pair = new FacingPair();
        ProjectorRecursivePortals portals = new ProjectorRecursivePortals(pair::portals);
        ProjectorRecursivePortals.Index index = portals.indexFor(pair.world, EYE_X, EYE_Y, EYE_Z, pair.back);

        ProjectorRecursivePortals.Hit fresh = index.find(SAMPLE_X, SAMPLE_Y, SAMPLE_Z, 3);
        ProjectorRecursivePortals.RecursionPath path = new ProjectorRecursivePortals.RecursionPath();
        path.push(pair.front.getId());
        ProjectorRecursivePortals.Hit revisited = index.find(SAMPLE_X, SAMPLE_Y, SAMPLE_Z, 3, path);

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
        FacingPair pair = new FacingPair();
        ProjectorRecursivePortals portals = new ProjectorRecursivePortals(pair::portals);
        AtomicInteger nestedViewLookups = new AtomicInteger();
        ProjectionWorldView view = new StoneWorldView(pair.world);
        ProjectorSampler[] sampler = new ProjectorSampler[1];
        RenderTestSupport.withBukkitServer(() -> sampler[0] = new ProjectorSampler(new ProjectorSampleMemo(), portals,
            ignored -> {
                nestedViewLookups.incrementAndGet();
                return view;
            }));

        ProjectorSample sample = sampler[0].resolve(view, SAMPLE_X, SAMPLE_Y, SAMPLE_Z, EYE_X, EYE_Y, EYE_Z,
            pair.back, 3, false, null, null);

        assertEquals(ProjectorSample.Kind.MASK_AIR, sample.kind);
        assertEquals(1, nestedViewLookups.get(), "the second nesting must be masked, not sampled");
    }

    private static final class FacingPair {
        private final World world;
        private final ILocalPortal front;
        private final ILocalPortal back;

        private FacingPair() {
            world = RenderTestSupport.world("recursion", List.<Entity>of());
            Map<String, Object> frontState = portalState(PortalFrame.canonical(Direction.S), 0.0D);
            Map<String, Object> backState = portalState(PortalFrame.canonical(Direction.N), 4.0D);
            front = RenderTestSupport.portal(frontState);
            back = RenderTestSupport.portal(backState);
            frontState.put("tunnel", RenderTestSupport.tunnel(back));
            backState.put("tunnel", RenderTestSupport.tunnel(front));
        }

        private Map<String, Object> portalState(PortalFrame frame, double planeZ) {
            Map<String, Object> state = RenderTestSupport.portalState(world, new Vector(1.0D, 65.0D, planeZ), frame);
            state.put("structure", new ApertureStructure(new AxisAlignedBB(0.0D, 2.0D, 64.0D, 66.0D, planeZ, planeZ + 1.0D)));
            state.put("view", new AxisAlignedBB(-20.0D, 20.0D, 40.0D, 90.0D, -20.0D, 20.0D));
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
        private final AxisAlignedBB area;

        private ApertureStructure(AxisAlignedBB area) {
            this.area = area;
        }

        @Override
        public AxisAlignedBB getArea() {
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

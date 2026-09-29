package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

final class ProjectorRecursivePortalsRevalidateTest {
    private static final double EYE_X = 1.0D;
    private static final double EYE_Y = 65.0D;
    private static final double EYE_Z = -5.0D;

    @Test
    void revalidateKeepsTheIndexWhileNoPortalChanged() {
        Pair pair = new Pair();
        ProjectorRecursivePortals<World, ILocalPortal> portals = BukkitProjectorPortalAccess.create(pair::portals);
        portals.revalidate();
        ProjectorRecursivePortals<World, ILocalPortal>.Index first = portals.indexFor(pair.world, EYE_X, EYE_Y, EYE_Z, pair.back);

        portals.revalidate();

        assertSame(first, portals.indexFor(pair.world, EYE_X, EYE_Y, EYE_Z, pair.back));
    }

    @Test
    void revalidateRebuildsTheIndexWhenAPortalCloses() {
        Pair pair = new Pair();
        ProjectorRecursivePortals<World, ILocalPortal> portals = BukkitProjectorPortalAccess.create(pair::portals);
        portals.revalidate();
        ProjectorRecursivePortals<World, ILocalPortal>.Index first = portals.indexFor(pair.world, EYE_X, EYE_Y, EYE_Z, pair.back);
        assertEquals(1, first.paths().size());

        pair.frontState.put("open", Boolean.FALSE);
        portals.revalidate();
        ProjectorRecursivePortals<World, ILocalPortal>.Index second = portals.indexFor(pair.world, EYE_X, EYE_Y, EYE_Z, pair.back);

        assertNotSame(first, second);
        assertEquals(0, second.paths().size());
    }

    @Test
    void revalidateRebuildsTheIndexWhenAPortalMovesOrRelinks() {
        Pair pair = new Pair();
        ProjectorRecursivePortals<World, ILocalPortal> portals = BukkitProjectorPortalAccess.create(pair::portals);
        portals.revalidate();
        ProjectorRecursivePortals<World, ILocalPortal>.Index first = portals.indexFor(pair.world, EYE_X, EYE_Y, EYE_Z, pair.back);

        pair.frontState.put("view", new AxisAlignedBB(-30.0D, 30.0D, 40.0D, 90.0D, -30.0D, 30.0D));
        portals.revalidate();
        ProjectorRecursivePortals<World, ILocalPortal>.Index moved = portals.indexFor(pair.world, EYE_X, EYE_Y, EYE_Z, pair.back);
        assertNotSame(first, moved);

        pair.frontState.put("tunnel", null);
        portals.revalidate();
        ProjectorRecursivePortals<World, ILocalPortal>.Index unlinked = portals.indexFor(pair.world, EYE_X, EYE_Y, EYE_Z, pair.back);
        assertNotSame(moved, unlinked);
        assertEquals(false, unlinked.paths().get(0).traversable);
    }

    private static final class Pair {
        private final World world;
        private final Map<String, Object> frontState;
        private final ILocalPortal front;
        private final ILocalPortal back;

        private Pair() {
            world = RenderTestSupport.world("revalidate", List.<Entity>of());
            frontState = portalState(PortalFrame.canonical(Direction.S), 0.0D);
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
}

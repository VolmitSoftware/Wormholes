package art.arcane.wormholes.modded;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.render.ProjectionGazeScheduler;
import art.arcane.wormholes.util.AxisAlignedBB;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftProjectionServiceGazeTest {
    private static final double EPSILON = 1.0E-9D;

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void candidateSpansTheApertureArea() {
        PortalGeometry geometry = new PortalGeometry();
        geometry.setArea(new AxisAlignedBB(10.0D, 12.999D, 64.0D, 66.999D, -3.0D, -3.0D));
        MinecraftPortal portal = portal(geometry, new GeometryVector(11.5D, 65.5D, -2.5D));

        ProjectionGazeScheduler.Candidate<MinecraftPortal> candidate = MinecraftProjectionService.gazeCandidate(portal, true);

        assertSame(portal, candidate.value());
        assertEquals(portal.getId(), candidate.id());
        assertEquals(10.0D, candidate.minX(), EPSILON);
        assertEquals(64.0D, candidate.minY(), EPSILON);
        assertEquals(-3.0D, candidate.minZ(), EPSILON);
        assertEquals(12.999D, candidate.maxX(), EPSILON);
        assertEquals(66.999D, candidate.maxY(), EPSILON);
        assertEquals(-3.0D, candidate.maxZ(), EPSILON);
        assertTrue(candidate.pendingScan());
        assertFalse(candidate.retiring());
    }

    @Test
    public void candidateWithoutAreaIsAUnitBoxAtTheOrigin() {
        MinecraftPortal portal = portal(new PortalGeometry(), new GeometryVector(4.0D, 70.0D, -8.0D));

        ProjectionGazeScheduler.Candidate<MinecraftPortal> candidate = MinecraftProjectionService.gazeCandidate(portal, false);

        assertEquals(3.5D, candidate.minX(), EPSILON);
        assertEquals(69.5D, candidate.minY(), EPSILON);
        assertEquals(-8.5D, candidate.minZ(), EPSILON);
        assertEquals(4.5D, candidate.maxX(), EPSILON);
        assertEquals(70.5D, candidate.maxY(), EPSILON);
        assertEquals(-7.5D, candidate.maxZ(), EPSILON);
        assertFalse(candidate.pendingScan());
    }

    @Test
    public void portalInViewOutranksPortalBehindTheObserver() {
        PortalGeometry ahead = new PortalGeometry();
        ahead.setArea(new AxisAlignedBB(-1.0D, 1.999D, 64.0D, 66.999D, 6.0D, 6.0D));
        PortalGeometry behind = new PortalGeometry();
        behind.setArea(new AxisAlignedBB(-1.0D, 1.999D, 64.0D, 66.999D, -6.0D, -6.0D));
        MinecraftPortal inView = portal(ahead, new GeometryVector(0.5D, 65.5D, 6.5D));
        MinecraftPortal outOfView = portal(behind, new GeometryVector(0.5D, 65.5D, -5.5D));
        ProjectionGazeScheduler scheduler = new ProjectionGazeScheduler();
        UUID observer = UUID.randomUUID();
        List<ProjectionGazeScheduler.Candidate<MinecraftPortal>> candidates = List.of(
            MinecraftProjectionService.gazeCandidate(outOfView, false), MinecraftProjectionService.gazeCandidate(inView, false));
        ProjectionGazeScheduler.Options options = new ProjectionGazeScheduler.Options(110.0D, 3, 20);

        scheduler.select(observer, eye(1L), candidates, 2, 1L, options);

        assertEquals(List.of(inView), scheduler.select(observer, eye(2L), candidates, 2, 2L, options));
    }

    @Test
    public void offCadenceTicksOnlyScheduleProjectorsWithPendingScans() {
        PortalGeometry ahead = new PortalGeometry();
        ahead.setArea(new AxisAlignedBB(-1.0D, 1.999D, 64.0D, 66.999D, 6.0D, 6.0D));
        MinecraftPortal inView = portal(ahead, new GeometryVector(0.5D, 65.5D, 6.5D));
        List<MinecraftPortal> active = List.of(inView);

        assertTrue(MinecraftProjectionService.blockCandidates(active, ignored -> false, false).isEmpty());
        List<ProjectionGazeScheduler.Candidate<MinecraftPortal>> pending = MinecraftProjectionService.blockCandidates(active, ignored -> true, false);
        assertEquals(1, pending.size());
        assertTrue(pending.get(0).pendingScan());
        assertEquals(1, MinecraftProjectionService.blockCandidates(active, ignored -> false, true).size());
    }

    @Test
    public void portalThatMissedItsPassStaysUnsettledUntilTheNextPassTick() {
        PortalGeometry ahead = new PortalGeometry();
        ahead.setArea(new AxisAlignedBB(-1.0D, 1.999D, 64.0D, 66.999D, 6.0D, 6.0D));
        MinecraftPortal inView = portal(ahead, new GeometryVector(0.5D, 65.5D, 6.5D));
        List<MinecraftPortal> active = List.of(inView);
        ProjectionGazeScheduler scheduler = new ProjectionGazeScheduler();
        UUID observer = UUID.randomUUID();
        ProjectionGazeScheduler.Options options = new ProjectionGazeScheduler.Options(110.0D, 3, 20);
        ProjectionGazeScheduler.Eye first = new ProjectionGazeScheduler.Eye(0.0D, 65.6D, 0.5D, 0.0F, 0.0F);
        ProjectionGazeScheduler.Eye moved = new ProjectionGazeScheduler.Eye(1.0D, 65.6D, 0.5D, 0.0F, 0.0F);
        int refreshIntervalTicks = 4;

        assertEquals(List.of(inView), scheduler.select(observer, first, MinecraftProjectionService.blockCandidates(active, ignored -> false,
            MinecraftProjectionService.blockPassTick(4L, refreshIntervalTicks)), 1, 4L, options));
        for (long tick = 5L; tick < 8L; tick++) {
            scheduler.select(observer, moved, MinecraftProjectionService.blockCandidates(active, ignored -> false,
                MinecraftProjectionService.blockPassTick(tick, refreshIntervalTicks)), 1, tick, options);
        }

        assertEquals(List.of(inView), scheduler.select(observer, moved, MinecraftProjectionService.blockCandidates(active, ignored -> false,
            MinecraftProjectionService.blockPassTick(8L, refreshIntervalTicks)), 1, 8L, options));
    }

    private static ProjectionGazeScheduler.Eye eye(long tick) {
        return new ProjectionGazeScheduler.Eye((tick & 1L) * 0.3D, 65.6D, 0.5D, 0.0F, 0.0F);
    }

    private static MinecraftPortal portal(PortalGeometry geometry, GeometryVector origin) {
        MinecraftPortal portal = mock(MinecraftPortal.class);
        when(portal.getId()).thenReturn(UUID.randomUUID());
        when(portal.getGeometry()).thenReturn(geometry);
        when(portal.getOrigin()).thenReturn(origin);
        return portal;
    }
}

package art.arcane.wormholes.modded;

import art.arcane.optics.math.Vec3;
import art.arcane.wormholes.modded.clientview.MinecraftClientViewService;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.volume.GazeScheduler;
import art.arcane.optics.math.Box;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftProjectionServiceGazeTest extends MinecraftTestBase {
    private static final double EPSILON = 1.0E-9D;

    @Test
    public void nativeDoorOwnershipHidesTheVeilOnlyWhileThatObserverOwnsTheView() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftClientViewService clientViews = mock(MinecraftClientViewService.class);
        when(runtime.clientViews()).thenReturn(clientViews);
        MinecraftProjectionService service = new MinecraftProjectionService(runtime);
        UUID observer = UUID.randomUUID();
        UUID door = UUID.randomUUID();
        assertFalse(service.isDoorProjected(observer, door));
        when(clientViews.owns(observer, door)).thenReturn(true);
        assertTrue(service.isDoorProjected(observer, door));
        assertFalse(service.isDoorProjected(UUID.randomUUID(), door));
        when(clientViews.owns(observer, door)).thenReturn(false);
        assertFalse(service.isDoorProjected(observer, door));
    }

    @Test
    public void candidateSpansTheApertureArea() {
        ApertureCells geometry = new ApertureCells();
        geometry.setArea(new Box(10.0D, 12.999D, 64.0D, 66.999D, -3.0D, -3.0D));
        MinecraftPortal portal = portal(geometry, new Vec3(11.5D, 65.5D, -2.5D));

        GazeScheduler.Candidate<MinecraftPortal> candidate = MinecraftProjectionService.gazeCandidate(portal, true);

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
        MinecraftPortal portal = portal(new ApertureCells(), new Vec3(4.0D, 70.0D, -8.0D));

        GazeScheduler.Candidate<MinecraftPortal> candidate = MinecraftProjectionService.gazeCandidate(portal, false);

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
        ApertureCells ahead = new ApertureCells();
        ahead.setArea(new Box(-1.0D, 1.999D, 64.0D, 66.999D, 6.0D, 6.0D));
        ApertureCells behind = new ApertureCells();
        behind.setArea(new Box(-1.0D, 1.999D, 64.0D, 66.999D, -6.0D, -6.0D));
        MinecraftPortal inView = portal(ahead, new Vec3(0.5D, 65.5D, 6.5D));
        MinecraftPortal outOfView = portal(behind, new Vec3(0.5D, 65.5D, -5.5D));
        GazeScheduler scheduler = new GazeScheduler();
        UUID observer = UUID.randomUUID();
        List<GazeScheduler.Candidate<MinecraftPortal>> candidates = List.of(
            MinecraftProjectionService.gazeCandidate(outOfView, false), MinecraftProjectionService.gazeCandidate(inView, false));
        GazeScheduler.Options options = new GazeScheduler.Options(110.0D, 3, 20);

        scheduler.select(observer, eye(1L), candidates, 2, 1L, options);

        assertEquals(List.of(inView), scheduler.select(observer, eye(2L), candidates, 2, 2L, options));
    }

    @Test
    public void offCadenceTicksOnlyScheduleProjectorsWithPendingScans() {
        ApertureCells ahead = new ApertureCells();
        ahead.setArea(new Box(-1.0D, 1.999D, 64.0D, 66.999D, 6.0D, 6.0D));
        MinecraftPortal inView = portal(ahead, new Vec3(0.5D, 65.5D, 6.5D));
        List<MinecraftPortal> active = List.of(inView);

        assertTrue(MinecraftProjectionService.blockCandidates(active, ignored -> false, false).isEmpty());
        List<GazeScheduler.Candidate<MinecraftPortal>> pending = MinecraftProjectionService.blockCandidates(active, ignored -> true, false);
        assertEquals(1, pending.size());
        assertTrue(pending.get(0).pendingScan());
        assertEquals(1, MinecraftProjectionService.blockCandidates(active, ignored -> false, true).size());
    }

    @Test
    public void portalThatMissedItsPassStaysUnsettledUntilTheNextPassTick() {
        ApertureCells ahead = new ApertureCells();
        ahead.setArea(new Box(-1.0D, 1.999D, 64.0D, 66.999D, 6.0D, 6.0D));
        MinecraftPortal inView = portal(ahead, new Vec3(0.5D, 65.5D, 6.5D));
        List<MinecraftPortal> active = List.of(inView);
        GazeScheduler scheduler = new GazeScheduler();
        UUID observer = UUID.randomUUID();
        GazeScheduler.Options options = new GazeScheduler.Options(110.0D, 3, 20);
        GazeScheduler.Eye first = new GazeScheduler.Eye(0.0D, 65.6D, 0.5D, 0.0F, 0.0F);
        GazeScheduler.Eye moved = new GazeScheduler.Eye(1.0D, 65.6D, 0.5D, 0.0F, 0.0F);
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

    private static GazeScheduler.Eye eye(long tick) {
        return new GazeScheduler.Eye((tick & 1L) * 0.3D, 65.6D, 0.5D, 0.0F, 0.0F);
    }

    private static MinecraftPortal portal(ApertureCells geometry, Vec3 origin) {
        MinecraftPortal portal = mock(MinecraftPortal.class);
        when(portal.getId()).thenReturn(UUID.randomUUID());
        when(portal.getGeometry()).thenReturn(geometry);
        when(portal.getOrigin()).thenReturn(origin);
        return portal;
    }
}

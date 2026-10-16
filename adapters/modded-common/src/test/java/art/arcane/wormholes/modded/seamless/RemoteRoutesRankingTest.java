package art.arcane.wormholes.modded.seamless;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RemoteRoutesRankingTest extends MinecraftTestBase {
    @Test
    public void nearestCandidatesWinUpToTheRouteLimit() {
        ServerLevel level = mock(ServerLevel.class);
        RemoteRoutes.Candidate far = candidate(level, 40.0D);
        RemoteRoutes.Candidate near = candidate(level, 3.0D);
        RemoteRoutes.Candidate middle = candidate(level, 12.0D);

        List<RemoteRoutes.Candidate> ranked = RemoteRoutes.rank(List.of(far, near, middle), 2);

        assertEquals(List.of(near, middle), ranked);
        assertEquals(List.of(near, middle, far), RemoteRoutes.rank(List.of(far, near, middle), 4));
        assertTrue(RemoteRoutes.rank(List.of(), 2).isEmpty());
    }

    @Test
    public void rankingPreservesTheExactDestinationDoorCollisionTarget() {
        ServerLevel level = mock(ServerLevel.class);
        RemoteRoutes.Candidate portal = candidate(level, 8.0D);
        RemoteRoutes.Candidate source = candidate(level, 3.0D);
        TravelMessage.DoorCollisionTarget target = new TravelMessage.DoorCollisionTarget(-17, 70, 33, true);
        RemoteRoutes.Candidate door = new RemoteRoutes.Candidate(source.source(), source.destination(), level, 3.0D, target);

        List<RemoteRoutes.Candidate> ranked = RemoteRoutes.rank(List.of(portal, door), 2);

        assertEquals(target, ranked.getFirst().doorCollision());
        assertNull(ranked.getLast().doorCollision());
    }

    @Test
    public void levelsWithAnInlineDimensionTypeHaveNoTravelWorld() {
        ServerLevel level = mock(ServerLevel.class);
        Holder<DimensionType> inline = Holder.direct(mock(DimensionType.class));
        when(level.dimensionTypeRegistration()).thenReturn(inline);
        when(level.dimension()).thenReturn(Level.OVERWORLD);

        assertTrue(RemoteRoutes.travelWorld(level).isEmpty());
    }

    @Test
    public void windowRadiusFollowsTheImmersivePortalsLoaderTiers() {
        assertEquals(12, RemoteRoutes.radius(12, 0.0D));
        assertEquals(12, RemoteRoutes.radius(12, 4.9D));
        assertEquals(8, RemoteRoutes.radius(12, 5.0D));
        assertEquals(8, RemoteRoutes.radius(12, 14.9D));
        assertEquals(4, RemoteRoutes.radius(12, 15.0D));
        assertEquals(5, RemoteRoutes.radius(16, 40.0D));
        assertEquals(1, RemoteRoutes.radius(2, 10.0D));
        assertEquals(1, RemoteRoutes.radius(2, 100.0D));
    }

    @Test
    public void distanceIsMeasuredToTheNearestPointOfThePortal() {
        Box area = new Box(10.0D, 13.0D, 64.0D, 67.0D, 5.0D, 5.0D);

        assertEquals(0.0D, RemoteRoutes.distance(area, new Vec3d(11.0D, 65.0D, 5.0D)), 1.0E-9D);
        assertEquals(4.0D, RemoteRoutes.distance(area, new Vec3d(12.0D, 64.0D, 9.0D)), 1.0E-9D);
        assertEquals(5.0D, RemoteRoutes.distance(area, new Vec3d(16.0D, 66.0D, 9.0D)), 1.0E-9D);
        assertEquals(2.0D, RemoteRoutes.distance(area, new Vec3d(11.0D, 62.0D, 5.0D)), 1.0E-9D);
    }

    @Test
    public void fullRadiusFollowsTheVanillaViewDistanceAndIsCappedAtSixteen() {
        assertEquals(10, RemoteRoutes.fullRadius(10, 12));
        assertEquals(12, RemoteRoutes.fullRadius(32, 12));
        assertEquals(2, RemoteRoutes.fullRadius(0, 12));
        assertEquals(16, RemoteRoutes.fullRadius(32, 32));
        assertEquals(16, RouteWindow.MAX_RADIUS);
    }

    @Test
    public void windowCentresOnTheDestinationChunkAndUsesVanillaTrackingMembership() {
        RouteWindow window = RemoteRoutes.window(new Vec3d(-17.5D, 70.0D, 33.0D), 3);

        assertEquals(-2, window.centerX());
        assertEquals(2, window.centerZ());
        ChunkTrackingView.Positioned view = window.view();
        assertEquals(new ChunkPos(-2, 2), view.center());
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                assertEquals(view.contains(x, z), window.contains(x, z));
            }
        }
        assertEquals(ChunkPos.pack(-2, 2), window.keys().getLong(0));
    }

    @Test
    public void routeIsNearOnlyWhenTheWholeWindowIsInsideTheVanillaView() {
        RouteWindow window = new RouteWindow(10, 10, 2);

        assertTrue(window.within(ChunkTrackingView.of(new ChunkPos(10, 10), 8)));
        assertTrue(window.within(ChunkTrackingView.of(new ChunkPos(8, 10), 8)));
        assertFalse(window.within(ChunkTrackingView.of(new ChunkPos(2, 10), 8)));
        assertFalse(window.within(ChunkTrackingView.EMPTY));
    }

    @Test
    public void managedEndPortalsNeverOpenTheFloorUnderTheirAperture() {
        ServerLevel level = mock(ServerLevel.class);
        Box standing = new Box(11.2D, 11.8D, 64.0D, 65.8D, 11.2D, 11.8D);
        RemoteRoutes.Candidate frame = floor(level, DimensionalPortalKind.NONE);

        for (DimensionalPortalKind kind : new DimensionalPortalKind[] {DimensionalPortalKind.END_SOURCE, DimensionalPortalKind.END_EXIT,
            DimensionalPortalKind.END_ARRIVAL}) {
            RemoteRoutes.Candidate end = floor(level, kind);
            assertNull(RemoteRoutes.straddled(standing, List.of(end)));
            assertSame(frame, RemoteRoutes.straddled(standing, List.of(end, frame)));
        }
        assertSame(frame, RemoteRoutes.straddled(standing, List.of(frame)));
        assertNull(RemoteRoutes.straddled(new Box(31.2D, 31.8D, 64.0D, 65.8D, 11.2D, 11.8D), List.of(frame)));
    }

    private static RemoteRoutes.Candidate floor(ServerLevel level, DimensionalPortalKind kind) {
        List<Vec3d> cells = new ArrayList<>(9);
        for (int x = 10; x <= 12; x++) {
            for (int z = 10; z <= 12; z++) {
                cells.add(new Vec3d(x, 64, z));
            }
        }
        ApertureCells geometry = new ApertureCells();
        geometry.setBlocks(cells);
        RemoteRoutes.Candidate candidate = candidate(level, 1.0D);
        when(candidate.source().getGeometry()).thenReturn(geometry);
        when(candidate.source().getDimensionalKind()).thenReturn(kind);
        return candidate;
    }

    private static RemoteRoutes.Candidate candidate(ServerLevel level, double distance) {
        MinecraftPortal source = mock(MinecraftPortal.class);
        MinecraftPortal destination = mock(MinecraftPortal.class);
        when(source.getId()).thenReturn(UUID.randomUUID());
        when(destination.getId()).thenReturn(UUID.randomUUID());
        return new RemoteRoutes.Candidate(source, destination, level, distance, null);
    }
}

package art.arcane.wormholes.modded.seamless;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftTestBase;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void levelsWithAnInlineDimensionTypeHaveNoTravelWorld() {
        ServerLevel level = mock(ServerLevel.class);
        Holder<DimensionType> inline = Holder.direct(mock(DimensionType.class));
        when(level.dimensionTypeRegistration()).thenReturn(inline);
        when(level.dimension()).thenReturn(Level.OVERWORLD);

        assertTrue(RemoteRoutes.travelWorld(level).isEmpty());
    }

    @Test
    public void windowRadiusShrinksWithDistanceFromTheSourcePortal() {
        assertEquals(12, RemoteRoutes.radius(12, 0.0D));
        assertEquals(12, RemoteRoutes.radius(12, 8.0D));
        assertEquals(8, RemoteRoutes.radius(12, 8.5D));
        assertEquals(8, RemoteRoutes.radius(12, 24.0D));
        assertEquals(4, RemoteRoutes.radius(12, 24.5D));
        assertEquals(1, RemoteRoutes.radius(2, 100.0D));
        assertEquals(2, RemoteRoutes.radius(2, 20.0D));
    }

    @Test
    public void fullRadiusFollowsTheVanillaViewDistanceAndNeverExceedsTheWindowCap() {
        assertEquals(10, RemoteRoutes.fullRadius(10, 12));
        assertEquals(12, RemoteRoutes.fullRadius(32, 12));
        assertEquals(2, RemoteRoutes.fullRadius(0, 12));
        assertEquals(RouteWindow.MAX_RADIUS, RemoteRoutes.fullRadius(32, 32));
        assertEquals(RemoteRoutes.MIN_CORE_RADIUS, RemoteRoutes.coreRadius(2));
        assertEquals(5, RemoteRoutes.coreRadius(15));
        assertEquals(2, RemoteRoutes.coreRadius(3));
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
        assertEquals(window.keys().size(), window.coordinates().size());
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

    private static RemoteRoutes.Candidate candidate(ServerLevel level, double distance) {
        MinecraftPortal source = mock(MinecraftPortal.class);
        MinecraftPortal destination = mock(MinecraftPortal.class);
        when(source.getId()).thenReturn(UUID.randomUUID());
        when(destination.getId()).thenReturn(UUID.randomUUID());
        return new RemoteRoutes.Candidate(source, destination, level, distance);
    }
}

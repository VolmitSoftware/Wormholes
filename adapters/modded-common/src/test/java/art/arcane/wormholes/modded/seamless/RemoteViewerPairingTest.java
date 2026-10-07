package art.arcane.wormholes.modded.seamless;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.mixin.RemoteTrackedEntityAccess;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RemoteViewerPairingTest extends MinecraftTestBase {
    @Test
    public void aResidentRoutePairsEntitiesThePlayerAlreadyTracksInItsOwnLevel() {
        Fixture fixture = new Fixture(true);
        RemoteViewer.update(fixture.route, fixture.tracked, fixture.player, mock(RoutedSends.class));
        assertTrue(fixture.seenBy.contains(fixture.viewer));
        assertTrue(fixture.paired.contains(7));
    }

    @Test
    public void aRouteIntoThePlayersOwnLevelLeavesVanillaTrackedEntitiesToVanilla() {
        Fixture fixture = new Fixture(false);
        RemoteViewer.update(fixture.route, fixture.tracked, fixture.player, mock(RoutedSends.class));
        assertEquals(Set.of(fixture.player.connection), fixture.seenBy);
        assertTrue(fixture.paired.isEmpty());
    }

    private static final class Fixture {
        final RemoteRoute route = mock(RemoteRoute.class);
        final RemoteViewerConnection viewer = mock(RemoteViewerConnection.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final RemoteTrackedEntityAccess tracked = mock(RemoteTrackedEntityAccess.class);
        final Set<ServerPlayerConnection> seenBy = new HashSet<>();
        final IntOpenHashSet paired = new IntOpenHashSet();

        Fixture(boolean resident) {
            player.connection = mock(ServerGamePacketListenerImpl.class);
            seenBy.add(player.connection);
            RouteStream stream = mock(RouteStream.class);
            when(stream.delivered(anyLong())).thenReturn(true);
            when(route.viewer()).thenReturn(viewer);
            when(route.window()).thenReturn(new RouteWindow(0, 0, 4));
            when(route.anchor()).thenReturn(new Vec3d(8.0D, 64.0D, 8.0D));
            when(route.stream()).thenReturn(stream);
            when(route.resident()).thenReturn(resident);
            when(route.paired()).thenReturn(paired);
            Entity entity = mock(Entity.class);
            when(entity.getId()).thenReturn(7);
            when(entity.chunkPosition()).thenReturn(new ChunkPos(0, 0));
            when(entity.getX()).thenReturn(10.0D);
            when(entity.getZ()).thenReturn(12.0D);
            when(entity.broadcastToPlayer(player)).thenReturn(true);
            when(tracked.wormholesTrackedEntity()).thenReturn(entity);
            when(tracked.wormholesSeenBy()).thenReturn(seenBy);
            when(tracked.wormholesServerEntity()).thenReturn(mock(ServerEntity.class));
            when(tracked.wormholesEffectiveRange()).thenReturn(80);
        }
    }
}

package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.seamless.RemoteRoute;
import art.arcane.wormholes.modded.seamless.RemoteViewerConnection;
import art.arcane.wormholes.modded.seamless.RoutedSends;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class EntityCrossingObserverTest extends MinecraftTestBase {
    private final ServerLevel overworld = mock(ServerLevel.class);
    private final ServerLevel nether = mock(ServerLevel.class);

    @Test
    public void observersStandingInTheEntityLevelSeeItInTheirOwnLevel() {
        assertEquals(0, MinecraftClientViewService.observerHandle(direct(overworld), overworld));
        assertEquals(-1, MinecraftClientViewService.observerHandle(direct(nether), overworld));
    }

    @Test
    public void observersWatchingThroughAResidentRouteSeeItInThatResidentLevel() {
        assertEquals(5, MinecraftClientViewService.observerHandle(routed(overworld, 5), overworld));
        assertEquals(-1, MinecraftClientViewService.observerHandle(routed(overworld, 0), overworld));
        assertEquals(-1, MinecraftClientViewService.observerHandle(routed(nether, 5), overworld));
    }

    private static ServerPlayerConnection direct(ServerLevel level) {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.level()).thenReturn(level);
        ServerPlayerConnection connection = mock(ServerPlayerConnection.class);
        when(connection.getPlayer()).thenReturn(player);
        return connection;
    }

    private static ServerPlayerConnection routed(ServerLevel level, int handle) {
        RemoteRoute route = mock(RemoteRoute.class);
        when(route.level()).thenReturn(level);
        when(route.handle()).thenReturn(handle);
        when(route.resident()).thenReturn(handle > 0);
        return new RemoteViewerConnection(mock(ServerPlayer.class), route, mock(RoutedSends.class));
    }
}

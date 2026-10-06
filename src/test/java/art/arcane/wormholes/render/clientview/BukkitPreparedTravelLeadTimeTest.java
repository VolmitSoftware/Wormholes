package art.arcane.wormholes.render.clientview;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.render.ClientViewPortalSource;
import art.arcane.wormholes.render.client.session.ClientViewServerSession;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BukkitPreparedTravelLeadTimeTest {
    @Test
    @SuppressWarnings("unchecked")
    void nearestEligibleInterestedPortalCanPrepareBeyondTwelveBlocksWithoutSelectingUninterestedPortals() throws Exception {
        Player player = mock(Player.class);
        World sourceWorld = mock(World.class);
        World destinationWorld = mock(World.class);
        when(player.getWorld()).thenReturn(sourceWorld);
        when(player.getLocation()).thenReturn(new Location(sourceWorld, 0.5D, 64.0D, 48.5D));
        ClientViewObserver observer = mock(ClientViewObserver.class);
        ClientViewServerSession<ClientViewObserver, BlockData> session = mock(ClientViewServerSession.class);
        when(session.player()).thenReturn(observer);
        ILocalPortal portal = mock(ILocalPortal.class);
        ILocalPortal destination = mock(ILocalPortal.class);
        UUID id = UUID.randomUUID();
        when(portal.getId()).thenReturn(id);
        when(portal.getOrigin()).thenReturn(new Vec3d(0.5D, 64.0D, 0.5D));
        when(portal.isOpen()).thenReturn(true);
        when(portal.canDepart(player)).thenReturn(true);
        ClientViewPortalSource route = mock(ClientViewPortalSource.class);
        when(route.destinationWorld()).thenReturn(destinationWorld);
        when(route.destinationAnchor()).thenReturn(destination);
        when(observer.source(id)).thenReturn(route);
        Method nearest = BukkitPreparedTravel.class.getDeclaredMethod("nearest", ClientViewServerSession.class, Player.class, List.class);
        nearest.setAccessible(true);
        assertSame(portal, nearest.invoke(null, session, player, List.of(portal)));
        assertNull(nearest.invoke(null, session, player, List.of()));
        when(portal.canDepart(player)).thenReturn(false);
        assertNull(nearest.invoke(null, session, player, List.of(portal)));
    }
}

package art.arcane.wormholes.portal;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.TraversableManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.util.BukkitGeometry;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class ArrivalWarmerCrossingTest {
    @Test
    void crossingDoesNotReplaceTheApproachStageWithTheOppositeSide() {
        verifyWarming(true);
    }

    @Test
    void arrivalLatchStillAllowsPreparingTheReturnRouteBeforeRecrossing() {
        verifyWarming(false);
    }

    private static void verifyWarming(boolean crossing) {
        TraversableManager previousManager = Wormholes.traversableManager;
        ArrivalWarmer previousWarmer = Wormholes.arrivalWarmer;
        World world = LocalPortalTestSupport.world("arrival-crossing");
        LocalPortal original = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
        original.setFrame(Frame.canonical(Face.E));
        LocalPortal portal = spy(original);
        doReturn(true).when(portal).isOpen();
        Location target = new Location(world, portal.getOrigin().x() + (crossing ? 0.1D : -2.0D), 64.0D, portal.getOrigin().z());
        Vector velocity = new Vector(0.2D, 0.0D, 0.0D);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenAnswer(call -> target.clone());
        when(player.getBoundingBox()).thenReturn(new BoundingBox(target.getX() - 0.3D, 64.0D, target.getZ() - 0.3D,
            target.getX() + 0.3D, 65.8D, target.getZ() + 0.3D));
        when(player.getEyeHeight()).thenReturn(1.62D);
        ArrivalWarmer warmer = mock(ArrivalWarmer.class);
        LocalPortalTraversal traversal = new LocalPortalTraversal(portal);
        try (MockedStatic<BukkitGeometry> geometry = mockStatic(BukkitGeometry.class, CALLS_REAL_METHODS)) {
            geometry.when(() -> BukkitGeometry.entities(portal.getStructure().getCaptureZone(), world)).thenReturn(List.of(player));
            Wormholes.traversableManager = new TraversableManager();
            Wormholes.arrivalWarmer = warmer;
            Wormholes.traversableManager.on(new PlayerMoveEvent(player, target.clone().subtract(velocity), target));
            LocalPortalTransitRegistry.latchReentry(playerId, portal.getId());
            if (crossing) {
                assertNotNull(traversal.rayTeleport(player, null));
            }
            traversal.updateCaptures(mock(ITunnel.class), true);
            if (crossing) {
                verify(warmer, never()).warmImminent(portal, player);
            } else {
                verify(warmer).warmImminent(portal, player);
            }
        } finally {
            LocalPortalTransitRegistry.clearReentryLatch(playerId);
            Wormholes.traversableManager = previousManager;
            Wormholes.arrivalWarmer = previousWarmer;
        }
    }
}

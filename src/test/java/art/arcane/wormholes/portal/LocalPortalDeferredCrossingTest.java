package art.arcane.wormholes.portal;

import art.arcane.wormholes.TraversableManager;
import art.arcane.wormholes.TraversableManager.Movement;
import art.arcane.optics.math.Vec3;
import art.arcane.wormholes.util.BukkitGeometry;
import art.arcane.optics.math.Face;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import art.arcane.optics.frame.Frame;

class LocalPortalDeferredCrossingTest {
    @Test
    void acceptedSameWorldDoorTeleportInvalidatesSavedPortalCrossing() throws Exception {
        Fixture fixture = fixture();
        Object deferred = deferred(fixture);
        assertTrue(continuous(deferred, fixture));
        fixture.manager().on(new PlayerTeleportEvent(fixture.player(), fixture.start(),
            new Location(fixture.world(), 20.5D, 65, 1.5D)));
        assertFalse(continuous(deferred, fixture));
    }

    @Test
    void ordinaryMovementOutsideCaptureZoneRetainsTheExactEntrance() throws Exception {
        Fixture fixture = fixture();
        Object deferred = deferred(fixture);
        fixture.manager().on(new PlayerMoveEvent(fixture.player(), fixture.start(),
            new Location(fixture.world(), -12, 65, 1.5D)));
        assertTrue(continuous(deferred, fixture));
        when(fixture.destination().getId()).thenReturn(UUID.randomUUID());
        assertFalse(continuous(deferred, fixture));
    }

    @Test
    void changingSourcePlaneOrClosingEntranceInvalidatesSavedCrossing() throws Exception {
        Fixture fixture = fixture();
        Object deferred = deferred(fixture);
        when(fixture.portal().getOrigin()).thenReturn(new Vec3(1.5D, 65, 1.5D));
        assertFalse(continuous(deferred, fixture));
        when(fixture.portal().getOrigin()).thenReturn(new Vec3(0.5D, 65, 1.5D));
        when(fixture.portal().isOpen()).thenReturn(false);
        assertFalse(continuous(deferred, fixture));
    }

    private Fixture fixture() {
        World world = LocalPortalTestSupport.world("deferred-entrance");
        Location start = new Location(world, 0.1D, 65, 1.5D);
        Player player = (Player) LocalPortalTestSupport.FakeEntity.player("deferred", start).entity();
        TraversableManager manager = new TraversableManager();
        manager.movement(player, start);
        LocalPortal portal = mock(LocalPortal.class);
        Frame frame = Frame.canonical(Face.E);
        Vec3 origin = new Vec3(0.5D, 65, 1.5D);
        when(portal.isOpen()).thenReturn(true);
        when(portal.getOrigin()).thenReturn(origin);
        when(portal.getFrame()).thenReturn(frame);
        IPortal destination = mock(IPortal.class);
        when(destination.getId()).thenReturn(UUID.randomUUID());
        ITunnel tunnel = mock(ITunnel.class);
        when(tunnel.isValid()).thenReturn(true);
        when(tunnel.getDestination()).thenReturn(destination);
        Traversive crossing = new Traversive(player, frame.view(true), BukkitGeometry.bukkit(origin),
            start.toVector(), new Vector(-0.4D, 0, 0), new Vector(-1, 0, 0), true);
        return new Fixture(world, start, player, manager, portal, destination, tunnel, crossing);
    }

    private Object deferred(Fixture fixture) throws Exception {
        Class<?> type = Class.forName(LocalPortalTraversal.class.getName() + "$DeferredCrossing");
        Constructor<?> constructor = type.getDeclaredConstructor(Traversive.class, ITunnel.class, Movement.class, UUID.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(fixture.crossing(), fixture.tunnel(), fixture.manager().movement(fixture.player().getUniqueId()),
            fixture.destination().getId(), System.currentTimeMillis() + 2_500L);
    }

    private boolean continuous(Object deferred, Fixture fixture) throws Exception {
        Method method = deferred.getClass().getDeclaredMethod("continuous", Movement.class, LocalPortal.class, ITunnel.class, long.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(deferred, fixture.manager().movement(fixture.player().getUniqueId()), fixture.portal(),
            fixture.tunnel(), System.currentTimeMillis());
    }

    private record Fixture(World world, Location start, Player player, TraversableManager manager, LocalPortal portal,
                           IPortal destination, ITunnel tunnel, Traversive crossing) {
    }
}

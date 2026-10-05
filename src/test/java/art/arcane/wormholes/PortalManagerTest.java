package art.arcane.wormholes;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class PortalManagerTest {
    @Test
    void bulkDeletionRetiresDomainStateBeforeRemovingTheRegistryAndStorage() throws ReflectiveOperationException {
        LocalPortal portal = portal();
        try (DeletionFixture fixture = new DeletionFixture(List.of(portal))) {
            assertEquals(1, fixture.manager.deleteAllPortals());
            InOrder cleanup = inOrder(portal, fixture.plugin, fixture.storage);
            cleanup.verify(portal).retireForBulkDeletion();
            cleanup.verify(fixture.plugin).unregisterListener(portal);
            cleanup.verify(fixture.storage).deletePortalFolder(fixture.snapshot);
            assertEquals(List.of(), fixture.manager.getLocalPortals());
        }
    }

    @Test
    void failedPortalRetirementDoesNotStrandLaterPortalsOrStorageCleanup() throws ReflectiveOperationException {
        LocalPortal failed = portal();
        LocalPortal healthy = portal();
        IllegalStateException failure = new IllegalStateException("portal retirement failed");
        doThrow(failure).when(failed).retireForBulkDeletion();
        try (DeletionFixture fixture = new DeletionFixture(List.of(failed, healthy))) {
            assertEquals(2, fixture.manager.deleteAllPortals());
            verify(healthy).retireForBulkDeletion();
            verify(fixture.plugin).unregisterListener(failed);
            verify(fixture.plugin).unregisterListener(healthy);
            verify(fixture.storage).deletePortalFolder(fixture.snapshot);
            verify(fixture.logger).log(Level.WARNING, "Could not retire portal " + failed.getId(), failure);
            assertEquals(List.of(), fixture.manager.getLocalPortals());
        }
    }

	@Test
	public void sameWorldTeleportImmediatelyMovesDoorAttendanceWithoutAnotherMovement() throws ReflectiveOperationException
	{
		World world = mock(World.class);
		Player player = mock(Player.class);
		UUID worldId = UUID.randomUUID();
		when(world.getUID()).thenReturn(worldId);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());
		Location from = new Location(world, 0, 64, 0);
		Location to = new Location(world, 1000, 80, 1000);
		PortalRegistryAttendance attendance = new PortalRegistryAttendance();
		attendance.record(player, from);
		PortalManager manager = mock(PortalManager.class, CALLS_REAL_METHODS);
		set(manager, "attendance", attendance);
		assertTrue(manager.hasPlayerWithin(worldId, 0, 64, 0, 16));
		assertFalse(manager.hasPlayerWithin(worldId, 1000, 80, 1000, 16));
		manager.on(new PlayerTeleportEvent(player, from, to, PlayerTeleportEvent.TeleportCause.PLUGIN));
		assertFalse(manager.hasPlayerWithin(worldId, 0, 64, 0, 16));
		assertTrue(manager.hasPlayerWithin(worldId, 1000, 80, 1000, 16));
		EventHandler handler = PortalManager.class.getMethod("on", PlayerTeleportEvent.class).getAnnotation(EventHandler.class);
		assertEquals(EventPriority.MONITOR, handler.priority());
		assertTrue(handler.ignoreCancelled());
	}

	@Test
	public void lookOnlyMovementDoesNotTriggerPositionWork()
	{
		Location from = new Location(null, 1.25D, 64.0D, -3.5D, 10.0F, 20.0F);
		Location to = new Location(null, 1.25D, 64.0D, -3.5D, 80.0F, -15.0F);

		assertFalse(PortalManager.positionChanged(from, to));
	}

	@Test
	public void coordinateMovementTriggersPositionWork()
	{
		Location from = new Location(null, 1.25D, 64.0D, -3.5D);
		Location to = new Location(null, 1.2501D, 64.0D, -3.5D);

		assertTrue(PortalManager.positionChanged(from, to));
	}

    private static LocalPortal portal() {
        LocalPortal portal = mock(LocalPortal.class);
        when(portal.getId()).thenReturn(UUID.randomUUID());
        return portal;
    }

    private static void set(PortalManager target, String name, Object value) throws ReflectiveOperationException {
        Field field = PortalManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class DeletionFixture implements AutoCloseable {
        private final PortalManager manager = mock(PortalManager.class, CALLS_REAL_METHODS);
        private final PortalRegistryStorage storage = mock(PortalRegistryStorage.class);
        private final Wormholes plugin = mock(Wormholes.class);
        private final Logger logger = mock(Logger.class);
        private final Wormholes previous = Wormholes.instance;
        private final List<ILocalPortal> snapshot;

        private DeletionFixture(List<ILocalPortal> snapshot) throws ReflectiveOperationException {
            this.snapshot = snapshot;
            ConcurrentHashMap<UUID, ILocalPortal> portals = new ConcurrentHashMap<UUID, ILocalPortal>();
            for (ILocalPortal portal : snapshot) {
                portals.put(portal.getId(), portal);
            }
            set(manager, "portals", portals);
            set(manager, "portalSnapshot", snapshot);
            set(manager, "pendingPortalFiles", new PortalRegistryPendingFiles());
            set(manager, "storage", storage);
            when(plugin.getLogger()).thenReturn(logger);
            Wormholes.instance = plugin;
        }

        @Override
        public void close() {
            Wormholes.instance = previous;
        }
    }
}

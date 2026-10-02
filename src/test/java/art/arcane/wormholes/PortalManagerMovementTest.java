package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.CALLS_REAL_METHODS;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerTeleportEvent;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public final class PortalManagerMovementTest
{
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
		Field field = PortalManager.class.getDeclaredField("attendance");
		field.setAccessible(true);
		field.set(manager, attendance);
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
}

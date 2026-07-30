package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.Cuboid;
import art.arcane.volmlib.util.json.JSONObject;

public final class LocalPortalMirrorDestinationTest
{
	@Test
	public void enablingMirrorModeShedsTheLinkedDestination()
	{
		World world = LocalPortalTestSupport.world("overworld");
		LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortal destination = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		source.setDestination(destination);
		assertTrue(source.hasTunnel());

		source.setMirrorMode(true);

		assertTrue(source.isMirrorMode());
		assertNull(source.getTunnel());
		assertFalse(source.hasTunnel());
	}

	@Test
	public void enablingMirrorModeShedsTheTunnelButKeepsTheDimensionalPairIdentity()
	{
		World world = LocalPortalTestSupport.world("overworld");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortal destination = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		UUID counterpartId = UUID.randomUUID();
		portal.setDestination(destination);
		portal.setDimensionalCounterpartId(counterpartId);

		portal.setMirrorMode(true);

		assertTrue(portal.isMirrorMode());
		assertNull(portal.getTunnel());
		assertEquals(counterpartId, portal.getDimensionalCounterpartId());
	}

	@Test
	public void mirrorModeRefusesLocalDestinationAssignment()
	{
		World world = LocalPortalTestSupport.world("overworld");
		LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortal destination = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		source.setMirrorMode(true);

		assertFalse(source.setDestination(destination));

		assertNull(source.getTunnel());
		assertFalse(source.hasTunnel());
	}

	@Test
	public void mirrorModeRefusesRemoteLinkWhileNormalGatewaysStillAcceptIt()
	{
		World world = LocalPortalTestSupport.world("overworld");
		LocalPortal mirror = LocalPortalTestSupport.portal(world, PortalType.GATEWAY);
		mirror.setMirrorMode(true);
		LocalPortal gateway = LocalPortalTestSupport.portal(world, PortalType.GATEWAY);

		assertFalse(mirror.linkRemote("beta", UUID.randomUUID()));
		assertTrue(gateway.linkRemote("beta", UUID.randomUUID()));

		assertNull(mirror.getTunnel());
		assertNotNull(gateway.getTunnel());
	}

	@Test
	public void everyLinkRefusalReasonIsReportedToTheCaller()
	{
		World world = LocalPortalTestSupport.world("overworld");
		LocalPortal destination = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortal mirror = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		mirror.setMirrorMode(true);
		LocalPortal rtp = LocalPortalTestSupport.portal(world, PortalType.RTP);
		LocalPortal managed = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		managed.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);
		LocalPortal linkable = LocalPortalTestSupport.portal(world, PortalType.PORTAL);

		assertFalse(mirror.setDestination(destination));
		assertFalse(rtp.setDestination(destination));
		assertFalse(managed.setDestination(destination));
		assertTrue(linkable.setDestination(destination));

		assertFalse(mirror.linkRemote("beta", UUID.randomUUID()));
		assertFalse(rtp.linkRemote("beta", UUID.randomUUID()));
		assertFalse(managed.linkRemote("beta", UUID.randomUUID()));
		assertTrue(linkable.linkRemote("beta", UUID.randomUUID()));
	}

	@Test
	public void disablingMirrorModeDoesNotRestoreTheShedDestination()
	{
		World world = LocalPortalTestSupport.world("overworld");
		LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortal destination = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		source.setDestination(destination);
		source.setMirrorMode(true);

		source.setMirrorMode(false);

		assertFalse(source.isMirrorMode());
		assertNull(source.getTunnel());
		assertFalse(source.hasTunnel());
	}

	@Test
	public void aMirrorRemainsSelectableAsAnotherPortalsDestination()
	{
		World world = LocalPortalTestSupport.world("overworld");
		LocalPortal mirror = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		mirror.setMirrorMode(true);
		LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);

		source.setDestination(mirror);

		assertNotNull(source.getTunnel());
		assertSame(mirror, source.getTunnel().getDestination());
	}

	@Test
	public void aMirrorWithAPriorDestinationClosesWhenProjectionIsDisabled()
	{
		World world = LocalPortalTestSupport.world("overworld");
		LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortal destination = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		source.setDestination(destination);
		source.update();
		assertTrue(source.isOpen());

		source.setMirrorMode(true);
		source.setProjectionMode(ProjectionMode.OFF);
		source.update();

		assertFalse(source.isOpen());
	}

	@Test
	public void mirrorModeSuppressesTheDestinationPicker()
	{
		World world = LocalPortalTestSupport.world("overworld");
		LocalPortal mirror = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		mirror.setMirrorMode(true);
		LocalPortal linkable = LocalPortalTestSupport.portal(world, PortalType.PORTAL);

		assertDoesNotThrow(() -> mirror.uiChooseDestination(null));
		assertThrows(LinkageError.class, () -> linkable.uiChooseDestination(null));
	}

	@Test
	public void shedDestinationIsPersistedForAMirror() throws Exception
	{
		World world = LocalPortalTestSupport.world("overworld");
		LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortal destination = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		source.setDestination(destination);
		source.setMirrorMode(true);

		JSONObject stored = source.toJSON();
		assertFalse(stored.has("tunnel"));
		assertTrue(stored.getBoolean("mirrorMode"));

		LocalPortal reloaded = reload(stored, world);
		assertTrue(reloaded.isMirrorMode());
		assertNull(reloaded.getTunnel());
		assertFalse(reloaded.toJSON().has("tunnel"));
	}

	@Test
	public void loadingAMirrorThatStillCarriesADestinationClearsIt() throws Exception
	{
		World world = LocalPortalTestSupport.world("overworld");
		LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortal destination = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		source.setDestination(destination);
		UUID counterpartId = UUID.randomUUID();
		JSONObject stored = source.toJSON();
		stored.put("mirrorMode", true);
		stored.put("dimensionalCounterpartId", counterpartId.toString());
		assertTrue(stored.has("tunnel"));

		LocalPortal reloaded = reload(stored, world);

		assertTrue(reloaded.isMirrorMode());
		assertNull(reloaded.getTunnel());
		assertEquals(counterpartId, reloaded.getDimensionalCounterpartId());
		assertFalse(reloaded.toJSON().has("tunnel"));
		assertTrue(reloaded.toJSON().has("dimensionalCounterpartId"));
	}

	@Test
	public void loadingANonMirrorPortalKeepsItsDestination() throws Exception
	{
		World world = LocalPortalTestSupport.world("overworld");
		LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortal destination = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		source.setDestination(destination);
		JSONObject stored = source.toJSON();

		LocalPortal reloaded = reload(stored, world);

		assertFalse(reloaded.isMirrorMode());
		assertNotNull(reloaded.getTunnel());
		assertEquals(destination.getId(), reloaded.getTunnel().getDestinationId());
	}

	private static LocalPortal reload(JSONObject stored, World world) throws Exception
	{
		PortalStructure structure = new PortalStructure();
		structure.setWorld(world);
		structure.setArea(new Cuboid(new Location(world, 0.0D, 64.0D, 0.0D), new Location(world, 0.0D, 66.0D, 2.0D)));
		LocalPortal portal = new LocalPortal(
				UUID.fromString(stored.getString("id")),
				PortalType.valueOf(stored.getString("type")),
				structure);
		portal.setAmbientAttended(false);
		return withBukkitWorld(world, () ->
		{
			portal.loadJSON(stored);
			return portal;
		});
	}

	private static <T> T withBukkitWorld(World world, Supplier<T> action) throws Exception
	{
		synchronized(Bukkit.class)
		{
			Field serverField = Bukkit.class.getDeclaredField("server");
			serverField.setAccessible(true);
			Object previous = serverField.get(null);
			serverField.set(null, server(world));
			try
			{
				return action.get();
			}
			finally
			{
				serverField.set(null, previous);
			}
		}
	}

	private static Server server(World world)
	{
		return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] { Server.class },
				(proxy, method, arguments) -> switch(method.getName())
		{
			case "getWorlds" -> List.of(world);
			case "createBlockData" -> blockData((String) arguments[0]);
			case "getName" -> "LocalPortalMirrorDestinationTestServer";
			case "toString" -> "LocalPortalMirrorDestinationTestServer";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	private static BlockData blockData(String state)
	{
		return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] { BlockData.class },
				(proxy, method, arguments) -> switch(method.getName())
		{
			case "getAsString" -> state;
			case "toString" -> state;
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}
}

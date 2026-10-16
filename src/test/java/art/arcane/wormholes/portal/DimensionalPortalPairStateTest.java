package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.portal.vanilla.PortalFactory;
import art.arcane.wormholes.portal.vanilla.VanillaPortalEndTravel;

public final class DimensionalPortalPairStateTest
{
	@AfterEach
	void restoreEndTravel()
	{
		Settings.TWO_WAY_END_PORTALS = true;
	}

	@Test
	public void mirrorAndProjectionStatePersistIndependently()
	{
		LocalPortal portal = portal();
		portal.setMirrorMode(true);
		portal.setProjectionMode(ProjectionMode.OFF);

		JSONObject stored = portal.toJSON();

		assertTrue(stored.getBoolean("mirrorMode"));
		assertEquals(ProjectionMode.OFF.name(), stored.getString("projectionMode"));
	}

	@Test
	public void counterpartIdentityPersistsOutsideTheTunnel()
	{
		LocalPortal portal = portal();
		UUID counterpartId = UUID.randomUUID();
		portal.setDimensionalCounterpartId(counterpartId);
		portal.setDimensionalPortalKind(DimensionalPortalKind.NETHER);

		JSONObject stored = portal.toJSON();

		assertEquals(counterpartId.toString(), stored.getString("dimensionalCounterpartId"));
		assertEquals(counterpartId, LocalPortalPersistence.resolveOptionalUuid(stored.getString("dimensionalCounterpartId")));
		assertEquals(DimensionalPortalKind.NETHER.name(), stored.getString("dimensionalPortalKind"));
	}

	@Test
	public void malformedCounterpartIdentityIsIgnored()
	{
		assertNull(LocalPortalPersistence.resolveOptionalUuid(""));
		assertNull(LocalPortalPersistence.resolveOptionalUuid("not-a-uuid"));
	}

	@Test
	public void unlinkClearsPersistedCounterpartWithoutATunnel()
	{
		LocalPortal portal = portal();
		portal.setDimensionalCounterpartId(UUID.randomUUID());
		portal.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);

		portal.unlink();

		assertNull(portal.getDimensionalCounterpartId());
		assertNull(portal.getTunnel());
		assertEquals(DimensionalPortalKind.END_ARRIVAL, portal.getDimensionalPortalKind());
	}

	@Test
	public void dimensionalKindParsingIsBackwardCompatible()
	{
		assertEquals(DimensionalPortalKind.NETHER, DimensionalPortalKind.fromName("nether"));
		assertEquals(DimensionalPortalKind.END_SOURCE, DimensionalPortalKind.fromName(" END_SOURCE "));
		assertEquals(DimensionalPortalKind.NONE, DimensionalPortalKind.fromName("unknown"));
		assertEquals(DimensionalPortalKind.NONE, DimensionalPortalKind.fromName(null));
	}

	@Test
	public void oneWayEndArrivalNormalizesTamperedStateForPersistence()
	{
		Settings.TWO_WAY_END_PORTALS = false;
		World world = world();
		LocalPortal arrival = portal(PortalType.GATEWAY, world);
		LocalPortal unrelated = portal(PortalType.PORTAL, world);
		UUID counterpartId = UUID.randomUUID();
		arrival.setDestination(unrelated);
		arrival.setDimensionalCounterpartId(counterpartId);
		arrival.setMirrorMode(true);
		arrival.setOutgoingTraversalsEnabled(true);
		arrival.setIncomingTraversalsEnabled(false);

		arrival.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);

		assertEquals(PortalType.PORTAL, arrival.getType());
		assertEquals(ProjectionMode.OFF, arrival.getProjectionMode());
		assertFalse(arrival.isMirrorMode());
		assertFalse(arrival.isOutgoingTraversalsEnabled());
		assertTrue(arrival.isIncomingTraversalsEnabled());
		assertNull(arrival.getTunnel());
		assertEquals(counterpartId, arrival.getDimensionalCounterpartId());
		JSONObject stored = arrival.toJSON();
		assertFalse(stored.has("tunnel"));
		assertEquals(ProjectionMode.OFF.name(), stored.getString("projectionMode"));
		assertFalse(stored.getBoolean("mirrorMode"));
		assertFalse(stored.getBoolean("outgoingTraversalsEnabled"));
		assertTrue(stored.getBoolean("incomingTraversalsEnabled"));
	}

	@Test
	public void dragonExitProjectsWithoutReplacingVanillaTravel()
	{
		LocalPortal exit = portal();
		exit.setDimensionalPortalKind(DimensionalPortalKind.END_EXIT);
		exit.setProjectionMode(ProjectionMode.OFF);
		exit.setOutgoingTraversalsEnabled(true);
		exit.setIncomingTraversalsEnabled(true);
		assertEquals(ProjectionMode.ON, exit.getProjectionMode());
		assertFalse(exit.isOutgoingTraversalsEnabled());
		assertFalse(exit.isIncomingTraversalsEnabled());
		assertNull(exit.getTunnel());
		assertTrue(exit.getDimensionalPortalKind().isManagedEndPortal());
		assertFalse(exit.getDimensionalPortalKind().isGenericDestination());
	}

	@Test
	public void endSourceAlwaysKeepsItsProjectionVisible()
	{
		LocalPortal source = portal();
		source.setProjectionMode(ProjectionMode.OFF);

		source.setDimensionalPortalKind(DimensionalPortalKind.END_SOURCE);

		assertEquals(ProjectionMode.ON, source.getProjectionMode());
		source.setProjectionMode(ProjectionMode.OFF);
		assertEquals(ProjectionMode.ON, source.getProjectionMode());
	}

	@Test
	public void oneWayEndArrivalRejectsOutboundAndRelinkingMutations()
	{
		Settings.TWO_WAY_END_PORTALS = false;
		World world = world();
		LocalPortal arrival = portal(PortalType.PORTAL, world);
		LocalPortal unrelated = portal(PortalType.PORTAL, world);
		UUID counterpartId = UUID.randomUUID();
		arrival.setDimensionalCounterpartId(counterpartId);
		arrival.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);

		arrival.setDestination(unrelated);
		arrival.linkRemote("other-server", UUID.randomUUID());
		arrival.setType(PortalType.GATEWAY);
		arrival.setMirrorMode(true);
		arrival.setOutgoingTraversalsEnabled(true);
		arrival.setIncomingTraversalsEnabled(false);

		assertNull(arrival.getTunnel());
		assertEquals(counterpartId, arrival.getDimensionalCounterpartId());
		assertEquals(PortalType.PORTAL, arrival.getType());
		assertEquals(ProjectionMode.OFF, arrival.getProjectionMode());
		assertFalse(arrival.isMirrorMode());
		assertFalse(arrival.isOutgoingTraversalsEnabled());
		assertTrue(arrival.isIncomingTraversalsEnabled());
	}

	@Test
	public void oneWayEndArrivalRecoversCounterpartIdentityBeforeRemovingReturnTunnel()
	{
		Settings.TWO_WAY_END_PORTALS = false;
		LocalPortal arrival = portal(PortalType.PORTAL, world());
		LocalPortal source = portal(PortalType.PORTAL, world());
		arrival.setDestination(source);

		arrival.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);

		assertNull(arrival.getTunnel());
		assertEquals(source.getId(), arrival.getDimensionalCounterpartId());
	}

	@Test
	public void oneWayFactoryKeepsManagedEndPairFunctional()
	{
		Settings.TWO_WAY_END_PORTALS = false;
		World world = world();
		LocalPortal source = portal(PortalType.PORTAL, world);
		LocalPortal arrival = portal(PortalType.PORTAL, world);
		source.setDimensionalPortalKind(DimensionalPortalKind.END_SOURCE);
		arrival.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);

		assertTrue(PortalFactory.linkOneWay(source, arrival));

		assertSame(arrival, source.getTunnel().getDestination());
		assertNull(arrival.getTunnel());
		assertEquals(arrival.getId(), source.getDimensionalCounterpartId());
		assertEquals(source.getId(), arrival.getDimensionalCounterpartId());
		assertTrue(source.isOutgoingTraversalsEnabled());
		assertFalse(source.isIncomingTraversalsEnabled());
		assertFalse(arrival.isOutgoingTraversalsEnabled());
		assertTrue(arrival.isIncomingTraversalsEnabled());

		source.setType(PortalType.GATEWAY);
		source.setMirrorMode(true);
		source.setOutgoingTraversalsEnabled(false);
		source.setIncomingTraversalsEnabled(true);
		assertEquals(PortalType.PORTAL, source.getType());
		assertEquals(ProjectionMode.ON, source.getProjectionMode());
		assertFalse(source.isMirrorMode());
		assertTrue(source.isOutgoingTraversalsEnabled());
		assertFalse(source.isIncomingTraversalsEnabled());
	}

	@Test
	public void twoWayEndPairLinksTheArrivalBackToItsSourceAndShowsBothViews()
	{
		LocalPortal source = portal(PortalType.PORTAL, world());
		LocalPortal arrival = portal(PortalType.PORTAL, world());
		source.setDimensionalPortalKind(DimensionalPortalKind.END_SOURCE);
		arrival.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);

		assertTrue(PortalFactory.linkEnd(source, arrival));

		assertSame(arrival, source.getTunnel().getDestination());
		assertSame(source, arrival.getTunnel().getDestination());
		assertEquals(arrival.getId(), source.getDimensionalCounterpartId());
		assertEquals(source.getId(), arrival.getDimensionalCounterpartId());
		assertTrue(source.isOutgoingTraversalsEnabled());
		assertTrue(source.isIncomingTraversalsEnabled());
		assertTrue(arrival.isOutgoingTraversalsEnabled());
		assertTrue(arrival.isIncomingTraversalsEnabled());
		assertEquals(ProjectionMode.ON, source.getProjectionMode());
		assertEquals(ProjectionMode.ON, arrival.getProjectionMode());
		arrival.setProjectionMode(ProjectionMode.OFF);
		arrival.setOutgoingTraversalsEnabled(false);
		assertEquals(ProjectionMode.ON, arrival.getProjectionMode());
		assertTrue(arrival.isOutgoingTraversalsEnabled());
	}

	@Test
	public void changingTheEndTravelSettingRenormalizesAnExistingPairBothWays()
	{
		LocalPortal source = portal(PortalType.PORTAL, world());
		LocalPortal arrival = portal(PortalType.PORTAL, world());
		source.setDimensionalPortalKind(DimensionalPortalKind.END_SOURCE);
		arrival.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);
		assertTrue(PortalFactory.linkEnd(source, arrival));

		Settings.TWO_WAY_END_PORTALS = false;
		source.setDimensionalPortalKind(DimensionalPortalKind.END_SOURCE);
		arrival.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);

		assertNull(arrival.getTunnel());
		assertEquals(source.getId(), arrival.getDimensionalCounterpartId());
		assertEquals(ProjectionMode.OFF, arrival.getProjectionMode());
		assertFalse(arrival.isOutgoingTraversalsEnabled());
		assertFalse(source.isIncomingTraversalsEnabled());
		assertSame(arrival, source.getTunnel().getDestination());

		Settings.TWO_WAY_END_PORTALS = true;
		source.setDimensionalPortalKind(DimensionalPortalKind.END_SOURCE);
		arrival.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);

		assertEquals(source.getId(), arrival.getTunnel().getDestinationId());
		assertEquals(TunnelType.DIMENSIONAL, arrival.getTunnel().getTunnelType());
		assertEquals(ProjectionMode.ON, arrival.getProjectionMode());
		assertTrue(arrival.isOutgoingTraversalsEnabled());
		assertTrue(source.isIncomingTraversalsEnabled());
		JSONObject stored = arrival.toJSON();
		assertEquals(source.getId().toString(), stored.getJSONObject("tunnel").getString("destination"));
	}

	@Test
	public void reloadingTheEndTravelSettingConvertsExistingPairs()
	{
		LocalPortal source = portal(PortalType.PORTAL, world());
		LocalPortal arrival = portal(PortalType.PORTAL, world());
		source.setDimensionalPortalKind(DimensionalPortalKind.END_SOURCE);
		arrival.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);
		assertTrue(PortalFactory.linkEnd(source, arrival));
		assertSame(source, arrival.getTunnel().getDestination());

		refreshEndTravel(false);
		VanillaPortalEndTravel.apply(List.<ILocalPortal>of(source, arrival));

		assertFalse(Settings.TWO_WAY_END_PORTALS);
		assertNull(arrival.getTunnel());
		assertEquals(ProjectionMode.OFF, arrival.getProjectionMode());
		assertFalse(arrival.isOutgoingTraversalsEnabled());
		assertFalse(source.isIncomingTraversalsEnabled());

		refreshEndTravel(true);
		VanillaPortalEndTravel.apply(List.<ILocalPortal>of(source, arrival));

		assertEquals(source.getId(), arrival.getTunnel().getDestinationId());
		assertEquals(ProjectionMode.ON, arrival.getProjectionMode());
		assertTrue(arrival.isOutgoingTraversalsEnabled());
		assertTrue(source.isIncomingTraversalsEnabled());
	}

	@Test
	public void oneWayEndPairStillLinksOnlyTowardTheArrival()
	{
		Settings.TWO_WAY_END_PORTALS = false;
		LocalPortal source = portal(PortalType.PORTAL, world());
		LocalPortal arrival = portal(PortalType.PORTAL, world());
		source.setDimensionalPortalKind(DimensionalPortalKind.END_SOURCE);
		arrival.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);

		assertTrue(PortalFactory.linkEnd(source, arrival));

		assertSame(arrival, source.getTunnel().getDestination());
		assertNull(arrival.getTunnel());
		assertFalse(source.isIncomingTraversalsEnabled());
		assertFalse(arrival.isOutgoingTraversalsEnabled());
		assertEquals(ProjectionMode.OFF, arrival.getProjectionMode());
	}

	@Test
	public void managedDimensionalPortalsAreNotGenericDestinations()
	{
		assertFalse(DimensionalPortalKind.END_SOURCE.isGenericDestination());
		assertFalse(DimensionalPortalKind.END_ARRIVAL.isGenericDestination());
		assertFalse(DimensionalPortalKind.NETHER.isGenericDestination());
		assertTrue(DimensionalPortalKind.NONE.isGenericDestination());
	}

	@Test
	public void managedNetherPortalKeepsBidirectionalVanillaState()
	{
		LocalPortal portal = portal();
		portal.setDimensionalPortalKind(DimensionalPortalKind.NETHER);

		portal.setType(PortalType.GATEWAY);
		portal.setMirrorMode(true);
		portal.setOutgoingTraversalsEnabled(false);
		portal.setIncomingTraversalsEnabled(false);

		assertEquals(PortalType.PORTAL, portal.getType());
		assertEquals(ProjectionMode.ON, portal.getProjectionMode());
		assertFalse(portal.isMirrorMode());
		assertTrue(portal.isOutgoingTraversalsEnabled());
		assertTrue(portal.isIncomingTraversalsEnabled());
	}

	@Test
	public void aManagedNetherPortalOpensOnlyOnceItsCounterpartIsLinked()
	{
		World world = world();
		LocalPortal source = portal(PortalType.PORTAL, world);
		LocalPortal counterpart = portal(PortalType.PORTAL, world);
		source.setAmbientAttended(false);
		source.setDimensionalPortalKind(DimensionalPortalKind.NETHER);
		counterpart.setDimensionalPortalKind(DimensionalPortalKind.NETHER);

		assertFalse(source.isOpen());
		source.update();
		assertFalse(source.isOpen(), "a portal whose counterpart is still being built has nothing to show yet");

		source.setDestination(counterpart);
		source.update();

		assertTrue(source.isOpen());
	}

	private static void refreshEndTravel(boolean twoWay)
	{
		MainConfig main = new MainConfig();
		main.twoWayEndPortals = twoWay;
		Settings.refresh(new WormholesSettings(main, new ProjectionConfig(), new RenderConfig(), new NetworkConfig()));
	}

	private static LocalPortal portal()
	{
		return portal(PortalType.PORTAL, world());
	}

	private static LocalPortal portal(PortalType type, World world)
	{
		PortalStructure structure = new PortalStructure();
		structure.setArea(cuboid());
		structure.setWorld(world);
		return new LocalPortal(UUID.randomUUID(), type, structure);
	}

	private static World world()
	{
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getName" -> "world";
			case "getKey" -> NamespacedKey.minecraft("overworld");
			case "toString" -> "DimensionalPortalPairStateWorld";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	private static Cuboid cuboid()
	{
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("worldKey", "minecraft:overworld");
		map.put("x1", Integer.valueOf(0));
		map.put("y1", Integer.valueOf(64));
		map.put("z1", Integer.valueOf(0));
		map.put("x2", Integer.valueOf(0));
		map.put("y2", Integer.valueOf(66));
		map.put("z2", Integer.valueOf(2));
		return new Cuboid(map);
	}
}

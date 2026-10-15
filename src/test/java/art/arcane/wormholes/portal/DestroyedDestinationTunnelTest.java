package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;

final class DestroyedDestinationTunnelTest
{
	private ProjectionManager previousProjections;
	private PortalManager previousPortals;

	@BeforeEach
	void setUp()
	{
		previousProjections = Wormholes.projectionManager;
		previousPortals = Wormholes.portalManager;
		Wormholes.projectionManager = null;
		Wormholes.portalManager = null;
	}

	@AfterEach
	void tearDown()
	{
		Wormholes.projectionManager = previousProjections;
		Wormholes.portalManager = previousPortals;
	}

	@Test
	void sameWorldLinkStopsLeadingToADeletedDestination()
	{
		World world = LocalPortalTestSupport.world("deleted-local-destination");
		LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortal destination = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		assertTrue(source.setDestination(destination));
		assertInstanceOf(LocalTunnel.class, source.getTunnel());
		assertSame(destination, source.getTunnel().getDestination());

		destination.destroy();

		assertDeadLink(source, destination);
	}

	@Test
	void crossWorldLinkStopsLeadingToADeletedDestination()
	{
		LocalPortal source = LocalPortalTestSupport.portal(LocalPortalTestSupport.world("deleted-end-source"), PortalType.PORTAL);
		LocalPortal destination = LocalPortalTestSupport.portal(LocalPortalTestSupport.world("deleted-overworld-destination"), PortalType.PORTAL);
		assertTrue(source.setDestination(destination));
		assertInstanceOf(DimensionalTunnel.class, source.getTunnel());
		assertSame(destination, source.getTunnel().getDestination());

		destination.destroy();

		assertDeadLink(source, destination);
	}

	private static void assertDeadLink(LocalPortal source, LocalPortal destination)
	{
		assertTrue(destination.isDestroyed());
		assertNull(source.getTunnel().getDestination());
		assertFalse(source.getTunnel().isValid());
		assertFalse(source.hasTunnel());
		assertEquals(destination.getId(), source.getTunnel().getDestinationId());
	}
}

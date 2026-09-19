package art.arcane.wormholes.portal.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.util.BlockVector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.util.Cuboid;

public final class NetherPortalShapesTest
{
	@Test
	public void ownershipRequiresOneLiveManagedPortalWithExactlyTheSubmittedCells()
	{
		PortalManager previous = Wormholes.portalManager;
		PortalManager manager = mock(PortalManager.class);
		World world = world();
		LocalPortal portal = portal(world, DimensionalPortalKind.NONE);
		Set<BlockVector> cells = Set.of(new BlockVector(0, 64, 0), new BlockVector(0, 64, 1), new BlockVector(0, 64, 2),
				new BlockVector(0, 65, 0), new BlockVector(0, 65, 1), new BlockVector(0, 65, 2),
				new BlockVector(0, 66, 0), new BlockVector(0, 66, 1), new BlockVector(0, 66, 2));
		when(manager.getLocalPortals()).thenReturn(List.of(portal));
		Wormholes.portalManager = manager;
		try
		{
			VanillaPortalIndex index = new VanillaPortalIndex();
			assertFalse(index.ownsNetherShape(world, cells));
			portal.setDimensionalPortalKind(DimensionalPortalKind.SHAPED_NETHER);
			assertTrue(index.ownsNetherShape(world, cells));
			assertFalse(index.ownsNetherShape(world, Set.of(new BlockVector(0, 64, 0))));
		}
		finally
		{
			Wormholes.portalManager = previous;
		}
	}

	@Test
	public void shapedSourceAndVanillaCounterpartKeepBothTravelDirections()
	{
		World world = world();
		LocalPortal source = portal(world, DimensionalPortalKind.SHAPED_NETHER);
		LocalPortal counterpart = portal(world, DimensionalPortalKind.NETHER);

		assertTrue(PortalFactory.linkBidirectional(source, counterpart));
		assertEquals(counterpart.getId(), source.getDimensionalCounterpartId());
		assertEquals(source.getId(), counterpart.getDimensionalCounterpartId());

		source.setIncomingTraversalsEnabled(false);
		source.setOutgoingTraversalsEnabled(false);
		counterpart.setIncomingTraversalsEnabled(false);
		counterpart.setOutgoingTraversalsEnabled(false);

		assertTrue(source.isIncomingTraversalsEnabled());
		assertTrue(source.isOutgoingTraversalsEnabled());
		assertTrue(counterpart.isIncomingTraversalsEnabled());
		assertTrue(counterpart.isOutgoingTraversalsEnabled());
		assertEquals("SHAPED_NETHER", source.toJSON().getString("dimensionalPortalKind"));
	}

	@Test
	public void shapedSourceCannotBecomeMirrorOrGenericDestination()
	{
		LocalPortal source = portal(world(), DimensionalPortalKind.SHAPED_NETHER);

		source.setMirrorMode(true);
		source.setType(PortalType.GATEWAY);

		assertFalse(source.isMirrorMode());
		assertEquals(PortalType.PORTAL, source.getType());
		assertFalse(source.getDimensionalPortalKind().isGenericDestination());
		assertTrue(VanillaPortalIndex.isManagedKind(source, VanillaPortalIndex.NETHER_TAG, DimensionalPortalKind.NETHER));
	}

	private static LocalPortal portal(World world, DimensionalPortalKind kind)
	{
		PortalStructure structure = new PortalStructure();
		structure.setArea(new Cuboid(Map.of("worldKey", "minecraft:overworld", "x1", 0, "y1", 64, "z1", 0, "x2", 0, "y2", 66, "z2", 2)));
		structure.setWorld(world);
		LocalPortal portal = new LocalPortal(UUID.randomUUID(), PortalType.PORTAL, structure);
		portal.setDimensionalPortalKind(kind);
		return portal;
	}

	private static World world()
	{
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class}, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getName" -> "world";
			case "getKey" -> NamespacedKey.minecraft("overworld");
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}
}

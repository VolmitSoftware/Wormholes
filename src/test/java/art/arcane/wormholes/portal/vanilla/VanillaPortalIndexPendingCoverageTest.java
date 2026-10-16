package art.arcane.wormholes.portal.vanilla;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockVector;
import org.junit.jupiter.api.Test;

public final class VanillaPortalIndexPendingCoverageTest
{
	@Test
	public void netherShapeOwnershipRequiresOneExactPendingShape()
	{
		World world = world("world");
		VanillaPortalIndex index = new VanillaPortalIndex();
		Set<BlockVector> shape = Set.of(new BlockVector(4, 65, 7), new BlockVector(4, 66, 7));
		index.registerPending(cells(new FakeBlock(world, 4, 65, 7, Material.AIR)));
		index.registerPending(cells(new FakeBlock(world, 4, 66, 7, Material.AIR)));

		assertFalse(index.ownsNetherShape(world, shape));

		VanillaPortalIndex.PendingCoverage pending = index.registerPending(cells(
				new FakeBlock(world, 4, 65, 7, Material.AIR), new FakeBlock(world, 4, 66, 7, Material.AIR)));

		assertTrue(index.ownsNetherShape(world, shape));
		assertFalse(index.ownsNetherShape(world("other"), shape));
		assertFalse(index.ownsNetherShape(world, Set.of(new BlockVector(4, 65, 7), new BlockVector(4, 67, 7))));

		index.releasePending(pending);

		assertFalse(index.ownsNetherShape(world, shape));
	}

	@Test
	public void netherShapeOwnershipExcludesPendingEndWindowsAndPartialShapes()
	{
		World world = world("world");
		VanillaPortalIndex index = new VanillaPortalIndex();
		index.registerPendingEnd(new Location(world, 4, 65, 7));
		Set<BlockVector> endCells = new LinkedHashSet<BlockVector>();
		for(int x = 0; x <= 8; x++)
		{
			for(int z = 3; z <= 11; z++)
			{
				endCells.add(new BlockVector(x, 65, z));
			}
		}
		assertFalse(index.ownsNetherShape(world, endCells));

		index.registerPending(cells(new FakeBlock(world, 4, 65, 7, Material.AIR), new FakeBlock(world, 4, 66, 7, Material.AIR)));

		assertFalse(index.ownsNetherShape(world, Set.of(new BlockVector(4, 65, 7))));
	}

	@Test
	public void pendingNetherCreateIsCoveredBeforePairFinishes()
	{
		World world = world("world");
		FakeBlock first = new FakeBlock(world, 4, 65, 7, Material.NETHER_PORTAL);
		FakeBlock second = new FakeBlock(world, 4, 66, 7, Material.NETHER_PORTAL);
		Set<Block> cells = cells(first, second);
		VanillaPortalIndex index = new VanillaPortalIndex();

		VanillaPortalIndex.PendingCoverage pending = index.registerPending(cells);

		assertNotNull(pending);
		assertTrue(index.covers(new Location(world, 4.5D, 65.2D, 7.5D)));
		assertTrue(index.covers(new Location(world, 4.1D, 66.8D, 7.4D)));
		assertFalse(index.covers(new Location(world, 8.5D, 65.2D, 7.5D)));
		assertFalse(index.coversCells(cells));
		assertFalse(index.nearEndWindow(new Location(world, 4.5D, 65.2D, 7.5D)));
	}

	@Test
	public void pendingEndEyePlaceIsCoveredBeforePairFinishes()
	{
		World world = world("world");
		Location frame = new Location(world, 20.5D, 64.0D, 12.5D);
		VanillaPortalIndex index = new VanillaPortalIndex();

		VanillaPortalIndex.PendingCoverage pending = index.registerPendingEnd(frame);

		assertNotNull(pending);
		assertTrue(index.covers(new Location(world, 20.5D, 64.2D, 12.5D)));
		assertTrue(index.covers(new Location(world, 22.5D, 64.2D, 11.5D)));
		assertTrue(index.nearEndWindow(new Location(world, 22.5D, 64.2D, 11.5D)));
		assertFalse(index.covers(new Location(world, 30.5D, 64.2D, 12.5D)));
		assertFalse(index.coversCells(cells(new FakeBlock(world, 22, 64, 11, Material.END_PORTAL))));
	}

	@Test
	public void releasingPendingCoverageUnblocksVanillaTravel()
	{
		World world = world("world_the_nether");
		Set<Block> cells = cells(new FakeBlock(world, 1, 70, 3, Material.NETHER_PORTAL));
		VanillaPortalIndex index = new VanillaPortalIndex();
		VanillaPortalIndex.PendingCoverage pending = index.registerPending(cells);
		Location inside = new Location(world, 1.5D, 70.4D, 3.5D);

		assertTrue(index.covers(inside));

		index.releasePending(pending);

		assertFalse(index.covers(inside));
	}

	@Test
	public void overlappingPendingCreatesSurviveASingleRelease()
	{
		World world = world("world");
		Set<Block> firstCells = cells(new FakeBlock(world, 4, 65, 7, Material.NETHER_PORTAL));
		Set<Block> secondCells = cells(new FakeBlock(world, 4, 65, 7, Material.NETHER_PORTAL));
		VanillaPortalIndex index = new VanillaPortalIndex();
		VanillaPortalIndex.PendingCoverage first = index.registerPending(firstCells);
		VanillaPortalIndex.PendingCoverage second = index.registerPending(secondCells);
		Location inside = new Location(world, 4.5D, 65.2D, 7.5D);

		index.releasePending(first);

		assertTrue(index.covers(inside));

		index.releasePending(second);

		assertFalse(index.covers(inside));
	}

	@Test
	public void pendingEndWindowSuppressesEveryEntityEnteringItsPortalBlocks()
	{
		World world = world("world");
		VanillaPortalIndex index = new VanillaPortalIndex();
		VanillaPortalIndex.PendingCoverage pending = index.registerPendingEnd(new Location(world, 20, 64, 12));

		assertTrue(index.suppressesEntry(entity(Player.class), new Location(world, 21, 64, 12)));
		assertTrue(index.suppressesEntry(entity(ArmorStand.class), new Location(world, 19, 64, 13)));
		assertFalse(index.suppressesEntry(entity(Player.class), new Location(world, 40, 64, 12)));

		index.releasePending(pending);

		assertFalse(index.suppressesEntry(entity(Player.class), new Location(world, 21, 64, 12)));
		assertFalse(index.suppressesEntry(entity(ArmorStand.class), new Location(world, 19, 64, 13)));
	}

	@Test
	public void pendingNetherCellsSuppressEntryUntilReleased()
	{
		World world = world("world");
		VanillaPortalIndex index = new VanillaPortalIndex();
		VanillaPortalIndex.PendingCoverage pending = index.registerPending(cells(
				new FakeBlock(world, 4, 65, 7, Material.NETHER_PORTAL), new FakeBlock(world, 4, 66, 7, Material.NETHER_PORTAL)));

		assertTrue(index.suppressesEntry(entity(Player.class), new Location(world, 4, 66, 7)));
		assertTrue(index.suppressesEntry(entity(ArmorStand.class), new Location(world, 4, 65, 7)));
		assertFalse(index.suppressesEntry(entity(Player.class), new Location(world, 5, 65, 7)));

		index.releasePending(pending);

		assertFalse(index.suppressesEntry(entity(Player.class), new Location(world, 4, 66, 7)));
	}

	@Test
	public void nearEndWindowSuppressesPlayersOnlyForEndTravelBlocks()
	{
		World world = world("world", Map.of("25,64,12", Material.NETHER_PORTAL, "25,64,13", Material.END_PORTAL));
		Location netherCell = new Location(world, 25, 64, 12);
		Location endCell = new Location(world, 25, 64, 13);
		VanillaPortalIndex index = new VanillaPortalIndex();
		index.registerPendingEnd(new Location(world, 20, 64, 12));

		assertFalse(index.covers(netherCell.clone().add(0.5D, 0.5D, 0.5D)));
		assertTrue(index.suppressesEntry(entity(Player.class), endCell));
		assertFalse(index.suppressesEntry(entity(Player.class), netherCell));
		assertTrue(index.suppressesEntry(entity(ArmorStand.class), netherCell));
	}

	private static Entity entity(Class<? extends Entity> type)
	{
		return (Entity) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "toString" -> type.getSimpleName() + "Proxy";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	private static Set<Block> cells(FakeBlock... blocks)
	{
		Set<Block> cells = new LinkedHashSet<Block>();
		for(FakeBlock block : blocks)
		{
			cells.add(block.proxy());
		}
		return cells;
	}

	private static World world(String name)
	{
		return world(name, Map.of());
	}

	private static World world(String name, Map<String, Material> types)
	{
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getName" -> name;
			case "getBlockAt" -> blockAt((World) proxy, types, (Location) arguments[0]);
			case "toString" -> "WorldProxy[" + name + "]";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	private static Block blockAt(World world, Map<String, Material> types, Location location)
	{
		Material type = types.getOrDefault(location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ(), Material.AIR);
		return new FakeBlock(world, location.getBlockX(), location.getBlockY(), location.getBlockZ(), type).proxy();
	}

	private static final class FakeBlock implements InvocationHandler
	{
		private final World world;
		private final int x;
		private final int y;
		private final int z;
		private final Material type;

		private FakeBlock(World world, int x, int y, int z, Material type)
		{
			this.world = world;
			this.x = x;
			this.y = y;
			this.z = z;
			this.type = type;
		}

		private Block proxy()
		{
			return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] { Block.class }, this);
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] arguments)
		{
			return switch(method.getName())
			{
				case "getWorld" -> world;
				case "getX" -> Integer.valueOf(x);
				case "getY" -> Integer.valueOf(y);
				case "getZ" -> Integer.valueOf(z);
				case "getType" -> type;
				case "toString" -> "BlockProxy";
				case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
				case "equals" -> Boolean.valueOf(proxy == arguments[0]);
				default -> throw new UnsupportedOperationException(method.getName());
			};
		}
	}
}

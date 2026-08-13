package art.arcane.wormholes.portal.vanilla;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

public final class VanillaPortalIndexPendingCoverageTest
{
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
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getName" -> name;
			case "toString" -> "WorldProxy[" + name + "]";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
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

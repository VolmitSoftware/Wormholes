package art.arcane.wormholes.portal.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

public final class VanillaPortalCleanupTest
{
	@Test
	public void acceptedRegionClearsEveryMatchingVanillaCell()
	{
		World world = world();
		FakeBlock first = new FakeBlock(world, 4, 65, 7, Material.NETHER_PORTAL);
		FakeBlock second = new FakeBlock(world, 5, 65, 7, Material.NETHER_PORTAL);
		FakeBlock foreign = new FakeBlock(world, 6, 65, 7, Material.STONE);
		Set<Block> cells = cells(first, second, foreign);

		assertTrue(VanillaPortalCleanup.clearCells(cells, Material.NETHER_PORTAL, (targetWorld, chunkX, chunkZ, command) ->
		{
			command.run();
			return true;
		}));

		assertEquals(Material.AIR, first.type);
		assertEquals(Material.AIR, second.type);
		assertEquals(Material.STONE, foreign.type);
	}

	@Test
	public void refusedRegionIsReportedInsteadOfSilentlyLeavingALiveVanillaPortal()
	{
		World world = world();
		FakeBlock cell = new FakeBlock(world, 4, 65, 7, Material.NETHER_PORTAL);
		Set<Block> cells = cells(cell);

		assertFalse(VanillaPortalCleanup.clearCells(cells, Material.NETHER_PORTAL, (targetWorld, chunkX, chunkZ, command) -> false));

		assertEquals(Material.NETHER_PORTAL, cell.type);
	}

	@Test
	public void oneRefusedChunkFailsTheWholeClearEvenWhenOtherChunksSucceed()
	{
		World world = world();
		FakeBlock accepted = new FakeBlock(world, 4, 65, 7, Material.NETHER_PORTAL);
		FakeBlock refused = new FakeBlock(world, 40, 65, 7, Material.NETHER_PORTAL);
		Set<Block> cells = cells(accepted, refused);

		assertFalse(VanillaPortalCleanup.clearCells(cells, Material.NETHER_PORTAL, (targetWorld, chunkX, chunkZ, command) ->
		{
			if(chunkX != 0)
			{
				return false;
			}
			command.run();
			return true;
		}));

		assertEquals(Material.AIR, accepted.type);
		assertEquals(Material.NETHER_PORTAL, refused.type);
	}

	@Test
	public void emptyCellSetsAreAlreadyClear()
	{
		assertTrue(VanillaPortalCleanup.clearCells(Set.of(), Material.NETHER_PORTAL, (targetWorld, chunkX, chunkZ, command) -> false));
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

	private static World world()
	{
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getName" -> "world_the_nether";
			case "toString" -> "WorldProxy";
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
		private Material type;

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
				case "setType" -> assignType(arguments);
				case "toString" -> "BlockProxy";
				case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
				case "equals" -> Boolean.valueOf(proxy == arguments[0]);
				default -> throw new UnsupportedOperationException(method.getName());
			};
		}

		private Object assignType(Object[] arguments)
		{
			type = (Material) arguments[0];
			return null;
		}
	}
}

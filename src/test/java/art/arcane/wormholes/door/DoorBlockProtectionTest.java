package art.arcane.wormholes.door;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityBreakDoorEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorBlockProtectionTest
{
	private static final UUID WORLD_ID = new UUID(0, 700);

	@TempDir
	Path temporaryDirectory;

	@Test
	void mobsCannotDestroyAPlacedDimensionalDoor() throws Exception
	{
		DoorBlockProtection protection = protection(placement());
		EntityChangeBlockEvent event = change(block(4, 64, 8));

		protection.onEntityChangeBlock(event);

		assertTrue(event.isCancelled());
	}

	@Test
	void mobsCannotDestroyTheUpperHalfOfAPlacedDimensionalDoor() throws Exception
	{
		DoorBlockProtection protection = protection(placement());
		EntityChangeBlockEvent event = change(block(4, 65, 8));

		protection.onEntityChangeBlock(event);

		assertTrue(event.isCancelled());
	}

	@Test
	void mobsCannotStealTheBlockHoldingUpADimensionalDoor() throws Exception
	{
		DoorBlockProtection protection = protection(placement());
		EntityChangeBlockEvent event = change(block(4, 63, 8));

		protection.onEntityChangeBlock(event);

		assertTrue(event.isCancelled());
	}

	@Test
	void unrelatedBlocksStayFullyMobBreakable() throws Exception
	{
		DoorBlockProtection protection = protection(placement());
		EntityChangeBlockEvent event = change(block(40, 64, 80));

		protection.onEntityChangeBlock(event);

		assertFalse(event.isCancelled());
	}

	@Test
	void doorBreakingMobsAreCoveredByTheBlockChangeHandler()
	{
		assertTrue(EntityChangeBlockEvent.class.isAssignableFrom(EntityBreakDoorEvent.class));
	}

	private DoorBlockProtection protection(PlacedDoorEndpoint endpoint) throws Exception
	{
		DoorStateGuard guard = new DoorStateGuard();
		guard.open(temporaryDirectory);
		guard.state().registerEndpoint(endpoint);
		return new DoorBlockProtection(guard, new PocketSpaceIndex(new PocketStructureService()));
	}

	private static PlacedDoorEndpoint placement()
	{
		return new PlacedDoorEndpoint(
			new DoorPosition(WORLD_ID, "minecraft:overworld", 4, 64, 8),
			DoorItemIdentity.publicDoor(new UUID(0, 701)));
	}

	private static EntityChangeBlockEvent change(Block block)
	{
		return new EntityChangeBlockEvent(proxy(Entity.class), block, proxy(BlockData.class));
	}

	private static Block block(int x, int y, int z)
	{
		World world = (World) Proxy.newProxyInstance(
			DoorBlockProtectionTest.class.getClassLoader(),
			new Class<?>[]{World.class},
			(instance, method, arguments) -> switch (method.getName())
			{
				case "getUID" -> WORLD_ID;
				case "getKey" -> NamespacedKey.fromString("minecraft:overworld");
				case "getName" -> "world";
				default -> defaultValue(method.getReturnType());
			});
		return (Block) Proxy.newProxyInstance(
			DoorBlockProtectionTest.class.getClassLoader(),
			new Class<?>[]{Block.class},
			(instance, method, arguments) -> switch (method.getName())
			{
				case "getWorld" -> world;
				case "getX" -> x;
				case "getY" -> y;
				case "getZ" -> z;
				default -> defaultValue(method.getReturnType());
			});
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type)
	{
		return (T) Proxy.newProxyInstance(
			DoorBlockProtectionTest.class.getClassLoader(),
			new Class<?>[]{type},
			(instance, method, arguments) -> defaultValue(method.getReturnType()));
	}

	private static Object defaultValue(Class<?> returnType)
	{
		if(returnType == boolean.class)
		{
			return Boolean.FALSE;
		}
		if(returnType == int.class)
		{
			return Integer.valueOf(0);
		}
		if(returnType == long.class)
		{
			return Long.valueOf(0L);
		}
		if(returnType == double.class)
		{
			return Double.valueOf(0.0D);
		}
		if(returnType == float.class)
		{
			return Float.valueOf(0.0F);
		}
		return null;
	}
}

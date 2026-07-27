package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.JSONObject;

public final class PortalVanillaTravelCostPersistenceTest
{
	@Test
	public void portalJsonWritesTheExactTemplateAndSeparateQuantity() throws Exception
	{
		World world = LocalPortalTestSupport.world("vanilla-cost-save");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.WORMHOLE);
		Constructor<VanillaTravelCost> constructor = VanillaTravelCost.class.getDeclaredConstructor(
				ItemStack.class, String.class, int.class);
		constructor.setAccessible(true);
		VanillaTravelCost cost = constructor.newInstance(
				new TestItemStack(27), "stored-exact-template", Integer.valueOf(19));
		Field costField = LocalPortalSettings.class.getDeclaredField("travelCost");
		costField.setAccessible(true);
		costField.set(portal.settings(), cost);

		JSONObject stored = portal.toJSON().getJSONObject("travelCost");

		assertEquals("VANILLA", stored.getString("type"));
		assertEquals("stored-exact-template", stored.getString("item"));
		assertEquals(19, stored.getInt("quantity"));
	}

	@Test
	public void missingCostDefaultsToFree() throws Exception
	{
		World world = LocalPortalTestSupport.world("vanilla-cost-default");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.WORMHOLE);

		boolean normalized = withBukkitWorld(world, () -> portal.settings().load(new JSONObject()));

		assertTrue(normalized);
		assertNull(portal.getTravelCost());
	}

	@Test
	public void portalJsonWritesVaultAmountAsDecimalText() throws Exception
	{
		World world = LocalPortalTestSupport.world("vault-cost-save");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.WORMHOLE);
		Field costField = LocalPortalSettings.class.getDeclaredField("travelCost");
		costField.setAccessible(true);
		costField.set(portal.settings(), VaultTravelCost.of("17.2500"));

		JSONObject stored = portal.toJSON().getJSONObject("travelCost");

		assertEquals("VAULT", stored.getString("type"));
		assertEquals("17.25", stored.getString("amount"));
	}

	@Test
	public void malformedCostNormalizesToFree() throws Exception
	{
		World world = LocalPortalTestSupport.world("vanilla-cost-malformed");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.WORMHOLE);
		JSONObject json = new JSONObject();
		json.put("travelCost", new JSONObject().put("type", "VANILLA").put("item", "not yaml").put("quantity", 4));

		boolean normalized = withBukkitWorld(world, () -> portal.settings().load(json));

		assertTrue(normalized);
		assertNull(portal.getTravelCost());
	}

	@Test
	public void nonObjectCostNormalizesToFree() throws Exception
	{
		World world = LocalPortalTestSupport.world("vanilla-cost-wrong-shape");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.WORMHOLE);
		JSONObject json = new JSONObject().put("travelCost", "diamond");

		boolean normalized = withBukkitWorld(world, () -> portal.settings().load(json));

		assertTrue(normalized);
		assertNull(portal.getTravelCost());
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
		return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "getWorlds" -> List.of(world);
					case "createBlockData" -> blockData((String) arguments[0]);
					case "getName" -> "PortalVanillaTravelCostPersistenceTestServer";
					case "toString" -> "PortalVanillaTravelCostPersistenceTestServer";
					case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
					case "equals" -> Boolean.valueOf(proxy == arguments[0]);
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private static BlockData blockData(String state)
	{
		return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] {BlockData.class},
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "getAsString" -> state;
					case "toString" -> state;
					case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
					case "equals" -> Boolean.valueOf(proxy == arguments[0]);
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private static final class TestItemStack extends ItemStack
	{
		private int amount;

		private TestItemStack(int amount)
		{
			super();
			this.amount = amount;
		}

		@Override
		public Material getType()
		{
			return Material.DIAMOND;
		}

		@Override
		public int getAmount()
		{
			return amount;
		}

		@Override
		public void setAmount(int amount)
		{
			this.amount = amount;
		}

		@Override
		public TestItemStack clone()
		{
			return new TestItemStack(amount);
		}
	}
}

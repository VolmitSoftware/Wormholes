package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

public final class VanillaTravelCostTest
{
	@Test
	public void capturedStackAmountDoesNotBecomeTheConfiguredQuantity() throws Exception
	{
		VanillaTravelCost cost = cost(new TestItemStack("diamond", 37), 5);

		assertEquals(1, cost.getTemplate().getAmount());
		assertEquals(5, cost.getQuantity());
	}

	@Test
	public void reserveAggregatesExactItemsAndRefundRestoresThem() throws Exception
	{
		InventoryHarness inventory = new InventoryHarness(9);
		inventory.set(0, new TestItemStack("diamond", 3));
		inventory.set(1, new TestItemStack("emerald", 12));
		inventory.set(2, new TestItemStack("diamond", 4));
		VanillaTravelCost cost = cost(new TestItemStack("diamond", 1), 6);

		PortalTravelCost.ReserveResult result = cost.reserve(inventory.player());
		PortalTravelCost.Reservation reservation = result.reservation();

		assertTrue(result.successful());
		assertNotNull(reservation);
		assertNull(inventory.get(0));
		assertEquals(1, inventory.get(2).getAmount());
		assertEquals(12, inventory.get(1).getAmount());

		Method restore = reservation.getClass().getDeclaredMethod("restore");
		restore.setAccessible(true);
		restore.invoke(reservation);

		assertEquals(7, inventory.countSimilar(new TestItemStack("diamond", 1)));
		assertEquals(12, inventory.countSimilar(new TestItemStack("emerald", 1)));
	}

	@Test
	public void insufficientInventoryIsNotPartiallyModified() throws Exception
	{
		InventoryHarness inventory = new InventoryHarness(9);
		inventory.set(0, new TestItemStack("diamond", 2));
		inventory.set(1, new TestItemStack("emerald", 8));
		VanillaTravelCost cost = cost(new TestItemStack("diamond", 1), 3);

		assertFalse(cost.canAfford(inventory.player()));
		assertFalse(cost.reserve(inventory.player()).successful());
		assertEquals(2, inventory.get(0).getAmount());
		assertEquals(8, inventory.get(1).getAmount());
	}

	@Test
	public void differentlyTaggedItemsDoNotMatch() throws Exception
	{
		InventoryHarness inventory = new InventoryHarness(9);
		inventory.set(0, new TestItemStack("diamond:first-name", 8));
		inventory.set(1, new TestItemStack("diamond:second-name", 8));
		VanillaTravelCost cost = cost(new TestItemStack("diamond:first-name", 1), 9);

		assertFalse(cost.canAfford(inventory.player()));
		assertFalse(cost.reserve(inventory.player()).successful());
		assertEquals(8, inventory.get(0).getAmount());
		assertEquals(8, inventory.get(1).getAmount());
	}

	@Test
	public void quantityIsClampedToSupportedInventoryRange() throws Exception
	{
		VanillaTravelCost original = cost(new TestItemStack("iron", 1), Integer.MAX_VALUE);

		assertEquals(VanillaTravelCost.MAX_QUANTITY, original.getQuantity());
		assertEquals(1, cost(new TestItemStack("iron", 1), Integer.MIN_VALUE).getQuantity());
	}

	private static VanillaTravelCost cost(ItemStack template, int quantity) throws Exception
	{
		Constructor<VanillaTravelCost> constructor = VanillaTravelCost.class.getDeclaredConstructor(
				ItemStack.class, String.class, int.class);
		constructor.setAccessible(true);
		return constructor.newInstance(template, "stored-template", Integer.valueOf(quantity));
	}

	private static final class InventoryHarness implements InvocationHandler
	{
		private final ItemStack[] contents;
		private final PlayerInventory inventory;
		private final Player player;

		private InventoryHarness(int size)
		{
			contents = new ItemStack[size];
			inventory = (PlayerInventory) Proxy.newProxyInstance(
					VanillaTravelCostTest.class.getClassLoader(),
					new Class<?>[] {PlayerInventory.class},
					this);
			player = (Player) Proxy.newProxyInstance(
					VanillaTravelCostTest.class.getClassLoader(),
					new Class<?>[] {Player.class},
					(instance, method, arguments) -> switch(method.getName())
					{
						case "getInventory" -> inventory;
						case "getName" -> "Traveler";
						default -> LocalPortalTestSupport.defaultValue(method.getReturnType());
					});
		}

		private Player player()
		{
			return player;
		}

		private ItemStack get(int slot)
		{
			return contents[slot];
		}

		private void set(int slot, ItemStack stack)
		{
			contents[slot] = stack;
		}

		private int countSimilar(ItemStack template)
		{
			int count = 0;
			for(ItemStack stack : contents)
			{
				if(stack != null && stack.isSimilar(template))
				{
					count += stack.getAmount();
				}
			}
			return count;
		}

		@Override
		public Object invoke(Object instance, Method method, Object[] arguments)
		{
			return switch(method.getName())
			{
				case "getStorageContents" -> contents.clone();
				case "getItem" -> contents[((Integer) arguments[0]).intValue()];
				case "setItem" ->
				{
					contents[((Integer) arguments[0]).intValue()] = (ItemStack) arguments[1];
					yield null;
				}
				case "addItem" -> add((ItemStack[]) arguments[0]);
				default -> LocalPortalTestSupport.defaultValue(method.getReturnType());
			};
		}

		private Map<Integer, ItemStack> add(ItemStack[] stacks)
		{
			Map<Integer, ItemStack> overflow = new HashMap<Integer, ItemStack>();
			for(int input = 0; input < stacks.length; input++)
			{
				ItemStack remaining = stacks[input].clone();
				for(int slot = 0; slot < contents.length && remaining.getAmount() > 0; slot++)
				{
					ItemStack current = contents[slot];
					if(current != null && current.isSimilar(remaining) && current.getAmount() < current.getMaxStackSize())
					{
						int moved = Math.min(remaining.getAmount(), current.getMaxStackSize() - current.getAmount());
						current.setAmount(current.getAmount() + moved);
						remaining.setAmount(remaining.getAmount() - moved);
					}
				}
				for(int slot = 0; slot < contents.length && remaining.getAmount() > 0; slot++)
				{
					if(contents[slot] != null)
					{
						continue;
					}
					int moved = Math.min(remaining.getAmount(), remaining.getMaxStackSize());
					ItemStack inserted = remaining.clone();
					inserted.setAmount(moved);
					contents[slot] = inserted;
					remaining.setAmount(remaining.getAmount() - moved);
				}
				if(remaining.getAmount() > 0)
				{
					overflow.put(Integer.valueOf(input), remaining);
				}
			}
			return overflow;
		}
	}

	private static final class TestItemStack extends ItemStack
	{
		private final String identity;
		private int amount;

		private TestItemStack(String identity, int amount)
		{
			super();
			this.identity = identity;
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
		public int getMaxStackSize()
		{
			return 64;
		}

		@Override
		public boolean isSimilar(ItemStack stack)
		{
			return stack instanceof TestItemStack other && identity.equals(other.identity);
		}

		@Override
		public TestItemStack clone()
		{
			return new TestItemStack(identity, amount);
		}
	}
}

package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
		RecordingRefundExecutor refunds = new RecordingRefundExecutor(true);
		inventory.set(0, new TestItemStack("diamond", 3));
		inventory.set(1, new TestItemStack("emerald", 12));
		inventory.set(2, new TestItemStack("diamond", 4));
		VanillaTravelCost cost = cost(new TestItemStack("diamond", 1), 6, refunds);

		PortalTravelCost.ReserveResult result = cost.reserve(inventory.player());
		PortalTravelCost.Reservation reservation = result.reservation();

		assertTrue(result.successful());
		assertNotNull(reservation);
		assertNull(inventory.get(0));
		assertEquals(1, inventory.get(2).getAmount());
		assertEquals(12, inventory.get(1).getAmount());

		reservation.refund();

		assertEquals(7, inventory.countSimilar(new TestItemStack("diamond", 1)));
		assertEquals(12, inventory.countSimilar(new TestItemStack("emerald", 1)));
		assertEquals(1, inventory.addCalls());
		assertTrue(((VanillaTravelCost.Reservation) reservation).refunded());
	}

	@Test
	public void rejectedOwnerDispatchRetriesWithoutOffOwnerInventoryAccess() throws Exception
	{
		InventoryHarness inventory = new InventoryHarness(9);
		RecordingRefundExecutor refunds = new RecordingRefundExecutor(false);
		refunds.rejectDispatch = true;
		inventory.set(0, new TestItemStack("diamond", 6));
		VanillaTravelCost cost = cost(new TestItemStack("diamond", 1), 6, refunds);
		VanillaTravelCost.Reservation reservation = (VanillaTravelCost.Reservation) cost.reserve(inventory.player()).reservation();

		reservation.refund();

		assertTrue(reservation.refundPending());
		assertEquals(0, inventory.addCalls());
		assertEquals(1, refunds.dispatches);
		assertEquals(List.of(1L), refunds.retryDelays());

		refunds.rejectDispatch = false;
		refunds.owned = true;
		refunds.runRetry(1L);
		reservation.refund();

		assertTrue(reservation.refunded());
		assertEquals(6, inventory.countSimilar(new TestItemStack("diamond", 1)));
		assertEquals(1, inventory.addCalls());
	}

	@Test
	public void retiredOwnerDispatchKeepsRefundPendingAndLateCallbacksCannotDuplicateIt() throws Exception
	{
		InventoryHarness inventory = new InventoryHarness(9);
		RecordingRefundExecutor refunds = new RecordingRefundExecutor(false);
		refunds.holdDispatch = true;
		inventory.set(0, new TestItemStack("diamond", 6));
		VanillaTravelCost cost = cost(new TestItemStack("diamond", 1), 6, refunds);
		VanillaTravelCost.Reservation reservation = (VanillaTravelCost.Reservation) cost.reserve(inventory.player()).reservation();

		reservation.refund();
		refunds.retireHeldDispatch();

		assertTrue(reservation.refundPending());
		assertEquals(0, inventory.addCalls());
		assertEquals(List.of(20L, 1L), refunds.retryDelays());

		refunds.owned = true;
		refunds.runRetry(1L);
		refunds.runHeldDispatch();
		refunds.runRetry(20L);

		assertTrue(reservation.refunded());
		assertEquals(6, inventory.countSimilar(new TestItemStack("diamond", 1)));
		assertEquals(1, inventory.addCalls());
	}

	@Test
	public void committedReservationCannotBeRefunded() throws Exception
	{
		InventoryHarness inventory = new InventoryHarness(9);
		RecordingRefundExecutor refunds = new RecordingRefundExecutor(true);
		inventory.set(0, new TestItemStack("diamond", 6));
		VanillaTravelCost cost = cost(new TestItemStack("diamond", 1), 6, refunds);
		PortalTravelCost.Reservation reservation = cost.reserve(inventory.player()).reservation();

		reservation.commit();
		reservation.refund();

		assertEquals(0, inventory.countSimilar(new TestItemStack("diamond", 1)));
		assertEquals(0, inventory.addCalls());
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
		return cost(template, quantity, new RecordingRefundExecutor(true));
	}

	private static VanillaTravelCost cost(
		ItemStack template,
		int quantity,
		OwnerRefundSettlement.Executor refundExecutor)
	{
		return new VanillaTravelCost(template, "stored-template", quantity, refundExecutor);
	}

	private static final class InventoryHarness implements InvocationHandler
	{
		private final ItemStack[] contents;
		private final PlayerInventory inventory;
		private final Player player;
		private int addCalls;

		private InventoryHarness(int size)
		{
			contents = new ItemStack[size];
			addCalls = 0;
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
						case "getUniqueId" -> UUID.fromString("4f629ccb-4ef9-48c3-9ad1-65efb632f333");
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

		private int addCalls()
		{
			return addCalls;
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
				case "addItem" ->
				{
					addCalls++;
					yield add((ItemStack[]) arguments[0]);
				}
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

	private static final class RecordingRefundExecutor implements OwnerRefundSettlement.Executor
	{
		private final List<ScheduledRetry> retries;
		private boolean active;
		private boolean owned;
		private boolean rejectDispatch;
		private boolean holdDispatch;
		private int dispatches;
		private Runnable heldTask;
		private Runnable heldRetired;

		private RecordingRefundExecutor(boolean owned)
		{
			this.owned = owned;
			active = true;
			retries = new ArrayList<ScheduledRetry>();
		}

		@Override
		public boolean active()
		{
			return active;
		}

		@Override
		public boolean isOwned(Player player)
		{
			return owned;
		}

		@Override
		public boolean dispatch(Player player, Runnable task, Runnable retired)
		{
			dispatches++;
			if(rejectDispatch)
			{
				return false;
			}
			if(holdDispatch)
			{
				heldTask = task;
				heldRetired = retired;
				return true;
			}
			task.run();
			return true;
		}

		@Override
		public boolean retry(Runnable task, long delayTicks)
		{
			retries.add(new ScheduledRetry(delayTicks, task));
			return true;
		}

		private List<Long> retryDelays()
		{
			List<Long> delays = new ArrayList<Long>(retries.size());
			for(ScheduledRetry retry : retries)
			{
				delays.add(Long.valueOf(retry.delayTicks()));
			}
			return List.copyOf(delays);
		}

		private void runRetry(long delayTicks)
		{
			for(int index = 0; index < retries.size(); index++)
			{
				ScheduledRetry retry = retries.get(index);
				if(retry.delayTicks() == delayTicks)
				{
					retries.remove(index);
					retry.task().run();
					return;
				}
			}
			throw new AssertionError("No refund retry queued for " + delayTicks + " ticks");
		}

		private void retireHeldDispatch()
		{
			Runnable retired = heldRetired;
			heldRetired = null;
			retired.run();
		}

		private void runHeldDispatch()
		{
			Runnable task = heldTask;
			heldTask = null;
			task.run();
		}
	}

	private record ScheduledRetry(long delayTicks, Runnable task)
	{
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

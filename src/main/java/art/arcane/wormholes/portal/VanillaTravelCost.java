package art.arcane.wormholes.portal;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.volmlib.util.json.JSONObject;

public final class VanillaTravelCost implements PortalTravelCost
{
	public static final int MAX_QUANTITY = 2304;

	private final ItemStack template;
	private final String serializedTemplate;
	private final int quantity;

	private VanillaTravelCost(ItemStack template, String serializedTemplate, int quantity)
	{
		this.template = normalizeTemplate(template);
		this.serializedTemplate = serializedTemplate;
		this.quantity = clampQuantity(quantity);
	}

	public static VanillaTravelCost of(ItemStack heldItem, int quantity)
	{
		ItemStack template = normalizeTemplate(heldItem);
		return new VanillaTravelCost(template, encode(template), quantity);
	}

	static VanillaTravelCost fromJson(JSONObject json)
	{
		if(json == null)
		{
			return null;
		}
		String serialized = json.optString("item", "");
		if(serialized.isBlank())
		{
			throw new IllegalArgumentException("Stored travel cost item is empty");
		}
		ItemStack template = decode(serialized);
		return new VanillaTravelCost(template, serialized, json.optInt("quantity", 1));
	}

	@Override
	public JSONObject toJson()
	{
		return new JSONObject()
				.put("type", Type.VANILLA.name())
				.put("item", serializedTemplate)
				.put("quantity", quantity);
	}

	@Override
	public Type getType()
	{
		return Type.VANILLA;
	}

	public ItemStack getTemplate()
	{
		return template.clone();
	}

	public Material getMaterial()
	{
		return template.getType();
	}

	public int getQuantity()
	{
		return quantity;
	}

	public VanillaTravelCost withQuantity(int quantity)
	{
		return new VanillaTravelCost(template, serializedTemplate, quantity);
	}

	public String getItemLabel()
	{
		String[] words = template.getType().name().toLowerCase(Locale.ROOT).split("_");
		StringBuilder label = new StringBuilder();
		for(String word : words)
		{
			if(!label.isEmpty())
			{
				label.append(' ');
			}
			label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return label.toString();
	}

	public boolean canAfford(Player player)
	{
		if(player == null)
		{
			return false;
		}
		PlayerInventory inventory = player.getInventory();
		int found = 0;
		int storageSize = inventory.getStorageContents().length;
		for(int slot = 0; slot < storageSize; slot++)
		{
			ItemStack stack = inventory.getItem(slot);
			if(stack == null || !stack.isSimilar(template))
			{
				continue;
			}
			found += stack.getAmount();
			if(found >= quantity)
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public Status status(Player player)
	{
		return canAfford(player) ? Status.AVAILABLE : Status.INSUFFICIENT;
	}

	@Override
	public ReserveResult reserve(Player player)
	{
		if(!canAfford(player))
		{
			return ReserveResult.failed(Status.INSUFFICIENT);
		}
		PlayerInventory inventory = player.getInventory();
		int remaining = quantity;
		int storageSize = inventory.getStorageContents().length;
		for(int slot = 0; slot < storageSize && remaining > 0; slot++)
		{
			ItemStack stack = inventory.getItem(slot);
			if(stack == null || !stack.isSimilar(template))
			{
				continue;
			}
			int removed = Math.min(remaining, stack.getAmount());
			int retained = stack.getAmount() - removed;
			if(retained == 0)
			{
				inventory.setItem(slot, null);
			}
			else
			{
				ItemStack replacement = stack.clone();
				replacement.setAmount(retained);
				inventory.setItem(slot, replacement);
			}
			remaining -= removed;
		}
		return ReserveResult.reserved(new Reservation(player, template, quantity));
	}

	private static ItemStack normalizeTemplate(ItemStack item)
	{
		if(item == null || item.getType() == Material.AIR || item.getType() == Material.CAVE_AIR
				|| item.getType() == Material.VOID_AIR)
		{
			throw new IllegalArgumentException("Travel cost item must not be empty");
		}
		ItemStack template = item.clone();
		template.setAmount(1);
		return template;
	}

	private static int clampQuantity(int quantity)
	{
		return Math.max(1, Math.min(quantity, MAX_QUANTITY));
	}

	private static String encode(ItemStack template)
	{
		try
		{
			YamlConfiguration configuration = new YamlConfiguration();
			configuration.set("item", template);
			return configuration.saveToString();
		}
		catch(RuntimeException exception)
		{
			throw new IllegalArgumentException("Could not preserve the exact travel cost item", exception);
		}
	}

	private static ItemStack decode(String serialized)
	{
		try
		{
			YamlConfiguration configuration = new YamlConfiguration();
			configuration.loadFromString(serialized);
			ItemStack item = configuration.getItemStack("item");
			if(item == null)
			{
				throw new IllegalArgumentException("Stored travel cost item is not an item stack");
			}
			return normalizeTemplate(item);
		}
		catch(InvalidConfigurationException | RuntimeException exception)
		{
			throw new IllegalArgumentException("Could not read the stored travel cost item", exception);
		}
	}

	private static final class Reservation implements PortalTravelCost.Reservation
	{
		private final Player player;
		private final ItemStack template;
		private final int quantity;
		private final AtomicBoolean settled;

		private Reservation(Player player, ItemStack template, int quantity)
		{
			this.player = player;
			this.template = template.clone();
			this.quantity = quantity;
			settled = new AtomicBoolean(false);
		}

		@Override
		public void commit()
		{
			settled.compareAndSet(false, true);
		}

		@Override
		public void refund()
		{
			if(!settled.compareAndSet(false, true))
			{
				return;
			}
			if(FoliaScheduler.isOwnedByCurrentRegion(player))
			{
				restore();
				return;
			}
			Wormholes plugin = Wormholes.instance;
			if(plugin == null)
			{
				Wormholes.w("Could not refund a vanilla travel cost for " + player.getName() + " because Wormholes is not active");
				return;
			}
			boolean scheduled = FoliaScheduler.runEntity(plugin, player, this::restore, 0L,
					() -> Wormholes.w("Could not refund a vanilla travel cost because " + player.getName() + " left the server"));
			if(!scheduled)
			{
				Wormholes.w("Could not schedule a vanilla travel cost refund for " + player.getName());
			}
		}

		private void restore()
		{
			int remaining = quantity;
			while(remaining > 0)
			{
				ItemStack stack = template.clone();
				int restored = Math.min(remaining, stack.getMaxStackSize());
				stack.setAmount(restored);
				Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
				for(ItemStack dropped : overflow.values())
				{
					player.getWorld().dropItemNaturally(player.getLocation(), dropped);
				}
				remaining -= restored;
			}
		}
	}
}

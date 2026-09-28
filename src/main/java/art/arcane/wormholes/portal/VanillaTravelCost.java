package art.arcane.wormholes.portal;

import java.io.IOException;
import java.util.Locale;

import art.arcane.volmlib.nativelib.NativeAdapters;
import art.arcane.volmlib.nativelib.item.ItemStackAccess;
import java.util.Base64;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import art.arcane.volmlib.util.json.JSONObject;

public final class VanillaTravelCost implements PortalTravelCost
{
	public static final int MAX_QUANTITY = ExactItemPayment.MAX_QUANTITY;

	private final ItemStack template;
	private final String serializedTemplate;
	private final int quantity;
	private final OwnerRefundSettlement.Executor<Player> refundExecutor;

	private VanillaTravelCost(ItemStack template, String serializedTemplate, int quantity)
	{
		this(template, serializedTemplate, quantity, BukkitOwnerRefundExecutor.INSTANCE);
	}

	VanillaTravelCost(
		ItemStack template,
		String serializedTemplate,
		int quantity,
		OwnerRefundSettlement.Executor<Player> refundExecutor)
	{
		this.template = normalizeTemplate(template);
		this.serializedTemplate = serializedTemplate;
		this.quantity = clampQuantity(quantity);
		this.refundExecutor = refundExecutor;
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
        return player != null && ExactItemPayment.canAfford(new Inventory(player), template, quantity);
    }

	@Override
	public Status status(Player player)
	{
		return canAfford(player) ? Status.AVAILABLE : Status.INSUFFICIENT;
	}

	@Override
	public ReserveResult reserve(Player player)
	{
        if (player == null || !ExactItemPayment.take(new Inventory(player), template, quantity)) {
            return ReserveResult.failed(Status.INSUFFICIENT);
        }
        return ReserveResult.reserved(new Reservation(player, template, quantity, refundExecutor));
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
		return ExactItemPayment.clampQuantity(quantity);
	}

    private static String encode(ItemStack template) {
        try {
            return Base64.getEncoder().encodeToString(NativeAdapters.require(ItemStackAccess.class).encode(template));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Could not preserve the exact travel cost item", exception);
        }
    }

    private static ItemStack decode(String serialized) {
        try {
            return normalizeTemplate(NativeAdapters.require(ItemStackAccess.class).decode(Base64.getDecoder().decode(serialized)));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Could not read the stored travel cost item", exception);
        }
    }

	static final class Reservation implements PortalTravelCost.Reservation
	{
		private final ItemStack template;
		private final int quantity;
		private final OwnerRefundSettlement<Player> settlement;

		Reservation(
			Player player,
			ItemStack template,
			int quantity,
			OwnerRefundSettlement.Executor<Player> refundExecutor)
		{
			this.template = template.clone();
			this.quantity = quantity;
			settlement = new OwnerRefundSettlement<>(new OwnerRefundSettlement.Options<>(
				player, player.getUniqueId(),
				refundExecutor,
				ownedPlayer ->
				{
					restore(ownedPlayer);
					return true;
				},
				"vanilla portal travel cost", BukkitOwnerRefundExecutor.logger()));
		}

		@Override
		public void commit()
		{
			settlement.commit();
		}

		@Override
		public void refund()
		{
			settlement.refund();
		}

		boolean refundPending()
		{
			return settlement.refundPending();
		}

		boolean refunded()
		{
			return settlement.refunded();
		}

		private void restore(Player ownedPlayer)
		{
            ExactItemPayment.restore(new Inventory(ownedPlayer), template, quantity);
        }
    }

    private record Inventory(Player player) implements ExactItemPayment.Inventory<ItemStack> {
        @Override
        public int storageSize() {
            return player.getInventory().getStorageContents().length;
        }

        @Override
        public ItemStack get(int slot) {
            return player.getInventory().getItem(slot);
        }

        @Override
        public void set(int slot, ItemStack item) {
            player.getInventory().setItem(slot, item);
        }

        @Override
        public ItemStack empty() {
            return null;
        }

        @Override
        public boolean matches(ItemStack stack, ItemStack template) {
            return stack != null && stack.isSimilar(template);
        }

        @Override
        public int count(ItemStack item) {
            return item.getAmount();
        }

        @Override
        public ItemStack copy(ItemStack item, int count) {
            ItemStack copy = item.clone();
            copy.setAmount(count);
            return copy;
        }

        @Override
        public int maximumStackSize(ItemStack item) {
            return item.getMaxStackSize();
        }

        @Override
        public void give(ItemStack item) {
            for (ItemStack overflow : player.getInventory().addItem(item).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            }
        }
    }
}

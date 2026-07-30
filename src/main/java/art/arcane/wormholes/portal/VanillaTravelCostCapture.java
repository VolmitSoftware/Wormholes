package art.arcane.wormholes.portal;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.service.WormholesHud;

public final class VanillaTravelCostCapture implements Listener
{
	private final Map<UUID, PendingCapture> pending = new ConcurrentHashMap<UUID, PendingCapture>();

	public void begin(Player player, LocalPortal portal, Consumer<Player> completion)
	{
		if(player == null || portal == null || completion == null)
		{
			return;
		}
		pending.put(player.getUniqueId(), new PendingCapture(portal, completion));
	}

	public void clear()
	{
		pending.clear();
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void on(PlayerDropItemEvent event)
	{
		Player player = event.getPlayer();
		PendingCapture capture = pending.remove(player.getUniqueId());
		if(capture == null)
		{
			return;
		}
		event.setCancelled(true);
		LocalPortal portal = capture.portal();
		boolean administrator = player.isOp() || player.hasPermission("wormholes.admin");
		if(portal.isDestroyed() || !PortalAccessPolicy.canManage(
				portal.getId(), portal.getOwner(), player.getUniqueId(), administrator))
		{
			WormholesHud.notice(player, Wormholes.text().component(WormholesMessages.PORTAL_EDIT_DENIED));
			return;
		}
		ItemStack selected = event.getItemDrop().getItemStack().clone();
		selected.setAmount(1);
		try
		{
			portal.setVanillaTravelCostItem(selected);
			VanillaTravelCost cost = (VanillaTravelCost) portal.getTravelCost();
			WormholesHud.notice(player, Wormholes.text().component(
					WormholesMessages.PORTAL_COST_ITEM_SET,
					LocalPortalText.arguments("item", cost.getItemLabel())));
		}
		catch(IllegalArgumentException exception)
		{
			WormholesHud.notice(player, Wormholes.text().component(WormholesMessages.PORTAL_COST_ITEM_INVALID));
		}
		capture.completion().accept(player);
	}

	@EventHandler
	public void on(PlayerQuitEvent event)
	{
		pending.remove(event.getPlayer().getUniqueId());
	}

	private record PendingCapture(LocalPortal portal, Consumer<Player> completion)
	{
	}
}

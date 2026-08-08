package art.arcane.wormholes;

import java.util.Optional;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalInteractionGestures;
import art.arcane.wormholes.portal.PortalSurfaceSkins;

public class PortalSkinListener implements Listener
{
	public PortalSkinListener()
	{
		Wormholes.v("Starting Portal Skin Listener");
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void on(PlayerInteractEvent e)
	{
		Action action = e.getAction();
		if(action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK)
		{
			return;
		}
		if(e.getHand() != null && e.getHand() != EquipmentSlot.HAND)
		{
			return;
		}
		if(e.getClickedBlock() != null && e.useInteractedBlock() == Event.Result.DENY)
		{
			return;
		}

		Player player = e.getPlayer();
		ItemStack hand = player.getInventory().getItemInMainHand();
		boolean empty = hand == null || hand.getType() == Material.AIR;
		if(!PortalInteractionGestures.appliesSurfaceSkin(Wormholes.blockManager.isPortalTool(hand), empty))
		{
			return;
		}

		String skin = skinForItem(hand.getType());
		if(skin == null)
		{
			return;
		}

		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if(!(portal instanceof LocalPortal local))
			{
				continue;
			}
			if(!local.isLookingAt(player))
			{
				continue;
			}
			if(local.applySurfaceSkinFromInteraction(player, skin))
			{
				deny(e);
			}
			return;
		}
	}

	private static String skinForItem(Material material)
	{
		Optional<String> fluid = PortalSurfaceSkins.skinForHeldItem(material.name());
		if(fluid.isPresent())
		{
			return fluid.get();
		}
		if(material == Material.AIR || !material.isBlock())
		{
			return null;
		}
		try
		{
			return material.createBlockData().getAsString();
		}
		catch(IllegalArgumentException ex)
		{
			return null;
		}
	}

	private static void deny(PlayerInteractEvent e)
	{
		e.setUseInteractedBlock(Event.Result.DENY);
		e.setCancelled(true);
	}
}

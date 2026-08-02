package art.arcane.wormholes.portal;

import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;

public final class PortalInteractionGestures
{
	private PortalInteractionGestures()
	{
	}

	public static boolean opensPortalMenu(boolean sneaking, boolean mainHandEmpty, Action action, EquipmentSlot hand)
	{
		return matchesMenuGesture(sneaking, mainHandEmpty, action) && (hand == null || hand == EquipmentSlot.HAND);
	}

	public static boolean deniesOffHandUse(boolean sneaking, boolean mainHandEmpty, Action action, EquipmentSlot hand)
	{
		return matchesMenuGesture(sneaking, mainHandEmpty, action) && hand == EquipmentSlot.OFF_HAND;
	}

	public static boolean appliesSurfaceSkin(boolean portalToolHeld, boolean mainHandEmpty)
	{
		return !portalToolHeld && !mainHandEmpty;
	}

	private static boolean matchesMenuGesture(boolean sneaking, boolean mainHandEmpty, Action action)
	{
		return sneaking && mainHandEmpty && action == Action.RIGHT_CLICK_BLOCK;
	}
}

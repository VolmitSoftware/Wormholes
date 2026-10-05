package art.arcane.wormholes.portal;

import org.bukkit.entity.Player;

public final class PortalTypeAccess
{
	public static final String ADMIN = "wormholes.admin";

	private PortalTypeAccess()
	{
	}

	public static boolean allows(Player player, PortalType type)
	{
		if(player == null || type == null)
		{
			return false;
		}
		if(player.isOp() || player.hasPermission(ADMIN))
		{
			return true;
		}
		return player.hasPermission(type.permission());
	}
}

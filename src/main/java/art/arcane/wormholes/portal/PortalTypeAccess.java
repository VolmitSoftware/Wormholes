package art.arcane.wormholes.portal;

import org.bukkit.entity.Player;

public final class PortalTypeAccess
{
	public static final String PORTAL = "wormholes.portals.portal";
	public static final String WORMHOLE = "wormholes.portals.wormhole";
	public static final String GATEWAY = "wormholes.gateway";
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
		return switch(type)
		{
			case PORTAL, RTP -> player.hasPermission(PORTAL);
			case WORMHOLE -> player.hasPermission(WORMHOLE);
			case GATEWAY -> player.hasPermission(GATEWAY);
		};
	}
}

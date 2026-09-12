package art.arcane.wormholes.portal;

import org.bukkit.entity.Player;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;

import java.util.Locale;

public enum PortalPermissionMode
{
	BLACKLIST,
	WHITELIST;

	public PortalPermissionMode next()
	{
		return this == BLACKLIST ? WHITELIST : BLACKLIST;
	}

	public boolean allows(Player player, String node)
	{
		boolean hasNode = player.hasPermission(node);
		return this == WHITELIST ? hasNode : !hasNode;
	}

	/** Aliased nodes: a whitelist grants on any of them, a blacklist refuses on any of them. */
	public boolean allowsAny(Player player, String... nodes)
	{
		boolean hasAny = false;

		for(String node : nodes)
		{
			if(node != null && player.hasPermission(node))
			{
				hasAny = true;
				break;
			}
		}

		return this == WHITELIST ? hasAny : !hasAny;
	}

	public String getDisplayName()
	{
		return Wormholes.text().plain(this == WHITELIST
				? WormholesMessages.PORTAL_LABEL_WHITELIST
				: WormholesMessages.PORTAL_LABEL_BLACKLIST);
	}

	public static PortalPermissionMode fromName(String name)
	{
		if(name == null)
		{
			return BLACKLIST;
		}

		try
		{
			return PortalPermissionMode.valueOf(name.toUpperCase(Locale.ROOT));
		}
		catch(IllegalArgumentException e)
		{
			return BLACKLIST;
		}
	}
}

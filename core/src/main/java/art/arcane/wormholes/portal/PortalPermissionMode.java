package art.arcane.wormholes.portal;

import java.util.function.Predicate;


import java.util.Locale;

public enum PortalPermissionMode
{
	BLACKLIST,
	WHITELIST;

	public PortalPermissionMode next()
	{
		return this == BLACKLIST ? WHITELIST : BLACKLIST;
	}

	public boolean allows(Predicate<String> permissions, String node)
	{
		boolean hasNode = permissions.test(node);
		return this == WHITELIST ? hasNode : !hasNode;
	}

	/** Aliased nodes: a whitelist grants on any of them, a blacklist refuses on any of them. */
	public boolean allowsAny(Predicate<String> permissions, String... nodes)
	{
		boolean hasAny = false;

		for(String node : nodes)
		{
			if(node != null && permissions.test(node))
			{
				hasAny = true;
				break;
			}
		}

		return this == WHITELIST ? hasAny : !hasAny;
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

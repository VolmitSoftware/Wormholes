package art.arcane.wormholes.portal.vanilla;

import java.util.Collection;

import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.ILocalPortal;

public final class VanillaPortalEndTravel
{
	private VanillaPortalEndTravel()
	{
	}

	public static void apply(Collection<ILocalPortal> portals)
	{
		for(ILocalPortal portal : portals)
		{
			DimensionalPortalKind kind = portal.getDimensionalPortalKind();
			if(kind == DimensionalPortalKind.END_SOURCE || kind == DimensionalPortalKind.END_ARRIVAL)
			{
				portal.setDimensionalPortalKind(kind);
			}
		}
	}
}

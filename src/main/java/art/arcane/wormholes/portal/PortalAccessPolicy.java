package art.arcane.wormholes.portal;

import java.util.UUID;

public final class PortalAccessPolicy
{
	private PortalAccessPolicy()
	{
	}

	public static boolean canManage(UUID portalId, UUID ownerId, UUID playerId, boolean administrator)
	{
		if(administrator)
		{
			return true;
		}
		return portalId != null && ownerId != null && playerId != null && !portalId.equals(ownerId) && ownerId.equals(playerId);
	}

	/**
	 * Co-owners come from the access lane's portal roles and manage a portal they do not own.
	 */
	public static boolean canManage(UUID portalId, UUID ownerId, UUID playerId, boolean administrator, boolean coOwner)
	{
		return coOwner || canManage(portalId, ownerId, playerId, administrator);
	}
}

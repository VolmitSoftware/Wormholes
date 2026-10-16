package art.arcane.wormholes.portal;

import java.util.UUID;

import org.bukkit.entity.Player;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.access.AccessPortalExtension;
import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.service.WormholesHud;

final class LocalPortalAccess
{
	private final LocalPortal portal;

	LocalPortalAccess(LocalPortal portal)
	{
		this.portal = portal;
	}

	boolean canManage(UUID playerId, boolean administrator)
	{
		AccessPortalExtension access = portal.extension(AccessPortalExtension.class);
		PortalRole role = access == null ? null : access.role(playerId);
		return PortalAccessPolicy.canManage(portal.getId(), portal.getOwner(), playerId, administrator, role != null && role.manages());
	}

	boolean ensureCanManage(Player player)
	{
		if(player == null)
		{
			return false;
		}
		if(canManage(player.getUniqueId(), player.isOp() || player.hasPermission(PortalTypeAccess.ADMIN)))
		{
			return true;
		}
		WormholesHud.notice(player, Wormholes.text().component(WormholesMessages.PORTAL_EDIT_DENIED));
		player.closeInventory();
		return false;
	}

	boolean ensureCanEditSurfaceSkin(Player player)
	{
		if(player == null)
		{
			return false;
		}
		if(!player.hasPermission(PortalTypeAccess.ADMIN))
		{
			WormholesHud.notice(player, Wormholes.text().component(WormholesMessages.PORTAL_EDIT_DENIED));
			return false;
		}
		return ensureCanManage(player);
	}
}

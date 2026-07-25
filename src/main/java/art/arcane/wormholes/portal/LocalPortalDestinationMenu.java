package art.arcane.wormholes.portal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;

final class LocalPortalDestinationMenu
{
	private final LocalPortal portal;
	private final LocalPortalMenus menus;

	LocalPortalDestinationMenu(LocalPortal portal, LocalPortalMenus menus)
	{
		this.portal = portal;
		this.menus = menus;
	}

	void open(Player p)
	{
		if(portal.getType() == PortalType.RTP)
		{
			menus.text().notifySetting(p, WormholesMessages.PORTAL_RTP_CANNOT_LINK);
			return;
		}
		if(portal.getDimensionalPortalKind().isManagedPortal())
		{
			menus.text().notifySetting(p, WormholesMessages.PORTAL_DIMENSIONAL_LINK_MANAGED);
			return;
		}
		Window window = new UIWindow(Wormholes.instance, p)
				.setTitle(portal.getRouter(true))
				.setResolution(WindowResolution.W9_H6)
				.setDecorator(new UIPaneDecorator(Material.GRAY_STAINED_GLASS_PANE))
				.onClosed((w) -> FoliaScheduler.runEntity(Wormholes.instance, p, () -> portal.uiOpenPortalMenu(p)));
		int pos = 0;

		List<ILocalPortal> localTargets = new ArrayList<>();
		for(ILocalPortal i : Wormholes.portalManager.getLocalPortals())
		{
			if(i.getId().equals(portal.getId()) || i.getType() == PortalType.RTP)
			{
				continue;
			}

			if(i.isGateway() != portal.isGateway())
			{
				continue;
			}

			if(!i.getDimensionalPortalKind().isGenericDestination())
			{
				continue;
			}

			if(i.getStructure() == null || i.getStructure().getWorld() == null || i.getStructure().getCenter() == null)
			{
				continue;
			}

			localTargets.add(i);
		}
		localTargets.sort(Comparator
				.comparing((ILocalPortal target) -> !isLinkedToLocal(target))
				.thenComparingDouble(this::localDestinationDistanceSquared)
				.thenComparing(target -> target.getStructure().getWorld().getName(), String.CASE_INSENSITIVE_ORDER)
				.thenComparing(ILocalPortal::getName, String.CASE_INSENSITIVE_ORDER));

		for(ILocalPortal target : localTargets)
		{
			Location targetCenter = target.getStructure().getCenter();
			UIElement targetElement = LocalPortalText.localizedElement(
					"portal-" + pos,
					WormholesMessages.PORTAL_MENU_LOCAL_DESTINATION,
					LocalPortalText.arguments(
							"portal", target.getName(),
							"x", targetCenter.getBlockX(),
							"y", targetCenter.getBlockY(),
							"z", targetCenter.getBlockZ(),
							"world", target.getStructure().getWorld().getName(),
							"direction", LocalPortalText.directionLabel(target.getDirection())),
					Material.ENDER_PEARL);
			targetElement.setEnchanted(isLinkedToLocal(target));
			targetElement.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, p, () -> {
				window.close();

				if(isLinkedToLocal(target))
				{
					portal.unlink();
					Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
							WormholesMessages.PORTAL_UNLINKED,
							LocalPortalText.arguments("portal", portal.getName(), "destination", target.getName())), portal.getStructure().getCenter());
				}
				else
				{
					portal.setDestination(target);
					Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
							WormholesMessages.PORTAL_LINKED,
							LocalPortalText.arguments("portal", portal.getName(), "destination", target.getName())), portal.getStructure().getCenter());
				}
			}));
			window.setElement(window.getPosition(pos), window.getRow(pos), targetElement);
			pos++;
		}

		if(portal.isGateway() && Wormholes.remotePortalRegistry != null)
		{
			List<RemotePortal> remoteTargets = new ArrayList<>();
			for(RemotePortal i : Wormholes.remotePortalRegistry.all())
			{
				if(i.getType() != PortalType.GATEWAY)
				{
					continue;
				}

				remoteTargets.add(i);
			}
			remoteTargets.sort(Comparator
					.comparing((RemotePortal target) -> !isLinkedToRemote(target))
					.thenComparing(target -> !target.isOpen())
					.thenComparing(target -> target.getServer().getName(), String.CASE_INSENSITIVE_ORDER)
					.thenComparing(RemotePortal::getName, String.CASE_INSENSITIVE_ORDER));

			for(RemotePortal target : remoteTargets)
			{
				boolean linked = isLinkedToRemote(target);
				UIElement targetElement = LocalPortalText.localizedElement(
						"remote-portal-" + pos,
						WormholesMessages.PORTAL_MENU_REMOTE_DESTINATION,
						LocalPortalText.arguments(
								"portal", target.getName(),
								"server", target.getServer().getName(),
								"x", target.getOrigin().getBlockX(),
								"y", target.getOrigin().getBlockY(),
								"z", target.getOrigin().getBlockZ(),
								"world", target.getServer().getWorld(),
								"direction", LocalPortalText.directionLabel(target.getDirection()),
								"state", LocalPortalText.localized(target.isOpen() ? WormholesMessages.LABEL_OPEN : WormholesMessages.LABEL_CLOSED)),
						Material.END_CRYSTAL);
				targetElement.setEnchanted(linked);
				targetElement.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, p, () -> {
					window.close();

					if(isLinkedToRemote(target))
					{
						portal.unlink();
						Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
								WormholesMessages.PORTAL_UNLINKED,
								LocalPortalText.arguments("portal", portal.getName(), "destination", target.getName())), portal.getStructure().getCenter());
					}
					else
					{
						portal.setDestination(target);
						Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
								WormholesMessages.PORTAL_LINKED_REMOTE,
								LocalPortalText.arguments(
										"portal", portal.getName(),
										"destination", target.getName(),
										"server", target.getServer().getName())), portal.getStructure().getCenter());
					}
				}));
				window.setElement(window.getPosition(pos), window.getRow(pos), targetElement);
				pos++;
			}
		}

		window.setVisible(true);
	}

	private boolean isLinkedToLocal(ILocalPortal target)
	{
		return portal.hasTunnel() && portal.getTunnel().getDestination().getId().equals(target.getId());
	}

	private double localDestinationDistanceSquared(ILocalPortal target)
	{
		Location source = portal.getStructure().getCenter();
		Location destination = target.getStructure().getCenter();
		if(source == null || destination == null || source.getWorld() == null || !source.getWorld().equals(destination.getWorld()))
		{
			return Double.MAX_VALUE;
		}
		return source.distanceSquared(destination);
	}

	private boolean isLinkedToRemote(RemotePortal target)
	{
		if(!(portal.getTunnel() instanceof UniversalTunnel universal))
		{
			return false;
		}

		return target.getServer().getName().equals(universal.getServerName())
			&& target.getId().equals(universal.getDestinationId());
	}
}

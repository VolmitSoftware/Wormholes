package art.arcane.wormholes.portal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.LocalPortalDestinationModel.Entry;
import art.arcane.wormholes.portal.LocalPortalDestinationModel.SortMode;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;

final class LocalPortalDestinationMenu
{
	private static final int ROW_WIDTH = 9;

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
		if(portal.isMirrorMode())
		{
			menus.text().notifySetting(p, WormholesMessages.PORTAL_TRAVEL_MIRROR_LOCKED);
			return;
		}
		if(portal.getDimensionalPortalKind().isManagedPortal())
		{
			menus.text().notifySetting(p, WormholesMessages.PORTAL_DIMENSIONAL_LINK_MANAGED);
			return;
		}
		new Session(p).open();
	}

	private boolean isLinkedToLocal(ILocalPortal target)
	{
		ITunnel activeTunnel = portal.getTunnel();
		IPortal destination = activeTunnel == null ? null : activeTunnel.getDestination();
		return destination != null && destination.getId().equals(target.getId());
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

	private record Target(Entry entry, ILocalPortal local, RemotePortal remote)
	{
	}

	private final class Session
	{
		private final Player viewer;
		private final Window window;
		private SortMode sortMode;
		private int page;

		private Session(Player viewer)
		{
			this.viewer = viewer;
			sortMode = SortMode.SMART;
			window = new UIWindow(Wormholes.instance, viewer)
					.setTitle(portal.getRouter(true))
					.setResolution(WindowResolution.W9_H6)
					.setDecorator(new UIPaneDecorator(Material.GRAY_STAINED_GLASS_PANE))
					.onClosed((w) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> portal.uiOpenPortalMenu(viewer)));
			window.setViewportHeight(6);
		}

		private void open()
		{
			window.batch(this::populate);
			window.setVisible(true);
		}

		private void repopulate()
		{
			window.batch(() ->
			{
				window.clearElements();
				populate();
			});
			window.updateInventory();
		}

		private void populate()
		{
			List<Target> targets = collectTargets();
			targets.sort(Comparator.comparing(Target::entry, LocalPortalDestinationModel.comparator(sortMode)));
			int pageCount = LocalPortalDestinationModel.pageCount(targets.size());
			page = LocalPortalDestinationModel.clampPage(page, pageCount);
			int start = LocalPortalDestinationModel.pageStart(page);
			int end = LocalPortalDestinationModel.pageEnd(targets.size(), page);
			for(int index = start; index < end; index++)
			{
				Target target = targets.get(index);
				int slot = index - start;
				int row = slot / ROW_WIDTH;
				int position = slot % ROW_WIDTH - ROW_WIDTH / 2;
				window.setElement(position, row, target.local() != null
						? localElement(target.local(), index)
						: remoteElement(target.remote(), index));
			}
			if(targets.isEmpty())
			{
				window.setElement(0, 2, LocalPortalText.localizedElement(
						"destination-empty",
						WormholesMessages.PORTAL_MENU_DESTINATION_EMPTY,
						MessageArgs.empty(),
						Material.BARRIER));
			}
			if(page > 0)
			{
				UIElement previous = LocalPortalText.localizedElement(
						"destination-previous",
						WormholesMessages.PORTAL_MENU_DESTINATION_PREVIOUS,
						MessageArgs.empty(),
						Material.ARROW);
				previous.onLeftClick((e) ->
				{
					page--;
					repopulate();
				});
				window.setElement(-4, 5, previous);
			}
			UIElement sort = LocalPortalText.localizedElement(
					"destination-sort",
					WormholesMessages.PORTAL_MENU_DESTINATION_SORT,
					LocalPortalText.arguments("mode", Wormholes.text().plain(sortLabel(sortMode))),
					Material.COMPARATOR);
			sort.onLeftClick((e) ->
			{
				sortMode = sortMode.next();
				page = 0;
				repopulate();
			});
			window.setElement(-2, 5, sort);
			window.setElement(0, 5, LocalPortalText.localizedElement(
					"destination-page",
					WormholesMessages.PORTAL_MENU_DESTINATION_PAGE,
					LocalPortalText.arguments(
							"page", page + 1,
							"pages", pageCount,
							"count", targets.size()),
					Material.PAPER));
			if(page + 1 < pageCount)
			{
				UIElement next = LocalPortalText.localizedElement(
						"destination-next",
						WormholesMessages.PORTAL_MENU_DESTINATION_NEXT,
						MessageArgs.empty(),
						Material.ARROW);
				next.onLeftClick((e) ->
				{
					page++;
					repopulate();
				});
				window.setElement(4, 5, next);
			}
		}

		private List<Target> collectTargets()
		{
			List<Target> targets = new ArrayList<Target>();
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

				targets.add(new Target(new Entry(
						i.getName(),
						i.getStructure().getWorld().getName(),
						localDestinationDistanceSquared(i),
						isLinkedToLocal(i),
						false,
						true), i, null));
			}
			if(portal.isGateway() && Wormholes.remotePortalRegistry != null)
			{
				for(RemotePortal i : Wormholes.remotePortalRegistry.all())
				{
					if(i.getType() != PortalType.GATEWAY)
					{
						continue;
					}

					targets.add(new Target(new Entry(
							i.getName(),
							i.getServer().getName(),
							Double.MAX_VALUE,
							isLinkedToRemote(i),
							true,
							i.isOpen()), null, i));
				}
			}
			return targets;
		}

		private UIElement localElement(ILocalPortal target, int index)
		{
			Location targetCenter = target.getStructure().getCenter();
			UIElement targetElement = LocalPortalText.localizedElement(
					"portal-" + index,
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
			targetElement.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
			{
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
			return targetElement;
		}

		private UIElement remoteElement(RemotePortal target, int index)
		{
			boolean linked = isLinkedToRemote(target);
			UIElement targetElement = LocalPortalText.localizedElement(
					"remote-portal-" + index,
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
			targetElement.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
			{
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
			return targetElement;
		}

		private TextKey sortLabel(SortMode mode)
		{
			return switch(mode)
			{
				case SMART -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_SMART;
				case NAME -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_NAME;
				case WORLD -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_WORLD;
				case DISTANCE -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_DISTANCE;
			};
		}
	}
}

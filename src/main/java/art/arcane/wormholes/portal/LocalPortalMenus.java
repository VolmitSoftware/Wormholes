package art.arcane.wormholes.portal;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.wormholes.service.WormholesHud;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.data.MaterialBlock;
import art.arcane.volmlib.util.inventorygui.Element;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;

final class LocalPortalMenus
{
	@FunctionalInterface
	interface MenuRefreshDispatcher
	{
		boolean dispatch(Player viewer, Runnable task, Runnable retired);
	}

	private static final String MENU_REFRESH_SCHEDULE_REJECTED = "PORTAL_MENU_REFRESH_SCHEDULE_REJECTED";
	private static final MenuRefreshDispatcher MENU_REFRESH_DISPATCHER = (viewer, task, retired) ->
			FoliaScheduler.runEntity(Wormholes.instance, viewer, task, 0L, retired);

	private final LocalPortal portal;
	private final LocalPortalText text;
	private final LocalPortalSettingsMenu settingsMenu;
	private final LocalPortalCostMenu costMenu;
	private final LocalPortalCosmeticsMenu cosmeticsMenu;
	private final LocalPortalDestinationMenu destinationMenu;
	private final LocalPortalRtpEditor rtpEditor;
	private final Map<UUID, UIWindow> openMenus = new ConcurrentHashMap<UUID, UIWindow>();

	LocalPortalMenus(LocalPortal portal)
	{
		this.portal = portal;
		text = new LocalPortalText(portal);
		cosmeticsMenu = new LocalPortalCosmeticsMenu(portal, this);
		settingsMenu = new LocalPortalSettingsMenu(portal, this);
		costMenu = new LocalPortalCostMenu(portal, this);
		destinationMenu = new LocalPortalDestinationMenu(portal, this);
		rtpEditor = new LocalPortalRtpEditor(portal, this);
	}

	LocalPortalText text()
	{
		return text;
	}

	LocalPortalSettingsMenu settings()
	{
		return settingsMenu;
	}

	LocalPortalCosmeticsMenu cosmetics()
	{
		return cosmeticsMenu;
	}

	LocalPortalCostMenu costs()
	{
		return costMenu;
	}

	boolean ensureCanManage(Player player)
	{
		if(player == null)
		{
			return false;
		}
		boolean administrator = player.isOp() || player.hasPermission("wormholes.admin");
		UUID playerId = player.getUniqueId();
		if(PortalAccessPolicy.canManage(portal.getId(), portal.getOwner(), playerId, administrator))
		{
			return true;
		}
		WormholesHud.notice(player, Wormholes.text().component(WormholesMessages.PORTAL_EDIT_DENIED));
		player.closeInventory();
		return false;
	}

	void uiOpenPortalMenu(Player p)
	{
		if(!ensureCanManage(p))
		{
			return;
		}
		Wormholes.v("QA_EVT {\"event\":\"portal_menu_open\",\"status\":\"info\",\"details\":\"home\",\"context\":{\"portal\":\""
				+ portal.getId() + "\",\"gateway\":" + portal.isGateway() + "}}");
		Window w = uiCreatePortalMenu(p);
		w.setVisible(true);
	}

	Window uiCreatePortalMenu(Player p)
	{
		UIWindow window = new UIWindow(Wormholes.instance, p);
		window.setTitle(portal.getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(4);
		window.setDecorator(new UIPaneDecorator(Material.GRAY_STAINED_GLASS_PANE));
		window.onClosed((w) -> openMenus.remove(p.getUniqueId(), window));
		rebuildPortalMenuElements(window, p);
		openMenus.put(p.getUniqueId(), window);
		return window;
	}

	private void rebuildPortalMenuElements(UIWindow window, Player p)
	{
		window.batch(() ->
		{
			window.clearElements();
			window.setElement(0, 0, portalPlacardElement());

			RtpSettings currentRtpSettings = portal.getRtpSettings();
			boolean rtp = portal.getType() == PortalType.RTP && currentRtpSettings != null;
			IPortal linkedDestination = linkedDestination();
			UIElement destination = new UIElement("set-destination");
			LinesKey destinationKey = rtp
					? WormholesMessages.PORTAL_MENU_RTP_DESTINATION
					: portal.isGateway() ? WormholesMessages.PORTAL_MENU_GATEWAY_DESTINATION : WormholesMessages.PORTAL_MENU_DESTINATION;
			String destinationLabel = rtp
					? text.rtpRotationSummary(currentRtpSettings)
					: linkedDestination != null ? linkedDestination.getName() : LocalPortalText.localized(WormholesMessages.LABEL_NONE);
			String destinationArgument = rtp ? "rotation" : "destination";
			Wormholes.text().apply(destination, destinationKey, LocalPortalText.arguments(destinationArgument, destinationLabel));
			destination.setMaterial(new MaterialBlock(rtp ? Material.COMPASS : portal.isGateway() ? Material.END_CRYSTAL : Material.ENDER_EYE));
			destination.setCount(rtp ? 1 : Math.max(1, Wormholes.portalManager.getAccessableCount(portal.getType()) - 1));
			destination.onLeftClick((e) ->
			{
						if(portal.getDimensionalPortalKind().isManagedPortal())
						{
							text.notifySetting(p, WormholesMessages.PORTAL_DIMENSIONAL_LINK_MANAGED);
							return;
						}
						if(rtp)
						{
							window.close();
							rtpEditor.open(p);
						}
						else if(portal.isGateway())
						{
							window.close();
							uiOpenGatewayPairMenu(p);
						}
						else
						{
							portal.uiChooseDestination(p);
						}
					});
			window.setElement(-2, 1, destination);

			UIElement rename = LocalPortalText.localizedElement(
					"set-name", WormholesMessages.PORTAL_MENU_RENAME, LocalPortalText.arguments("portal", portal.getName()), Material.NAME_TAG);
			rename.onLeftClick((e) -> portal.uiChangeName(p));
			window.setElement(-2, 2, rename);

			window.setElement(0, 1, projectionsElement(window, p));
			window.setElement(2, 1, settingsOpenerElement(window, p));
			window.setElement(0, 2, orientationOpenerElement(window, p));
			window.setElement(2, 2, modeOpenerElement(window, p));

			UIElement destroy = LocalPortalText.localizedElement(
					"destroy", WormholesMessages.PORTAL_MENU_DELETE, MessageArgs.empty(), Material.GUNPOWDER);
			destroy.onShiftLeftClick((e) ->
			{
						if(!ensureCanManage(p))
						{
							return;
						}
						window.close();
						portal.destroy();
					});
			window.setElement(0, 3, destroy);
		});
	}

	void refreshOpenMenus()
	{
		if(openMenus.isEmpty())
		{
			return;
		}
		int rejected = dispatchMenuRefreshes(
				openMenus,
				UIWindow::getViewer,
				MENU_REFRESH_DISPATCHER,
				(window, viewer) -> viewer.isOnline() && window.isVisible(),
				this::rebuildPortalMenuElements);
		if(rejected <= 0)
		{
			return;
		}
		WormholesTelemetry.countFailure(MENU_REFRESH_SCHEDULE_REJECTED);
		Wormholes.w("Portal " + portal.getId() + " could not refresh " + rejected
				+ " open menu(s) because their viewer schedulers rejected the work.");
	}

	static <W> int dispatchMenuRefreshes(Map<UUID, W> openMenus, Function<W, Player> viewerResolver,
			MenuRefreshDispatcher dispatcher, BiPredicate<W, Player> active,
			BiConsumer<W, Player> refresher)
	{
		int rejected = 0;
		for(Map.Entry<UUID, W> entry : openMenus.entrySet())
		{
			UUID viewerId = entry.getKey();
			W window = entry.getValue();
			Player viewer = viewerResolver.apply(window);
			if(viewer == null)
			{
				openMenus.remove(viewerId, window);
				continue;
			}
			boolean scheduled = dispatcher.dispatch(viewer, () ->
			{
				if(!active.test(window, viewer))
				{
					openMenus.remove(viewerId, window);
					return;
				}
				refresher.accept(window, viewer);
			}, () -> openMenus.remove(viewerId, window));
			if(!scheduled)
			{
				rejected++;
			}
		}
		return rejected;
	}

	void uiChangeName(Player p)
	{
		p.closeInventory();
		WormholesAudience.sendMessage(p, Wormholes.text().component(
				WormholesMessages.PORTAL_PROMPT_NAME,
				LocalPortalText.arguments("cancel", LocalPortalText.localized(WormholesMessages.PORTAL_INPUT_CANCEL))));
		Wormholes.awaitChatInput(p, (input) -> {
			if (input == null || LocalPortalText.isCancelInput(input)) {
				uiOpenPortalMenu(p);
				return;
			}
			portal.setName(input);
			uiOpenPortalMenu(p);
		});
	}

	void uiChooseDestination(Player p)
	{
		destinationMenu.open(p);
	}

	void uiChooseMode(Player p)
	{
		if(portal.getDimensionalPortalKind().isManagedPortal())
		{
			text.notifySetting(p, WormholesMessages.PORTAL_MANAGED_MODE);
			return;
		}
		UIWindow window = new UIWindow(Wormholes.instance, p);
		window.setTitle(portal.getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(3);
		window.setDecorator(new UIPaneDecorator(Material.GRAY_STAINED_GLASS_PANE));
		window.onClosed((w) -> FoliaScheduler.runEntity(Wormholes.instance, p, () -> uiOpenPortalMenu(p)));

		window.setElement(0, 0, modePlacardElement());
		window.setElement(-4, 1, modeOption(PortalType.PORTAL, p, window));
		window.setElement(-2, 1, modeOption(PortalType.WORMHOLE, p, window));
		window.setElement(0, 1, modeOption(PortalType.GATEWAY, p, window));
		window.setElement(2, 1, modeOption(PortalType.RTP, p, window));
		window.setElement(4, 1, mirrorModeOption(p, window));
		window.setElement(0, 2, backToPortalMenuElement(window, p));

		window.setVisible(true);
	}

	private IPortal linkedDestination()
	{
		ITunnel activeTunnel = portal.getTunnel();
		return activeTunnel == null ? null : activeTunnel.getDestination();
	}

	private Element portalPlacardElement()
	{
		UIElement element = new UIElement("portal-placard");
		element.setMaterial(new MaterialBlock(Material.BOOK));
		RtpSettings rtpSettings = portal.getRtpSettings();
		IPortal linkedDestination = linkedDestination();
		if(portal.getType() == PortalType.RTP && rtpSettings != null)
		{
			Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_PLACARD_RTP,
					LocalPortalText.arguments(
							"portal", portal.getName(),
							"type", text.currentModeLabel(),
							"facing", LocalPortalText.directionLabel(portal.getDirection()),
							"allocation", LocalPortalText.rtpAllocationLabel(rtpSettings.getAllocationMode()),
							"rotation", text.rtpRotationSummary(rtpSettings)));
		}
		else if(linkedDestination != null)
		{
			Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_PLACARD_LINKED,
					LocalPortalText.arguments(
							"portal", portal.getName(),
							"type", text.currentModeLabel(),
							"facing", LocalPortalText.directionLabel(portal.getDirection()),
							"destination", linkedDestination.getName()));
		}
		else
		{
			Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_PLACARD_UNLINKED,
					LocalPortalText.arguments(
							"portal", portal.getName(),
							"type", text.currentModeLabel(),
							"facing", LocalPortalText.directionLabel(portal.getDirection()),
							"none", LocalPortalText.localized(WormholesMessages.LABEL_NONE)));
		}
		return element;
	}

	private Element modePlacardElement()
	{
		return LocalPortalText.localizedElement("mode-placard", WormholesMessages.PORTAL_MENU_MODE_PLACARD,
				LocalPortalText.arguments("mode", text.currentModeLabel()), Material.BEACON);
	}

	private Element modeOpenerElement(Window window, Player viewer)
	{
		String description = portal.isMirrorMode()
				? LocalPortalText.localized(WormholesMessages.PORTAL_MODE_DESCRIPTION_MIRROR)
				: LocalPortalText.modeDescription(portal.getType());
		UIElement element = LocalPortalText.localizedElement("set-mode", WormholesMessages.PORTAL_MENU_MODE_OPENER,
				LocalPortalText.arguments("description", description, "mode", text.currentModeLabel()),
				portal.isMirrorMode() ? Material.COPPER_TORCH : LocalPortalText.modeIcon(portal.getType()));
		element.setEnchanted(true);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			window.close();
			portal.uiChooseMode(viewer);
		}));
		return element;
	}

	private Element orientationOpenerElement(Window window, Player viewer)
	{
		UIElement element = LocalPortalText.localizedElement("portal-orientation", WormholesMessages.PORTAL_MENU_ORIENTATION,
				LocalPortalText.arguments("facing", LocalPortalText.directionLabel(portal.getDirection()), "up", LocalPortalText.directionLabel(portal.getFrame().getUp())), Material.COMPASS);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			window.close();
			uiOpenOrientationMenu(viewer);
		}));
		return element;
	}

	private void uiOpenOrientationMenu(Player viewer)
	{
		UIWindow window = new UIWindow(Wormholes.instance, viewer);
		window.setTitle(portal.getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(3);
		window.setDecorator(new UIPaneDecorator(Material.BLUE_STAINED_GLASS_PANE));
		window.setElement(0, 0, orientationPlacardElement());
		window.setElement(-3, 1, directionElement(window, viewer));
		window.setElement(-1, 1, flipFaceElement(window, viewer));
		window.setElement(1, 1, rotateCounterClockwiseElement(window, viewer));
		window.setElement(3, 1, rotateClockwiseElement(window, viewer));
		window.setElement(0, 2, backToPortalMenuElement(window, viewer));
		window.setVisible(true);
	}

	private Element orientationPlacardElement()
	{
		return LocalPortalText.localizedElement("orientation-placard", WormholesMessages.PORTAL_MENU_ORIENTATION_PLACARD,
				LocalPortalText.arguments("facing", LocalPortalText.directionLabel(portal.getDirection()), "up", LocalPortalText.directionLabel(portal.getFrame().getUp())), Material.COMPASS);
	}

	void uiOpenGatewayPairMenu(Player viewer)
	{
		UIWindow window = new UIWindow(Wormholes.instance, viewer);
		window.setTitle(portal.getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(3);
		window.setDecorator(new UIPaneDecorator(Material.BLACK_STAINED_GLASS_PANE));
		window.setElement(0, 0, gatewayPairPlacardElement());
		window.setElement(-2, 1, exportPortalElement(window, viewer));
		UIElement chooseDestination = LocalPortalText.localizedElement(
				"choose-gateway-destination", WormholesMessages.PORTAL_MENU_GATEWAY_CHOOSE,
				MessageArgs.empty(), Material.END_CRYSTAL);
		chooseDestination.onLeftClick((e) ->
		{
					window.close();
					portal.uiChooseDestination(viewer);
				});
		window.setElement(0, 1, chooseDestination);
		window.setElement(2, 1, importPortalElement(window, viewer));
		window.setElement(0, 2, backToPortalMenuElement(window, viewer));
		window.setVisible(true);
	}

	private Element gatewayPairPlacardElement()
	{
		UIElement element = new UIElement("gateway-pair-placard");
		element.setMaterial(new MaterialBlock(Material.RESPAWN_ANCHOR));
		ITunnel activeTunnel = portal.getTunnel();
		IPortal linkedDestination = activeTunnel == null ? null : activeTunnel.getDestination();
		if(linkedDestination == null)
		{
			Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_GATEWAY_UNPAIRED);
			return element;
		}
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_GATEWAY_PAIRED,
				LocalPortalText.arguments("destination", linkedDestination.getName()));
		if(activeTunnel instanceof UniversalTunnel universal && Wormholes.networkManager != null)
		{
			String peer = universal.getServerName();
			boolean ready = Wormholes.networkManager.isPeerReady(peer);
			String transport = LocalPortalText.localized(Wormholes.networkManager.isSidebandOnlyPeer(peer)
					? WormholesMessages.PORTAL_LABEL_SIDEBAND : WormholesMessages.PORTAL_LABEL_DIRECT);
			String state = ready ? transport : LocalPortalText.localized(WormholesMessages.PORTAL_LABEL_RECONNECTING);
			element.addLore(Wormholes.text().legacy(WormholesMessages.PORTAL_MENU_GATEWAY_SERVER, LocalPortalText.arguments("server", peer)));
			element.addLore(Wormholes.text().legacy(WormholesMessages.PORTAL_MENU_GATEWAY_LINK, LocalPortalText.arguments("state", state)));
		}
		return element;
	}

	private Element exportPortalElement(Window window, Player viewer)
	{
		UIElement element = LocalPortalText.localizedElement("export-portal", WormholesMessages.PORTAL_MENU_GATEWAY_EXPORT,
				MessageArgs.empty(), Material.PAPER);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
				{
					window.close();
					if(Wormholes.importExportService != null)
					{
						Wormholes.importExportService.exportToChat(viewer, portal);
					}
				}));
		return element;
	}

	private Element importPortalElement(Window window, Player viewer)
	{
		UIElement element = LocalPortalText.localizedElement("import-portal", WormholesMessages.PORTAL_MENU_GATEWAY_IMPORT,
				MessageArgs.empty(), Material.WRITABLE_BOOK);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
				{
					window.close();
					viewer.closeInventory();
					WormholesAudience.sendMessage(viewer, Wormholes.text().component(
							WormholesMessages.PORTAL_PROMPT_INVITE,
							LocalPortalText.arguments("cancel", LocalPortalText.localized(WormholesMessages.PORTAL_INPUT_CANCEL))));
					Wormholes.awaitChatInput(viewer, (input) ->
					{
						if(input == null || LocalPortalText.isCancelInput(input))
						{
							uiOpenGatewayPairMenu(viewer);
							return;
						}
						if(Wormholes.importExportService != null)
						{
							Wormholes.importExportService.importCode(viewer, portal, input);
						}
					});
				}));
		return element;
	}

	Element backToPortalMenuElement(Window window, Player viewer)
	{
		UIElement element = LocalPortalText.localizedElement("back-to-portal", WormholesMessages.PORTAL_MENU_BACK,
				MessageArgs.empty(), Material.ARROW);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			window.close();
			uiOpenPortalMenu(viewer);
		}));
		return element;
	}

	private Element projectionsElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("toggle-projections");
		element.onLeftClick((e) ->
		{
			ProjectionMode previous = portal.getProjectionMode();
			if(portal.getDimensionalPortalKind().isReceiverOnly())
			{
				text.notifySetting(viewer, WormholesMessages.PORTAL_PROJECTION_RECEIVER_INACTIVE);
				return;
			}
			portal.setProjectionMode(previous.next());
			applyProjectionMode(element);
			window.updateInventory();
			if(previous != portal.getProjectionMode())
			{
				text.notifySetting(viewer, WormholesMessages.PORTAL_PROJECTION_CHANGED,
						LocalPortalText.arguments("mode", LocalPortalText.projectionModeLabel(portal.getProjectionMode())));
			}
		});
		applyProjectionMode(element);
		return element;
	}

	private void applyProjectionMode(Element element)
	{
		ProjectionMode mode = portal.getProjectionMode();
		Wormholes.text().apply(element, mode == ProjectionMode.ON
				? WormholesMessages.PORTAL_MENU_PROJECTION_ON : WormholesMessages.PORTAL_MENU_PROJECTION_OFF);
		element.setEnchanted(mode.isEnchanted());
		element.setMaterial(new MaterialBlock(mode.getIcon()));
	}

	private Element settingsOpenerElement(Window window, Player viewer)
	{
		LinesKey key = WormholesMessages.PORTAL_MENU_SETTINGS_GATEWAY;
		MessageArgs arguments = LocalPortalText.arguments(
				"access", LocalPortalText.permissionModeLabel(portal.getPermissionMode()),
				"send", LocalPortalText.localized(portal.isOutgoingTraversalsEnabled() ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF),
				"receive", LocalPortalText.localized(portal.isIncomingTraversalsEnabled() ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF),
				"depth", portal.getNetworkViewDepth(),
				"entity", portal.getNetworkViewEntityIntervalTicks());
		UIElement element = LocalPortalText.localizedElement("portal-settings", key, arguments, Material.LEVER);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			window.close();
			settingsMenu.open(viewer);
		}));
		return element;
	}

	private Element modeOption(PortalType target, Player p, Window window)
	{
		boolean current = portal.getType() == target && !portal.isMirrorMode();
		String label = LocalPortalText.portalTypeLabel(target);
		UIElement element = new UIElement("mode-" + target.name().toLowerCase());
		Wormholes.text().apply(element,
				current ? WormholesMessages.PORTAL_MENU_MODE_OPTION_SELECTED : WormholesMessages.PORTAL_MENU_MODE_OPTION_AVAILABLE,
				LocalPortalText.arguments("mode", label, "description", LocalPortalText.modeDescription(target)));
		element.setMaterial(new MaterialBlock(LocalPortalText.modeIcon(target)));
		element.setEnchanted(current);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, p, () ->
		{
			if(portal.getType() != target && !PortalTypeAccess.allows(p, target))
			{
				Wormholes.effectManager.playNotificationFail(
						Wormholes.text().legacy(WormholesMessages.COMMAND_NO_PERMISSION),
						portal.getStructure().getCenter());
				window.close();
				return;
			}
			boolean changed = false;
			if(portal.isMirrorMode())
			{
				portal.setMirrorMode(false);
				changed = true;
			}
			if(portal.getType() != target)
			{
				portal.setType(target);
				changed = true;
			}
			if(changed)
			{
				Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
						WormholesMessages.PORTAL_MODE_CHANGED,
						LocalPortalText.arguments("portal", portal.getName(), "mode", label)), portal.getStructure().getCenter());
			}
			window.close();
		}));
		return element;
	}

	private Element mirrorModeOption(Player p, Window window)
	{
		UIElement element = new UIElement("mode-mirror");
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, p, () ->
		{
			if(!portal.isMirrorMode())
			{
				portal.setMirrorMode(true);
				Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
						WormholesMessages.PORTAL_MODE_CHANGED,
						LocalPortalText.arguments("portal", portal.getName(), "mode", LocalPortalText.localized(WormholesMessages.PORTAL_LABEL_MIRROR))),
						portal.getStructure().getCenter());
			}
			window.close();
		}));
		element.onRightClick((e) -> rotateMirrorImage(element, window, p, portal.getMirrorRotation().clockwiseFor(portal.getFrame())));
		element.onShiftRightClick((e) -> rotateMirrorImage(element, window, p, portal.getMirrorRotation().counterClockwiseFor(portal.getFrame())));
		applyMirrorModeOption(element);
		return element;
	}

	private void applyMirrorModeOption(Element element)
	{
		boolean current = portal.isMirrorMode();
		Wormholes.text().apply(element,
				current ? WormholesMessages.PORTAL_MENU_MIRROR_SELECTED : WormholesMessages.PORTAL_MENU_MIRROR_AVAILABLE);
		element.setMaterial(new MaterialBlock(Material.COPPER_TORCH));
		element.setEnchanted(current);
		if(!current)
		{
			return;
		}
		KList<String> lore = element.getLore();
		lore.add(Wormholes.text().legacy(WormholesMessages.PORTAL_MENU_MIRROR_ROTATION,
				LocalPortalText.arguments("degrees", portal.getMirrorRotation().getDegrees())));
		if(MirrorRotation.supportsQuarterTurns(portal.getFrame()))
		{
			lore.add(Wormholes.text().legacy(WormholesMessages.PORTAL_MENU_MIRROR_ROTATE_CLOCKWISE));
			lore.add(Wormholes.text().legacy(WormholesMessages.PORTAL_MENU_MIRROR_ROTATE_COUNTERCLOCKWISE));
			return;
		}
		lore.addAll(Wormholes.text().legacyLines(WormholesMessages.PORTAL_MENU_MIRROR_FLIP));
	}

	private void rotateMirrorImage(Element element, Window window, Player viewer, MirrorRotation rotation)
	{
		if(!portal.isMirrorMode())
		{
			text.notifySetting(viewer, WormholesMessages.PORTAL_MIRROR_SELECT_FIRST);
			return;
		}
		portal.setMirrorRotation(rotation);
		applyMirrorModeOption(element);
		window.updateInventory();
		text.notifySetting(viewer, WormholesMessages.PORTAL_MIRROR_ROTATION_CHANGED,
				LocalPortalText.arguments("degrees", portal.getMirrorRotation().getDegrees()));
	}

	private Element directionElement(Window window, Player viewer)
	{
		UIElement element = LocalPortalText.localizedElement("set-direction", WormholesMessages.PORTAL_MENU_DIRECTION,
				LocalPortalText.arguments("direction", LocalPortalText.directionLabel(portal.getDirection())), Material.COMPASS);
		element.onLeftClick((e) ->
		{
			window.close();
			portal.uiChangeDirection(viewer);
		});
		return element;
	}

	private Element flipFaceElement(Window window, Player viewer)
	{
		UIElement element = LocalPortalText.localizedElement("flip-face", WormholesMessages.PORTAL_MENU_FLIP_FACE,
				LocalPortalText.arguments("up", LocalPortalText.directionLabel(portal.getFrame().getUp())), Material.TARGET);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			portal.setFrame(portal.getFrame().flipNormal());
			Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
					WormholesMessages.PORTAL_FACE_FLIPPED,
					LocalPortalText.arguments("portal", portal.getName(), "direction", LocalPortalText.directionLabel(portal.getDirection()))), portal.getStructure().getCenter());
			window.close();
			uiOpenPortalMenu(viewer);
		}));
		return element;
	}

	private Element rotateCounterClockwiseElement(Window window, Player viewer)
	{
		UIElement element = LocalPortalText.localizedElement("rotate-counter-clockwise", WormholesMessages.PORTAL_MENU_ROTATE_COUNTERCLOCKWISE,
				LocalPortalText.arguments("up", LocalPortalText.directionLabel(portal.getFrame().getUp())), Material.REPEATER);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			portal.setFrame(portal.getFrame().rotateCounterClockwise());
			Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
					WormholesMessages.PORTAL_ROTATED_COUNTERCLOCKWISE,
					LocalPortalText.arguments("portal", portal.getName())), portal.getStructure().getCenter());
			window.close();
			uiOpenPortalMenu(viewer);
		}));
		return element;
	}

	private Element rotateClockwiseElement(Window window, Player viewer)
	{
		UIElement element = LocalPortalText.localizedElement("rotate-clockwise", WormholesMessages.PORTAL_MENU_ROTATE_CLOCKWISE,
				LocalPortalText.arguments("up", LocalPortalText.directionLabel(portal.getFrame().getUp())), Material.LEVER);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			portal.setFrame(portal.getFrame().rotateClockwise());
			Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
					WormholesMessages.PORTAL_ROTATED_CLOCKWISE,
					LocalPortalText.arguments("portal", portal.getName())), portal.getStructure().getCenter());
			window.close();
			uiOpenPortalMenu(viewer);
		}));
		return element;
	}
}

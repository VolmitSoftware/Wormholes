package art.arcane.wormholes.portal;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.volmlib.util.data.MaterialBlock;
import art.arcane.volmlib.util.inventorygui.Element;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;

final class LocalPortalSettingsMenu
{
	private final LocalPortal portal;
	private final LocalPortalMenus menus;

	LocalPortalSettingsMenu(LocalPortal portal, LocalPortalMenus menus)
	{
		this.portal = portal;
		this.menus = menus;
	}

	void open(Player p)
	{
		Window w = create(p);
		w.setVisible(true);
	}

	private Window create(Player p)
	{
		UIWindow window = new UIWindow(Wormholes.instance, p);
		window.setTitle(portal.getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		boolean custom = portal.settings().getNetworkViewQuality() == NetworkViewQuality.CUSTOM;
		window.setViewportHeight(custom ? 6 : 4);
		window.setDecorator(new UIPaneDecorator(Material.GRAY_STAINED_GLASS_PANE));

		window.setElement(0, 0, settingsPlacardElement());

		window.setElement(-3, 1, permissionElement(window, p));
		window.setElement(-1, 1, travelDirectionElement(window, p));
		window.setElement(1, 1, networkViewQualityElement(window, p));
		window.setElement(3, 1, settingsSyncElement(window, p));

		if(custom)
		{
			window.setElement(-3, 2, networkViewNumberElement(window, p, "network-view-depth",
					WormholesMessages.PORTAL_NETWORK_LABEL_CAPTURE_RADIUS,
					WormholesMessages.PORTAL_NETWORK_DESCRIPTION_CAPTURE_RADIUS,
					Material.SPYGLASS, portal::getNetworkViewDepth, portal::setNetworkViewDepth, 4, 16));
			window.setElement(-1, 2, networkViewNumberElement(window, p, "network-view-heartbeat",
					WormholesMessages.PORTAL_NETWORK_LABEL_FULL_REFRESH,
					WormholesMessages.PORTAL_NETWORK_DESCRIPTION_FULL_REFRESH,
					Material.CLOCK, portal::getNetworkViewHeartbeatTicks, portal::setNetworkViewHeartbeatTicks, 10, 60));
			window.setElement(1, 2, networkViewNumberElement(window, p, "network-view-entities",
					WormholesMessages.PORTAL_NETWORK_LABEL_ENTITY_UPDATE,
					WormholesMessages.PORTAL_NETWORK_DESCRIPTION_ENTITY_UPDATE,
					Material.ENDER_EYE, portal::getNetworkViewEntityIntervalTicks, portal::setNetworkViewEntityIntervalTicks, 2, 20));
			window.setElement(3, 2, networkViewNumberElement(window, p, "network-view-grace",
					WormholesMessages.PORTAL_NETWORK_LABEL_VIEW_GRACE,
					WormholesMessages.PORTAL_NETWORK_DESCRIPTION_VIEW_GRACE,
					Material.REDSTONE, portal::getNetworkViewUnsubscribeGraceSeconds, portal::setNetworkViewUnsubscribeGraceSeconds, 5, 30));
			window.setElement(-4, 3, menus.cosmetics().blackoutElement(window, p));
			window.setElement(-2, 3, menus.cosmetics().ambientParticlesElement(window, p));
			window.setElement(0, 3, networkViewFallbackElement(p, window));
			window.setElement(2, 3, menus.cosmetics().surfaceSkinElement(window, p));
			window.setElement(4, 3, activationRangeElement(window, p));
			window.setElement(0, 4, renderModeElement(window, p));
		}
		else
		{
			window.setElement(-4, 2, menus.cosmetics().blackoutElement(window, p));
			window.setElement(-2, 2, activationRangeElement(window, p));
			window.setElement(0, 2, renderModeElement(window, p));
			window.setElement(2, 2, menus.cosmetics().ambientParticlesElement(window, p));
			window.setElement(4, 2, menus.cosmetics().surfaceSkinElement(window, p));
		}

		window.setElement(0, custom ? 5 : 3, menus.backToPortalMenuElement(window, p));

		return window;
	}

	private void openAdvanced(Player p)
	{
		UIWindow window = new UIWindow(Wormholes.instance, p);
		window.setTitle(portal.getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(5);
		window.setDecorator(new UIPaneDecorator(Material.BLACK_STAINED_GLASS_PANE));
		window.setElement(0, 0, advancedSettingsPlacardElement());
		window.setElement(-3, 1, networkViewNumberElement(window, p, "network-view-depth",
				WormholesMessages.PORTAL_NETWORK_LABEL_CAPTURE_RADIUS,
				WormholesMessages.PORTAL_NETWORK_DESCRIPTION_CAPTURE_RADIUS,
				Material.SPYGLASS, portal::getNetworkViewDepth, portal::setNetworkViewDepth, 4, 16));
		window.setElement(-1, 1, networkViewNumberElement(window, p, "network-view-heartbeat",
				WormholesMessages.PORTAL_NETWORK_LABEL_FULL_REFRESH,
				WormholesMessages.PORTAL_NETWORK_DESCRIPTION_FULL_REFRESH,
				Material.CLOCK, portal::getNetworkViewHeartbeatTicks, portal::setNetworkViewHeartbeatTicks, 10, 60));
		window.setElement(1, 1, networkViewNumberElement(window, p, "network-view-entities",
				WormholesMessages.PORTAL_NETWORK_LABEL_ENTITY_UPDATE,
				WormholesMessages.PORTAL_NETWORK_DESCRIPTION_ENTITY_UPDATE,
				Material.ENDER_EYE, portal::getNetworkViewEntityIntervalTicks, portal::setNetworkViewEntityIntervalTicks, 2, 20));
		window.setElement(3, 1, networkViewNumberElement(window, p, "network-view-grace",
				WormholesMessages.PORTAL_NETWORK_LABEL_VIEW_GRACE,
				WormholesMessages.PORTAL_NETWORK_DESCRIPTION_VIEW_GRACE,
				Material.REDSTONE, portal::getNetworkViewUnsubscribeGraceSeconds, portal::setNetworkViewUnsubscribeGraceSeconds, 5, 30));
		window.setElement(-2, 2, menus.cosmetics().ambientParticlesElement(window, p));
		window.setElement(0, 2, networkViewFallbackElement(p, window));
		window.setElement(2, 2, menus.cosmetics().surfaceSkinElement(window, p));
		window.setElement(-2, 3, menus.cosmetics().blackoutElement(window, p));
		window.setElement(0, 3, renderModeElement(window, p));
		window.setElement(2, 3, activationRangeElement(window, p));
		window.setElement(0, 4, backToSettingsMenuElement(window, p));
		window.setVisible(true);
	}

	private Element settingsPlacardElement()
	{
		return LocalPortalText.localizedElement("settings-placard",
				WormholesMessages.PORTAL_MENU_SETTINGS_PLACARD_GATEWAY,
				MessageArgs.empty(), Material.LEVER);
	}

	private Element advancedSettingsPlacardElement()
	{
		return LocalPortalText.localizedElement("advanced-settings-placard", WormholesMessages.PORTAL_MENU_ADVANCED_SETTINGS,
				MessageArgs.empty(), Material.COMPARATOR);
	}

	Element backToSettingsMenuElement(Window window, Player viewer)
	{
		UIElement element = LocalPortalText.localizedElement("back-to-settings", WormholesMessages.PORTAL_MENU_BACK_SETTINGS,
				MessageArgs.empty(), Material.ARROW);
		element.onLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			window.close();
			open(viewer);
		}));
		return element;
	}

	private Element travelDirectionElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("travel-direction");
		element.onLeftClick((e) ->
		{
			if(portal.getDimensionalPortalKind().isManagedPortal())
			{
				menus.text().notifySetting(viewer, WormholesMessages.PORTAL_TRAVEL_MANAGED);
				return;
			}
			if(portal.isMirrorMode())
			{
				menus.text().notifySetting(viewer, WormholesMessages.PORTAL_TRAVEL_MIRROR_LOCKED);
				return;
			}
			PortalTravelMode mode = portal.settings().getTravelMode().next();
			portal.settings().setTravelMode(mode);
			applyTravelDirectionElement(element);
			window.updateInventory();
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_TRAVEL_CHANGED,
					LocalPortalText.arguments("mode", LocalPortalText.travelModeLabel(mode)));
		});
		applyTravelDirectionElement(element);
		return element;
	}

	private void applyTravelDirectionElement(Element element)
	{
		DimensionalPortalKind kind = portal.getDimensionalPortalKind();
		if(kind.isManagedPortal())
		{
			boolean receiver = kind.isReceiverOnly();
			String direction = kind == DimensionalPortalKind.NETHER
					? LocalPortalText.localized(WormholesMessages.PORTAL_LABEL_BOTH_WAYS)
					: receiver
							? LocalPortalText.localized(WormholesMessages.PORTAL_LABEL_ARRIVAL_ONLY)
							: LocalPortalText.localized(WormholesMessages.PORTAL_LABEL_DEPARTURE_ONLY);
			String detail = kind == DimensionalPortalKind.NETHER
					? LocalPortalText.localized(WormholesMessages.PORTAL_LABEL_DIMENSIONAL_BOTH_ACTIVE)
					: LocalPortalText.localized(WormholesMessages.PORTAL_LABEL_DIMENSIONAL_RETURN_DISABLED);
			Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_TRAVEL_MANAGED,
					LocalPortalText.arguments("direction", direction, "detail", detail));
			element.setEnchanted(true);
			element.setMaterial(new MaterialBlock(kind == DimensionalPortalKind.NETHER ? Material.OBSIDIAN
					: receiver ? Material.ENDER_EYE : Material.ENDER_PEARL));
			return;
		}
		if(portal.isMirrorMode())
		{
			Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_TRAVEL_MIRROR);
			element.setEnchanted(false);
			element.setMaterial(new MaterialBlock(Material.BARRIER));
			return;
		}
		PortalTravelMode mode = portal.settings().getTravelMode();
		boolean locked = mode == PortalTravelMode.LOCKED;
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_TRAVEL,
				LocalPortalText.arguments(
						"mode", LocalPortalText.travelModeLabel(mode),
						"outgoing", LocalPortalText.localized(mode.allowsOutgoing() ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF),
						"incoming", LocalPortalText.localized(mode.allowsIncoming() ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF)));
		element.setEnchanted(!locked);
		element.setMaterial(new MaterialBlock(switch(mode)
		{
			case BOTH -> Material.RECOVERY_COMPASS;
			case OUTBOUND -> Material.ENDER_PEARL;
			case INBOUND -> Material.ENDER_EYE;
			case LOCKED -> Material.BARRIER;
		}));
	}

	private Element networkViewQualityElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("network-view-quality");
		element.onLeftClick((e) ->
		{
			NetworkViewQuality previous = portal.settings().getNetworkViewQuality();
			NetworkViewQuality quality = previous.next();
			portal.settings().setNetworkViewQuality(quality);
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_STREAM_QUALITY_CHANGED,
					LocalPortalText.arguments("quality", LocalPortalText.networkViewQualityLabel(quality)));
			if((previous == NetworkViewQuality.CUSTOM) != (quality == NetworkViewQuality.CUSTOM))
			{
				FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
				{
					window.close();
					open(viewer);
				});
				return;
			}
			applyNetworkViewQualityElement(element);
			window.updateInventory();
		});
		element.onShiftLeftClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			window.close();
			openAdvanced(viewer);
		}));
		applyNetworkViewQualityElement(element);
		return element;
	}

	private void applyNetworkViewQualityElement(Element element)
	{
		NetworkViewQuality quality = portal.settings().getNetworkViewQuality();
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_STREAM_QUALITY,
				LocalPortalText.arguments(
						"quality", LocalPortalText.networkViewQualityLabel(quality),
						"depth", portal.getNetworkViewDepth(),
						"entities", portal.getNetworkViewEntityIntervalTicks(),
						"refresh", portal.getNetworkViewHeartbeatTicks(),
						"grace", portal.getNetworkViewUnsubscribeGraceSeconds()));
		element.setEnchanted(quality == NetworkViewQuality.CINEMATIC);
		element.setMaterial(new MaterialBlock(switch(quality)
		{
			case STANDARD -> Material.CONDUIT;
			case PERFORMANCE -> Material.FEATHER;
			case BALANCED -> Material.SPYGLASS;
			case CINEMATIC -> Material.BEACON;
			case CUSTOM -> Material.COMPARATOR;
		}));
	}

	private Element networkViewNumberElement(Window window, Player viewer, String id, TextKey label, TextKey description, Material material, IntSupplier getter, IntConsumer setter, int step, int largeStep)
	{
		UIElement element = new UIElement(id);
		element.onLeftClick((e) -> adjustNetworkViewNumber(element, window, viewer, label, description, material, getter, setter, step));
		element.onRightClick((e) -> adjustNetworkViewNumber(element, window, viewer, label, description, material, getter, setter, -step));
		element.onShiftLeftClick((e) -> adjustNetworkViewNumber(element, window, viewer, label, description, material, getter, setter, largeStep));
		element.onShiftRightClick((e) -> adjustNetworkViewNumber(element, window, viewer, label, description, material, getter, setter, -largeStep));
		applyNetworkViewNumberElement(element, label, description, material, getter, step, largeStep);
		return element;
	}

	private void adjustNetworkViewNumber(UIElement element, Window window, Player viewer, TextKey label, TextKey description, Material material, IntSupplier getter, IntConsumer setter, int delta)
	{
		int previous = getter.getAsInt();
		setter.accept(previous + delta);
		int current = getter.getAsInt();
		applyNetworkViewNumberElement(element, label, description, material, getter, Math.abs(delta), Math.abs(delta));
		window.updateInventory();
		if(current != previous)
		{
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED,
					LocalPortalText.arguments("label", LocalPortalText.localized(label), "value", current));
		}
	}

	private void applyNetworkViewNumberElement(Element element, TextKey label, TextKey description, Material material, IntSupplier getter, int step, int largeStep)
	{
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_NETWORK_NUMBER,
				LocalPortalText.arguments(
						"label", LocalPortalText.localized(label),
						"description", LocalPortalText.localized(description),
						"value", getter.getAsInt(),
						"step", Math.abs(step),
						"large_step", Math.abs(largeStep)));
		element.setMaterial(new MaterialBlock(material));
	}

	private Element networkViewFallbackElement(Player p, Window window)
	{
		UIElement element = new UIElement("network-view-fallback");
		element.onLeftClick((e) ->
		{
			window.close();
			p.closeInventory();
			WormholesAudience.sendMessage(p, Wormholes.text().component(
					WormholesMessages.PORTAL_PROMPT_BLOCK_STATE,
					LocalPortalText.arguments("cancel", LocalPortalText.localized(WormholesMessages.PORTAL_INPUT_CANCEL))));
			Wormholes.awaitChatInput(p, (input) ->
			{
				if(input == null || LocalPortalText.isCancelInput(input))
				{
					open(p);
					return;
				}
				String previous = portal.getNetworkViewFallbackBlock();
				String normalized = LocalPortalSettings.normalizeNetworkViewFallbackBlock(input);
				portal.setNetworkViewFallbackBlock(normalized);
				if(!previous.equals(portal.getNetworkViewFallbackBlock()))
				{
					menus.text().notifySetting(p, WormholesMessages.PORTAL_FALLBACK_SET,
							LocalPortalText.arguments("block", portal.getNetworkViewFallbackBlock()));
				}
				open(p);
			});
		});
		element.onRightClick((e) ->
		{
			String previous = portal.getNetworkViewFallbackBlock();
			portal.setNetworkViewFallbackBlock(LocalPortalSettings.DEFAULT_NETWORK_VIEW_FALLBACK_BLOCK);
			applyNetworkViewFallbackElement(element);
			window.updateInventory();
			if(!previous.equals(portal.getNetworkViewFallbackBlock()))
			{
				menus.text().notifySetting(p, WormholesMessages.PORTAL_FALLBACK_RESET,
						LocalPortalText.arguments("block", portal.getNetworkViewFallbackBlock()));
			}
		});
		applyNetworkViewFallbackElement(element);
		return element;
	}

	private void applyNetworkViewFallbackElement(Element element)
	{
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_FALLBACK_BLOCK,
				LocalPortalText.arguments("block", portal.getNetworkViewFallbackBlock()));
		element.setMaterial(new MaterialBlock(Material.GLASS));
	}

	private Element activationRangeElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("activation-range");
		element.onLeftClick((e) -> adjustActivationRange(element, window, viewer, 8));
		element.onRightClick((e) -> adjustActivationRange(element, window, viewer, -8));
		element.onShiftLeftClick((e) -> adjustActivationRange(element, window, viewer, 32));
		element.onShiftRightClick((e) -> adjustActivationRange(element, window, viewer, -32));
		applyActivationRangeElement(element);
		return element;
	}

	private void adjustActivationRange(UIElement element, Window window, Player viewer, int delta)
	{
		int previous = portal.getActivationRange();
		int base = previous > 0 ? previous : (int) Math.round(Settings.PROJECTION_RANGE);
		int target = base + delta;
		if(target < 8)
		{
			target = 0;
		}
		portal.setActivationRange(target);
		int current = portal.getActivationRange();
		applyActivationRangeElement(element);
		window.updateInventory();
		if(current != previous)
		{
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED,
					LocalPortalText.arguments("label", LocalPortalText.localized(WormholesMessages.PORTAL_NETWORK_LABEL_ACTIVATION_RANGE), "value", activationRangeDisplay()));
		}
	}

	private void applyActivationRangeElement(Element element)
	{
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_ACTIVATION_RANGE,
				LocalPortalText.arguments("value", activationRangeDisplay(), "step", 8, "large_step", 32));
		element.setMaterial(new MaterialBlock(Material.LODESTONE));
		element.setEnchanted(portal.getActivationRange() > 0);
	}

	private String activationRangeDisplay()
	{
		if(portal.getActivationRange() > 0)
		{
			return Integer.toString(portal.getActivationRange());
		}
		return Wormholes.text().plain(WormholesMessages.PORTAL_LABEL_ACTIVATION_GLOBAL,
				LocalPortalText.arguments("range", (int) Math.round(Settings.PROJECTION_RANGE)));
	}

	private Element renderModeElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("render-mode");
		element.onLeftClick((e) ->
		{
			portal.setRenderMode(portal.getRenderMode().next());
			applyRenderModeElement(element);
			window.updateInventory();
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED,
					LocalPortalText.arguments("label", LocalPortalText.localized(WormholesMessages.PORTAL_NETWORK_LABEL_RENDER_MODE),
							"value", portal.getRenderMode().displayName()));
		});
		applyRenderModeElement(element);
		return element;
	}

	private void applyRenderModeElement(Element element)
	{
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_RENDER_MODE,
				LocalPortalText.arguments("mode", portal.getRenderMode().displayName()));
		element.setMaterial(new MaterialBlock(Material.valueOf(portal.getRenderMode().iconMaterialName())));
		element.setEnchanted(portal.getRenderMode() == ProjectionRenderMode.VENTICULAR);
	}

	private Element settingsSyncElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("settings-sync");
		element.onLeftClick((e) ->
		{
			boolean previous = portal.isSettingsSyncEnabled();
			portal.setSettingsSyncEnabled(!previous);
			applySettingsSyncElement(element);
			window.updateInventory();
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_SETTINGS_SYNC_CHANGED,
					LocalPortalText.arguments("state", LocalPortalText.localized(portal.isSettingsSyncEnabled() ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF)));
		});
		applySettingsSyncElement(element);
		return element;
	}

	private void applySettingsSyncElement(Element element)
	{
		boolean enabled = portal.isSettingsSyncEnabled();
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_SETTINGS_SYNC,
				LocalPortalText.arguments("state", LocalPortalText.localized(enabled ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF)));
		element.setEnchanted(enabled);
		element.setMaterial(new MaterialBlock(enabled ? Material.SOUL_LANTERN : Material.LANTERN));
	}

	private Element permissionElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("permission-mode");
		element.onLeftClick((e) ->
		{
			PortalPermissionMode previous = portal.getPermissionMode();
			portal.setPermissionMode(portal.getPermissionMode().next());
			applyPermissionElement(element);
			window.updateInventory();
			if(previous != portal.getPermissionMode())
			{
				menus.text().notifySetting(viewer, WormholesMessages.PORTAL_ACCESS_CHANGED,
						LocalPortalText.arguments("mode", LocalPortalText.permissionModeLabel(portal.getPermissionMode())));
			}
		});
		applyPermissionElement(element);
		return element;
	}

	private void applyPermissionElement(Element element)
	{
		PortalPermissionMode mode = portal.getPermissionMode();
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_PERMISSION,
				LocalPortalText.arguments(
						"mode", LocalPortalText.permissionModeLabel(mode),
						"description", LocalPortalText.permissionModeDescription(mode),
						"node", portal.getPermissionNode()));
		element.setEnchanted(mode == PortalPermissionMode.WHITELIST);
		element.setMaterial(new MaterialBlock(mode == PortalPermissionMode.WHITELIST ? Material.GOLDEN_HELMET : Material.IRON_HELMET));
	}
}

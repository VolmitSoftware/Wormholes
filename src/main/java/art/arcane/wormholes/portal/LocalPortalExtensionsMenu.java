package art.arcane.wormholes.portal;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import art.arcane.volmlib.util.inventorygui.Element;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.hook.PortalMenuEntry;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.localization.WormholesMessages;

/** "More settings" grid: one element per registered {@link PortalMenuEntry} visible to the viewer. */
final class LocalPortalExtensionsMenu
{
	private static final int COLUMNS = 9;
	private static final int MAX_ROWS = 4;

	private final LocalPortal portal;
	private final LocalPortalMenus menus;

	LocalPortalExtensionsMenu(LocalPortal portal, LocalPortalMenus menus)
	{
		this.portal = portal;
		this.menus = menus;
	}

	static boolean hasEntries()
	{
		return !WormholesHooks.portalMenuEntries().isEmpty();
	}

	List<PortalMenuEntry> visibleEntries(Player viewer)
	{
		List<PortalMenuEntry> visible = new ArrayList<PortalMenuEntry>();
		for(PortalMenuEntry entry : WormholesHooks.portalMenuEntries())
		{
			if(entry.visible(portal, viewer))
			{
				visible.add(entry);
			}
		}
		return visible;
	}

	void open(Player viewer)
	{
		if(!menus.ensureCanManage(viewer))
		{
			return;
		}
		List<PortalMenuEntry> entries = visibleEntries(viewer);
		int rows = Math.max(1, Math.min(MAX_ROWS, (entries.size() + COLUMNS - 1) / COLUMNS));
		UIWindow window = new UIWindow(Wormholes.instance, viewer);
		window.setTitle(portal.getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(rows + 2);
		window.setDecorator(new UIPaneDecorator(Material.CYAN_STAINED_GLASS_PANE));
		window.setElement(0, 0, LocalPortalText.localizedElement("extensions-placard", WormholesMessages.PORTAL_MENU_EXTENSIONS,
				MessageArgs.empty(), Material.COMPARATOR));
		for(int index = 0; index < entries.size() && index < COLUMNS * MAX_ROWS; index++)
		{
			int row = 1 + (index / COLUMNS);
			int column = (index % COLUMNS) - 4;
			window.setElement(column, row, element(entries.get(index), viewer, window));
		}
		window.setElement(0, rows + 1, menus.settings().backToSettingsMenuElement(window, viewer));
		window.setVisible(true);
	}

	Element openerElement(Window window, Player viewer)
	{
		UIElement element = LocalPortalText.localizedElement("extensions", WormholesMessages.PORTAL_MENU_EXTENSIONS,
				MessageArgs.empty(), Material.COMPARATOR);
		element.onLeftClick((event) ->
		{
			window.close();
			open(viewer);
		});
		return element;
	}

	private Element element(PortalMenuEntry entry, Player viewer, Window window)
	{
		UIElement element = LocalPortalText.localizedElement(entry.id(), entry.label(), entry.arguments(portal, viewer), entry.icon());
		element.setEnchanted(entry.enchanted(portal, viewer));
		element.onLeftClick((event) -> entry.onLeftClick(portal, viewer, window));
		element.onRightClick((event) -> entry.onRightClick(portal, viewer, window));
		element.onShiftLeftClick((event) -> entry.onShiftLeftClick(portal, viewer, window));
		return element;
	}
}

package art.arcane.wormholes.portal;

import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
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

final class LocalPortalCosmeticsMenu
{
	private final LocalPortal portal;
	private final LocalPortalMenus menus;

	LocalPortalCosmeticsMenu(LocalPortal portal, LocalPortalMenus menus)
	{
		this.portal = portal;
		this.menus = menus;
	}

	Element blackoutElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("blackout-background");
		element.onLeftClick((e) ->
		{
			portal.setBlackoutBackground(!portal.isBlackoutBackground());
			applyBlackoutElement(element);
			window.updateInventory();
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED,
					LocalPortalText.arguments("label", LocalPortalText.localized(WormholesMessages.PORTAL_NETWORK_LABEL_BLACKOUT),
							"value", LocalPortalText.localized(portal.isBlackoutBackground() ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF)));
		});
		element.onRightClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			window.close();
			uiOpenBlackoutColorMenu(viewer);
		}));
		applyBlackoutElement(element);
		return element;
	}

	private void applyBlackoutElement(Element element)
	{
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_BLACKOUT,
				LocalPortalText.arguments(
						"state", LocalPortalText.localized(portal.isBlackoutBackground() ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF),
						"color", portal.getBlackoutColor().displayName()));
		element.setMaterial(new MaterialBlock(portal.isBlackoutBackground() ? blackoutColorMaterial(portal.getBlackoutColor()) : Material.GLASS));
		element.setEnchanted(portal.isBlackoutBackground());
	}

	private void uiOpenBlackoutColorMenu(Player viewer)
	{
		UIWindow window = new UIWindow(Wormholes.instance, viewer);
		window.setTitle(portal.getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(4);
		window.setDecorator(new UIPaneDecorator(Material.BLACK_STAINED_GLASS_PANE));
		refreshBlackoutColorOptions(window, viewer);
		window.setElement(0, 3, menus.settings().backToSettingsMenuElement(window, viewer));
		window.setVisible(true);
	}

	private void refreshBlackoutColorOptions(Window window, Player viewer)
	{
		window.setElement(0, 0, blackoutColorPlacardElement());
		BlackoutColor[] colors = BlackoutColor.values();
		for(int index = 0; index < colors.length; index++)
		{
			int row = 1 + (index / 8);
			int column = -4 + (index % 8);
			window.setElement(column, row, blackoutColorOptionElement(window, viewer, colors[index]));
		}
	}

	private Element blackoutColorPlacardElement()
	{
		return LocalPortalText.localizedElement("blackout-color-placard", WormholesMessages.PORTAL_MENU_BLACKOUT_COLOR_PLACARD,
				LocalPortalText.arguments("color", portal.getBlackoutColor().displayName()), blackoutColorMaterial(portal.getBlackoutColor()));
	}

	private Element blackoutColorOptionElement(Window window, Player viewer, BlackoutColor color)
	{
		UIElement element = LocalPortalText.localizedElement("blackout-color-" + color.name().toLowerCase(Locale.ROOT),
				WormholesMessages.PORTAL_MENU_BLACKOUT_COLOR_OPTION,
				LocalPortalText.arguments("color", color.displayName()), blackoutColorMaterial(color));
		element.setEnchanted(color == portal.getBlackoutColor());
		element.onLeftClick((e) ->
		{
			portal.setBlackoutColor(color);
			refreshBlackoutColorOptions(window, viewer);
			window.updateInventory();
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED,
					LocalPortalText.arguments("label", LocalPortalText.localized(WormholesMessages.PORTAL_NETWORK_LABEL_BLACKOUT_COLOR),
							"value", color.displayName()));
		});
		return element;
	}

	private static Material blackoutColorMaterial(BlackoutColor color)
	{
		return Material.valueOf(color.materialName());
	}

	Element ambientParticlesElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("ambient-particles");
		element.onLeftClick((e) ->
		{
			portal.setAmbientStyle(portal.getAmbientStyle().next());
			applyAmbientParticlesElement(element);
			window.updateInventory();
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED,
					LocalPortalText.arguments("label", LocalPortalText.localized(WormholesMessages.PORTAL_NETWORK_LABEL_AMBIENT_STYLE),
							"value", ambientStyleDisplay()));
		});
		element.onRightClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			window.close();
			uiOpenAmbientColorMenu(viewer);
		}));
		applyAmbientParticlesElement(element);
		return element;
	}

	private void applyAmbientParticlesElement(Element element)
	{
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_AMBIENT_PARTICLES,
				LocalPortalText.arguments(
						"style", ambientStyleDisplay(),
						"color", ambientColorHex()));
		element.setMaterial(new MaterialBlock(Material.valueOf(portal.getAmbientStyle().iconMaterialName())));
		element.setEnchanted(portal.getAmbientStyle() != AmbientParticleStyle.OFF);
	}

	private void uiOpenAmbientColorMenu(Player viewer)
	{
		UIWindow window = new UIWindow(Wormholes.instance, viewer);
		window.setTitle(portal.getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(5);
		window.setDecorator(new UIPaneDecorator(Material.BLACK_STAINED_GLASS_PANE));
		refreshAmbientColorMenu(window, viewer);
		window.setElement(0, 4, menus.settings().backToSettingsMenuElement(window, viewer));
		window.setVisible(true);
	}

	private void refreshAmbientColorMenu(Window window, Player viewer)
	{
		window.setElement(0, 0, ambientColorPlacardElement());
		window.setElement(-2, 1, ambientChannelElement(window, viewer, "ambient-red",
				WormholesMessages.PORTAL_LABEL_AMBIENT_RED, Material.RED_DYE, this::getAmbientRed, this::setAmbientRed));
		window.setElement(0, 1, ambientChannelElement(window, viewer, "ambient-green",
				WormholesMessages.PORTAL_LABEL_AMBIENT_GREEN, Material.GREEN_DYE, this::getAmbientGreen, this::setAmbientGreen));
		window.setElement(2, 1, ambientChannelElement(window, viewer, "ambient-blue",
				WormholesMessages.PORTAL_LABEL_AMBIENT_BLUE, Material.BLUE_DYE, this::getAmbientBlue, this::setAmbientBlue));
		DyeColor[] dyes = DyeColor.values();
		for(int index = 0; index < dyes.length; index++)
		{
			int row = 2 + (index / 8);
			int column = -4 + (index % 8);
			window.setElement(column, row, ambientColorOptionElement(window, viewer, dyes[index]));
		}
	}

	private Element ambientColorPlacardElement()
	{
		return LocalPortalText.localizedElement("ambient-color-placard", WormholesMessages.PORTAL_MENU_AMBIENT_COLOR_PLACARD,
				LocalPortalText.arguments("color", ambientColorHex()), Material.FIREWORK_STAR);
	}

	private Element ambientChannelElement(Window window, Player viewer, String id, TextKey label, Material material, IntSupplier getter, IntConsumer setter)
	{
		UIElement element = new UIElement(id);
		element.onLeftClick((e) -> adjustAmbientChannel(window, viewer, label, getter, setter, 8));
		element.onRightClick((e) -> adjustAmbientChannel(window, viewer, label, getter, setter, -8));
		element.onShiftLeftClick((e) -> adjustAmbientChannel(window, viewer, label, getter, setter, 32));
		element.onShiftRightClick((e) -> adjustAmbientChannel(window, viewer, label, getter, setter, -32));
		applyAmbientChannelElement(element, label, material, getter);
		return element;
	}

	private void adjustAmbientChannel(Window window, Player viewer, TextKey label, IntSupplier getter, IntConsumer setter, int delta)
	{
		int previous = getter.getAsInt();
		setter.accept(previous + delta);
		int current = getter.getAsInt();
		refreshAmbientColorMenu(window, viewer);
		window.updateInventory();
		if(current != previous)
		{
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED,
					LocalPortalText.arguments("label", LocalPortalText.localized(label), "value", current));
		}
	}

	private void applyAmbientChannelElement(Element element, TextKey label, Material material, IntSupplier getter)
	{
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_AMBIENT_CHANNEL,
				LocalPortalText.arguments(
						"label", LocalPortalText.localized(label),
						"value", getter.getAsInt(),
						"step", 8,
						"large_step", 32));
		element.setMaterial(new MaterialBlock(material));
	}

	private Element ambientColorOptionElement(Window window, Player viewer, DyeColor color)
	{
		int rgb = dyeRgb(color);
		UIElement element = LocalPortalText.localizedElement("ambient-color-" + color.name().toLowerCase(Locale.ROOT),
				WormholesMessages.PORTAL_MENU_AMBIENT_COLOR_OPTION,
				LocalPortalText.arguments("color", dyeDisplayName(color)), dyeMaterial(color));
		element.setEnchanted(nearestDye(portal.getAmbientColor()) == color);
		element.onLeftClick((e) ->
		{
			portal.setAmbientColor(rgb);
			refreshAmbientColorMenu(window, viewer);
			window.updateInventory();
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED,
					LocalPortalText.arguments("label", LocalPortalText.localized(WormholesMessages.PORTAL_NETWORK_LABEL_AMBIENT_COLOR),
							"value", dyeDisplayName(color)));
		});
		return element;
	}

	private int getAmbientRed()
	{
		return (portal.getAmbientColor() >> 16) & 0xFF;
	}

	private int getAmbientGreen()
	{
		return (portal.getAmbientColor() >> 8) & 0xFF;
	}

	private int getAmbientBlue()
	{
		return portal.getAmbientColor() & 0xFF;
	}

	private void setAmbientRed(int value)
	{
		portal.setAmbientColor((portal.getAmbientColor() & 0x00FFFF) | (clampColorChannel(value) << 16));
	}

	private void setAmbientGreen(int value)
	{
		portal.setAmbientColor((portal.getAmbientColor() & 0xFF00FF) | (clampColorChannel(value) << 8));
	}

	private void setAmbientBlue(int value)
	{
		portal.setAmbientColor((portal.getAmbientColor() & 0xFFFF00) | clampColorChannel(value));
	}

	private static int clampColorChannel(int value)
	{
		if(value < 0)
		{
			return 0;
		}
		if(value > 255)
		{
			return 255;
		}
		return value;
	}

	private String ambientColorHex()
	{
		return String.format(Locale.ROOT, "#%06X", portal.getAmbientColor());
	}

	private String ambientStyleDisplay()
	{
		return LocalPortalText.localized(ambientStyleLabel(portal.getAmbientStyle()));
	}

	private static TextKey ambientStyleLabel(AmbientParticleStyle style)
	{
		return switch(style)
		{
			case SPARKS -> WormholesMessages.PORTAL_LABEL_AMBIENT_STYLE_SPARKS;
			case OUTLINE -> WormholesMessages.PORTAL_LABEL_AMBIENT_STYLE_OUTLINE;
			case CORNERS -> WormholesMessages.PORTAL_LABEL_AMBIENT_STYLE_CORNERS;
			case OFF -> WormholesMessages.PORTAL_LABEL_AMBIENT_STYLE_OFF;
		};
	}

	private static int dyeRgb(DyeColor color)
	{
		Color rgb = color.getColor();
		return (rgb.getRed() << 16) | (rgb.getGreen() << 8) | rgb.getBlue();
	}

	private static DyeColor nearestDye(int rgb)
	{
		int red = (rgb >> 16) & 0xFF;
		int green = (rgb >> 8) & 0xFF;
		int blue = rgb & 0xFF;
		DyeColor best = DyeColor.WHITE;
		long bestDistance = Long.MAX_VALUE;
		for(DyeColor color : DyeColor.values())
		{
			Color candidate = color.getColor();
			long dr = red - candidate.getRed();
			long dg = green - candidate.getGreen();
			long db = blue - candidate.getBlue();
			long distance = (dr * dr) + (dg * dg) + (db * db);
			if(distance < bestDistance)
			{
				bestDistance = distance;
				best = color;
			}
		}
		return best;
	}

	private static Material dyeMaterial(DyeColor color)
	{
		return Material.valueOf(color.name() + "_WOOL");
	}

	private static String dyeDisplayName(DyeColor color)
	{
		String[] parts = color.name().split("_");
		StringBuilder builder = new StringBuilder();
		for(int index = 0; index < parts.length; index++)
		{
			if(index > 0)
			{
				builder.append(' ');
			}
			String part = parts[index];
			builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
			builder.append(part.substring(1).toLowerCase(Locale.ROOT));
		}
		return builder.toString();
	}

	Element surfaceSkinElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("surface-skin");
		element.onLeftClick((e) ->
		{
			if(!portal.hasSurfaceSkin())
			{
				return;
			}
			portal.setSurfaceSkin("");
			applySurfaceSkinElement(element);
			window.updateInventory();
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED,
					LocalPortalText.arguments("label", LocalPortalText.localized(WormholesMessages.PORTAL_NETWORK_LABEL_SURFACE_SKIN),
							"value", surfaceSkinDisplay()));
		});
		element.onRightClick((e) -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
		{
			window.close();
			uiOpenSurfaceSkinMenu(viewer);
		}));
		applySurfaceSkinElement(element);
		return element;
	}

	private void applySurfaceSkinElement(Element element)
	{
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_SURFACE_SKIN,
				LocalPortalText.arguments(
						"skin", surfaceSkinDisplay(),
						"thickness", surfaceThicknessDisplay()));
		element.setMaterial(new MaterialBlock(surfaceSkinIcon()));
		element.setEnchanted(portal.hasSurfaceSkin());
	}

	private void uiOpenSurfaceSkinMenu(Player viewer)
	{
		UIWindow window = new UIWindow(Wormholes.instance, viewer);
		window.setTitle(portal.getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(4);
		window.setDecorator(new UIPaneDecorator(Material.CYAN_STAINED_GLASS_PANE));
		refreshSurfaceSkinMenu(window, viewer);
		window.setElement(0, 3, menus.settings().backToSettingsMenuElement(window, viewer));
		window.setVisible(true);
	}

	private void refreshSurfaceSkinMenu(Window window, Player viewer)
	{
		window.setElement(0, 0, surfaceSkinPlacardElement());
		window.setElement(0, 1, surfaceThicknessElement(window, viewer));
		window.setElement(-1, 2, surfaceSkinGlassElement(window, viewer));
		window.setElement(1, 2, surfaceSkinClearElement(window, viewer));
	}

	private Element surfaceSkinPlacardElement()
	{
		return LocalPortalText.localizedElement("surface-skin-placard", WormholesMessages.PORTAL_MENU_SURFACE_SKIN_PLACARD,
				LocalPortalText.arguments("skin", surfaceSkinDisplay(), "thickness", surfaceThicknessDisplay()), surfaceSkinIcon());
	}

	private Element surfaceThicknessElement(Window window, Player viewer)
	{
		UIElement element = new UIElement("surface-thickness");
		element.onLeftClick((e) -> adjustSurfaceThickness(element, window, viewer, 5));
		element.onRightClick((e) -> adjustSurfaceThickness(element, window, viewer, -5));
		element.onShiftLeftClick((e) -> adjustSurfaceThickness(element, window, viewer, 25));
		element.onShiftRightClick((e) -> adjustSurfaceThickness(element, window, viewer, -25));
		applySurfaceThicknessElement(element);
		return element;
	}

	private void adjustSurfaceThickness(UIElement element, Window window, Player viewer, int delta)
	{
		int previous = portal.getSurfaceThickness();
		portal.setSurfaceThickness(previous + delta);
		int current = portal.getSurfaceThickness();
		applySurfaceThicknessElement(element);
		window.updateInventory();
		if(current != previous)
		{
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED,
					LocalPortalText.arguments("label", LocalPortalText.localized(WormholesMessages.PORTAL_NETWORK_LABEL_SURFACE_THICKNESS), "value", surfaceThicknessDisplay()));
		}
	}

	private void applySurfaceThicknessElement(Element element)
	{
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_SURFACE_THICKNESS,
				LocalPortalText.arguments("value", surfaceThicknessDisplay(), "step", 5, "large_step", 25));
		element.setMaterial(new MaterialBlock(Material.PAPER));
	}

	private Element surfaceSkinGlassElement(Window window, Player viewer)
	{
		UIElement element = LocalPortalText.localizedElement("surface-skin-glass", WormholesMessages.PORTAL_MENU_SURFACE_SKIN_GLASS,
				MessageArgs.empty(), Material.GLASS);
		element.setEnchanted("minecraft:glass".equals(portal.getSurfaceSkin()));
		element.onLeftClick((e) ->
		{
			portal.setSurfaceSkin("minecraft:glass");
			refreshSurfaceSkinMenu(window, viewer);
			window.updateInventory();
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED,
					LocalPortalText.arguments("label", LocalPortalText.localized(WormholesMessages.PORTAL_NETWORK_LABEL_SURFACE_SKIN),
							"value", surfaceSkinDisplay()));
		});
		return element;
	}

	private Element surfaceSkinClearElement(Window window, Player viewer)
	{
		UIElement element = LocalPortalText.localizedElement("surface-skin-clear", WormholesMessages.PORTAL_MENU_SURFACE_SKIN_CLEAR,
				MessageArgs.empty(), Material.BARRIER);
		element.onLeftClick((e) ->
		{
			if(!portal.hasSurfaceSkin())
			{
				return;
			}
			portal.setSurfaceSkin("");
			refreshSurfaceSkinMenu(window, viewer);
			window.updateInventory();
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED,
					LocalPortalText.arguments("label", LocalPortalText.localized(WormholesMessages.PORTAL_NETWORK_LABEL_SURFACE_SKIN),
							"value", surfaceSkinDisplay()));
		});
		return element;
	}

	private String surfaceThicknessDisplay()
	{
		return String.format(Locale.ROOT, "%.2f", portal.getSurfaceThickness() / 100.0);
	}

	private String surfaceSkinDisplay()
	{
		if(!portal.hasSurfaceSkin())
		{
			return LocalPortalText.localized(WormholesMessages.PORTAL_LABEL_SKIN_NONE);
		}
		return portal.getSurfaceSkin();
	}

	private Material surfaceSkinIcon()
	{
		if(!portal.hasSurfaceSkin())
		{
			return Material.GLASS;
		}
		if(PortalSurfaceSkins.isFluid(portal.getSurfaceSkin()))
		{
			return portal.getSurfaceSkin().contains("lava") ? Material.LAVA_BUCKET : Material.WATER_BUCKET;
		}
		Material material = skinMaterial(portal.getSurfaceSkin());
		return material != null && material.isItem() ? material : Material.GLASS;
	}

	private static Material skinMaterial(String skin)
	{
		try
		{
			return Bukkit.createBlockData(skin).getMaterial();
		}
		catch(IllegalArgumentException ex)
		{
			return null;
		}
	}

	boolean applySurfaceSkinFromInteraction(Player player, String skin)
	{
		if(!menus.ensureCanManage(player))
		{
			return false;
		}
		String previous = portal.getSurfaceSkin();
		portal.setSurfaceSkin(skin);
		if(!previous.equals(portal.getSurfaceSkin()))
		{
			menus.text().notifySetting(player, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED,
					LocalPortalText.arguments("label", LocalPortalText.localized(WormholesMessages.PORTAL_NETWORK_LABEL_SURFACE_SKIN),
							"value", surfaceSkinDisplay()));
		}
		return true;
	}

	boolean clearSurfaceSkinFromInteraction(Player player)
	{
		if(!menus.ensureCanManage(player))
		{
			return false;
		}
		if(!portal.hasSurfaceSkin())
		{
			return false;
		}
		portal.setSurfaceSkin("");
		menus.text().notifySetting(player, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED,
				LocalPortalText.arguments("label", LocalPortalText.localized(WormholesMessages.PORTAL_NETWORK_LABEL_SURFACE_SKIN),
						"value", surfaceSkinDisplay()));
		return true;
	}
}

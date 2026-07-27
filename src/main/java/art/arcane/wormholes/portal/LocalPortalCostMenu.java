package art.arcane.wormholes.portal;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import art.arcane.volmlib.integration.VaultEconomy;
import art.arcane.volmlib.util.data.MaterialBlock;
import art.arcane.volmlib.util.inventorygui.Element;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.service.WormholesAudience;

final class LocalPortalCostMenu
{
	private final LocalPortal portal;
	private final LocalPortalMenus menus;

	LocalPortalCostMenu(LocalPortal portal, LocalPortalMenus menus)
	{
		this.portal = portal;
		this.menus = menus;
	}

	void open(Player viewer)
	{
		if(!menus.ensureCanManage(viewer))
		{
			return;
		}
		UIWindow window = new UIWindow(Wormholes.instance, viewer);
		window.setTitle(portal.getRouter(true));
		window.setResolution(WindowResolution.W9_H6);
		window.setViewportHeight(4);
		window.setDecorator(new UIPaneDecorator(Material.BROWN_STAINED_GLASS_PANE));
		refresh(window, viewer);
		window.setVisible(true);
	}

	private void refresh(Window window, Player viewer)
	{
		window.setElement(0, 0, placardElement());
		window.setElement(-2, 1, freeModeElement(window, viewer));
		window.setElement(0, 1, vanillaModeElement(window, viewer));
		window.setElement(2, 1, vaultModeElement(window, viewer));
		window.setElement(-1, 2, detailPrimaryElement(window, viewer));
		window.setElement(1, 2, detailSecondaryElement(window, viewer));
		window.setElement(0, 3, menus.settings().backToSettingsMenuElement(window, viewer));
	}

	private Element placardElement()
	{
		return LocalPortalText.localizedElement("travel-cost-placard", WormholesMessages.PORTAL_MENU_COST_PLACARD,
				LocalPortalText.arguments("mode", modeLabel(), "cost", costSummary()), Material.CHEST);
	}

	private Element freeModeElement(Window window, Player viewer)
	{
		PortalTravelCost cost = portal.getTravelCost();
		UIElement element = LocalPortalText.localizedElement("travel-cost-free", WormholesMessages.PORTAL_MENU_COST_MODE_FREE,
				LocalPortalText.arguments(), Material.FEATHER);
		element.setEnchanted(cost == null);
		element.onLeftClick((event) ->
		{
			if(portal.getTravelCost() == null)
			{
				return;
			}
			portal.clearTravelCost();
			refresh(window, viewer);
			window.updateInventory();
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_COST_CLEARED);
		});
		return element;
	}

	private Element vanillaModeElement(Window window, Player viewer)
	{
		PortalTravelCost cost = portal.getTravelCost();
		UIElement element = LocalPortalText.localizedElement("travel-cost-vanilla",
				WormholesMessages.PORTAL_MENU_COST_MODE_VANILLA, LocalPortalText.arguments(),
				cost instanceof VanillaTravelCost vanilla ? vanilla.getMaterial() : Material.HOPPER);
		element.setEnchanted(cost instanceof VanillaTravelCost);
		if(cost instanceof VanillaTravelCost vanilla)
		{
			element.setBaseItemStack(vanilla.getTemplate());
		}
		element.onLeftClick((event) -> beginItemCapture(window, viewer));
		return element;
	}

	private Element vaultModeElement(Window window, Player viewer)
	{
		PortalTravelCost cost = portal.getTravelCost();
		VaultEconomy economy = Wormholes.vaultEconomy;
		boolean available = economy != null && economy.isAvailable();
		UIElement element = LocalPortalText.localizedElement("travel-cost-vault",
				available ? WormholesMessages.PORTAL_MENU_COST_MODE_VAULT : WormholesMessages.PORTAL_MENU_COST_MODE_VAULT_UNAVAILABLE,
				LocalPortalText.arguments(), available ? Material.EMERALD : Material.REDSTONE);
		element.setEnchanted(cost instanceof VaultTravelCost);
		element.onLeftClick((event) ->
		{
			if(!available)
			{
				menus.text().notifySetting(viewer, WormholesMessages.PORTAL_COST_VAULT_UNAVAILABLE_NOTICE);
				return;
			}
			beginVaultAmountInput(window, viewer);
		});
		return element;
	}

	private Element detailPrimaryElement(Window window, Player viewer)
	{
		PortalTravelCost cost = portal.getTravelCost();
		if(cost instanceof VanillaTravelCost vanilla)
		{
			UIElement element = new UIElement("travel-cost-item");
			Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_COST_ITEM,
					LocalPortalText.arguments("item", vanilla.getItemLabel()));
			element.setMaterial(new MaterialBlock(vanilla.getMaterial()));
			element.setBaseItemStack(vanilla.getTemplate());
			element.onLeftClick((event) -> beginItemCapture(window, viewer));
			element.onRightClick((event) -> clearCost(window, viewer));
			return element;
		}
		if(cost instanceof VaultTravelCost vault)
		{
			UIElement element = LocalPortalText.localizedElement("travel-cost-vault-amount",
					WormholesMessages.PORTAL_MENU_COST_VAULT_AMOUNT,
					LocalPortalText.arguments("amount", vault.getFormattedAmount()), Material.GOLD_INGOT);
			element.onLeftClick((event) -> beginVaultAmountInput(window, viewer));
			element.onRightClick((event) -> clearCost(window, viewer));
			return element;
		}
		return LocalPortalText.localizedElement("travel-cost-free-detail", WormholesMessages.PORTAL_MENU_COST_FREE_DETAIL,
				LocalPortalText.arguments(), Material.WHITE_STAINED_GLASS_PANE);
	}

	private Element detailSecondaryElement(Window window, Player viewer)
	{
		PortalTravelCost cost = portal.getTravelCost();
		if(cost instanceof VanillaTravelCost vanilla)
		{
			UIElement element = new UIElement("travel-cost-quantity");
			applyQuantityElement(element, vanilla);
			element.onLeftClick((event) -> adjustQuantity(element, window, viewer, 1));
			element.onRightClick((event) -> adjustQuantity(element, window, viewer, -1));
			element.onShiftLeftClick((event) -> adjustQuantity(element, window, viewer, 8));
			element.onShiftRightClick((event) -> adjustQuantity(element, window, viewer, -8));
			return element;
		}
		return LocalPortalText.localizedElement("travel-cost-secondary-empty", WormholesMessages.PORTAL_MENU_COST_SECONDARY_EMPTY,
				LocalPortalText.arguments(), Material.BROWN_STAINED_GLASS_PANE);
	}

	private void beginItemCapture(Window window, Player viewer)
	{
		VanillaTravelCostCapture capture = Wormholes.vanillaTravelCostCapture;
		if(capture == null)
		{
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_COST_ITEM_INVALID);
			return;
		}
		window.close();
		viewer.closeInventory();
		capture.begin(viewer, portal, this::open);
		WormholesAudience.sendMessage(viewer, Wormholes.text().component(WormholesMessages.PORTAL_PROMPT_COST_ITEM));
	}

	private void beginVaultAmountInput(Window window, Player viewer)
	{
		window.close();
		viewer.closeInventory();
		WormholesAudience.sendMessage(viewer, Wormholes.text().component(
				WormholesMessages.PORTAL_PROMPT_COST_VAULT,
				LocalPortalText.arguments("cancel", LocalPortalText.localized(WormholesMessages.PORTAL_INPUT_CANCEL))));
		Wormholes.awaitChatInput(viewer, (input) -> applyVaultAmount(viewer, input));
	}

	private void applyVaultAmount(Player viewer, String input)
	{
		if(input.equalsIgnoreCase(LocalPortalText.localized(WormholesMessages.PORTAL_INPUT_CANCEL)))
		{
			open(viewer);
			return;
		}
		try
		{
			portal.setVaultTravelCost(input.trim());
			VaultTravelCost cost = (VaultTravelCost) portal.getTravelCost();
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_COST_VAULT_CHANGED,
					LocalPortalText.arguments("amount", cost.getFormattedAmount()));
		}
		catch(IllegalArgumentException exception)
		{
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_COST_VAULT_INVALID,
					LocalPortalText.arguments("maximum", VaultTravelCost.MAX_AMOUNT.toPlainString()));
		}
		open(viewer);
	}

	private void clearCost(Window window, Player viewer)
	{
		portal.clearTravelCost();
		refresh(window, viewer);
		window.updateInventory();
		menus.text().notifySetting(viewer, WormholesMessages.PORTAL_COST_CLEARED);
	}

	private void adjustQuantity(UIElement element, Window window, Player viewer, int delta)
	{
		if(!(portal.getTravelCost() instanceof VanillaTravelCost previous))
		{
			return;
		}
		portal.setVanillaTravelCostQuantity(previous.getQuantity() + delta);
		VanillaTravelCost current = (VanillaTravelCost) portal.getTravelCost();
		applyQuantityElement(element, current);
		window.setElement(0, 0, placardElement());
		window.updateInventory();
		if(current.getQuantity() != previous.getQuantity())
		{
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_COST_QUANTITY_CHANGED,
					LocalPortalText.arguments("quantity", current.getQuantity()));
		}
	}

	private void applyQuantityElement(Element element, VanillaTravelCost cost)
	{
		Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_COST_QUANTITY,
				LocalPortalText.arguments("quantity", cost.getQuantity(), "maximum", VanillaTravelCost.MAX_QUANTITY));
		element.setMaterial(new MaterialBlock(Material.CHEST));
		element.setCount(Math.min(cost.getQuantity(), 64));
	}

	private String modeLabel()
	{
		PortalTravelCost cost = portal.getTravelCost();
		if(cost == null)
		{
			return LocalPortalText.localized(WormholesMessages.PORTAL_LABEL_COST_FREE);
		}
		return LocalPortalText.localized(cost.getType() == PortalTravelCost.Type.VANILLA
				? WormholesMessages.PORTAL_LABEL_COST_VANILLA
				: WormholesMessages.PORTAL_LABEL_COST_VAULT);
	}

	private String costSummary()
	{
		PortalTravelCost cost = portal.getTravelCost();
		if(cost instanceof VanillaTravelCost vanilla)
		{
			return vanilla.getQuantity() + "x " + vanilla.getItemLabel();
		}
		if(cost instanceof VaultTravelCost vault)
		{
			return vault.getFormattedAmount();
		}
		return LocalPortalText.localized(WormholesMessages.PORTAL_LABEL_COST_FREE);
	}
}

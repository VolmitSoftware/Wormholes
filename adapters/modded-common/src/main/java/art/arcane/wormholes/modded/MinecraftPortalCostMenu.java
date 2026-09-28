package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.ExactItemPayment;
import art.arcane.wormholes.portal.PortalAccessPolicy;
import art.arcane.wormholes.portal.TravelCurrencyAmount;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class MinecraftPortalCostMenu {
    private static final String VANILLA = "VANILLA";
    private static final String VAULT = "VAULT";

    private final WormholesModRuntime runtime;
    private final MinecraftPortalMenus menus;
    private final Map<UUID, UUID> captures = new HashMap<>();

    MinecraftPortalCostMenu(WormholesModRuntime runtime, MinecraftPortalMenus menus) {
        this.runtime = Objects.requireNonNull(runtime);
        this.menus = Objects.requireNonNull(menus);
    }

    void open(ServerPlayer viewer, MinecraftPortal portal) {
        if (!menus.ensureCanManage(viewer, portal)) {
            return;
        }
        MinecraftWindow window = menus.window(viewer, portal);
        window.setViewportHeight(4);
        window.setDecorator(Items.STAINED_GLASS_PANE.brown());
        refresh(window, viewer, portal);
        window.open();
    }

    boolean captureDroppedItem(ServerPlayer player) {
        runtime.requireServerThread();
        UUID portalId = captures.remove(player.getUUID());
        if (portalId == null) {
            return false;
        }
        MinecraftPortal portal = runtime.portals().get(portalId);
        boolean administrator = runtime.access().administrator(player);
        if (portal == null || !PortalAccessPolicy.canManage(portal.getId(), portal.getOwner(), player.getUUID(), administrator)) {
            MinecraftMenuText.notice(player, MinecraftMenuText.text(player, WormholesMessages.PORTAL_EDIT_DENIED, MessageArgs.empty()));
            return true;
        }
        ItemStack selected = player.getMainHandItem();
        if (selected.isEmpty()) {
            MinecraftMenuText.notice(player, MinecraftMenuText.text(player, WormholesMessages.PORTAL_COST_ITEM_INVALID, MessageArgs.empty()));
        } else {
            String encoded = MinecraftItemEncoding.encode(selected.copyWithCount(1), runtime.server().registryAccess());
            menus.update(player, portal, target -> target.setItemTravelCost(encoded));
            MinecraftMenuText.notice(player, MinecraftMenuText.text(player, WormholesMessages.PORTAL_COST_ITEM_SET,
                MinecraftPortalText.arguments("item", itemLabel(selected))));
        }
        open(player, portal);
        return true;
    }

    void disconnected(ServerPlayer player) {
        captures.remove(player.getUUID());
    }

    void close() {
        captures.clear();
    }

    private void refresh(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        window.setElement(0, 0, placardElement(viewer, portal));
        window.setElement(-2, 1, freeModeElement(window, viewer, portal));
        window.setElement(0, 1, vanillaModeElement(window, viewer, portal));
        window.setElement(2, 1, vaultModeElement(window, viewer, portal));
        window.setElement(-1, 2, detailPrimaryElement(window, viewer, portal));
        window.setElement(1, 2, detailSecondaryElement(window, viewer, portal));
        window.setElement(0, 3, menus.settings().backToSettingsMenuElement(window, viewer, portal));
    }

    private MinecraftElement placardElement(ServerPlayer viewer, MinecraftPortal portal) {
        Map<?, ?> price = price(portal);
        return MinecraftPortalText.localizedElement(viewer, "travel-cost-placard", WormholesMessages.PORTAL_MENU_COST_PLACARD,
            MinecraftPortalText.arguments("mode", modeLabel(viewer, price), "cost", costSummary(viewer, price)), Items.CHEST);
    }

    private MinecraftElement freeModeElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "travel-cost-free", WormholesMessages.PORTAL_MENU_COST_MODE_FREE,
            MinecraftPortalText.arguments(), Items.FEATHER);
        element.setEnchanted(price(portal).isEmpty());
        element.onLeftClick(clicked -> {
            if (price(portal).isEmpty()) {
                return;
            }
            menus.update(viewer, portal, MinecraftPortal::clearTravelCost);
            refresh(window, viewer, portal);
            window.updateInventory();
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_COST_CLEARED);
        });
        return element;
    }

    private MinecraftElement vanillaModeElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        Map<?, ?> price = price(portal);
        boolean vanilla = vanilla(price);
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "travel-cost-vanilla",
            WormholesMessages.PORTAL_MENU_COST_MODE_VANILLA, MinecraftPortalText.arguments(),
            vanilla ? template(price).getItem() : Items.HOPPER);
        element.setEnchanted(vanilla);
        if (vanilla) {
            element.setBaseItemStack(template(price));
        }
        element.onLeftClick(clicked -> beginItemCapture(window, viewer, portal));
        return element;
    }

    private MinecraftElement vaultModeElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        boolean available = runtime.costs().currencyAvailable();
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "travel-cost-vault",
            available ? WormholesMessages.PORTAL_MENU_COST_MODE_VAULT : WormholesMessages.PORTAL_MENU_COST_MODE_VAULT_UNAVAILABLE,
            MinecraftPortalText.arguments(), available ? Items.EMERALD : Items.REDSTONE);
        element.setEnchanted(vault(price(portal)));
        element.onLeftClick(clicked -> {
            if (!available) {
                MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_COST_VAULT_UNAVAILABLE_NOTICE);
                return;
            }
            beginVaultAmountInput(window, viewer, portal);
        });
        return element;
    }

    private MinecraftElement detailPrimaryElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        Map<?, ?> price = price(portal);
        if (vanilla(price)) {
            ItemStack template = template(price);
            MinecraftElement element = new MinecraftElement("travel-cost-item");
            MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_COST_ITEM,
                MinecraftPortalText.arguments("item", itemLabel(template)));
            element.setMaterial(template.getItem());
            element.setBaseItemStack(template);
            element.onLeftClick(clicked -> beginItemCapture(window, viewer, portal));
            element.onRightClick(clicked -> clearCost(window, viewer, portal));
            return element;
        }
        if (vault(price)) {
            MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "travel-cost-vault-amount",
                WormholesMessages.PORTAL_MENU_COST_VAULT_AMOUNT, MinecraftPortalText.arguments("amount", formattedAmount(price)), Items.GOLD_INGOT);
            element.onLeftClick(clicked -> beginVaultAmountInput(window, viewer, portal));
            element.onRightClick(clicked -> clearCost(window, viewer, portal));
            return element;
        }
        return MinecraftPortalText.localizedElement(viewer, "travel-cost-free-detail", WormholesMessages.PORTAL_MENU_COST_FREE_DETAIL,
            MinecraftPortalText.arguments(), Items.STAINED_GLASS_PANE.white());
    }

    private MinecraftElement detailSecondaryElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        Map<?, ?> price = price(portal);
        if (vanilla(price)) {
            MinecraftElement element = new MinecraftElement("travel-cost-quantity");
            applyQuantityElement(viewer, element, quantity(price));
            element.onLeftClick(clicked -> adjustQuantity(element, window, viewer, portal, 1));
            element.onRightClick(clicked -> adjustQuantity(element, window, viewer, portal, -1));
            element.onShiftLeftClick(clicked -> adjustQuantity(element, window, viewer, portal, 8));
            element.onShiftRightClick(clicked -> adjustQuantity(element, window, viewer, portal, -8));
            return element;
        }
        return MinecraftPortalText.localizedElement(viewer, "travel-cost-secondary-empty", WormholesMessages.PORTAL_MENU_COST_SECONDARY_EMPTY,
            MinecraftPortalText.arguments(), Items.STAINED_GLASS_PANE.brown());
    }

    private void beginItemCapture(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        window.close();
        viewer.closeContainer();
        captures.put(viewer.getUUID(), portal.getId());
        viewer.sendSystemMessage(MinecraftMenuText.text(viewer, WormholesMessages.PORTAL_PROMPT_COST_ITEM, MessageArgs.empty()));
    }

    private void beginVaultAmountInput(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        window.close();
        viewer.closeContainer();
        viewer.sendSystemMessage(MinecraftMenuText.text(viewer, WormholesMessages.PORTAL_PROMPT_COST_VAULT,
            MinecraftPortalText.arguments("cancel", MinecraftPortalText.localized(viewer, WormholesMessages.PORTAL_INPUT_CANCEL))));
        runtime.chatInput().await(viewer, input -> applyVaultAmount(viewer, portal, input));
    }

    private void applyVaultAmount(ServerPlayer viewer, MinecraftPortal portal, String input) {
        if (MinecraftPortalText.isCancelInput(viewer, input)) {
            open(viewer, portal);
            return;
        }
        try {
            if (menus.update(viewer, portal, target -> target.setCurrencyTravelCost(input.trim()))) {
                MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_COST_VAULT_CHANGED,
                    MinecraftPortalText.arguments("amount", formattedAmount(price(portal))));
            }
        } catch (IllegalArgumentException exception) {
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_COST_VAULT_INVALID,
                MinecraftPortalText.arguments("maximum", TravelCurrencyAmount.MAX_AMOUNT.toPlainString()));
        }
        open(viewer, portal);
    }

    private void clearCost(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        menus.update(viewer, portal, MinecraftPortal::clearTravelCost);
        refresh(window, viewer, portal);
        window.updateInventory();
        MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_COST_CLEARED);
    }

    private void adjustQuantity(MinecraftElement element, MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal, int delta) {
        Map<?, ?> previousPrice = price(portal);
        if (!vanilla(previousPrice)) {
            return;
        }
        int previous = quantity(previousPrice);
        menus.update(viewer, portal, target -> target.setTravelCostQuantity(previous + delta));
        int current = quantity(price(portal));
        applyQuantityElement(viewer, element, current);
        window.setElement(0, 0, placardElement(viewer, portal));
        window.updateInventory();
        if (current != previous) {
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_COST_QUANTITY_CHANGED,
                MinecraftPortalText.arguments("quantity", current));
        }
    }

    private void applyQuantityElement(ServerPlayer viewer, MinecraftElement element, int quantity) {
        MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_COST_QUANTITY,
            MinecraftPortalText.arguments("quantity", quantity, "maximum", ExactItemPayment.MAX_QUANTITY));
        element.setMaterial(Items.CHEST);
        element.setCount(Math.min(quantity, 64));
    }

    String modeLabel(ServerPlayer viewer, Map<?, ?> price) {
        if (price.isEmpty()) {
            return MinecraftPortalText.localized(viewer, WormholesMessages.PORTAL_LABEL_COST_FREE);
        }
        return MinecraftPortalText.localized(viewer, vanilla(price)
            ? WormholesMessages.PORTAL_LABEL_COST_VANILLA
            : WormholesMessages.PORTAL_LABEL_COST_VAULT);
    }

    String costSummary(ServerPlayer viewer, Map<?, ?> price) {
        if (vanilla(price)) {
            return quantity(price) + "x " + itemLabel(template(price));
        }
        if (vault(price)) {
            return formattedAmount(price);
        }
        return MinecraftPortalText.localized(viewer, WormholesMessages.PORTAL_LABEL_COST_FREE);
    }

    ItemStack template(Map<?, ?> price) {
        return MinecraftItemEncoding.decode(Objects.toString(price.get("item"), ""), runtime.server().registryAccess());
    }

    static Map<?, ?> price(MinecraftPortal portal) {
        return portal.setting("travelCost") instanceof Map<?, ?> price ? price : Map.of();
    }

    static boolean vanilla(Map<?, ?> price) {
        return VANILLA.equals(price.get("type"));
    }

    static boolean vault(Map<?, ?> price) {
        return VAULT.equals(price.get("type"));
    }

    private static int quantity(Map<?, ?> price) {
        return ExactItemPayment.clampQuantity(price.get("quantity") instanceof Number number ? number.intValue() : 1);
    }

    private static String formattedAmount(Map<?, ?> price) {
        return Objects.toString(price.get("amount"), "");
    }

    private static String itemLabel(ItemStack template) {
        String[] words = BuiltInRegistries.ITEM.getKey(template.getItem()).getPath().toLowerCase(Locale.ROOT).split("_");
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }
}

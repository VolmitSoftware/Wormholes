package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.localization.WormholesMessages;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class MinecraftPortalExtensionsMenu {
    private static final int COLUMNS = 9;
    private static final int MAX_ROWS = 4;

    private final MinecraftPortalMenus menus;
    private final List<MinecraftPortalMenuEntry> entries;

    MinecraftPortalExtensionsMenu(MinecraftPortalMenus menus, List<MinecraftPortalMenuEntry> entries) {
        this.menus = Objects.requireNonNull(menus);
        this.entries = List.copyOf(entries);
    }

    boolean hasEntries() {
        return !entries.isEmpty();
    }

    List<MinecraftPortalMenuEntry> visibleEntries(MinecraftPortal portal, ServerPlayer viewer) {
        List<MinecraftPortalMenuEntry> visible = new ArrayList<>(entries.size());
        for (MinecraftPortalMenuEntry entry : entries) {
            if (entry.visible(portal, viewer)) {
                visible.add(entry);
            }
        }
        return visible;
    }

    void open(ServerPlayer viewer, MinecraftPortal portal) {
        if (!menus.ensureCanManage(viewer, portal)) {
            return;
        }
        List<MinecraftPortalMenuEntry> visible = visibleEntries(portal, viewer);
        int rows = Math.max(1, Math.min(MAX_ROWS, (visible.size() + COLUMNS - 1) / COLUMNS));
        MinecraftWindow window = menus.window(viewer, portal);
        window.setViewportHeight(rows + 2);
        window.setDecorator(Items.STAINED_GLASS_PANE.cyan());
        window.setElement(0, 0, MinecraftPortalText.localizedElement(viewer, "extensions-placard", WormholesMessages.PORTAL_MENU_EXTENSIONS,
            MessageArgs.empty(), Items.COMPARATOR));
        for (int index = 0; index < visible.size() && index < COLUMNS * MAX_ROWS; index++) {
            int row = 1 + (index / COLUMNS);
            int column = (index % COLUMNS) - 4;
            window.setElement(column, row, element(visible.get(index), viewer, window, portal));
        }
        window.setElement(0, rows + 1, menus.settings().backToSettingsMenuElement(window, viewer, portal));
        window.open();
    }

    MinecraftElement openerElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "extensions", WormholesMessages.PORTAL_MENU_EXTENSIONS,
            MessageArgs.empty(), Items.COMPARATOR);
        element.onLeftClick(clicked -> {
            window.close();
            open(viewer, portal);
        });
        return element;
    }

    private MinecraftElement element(MinecraftPortalMenuEntry entry, ServerPlayer viewer, MinecraftWindow window, MinecraftPortal portal) {
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, entry.id(), entry.label(), entry.arguments(portal, viewer), entry.icon());
        element.setEnchanted(entry.enchanted(portal, viewer));
        element.onLeftClick(clicked -> entry.onLeftClick(portal, viewer, window));
        element.onRightClick(clicked -> entry.onRightClick(portal, viewer, window));
        element.onShiftLeftClick(clicked -> entry.onShiftLeftClick(portal, viewer, window));
        return element;
    }
}

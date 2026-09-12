package art.arcane.wormholes.render;

import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import art.arcane.volmlib.util.inventorygui.Element;
import art.arcane.volmlib.util.data.MaterialBlock;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.FidelityMessages;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.render.bedrock.ClientProfileService;

/**
 * Per-portal fidelity menu: cyclers for the atmosphere mode, acoustics profile and LOD profile, a
 * block-entity toggle, and a way back to the portal menu. Every field can be reset to the server
 * default with a shift-click; changes save the portal and replicate through the settings bag.
 */
public final class FidelityMenu {
    private final LocalPortal portal;

    public FidelityMenu(LocalPortal portal) {
        this.portal = portal;
    }

    public void open(Player viewer) {
        FidelityPortalExtension fidelity = portal.extension(FidelityPortalExtension.class);
        if (fidelity == null) {
            return;
        }
        UIWindow window = new UIWindow(Wormholes.instance, viewer);
        window.setTitle(portal.getRouter(true));
        window.setResolution(WindowResolution.W9_H6);
        window.setViewportHeight(3);
        window.setDecorator(new UIPaneDecorator(Material.CYAN_STAINED_GLASS_PANE));
        window.setElement(0, 0, placard(viewer));
        window.setElement(-3, 1, cycler("fidelity-atmosphere", Material.LIGHT_BLUE_STAINED_GLASS, FidelityMessages.MENU_ATMOSPHERE, "mode",
            () -> fidelity.atmosphereMode() == null ? null : fidelity.atmosphereMode().configName(),
            () -> fidelity.setAtmosphereMode(fidelity.effectiveAtmosphereMode().next()),
            () -> fidelity.setAtmosphereMode(null), viewer));
        window.setElement(-1, 1, cycler("fidelity-acoustics", Material.NOTE_BLOCK, FidelityMessages.MENU_ACOUSTICS, "mode",
            () -> fidelity.acousticsProfile() == null ? null : fidelity.acousticsProfile().configName(),
            () -> fidelity.setAcousticsProfile(fidelity.effectiveAcousticsProfile().next()),
            () -> fidelity.setAcousticsProfile(null), viewer));
        window.setElement(1, 1, cycler("fidelity-lod", Material.SPYGLASS, FidelityMessages.MENU_LOD, "mode",
            () -> fidelity.lodProfile() == null ? null : fidelity.lodProfile().configName(),
            () -> fidelity.setLodProfile(fidelity.effectiveLodProfile().next()),
            () -> fidelity.setLodProfile(null), viewer));
        window.setElement(3, 1, cycler("fidelity-block-entities", Material.OAK_SIGN, FidelityMessages.MENU_BLOCK_ENTITIES, "state",
            () -> fidelity.blockEntities() == null ? null : fidelity.blockEntities().toString(),
            () -> fidelity.setBlockEntities(Boolean.valueOf(!fidelity.effectiveBlockEntities())),
            () -> fidelity.setBlockEntities(null), viewer));
        window.setElement(0, 2, back(viewer));
        window.setVisible(true);
    }

    private Element placard(Player viewer) {
        UIElement element = element("fidelity-placard", FidelityMessages.MENU_PLACARD, MessageArgs.empty(), Material.COMPARATOR);
        int bedrockViewers = ClientProfileService.bedrockViewerCount();
        if (bedrockViewers > 0) {
            element.addLore(Wormholes.text().legacy(FidelityMessages.BEDROCK_PROFILE, args("count", Integer.valueOf(bedrockViewers))));
        }
        return element;
    }

    private Element cycler(String id,
                           Material icon,
                           TextKey name,
                           String placeholder,
                           Supplier<String> current,
                           Runnable cycle,
                           Runnable reset,
                           Player viewer) {
        UIElement element = element(id, FidelityMessages.MENU_HINT, MessageArgs.empty(), icon);
        String value = current.get();
        element.setName(Wormholes.text().legacy(name,
            args(placeholder, value == null ? Wormholes.text().plain(FidelityMessages.MENU_DEFAULT) : value)));
        element.setEnchanted(value != null);
        Consumer<Runnable> apply = change -> {
            change.run();
            persist();
            reopen(viewer);
        };
        element.onLeftClick(event -> apply.accept(cycle));
        element.onShiftLeftClick(event -> apply.accept(reset));
        return element;
    }

    private Element back(Player viewer) {
        UIElement element = element("fidelity-back", FidelityMessages.MENU_BACK, MessageArgs.empty(), Material.ARROW);
        element.onLeftClick(event -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> portal.uiOpenPortalMenu(viewer)));
        return element;
    }

    private void persist() {
        portal.save();
        if (Wormholes.portalSyncService != null) {
            Wormholes.portalSyncService.broadcastSettings(portal);
        }
        if (Wormholes.projectionManager != null) {
            Wormholes.projectionManager.plateCache().invalidatePortal(portal.getId());
        }
    }

    private void reopen(Player viewer) {
        FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> open(viewer));
    }

    private static UIElement element(String id, LinesKey key, MessageArgs arguments, Material material) {
        UIElement element = new UIElement(id);
        element.setMaterial(new MaterialBlock(material));
        Wormholes.text().apply(element, key, arguments);
        return element;
    }

    private static MessageArgs args(String name, Object value) {
        return WormholesLocalization.args(MessageArgument.untrusted(name, value));
    }
}

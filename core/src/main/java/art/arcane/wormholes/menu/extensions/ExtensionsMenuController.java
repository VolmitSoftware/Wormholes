package art.arcane.wormholes.menu.extensions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.localization.MenuMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.LabelElement;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuBindings;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuContext;
import art.arcane.wormholes.menu.MenuController;
import art.arcane.wormholes.menu.MenuDecision;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuElementCommon;
import art.arcane.wormholes.menu.MenuGuard;
import art.arcane.wormholes.menu.MenuGuards;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuLines;
import art.arcane.wormholes.menu.MenuNotices;
import art.arcane.wormholes.menu.MenuResult;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSideAction;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuStrings;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.NavigateMode;
import art.arcane.wormholes.menu.portal.MenuExtensionEntry;
import art.arcane.wormholes.menu.portal.PortalMenuHost;
import art.arcane.wormholes.menu.portal.PortalMenuText;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.menu.portal.PortalRoutes;

public final class ExtensionsMenuController implements MenuController {
    public static final Map<LinesKey, Integer> CHEST_HINTS = Map.of(
        WormholesMessages.PORTAL_MENU_EXTENSIONS, 1,
        WormholesMessages.PORTAL_MENU_BACK_SETTINGS, 0);

    private static final int COLUMNS = 9;
    private static final int MAX_ENTRY_ROWS = 4;
    private static final MenuIcon SECONDARY_ICON = MenuIcon.sprite("small/settings");
    private static final MenuIcon TERTIARY_ICON = MenuIcon.sprite("small/chevron_right");
    private static final Set<MenuInteraction> ROUTED = Set.of(MenuInteraction.PRIMARY);
    private static final Set<MenuInteraction> HOOKED = Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY);

    private final PortalMenuHost portals;
    private final ExtensionsMenuHost extensions;

    public ExtensionsMenuController(PortalMenuHost portals, ExtensionsMenuHost extensions) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.extensions = Objects.requireNonNull(extensions, "extensions");
    }

    @Override
    public String id() {
        return PortalRoutes.EXTENSIONS_CONTROLLER;
    }

    @Override
    public MenuDecision routeGuard(MenuContext context) {
        UUID portalId = PortalRoutes.portalId(context.route());
        if (portals.portal(portalId).isEmpty() || !portals.canManage(context.viewer(), portalId)) {
            return MenuDecision.denyAndClose(MenuNotices.hud(context.viewer(), WormholesMessages.PORTAL_EDIT_DENIED));
        }
        return MenuDecision.allow();
    }

    @Override
    public MenuBuild build(MenuContext context) {
        UUID portalId = PortalRoutes.portalId(context.route());
        PortalMenuView portal = portals.portal(portalId).orElseThrow(() -> new IllegalStateException("portal " + portalId + " is gone"));
        MenuStrings strings = context.strings();
        MenuGuard manage = MenuGuards.manage(portals, portalId);
        List<MenuExtensionEntry> visible = visibleEntries(context.viewer(), portalId);
        int entryRows = Math.max(1, Math.min(MAX_ENTRY_ROWS, (visible.size() + COLUMNS - 1) / COLUMNS));
        int shown = Math.min(visible.size(), COLUMNS * MAX_ENTRY_ROWS);
        List<MenuElement> elements = new ArrayList<>(shown + 2);
        MenuBindings.Builder bindings = MenuBindings.builder();

        elements.add(new LabelElement(MenuElementCommon.builder("extensions-placard")
            .lines(strings.lines(WormholesMessages.PORTAL_MENU_EXTENSIONS, MessageArgs.empty(), CHEST_HINTS.get(WormholesMessages.PORTAL_MENU_EXTENSIONS)))
            .icon(MenuIcon.item("minecraft:comparator"))
            .chestSlot(new MenuSlot(0, 0))
            .build()));
        for (int index = 0; index < shown; index++) {
            MenuExtensionEntry entry = visible.get(index);
            elements.add(entryElement(context.viewer(), strings, portalId, entry, new MenuSlot(index % COLUMNS - COLUMNS / 2, 1 + index / COLUMNS)));
            bindings.guard(entry.id(), manage);
            for (MenuInteraction interaction : entry.routed() ? ROUTED : HOOKED) {
                bindings.on(entry.id(), interaction, (ctx, action) -> open(ctx, portalId, entry, action));
            }
        }
        elements.add(new ButtonElement(MenuElementCommon.builder("back-to-settings")
            .lines(strings.lines(WormholesMessages.PORTAL_MENU_BACK_SETTINGS, MessageArgs.empty(), CHEST_HINTS.get(WormholesMessages.PORTAL_MENU_BACK_SETTINGS)))
            .icon(MenuIcon.item("minecraft:arrow"))
            .interactions(ROUTED)
            .chestSlot(new MenuSlot(0, entryRows + 1))
            .nativeHidden(true)
            .build()));
        bindings.guard("back-to-settings", manage)
            .on("back-to-settings", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.navigate(PortalRoutes.settings(portalId),
                NavigateMode.POP_TO, null));

        MenuScreen screen = MenuScreen.builder(PortalRoutes.EXTENSIONS_MAIN)
            .title(strings.text(MenuMessages.TITLE_EXTENSIONS))
            .context(strings.text(MenuMessages.CONTEXT_PORTAL, MessageArgs.builder()
                .untrusted("portal", portal.name())
                .untrusted("type", PortalMenuText.currentMode(strings, portal))
                .build()))
            .elements(elements)
            .build();
        return new MenuBuild(screen, new ChestLayout(portal.routerTitle(), entryRows + 2, ChestPane.CYAN, null), bindings.build());
    }

    @Override
    public boolean touches(MenuRoute route, MenuScope scope) {
        return scope instanceof MenuScope.Portal portal && portal.portalId().equals(PortalRoutes.portalId(route));
    }

    private List<MenuExtensionEntry> visibleEntries(MenuViewer viewer, UUID portalId) {
        List<MenuExtensionEntry> entries = extensions.entries();
        List<MenuExtensionEntry> visible = new ArrayList<>(entries.size());
        for (MenuExtensionEntry entry : entries) {
            if (entry.visible().test(viewer, portalId)) {
                visible.add(entry);
            }
        }
        return visible;
    }

    private MenuResult open(MenuContext context, UUID portalId, MenuExtensionEntry entry, MenuAction action) {
        if (entry.routed()) {
            return MenuResult.push(entry.route().apply(portalId));
        }
        MenuViewer viewer = context.viewer();
        return MenuResult.closeThen(() -> extensions.activate(viewer, portalId, entry.id(), action.interaction())
            ? null
            : MenuNotices.hud(viewer, MenuMessages.FEEDBACK_UNAVAILABLE));
    }

    private static ButtonElement entryElement(MenuViewer viewer, MenuStrings strings, UUID portalId, MenuExtensionEntry entry, MenuSlot slot) {
        MenuLines lines = strings.lines(entry.label(), entry.arguments().apply(portalId), entry.chestHintLines());
        return new ButtonElement(MenuElementCommon.builder(entry.id())
            .lines(lines)
            .icon(MenuIcon.item(entry.chestItemId(), 1, entry.enchanted().test(viewer, portalId)))
            .nativeIcon(MenuIcon.sprite(entry.nativeSprite()))
            .interactions(entry.routed() ? ROUTED : HOOKED)
            .sideActions(entry.routed() ? List.of() : hookSideActions(MenuText.stripColors(lines.label())))
            .chestSlot(slot)
            .build());
    }

    private static List<MenuSideAction> hookSideActions(String title) {
        return List.of(new MenuSideAction(MenuInteraction.SECONDARY, title, SECONDARY_ICON),
            new MenuSideAction(MenuInteraction.TERTIARY, title, TERTIARY_ICON));
    }
}

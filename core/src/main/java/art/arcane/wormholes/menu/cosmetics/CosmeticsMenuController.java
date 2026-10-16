package art.arcane.wormholes.menu.cosmetics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.MenuMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.LabelElement;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuBindings;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuContext;
import art.arcane.wormholes.menu.MenuController;
import art.arcane.wormholes.menu.MenuDecision;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuElementCommon;
import art.arcane.wormholes.menu.MenuFeedbackStyle;
import art.arcane.wormholes.menu.MenuGuard;
import art.arcane.wormholes.menu.MenuGuards;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuLines;
import art.arcane.wormholes.menu.MenuNotice;
import art.arcane.wormholes.menu.MenuNotices;
import art.arcane.wormholes.menu.MenuResult;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuStrings;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.NavigateMode;
import art.arcane.wormholes.menu.NumberElement;
import art.arcane.wormholes.menu.portal.PortalEdit;
import art.arcane.wormholes.menu.portal.PortalEditOutcome;
import art.arcane.wormholes.menu.portal.PortalMenuHost;
import art.arcane.wormholes.menu.portal.PortalMenuText;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.portal.BlackoutColor;

public final class CosmeticsMenuController implements MenuController {
    public static final Map<LinesKey, Integer> CHEST_HINTS = Map.ofEntries(
        Map.entry(WormholesMessages.PORTAL_MENU_BLACKOUT_COLOR_PLACARD, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_BLACKOUT_COLOR_OPTION, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_AMBIENT_COLOR_PLACARD, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_AMBIENT_CHANNEL, 4),
        Map.entry(WormholesMessages.PORTAL_MENU_AMBIENT_COLOR_OPTION, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_SURFACE_SKIN_PLACARD, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_SURFACE_SKIN_GLASS, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_SURFACE_SKIN_CLEAR, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_BACK_SETTINGS, 0));

    private static final Set<MenuInteraction> PRESS = Set.of(MenuInteraction.PRIMARY);
    private static final Set<MenuInteraction> NUMBER = Set.of(MenuInteraction.INCREMENT, MenuInteraction.DECREMENT,
        MenuInteraction.BIG_INCREMENT, MenuInteraction.BIG_DECREMENT, MenuInteraction.SET_VALUE);
    private static final String GLASS_SKIN = "minecraft:glass";
    private static final int GRID_WIDTH = 8;
    private static final int CHANNEL_STEP = 8;
    private static final int CHANNEL_BIG_STEP = 32;
    private static final int CHANNEL_MAX = 255;

    private final PortalMenuHost portals;
    private final CosmeticsMenuHost cosmetics;

    public CosmeticsMenuController(PortalMenuHost portals, CosmeticsMenuHost cosmetics) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.cosmetics = Objects.requireNonNull(cosmetics, "cosmetics");
    }

    @Override
    public String id() {
        return CosmeticsRoutes.CONTROLLER;
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
        PortalMenuView view = require(context);
        return switch (context.route().screen()) {
            case CosmeticsRoutes.BLACKOUT -> blackout(context, view);
            case CosmeticsRoutes.AMBIENT -> ambient(context, view);
            case CosmeticsRoutes.SKIN -> skin(context, view);
            default -> throw new IllegalArgumentException("cosmetics menus have no screen " + context.route().screen());
        };
    }

    @Override
    public boolean touches(MenuRoute route, MenuScope scope) {
        return scope instanceof MenuScope.Portal portal && portal.portalId().equals(PortalRoutes.portalId(route));
    }

    private MenuBuild blackout(MenuContext context, PortalMenuView view) {
        Parts parts = parts(context, view);
        MenuStrings strings = parts.strings();
        BlackoutColor current = view.blackoutColor();
        parts.elements().add(new LabelElement(common("blackout-color-placard", lines(strings, WormholesMessages.PORTAL_MENU_BLACKOUT_COLOR_PLACARD,
            args("color", current.displayName())), MenuIcon.item(current.blockState()), null, new MenuSlot(0, 0)).build()));
        BlackoutColor[] colors = BlackoutColor.values();
        for (int index = 0; index < colors.length; index++) {
            BlackoutColor color = colors[index];
            boolean selected = color == current;
            String id = "blackout-color-" + color.name().toLowerCase(Locale.ROOT);
            parts.elements().add(new ListEntryElement(common(id, lines(strings, WormholesMessages.PORTAL_MENU_BLACKOUT_COLOR_OPTION,
                args("color", color.displayName())), MenuIcon.item(color.blockState(), 1, selected), null, grid(index, 1)).interactions(PRESS).build(),
                selected));
            parts.bindings().guard(id, parts.manage()).on(id, MenuInteraction.PRIMARY, (ctx, action) -> chooseBlackout(ctx, color));
        }
        back(parts, new MenuSlot(0, 3));
        return build(parts, CosmeticsRoutes.BLACKOUT, MenuMessages.TITLE_BLACKOUT, new ChestLayout(view.routerTitle(), 4, ChestPane.BLACK, null));
    }

    private MenuBuild ambient(MenuContext context, PortalMenuView view) {
        Parts parts = parts(context, view);
        MenuStrings strings = parts.strings();
        int rgb = view.ambientColor();
        parts.elements().add(new LabelElement(common("ambient-color-placard", lines(strings, WormholesMessages.PORTAL_MENU_AMBIENT_COLOR_PLACARD,
            args("color", hex(rgb))), MenuIcon.item("minecraft:firework_star"), null, new MenuSlot(0, 0)).build()));
        for (AmbientChannel channel : AmbientChannel.values()) {
            channel(parts, channel, rgb);
        }
        List<DyeSwatch> palette = cosmetics.dyePalette();
        DyeSwatch nearest = nearest(palette, rgb);
        for (int index = 0; index < palette.size(); index++) {
            DyeSwatch swatch = palette.get(index);
            boolean selected = swatch.equals(nearest);
            String id = "ambient-color-" + swatch.name();
            parts.elements().add(new ListEntryElement(common(id, lines(strings, WormholesMessages.PORTAL_MENU_AMBIENT_COLOR_OPTION,
                args("color", swatch.displayName())), MenuIcon.item(swatch.itemId(), 1, selected), null, grid(index, 2)).interactions(PRESS).build(),
                selected));
            parts.bindings().guard(id, parts.manage()).on(id, MenuInteraction.PRIMARY, (ctx, action) -> chooseDye(ctx, swatch));
        }
        back(parts, new MenuSlot(0, 4));
        return build(parts, CosmeticsRoutes.AMBIENT, MenuMessages.TITLE_AMBIENT, new ChestLayout(view.routerTitle(), 5, ChestPane.BLACK, null));
    }

    private MenuBuild skin(MenuContext context, PortalMenuView view) {
        Parts parts = parts(context, view);
        MenuStrings strings = parts.strings();
        parts.elements().add(new LabelElement(common("surface-skin-placard", lines(strings, WormholesMessages.PORTAL_MENU_SURFACE_SKIN_PLACARD,
            args("skin", skinDisplay(strings, view))), MenuIcon.item(view.surfaceSkinItemId()), null, new MenuSlot(0, 0)).build()));
        parts.elements().add(new ButtonElement(common("surface-skin-glass", lines(strings, WormholesMessages.PORTAL_MENU_SURFACE_SKIN_GLASS,
            MessageArgs.empty()), MenuIcon.item(GLASS_SKIN, 1, GLASS_SKIN.equals(view.surfaceSkin())), null, new MenuSlot(-1, 1))
            .interactions(PRESS).build()));
        parts.bindings().guard("surface-skin-glass", parts.manage().and(MenuGuards.surfaceSkin(portals, view.id())))
            .on("surface-skin-glass", MenuInteraction.PRIMARY, (ctx, action) -> setSkin(ctx, GLASS_SKIN));
        parts.elements().add(new ButtonElement(common("surface-skin-clear", lines(strings, WormholesMessages.PORTAL_MENU_SURFACE_SKIN_CLEAR,
            MessageArgs.empty()), MenuIcon.item("minecraft:barrier"), "delete", new MenuSlot(1, 1)).interactions(PRESS).build()));
        parts.bindings().guard("surface-skin-clear", parts.manage().and(this::clearAllowed))
            .on("surface-skin-clear", MenuInteraction.PRIMARY, (ctx, action) -> hasSkin(require(ctx)) ? setSkin(ctx, "") : MenuResult.stay());
        back(parts, new MenuSlot(0, 2));
        return build(parts, CosmeticsRoutes.SKIN, MenuMessages.TITLE_SKIN, new ChestLayout(view.routerTitle(), 3, ChestPane.CYAN, null));
    }

    private void channel(Parts parts, AmbientChannel channel, int rgb) {
        MenuStrings strings = parts.strings();
        int value = channel.get(rgb);
        MenuLines lines = lines(strings, WormholesMessages.PORTAL_MENU_AMBIENT_CHANNEL, MessageArgs.builder()
            .untrusted("label", PortalMenuText.plain(strings, channel.label()))
            .untrusted("value", value)
            .untrusted("step", CHANNEL_STEP)
            .untrusted("large_step", CHANNEL_BIG_STEP)
            .build());
        parts.elements().add(new NumberElement(common(channel.id(), lines, MenuIcon.item(channel.itemId()), null, new MenuSlot(channel.position(), 1))
            .interactions(NUMBER).build(), value, 0, CHANNEL_MAX, CHANNEL_STEP, CHANNEL_BIG_STEP, Integer.toString(value)));
        parts.bindings().guard(channel.id(), parts.manage());
        for (MenuInteraction interaction : NUMBER) {
            parts.bindings().on(channel.id(), interaction, (ctx, action) -> adjustChannel(ctx, action, channel));
        }
    }

    private void back(Parts parts, MenuSlot slot) {
        parts.elements().add(new ButtonElement(common("back-to-settings", lines(parts.strings(), WormholesMessages.PORTAL_MENU_BACK_SETTINGS,
            MessageArgs.empty()), MenuIcon.item("minecraft:arrow"), null, slot).interactions(PRESS).nativeHidden(true).build()));
        UUID portalId = parts.view().id();
        parts.bindings().guard("back-to-settings", parts.manage())
            .on("back-to-settings", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.navigate(PortalRoutes.settings(portalId), NavigateMode.POP_TO, null));
    }

    private MenuResult chooseBlackout(MenuContext context, BlackoutColor color) {
        return apply(context, new PortalEdit.BlackoutColorChoice(color), WormholesMessages.PORTAL_NETWORK_LABEL_BLACKOUT_COLOR, color.displayName());
    }

    private MenuResult chooseDye(MenuContext context, DyeSwatch swatch) {
        return apply(context, new PortalEdit.AmbientColor(swatch.rgb()), WormholesMessages.PORTAL_NETWORK_LABEL_AMBIENT_COLOR, swatch.displayName());
    }

    private MenuResult setSkin(MenuContext context, String skin) {
        PortalMenuView view = require(context);
        PortalEditOutcome outcome = portals.edit(context.viewer(), view.id(), new PortalEdit.SurfaceSkin(skin));
        if (outcome instanceof PortalEditOutcome.Refused refused) {
            return MenuResult.stay(notice(context, view, refused.reason(), refused.arguments(), MenuFeedbackStyle.WARNING));
        }
        PortalMenuView after = portals.portal(view.id()).orElse(view);
        return MenuResult.stay(changed(context, after, WormholesMessages.PORTAL_NETWORK_LABEL_SURFACE_SKIN, skinDisplay(context.strings(), after)));
    }

    private MenuResult adjustChannel(MenuContext context, MenuAction action, AmbientChannel channel) {
        PortalMenuView view = require(context);
        int previous = channel.get(view.ambientColor());
        int target = Math.max(0, Math.min(CHANNEL_MAX, target(previous, action)));
        PortalEditOutcome outcome = portals.edit(context.viewer(), view.id(), new PortalEdit.AmbientColor(channel.set(view.ambientColor(), target)));
        if (outcome instanceof PortalEditOutcome.Refused refused) {
            return MenuResult.stay(notice(context, view, refused.reason(), refused.arguments(), MenuFeedbackStyle.WARNING));
        }
        PortalMenuView after = portals.portal(view.id()).orElse(view);
        int current = channel.get(after.ambientColor());
        if (current == previous) {
            return MenuResult.stay();
        }
        return MenuResult.stay(changed(context, after, channel.label(), Integer.toString(current)));
    }

    private MenuResult apply(MenuContext context, PortalEdit edit, TextKey label, String value) {
        PortalMenuView view = require(context);
        PortalEditOutcome outcome = portals.edit(context.viewer(), view.id(), edit);
        if (outcome instanceof PortalEditOutcome.Refused refused) {
            return MenuResult.stay(notice(context, view, refused.reason(), refused.arguments(), MenuFeedbackStyle.WARNING));
        }
        return MenuResult.stay(changed(context, view, label, value));
    }

    private MenuDecision clearAllowed(MenuContext context, MenuAction action) {
        PortalMenuView view = require(context);
        if (!hasSkin(view)) {
            return MenuDecision.allow();
        }
        return MenuGuards.surfaceSkin(portals, view.id()).check(context, action);
    }

    private MenuNotice changed(MenuContext context, PortalMenuView view, TextKey label, String value) {
        return notice(context, view, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED, MessageArgs.builder()
            .untrusted("label", PortalMenuText.plain(context.strings(), label))
            .untrusted("value", value)
            .build(), MenuFeedbackStyle.SUCCESS);
    }

    private MenuNotice notice(MenuContext context, PortalMenuView view, TextKey key, MessageArgs arguments, MenuFeedbackStyle style) {
        return PortalMenuText.settingNotice(context.viewer(), view, key, arguments, style);
    }

    private PortalMenuView require(MenuContext context) {
        UUID portalId = PortalRoutes.portalId(context.route());
        return portals.portal(portalId).orElseThrow(() -> new IllegalStateException("portal " + portalId + " is gone"));
    }

    private Parts parts(MenuContext context, PortalMenuView view) {
        return new Parts(context, view, MenuGuards.manage(portals, view.id()), new ArrayList<>(36), MenuBindings.builder());
    }

    private static MenuBuild build(Parts parts, String screenId, TextKey title, ChestLayout chest) {
        MenuStrings strings = parts.strings();
        PortalMenuView view = parts.view();
        MenuScreen screen = MenuScreen.builder(screenId)
            .title(strings.text(title))
            .context(strings.text(MenuMessages.CONTEXT_PORTAL, MessageArgs.builder()
                .untrusted("portal", view.name())
                .untrusted("type", PortalMenuText.currentMode(strings, view))
                .build()))
            .elements(parts.elements())
            .build();
        return new MenuBuild(screen, chest, parts.bindings().build());
    }

    private static DyeSwatch nearest(List<DyeSwatch> palette, int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        DyeSwatch best = null;
        long bestDistance = Long.MAX_VALUE;
        for (DyeSwatch swatch : palette) {
            long dr = red - ((swatch.rgb() >> 16) & 0xFF);
            long dg = green - ((swatch.rgb() >> 8) & 0xFF);
            long db = blue - (swatch.rgb() & 0xFF);
            long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = swatch;
            }
        }
        return best;
    }

    private static String skinDisplay(MenuStrings strings, PortalMenuView view) {
        return hasSkin(view) ? view.surfaceSkin() : PortalMenuText.plain(strings, WormholesMessages.PORTAL_LABEL_SKIN_NONE);
    }

    private static boolean hasSkin(PortalMenuView view) {
        return !view.surfaceSkin().isEmpty();
    }

    private static int target(int previous, MenuAction action) {
        return switch (action.interaction()) {
            case INCREMENT -> previous + CHANNEL_STEP;
            case DECREMENT -> previous - CHANNEL_STEP;
            case BIG_INCREMENT -> previous + CHANNEL_BIG_STEP;
            case BIG_DECREMENT -> previous - CHANNEL_BIG_STEP;
            case SET_VALUE -> (int) Math.round(((MenuValue.Number) action.value()).number());
            default -> previous;
        };
    }

    private static MenuSlot grid(int index, int firstRow) {
        return new MenuSlot(-4 + index % GRID_WIDTH, firstRow + index / GRID_WIDTH);
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb);
    }

    private static MenuElementCommon.Builder common(String id, MenuLines lines, MenuIcon icon, String sprite, MenuSlot slot) {
        return MenuElementCommon.builder(id)
            .lines(lines)
            .icon(icon)
            .nativeIcon(sprite == null ? null : MenuIcon.sprite(sprite))
            .chestSlot(slot);
    }

    private static MenuLines lines(MenuStrings strings, LinesKey key, MessageArgs arguments) {
        return strings.lines(key, arguments, CHEST_HINTS.getOrDefault(key, 0));
    }

    private static MessageArgs args(String name, Object value) {
        return MessageArgs.builder().untrusted(name, value).build();
    }

    private enum AmbientChannel {
        RED("ambient-red", WormholesMessages.PORTAL_LABEL_AMBIENT_RED, "minecraft:red_dye", 16, -2),
        GREEN("ambient-green", WormholesMessages.PORTAL_LABEL_AMBIENT_GREEN, "minecraft:green_dye", 8, 0),
        BLUE("ambient-blue", WormholesMessages.PORTAL_LABEL_AMBIENT_BLUE, "minecraft:blue_dye", 0, 2);

        private final String id;
        private final TextKey label;
        private final String itemId;
        private final int shift;
        private final int position;

        AmbientChannel(String id, TextKey label, String itemId, int shift, int position) {
            this.id = id;
            this.label = label;
            this.itemId = itemId;
            this.shift = shift;
            this.position = position;
        }

        String id() {
            return id;
        }

        TextKey label() {
            return label;
        }

        String itemId() {
            return itemId;
        }

        int position() {
            return position;
        }

        int get(int rgb) {
            return (rgb >> shift) & 0xFF;
        }

        int set(int rgb, int value) {
            return (rgb & ~(0xFF << shift) & 0xFFFFFF) | (value << shift);
        }
    }

    private record Parts(MenuContext context, PortalMenuView view, MenuGuard manage, List<MenuElement> elements, MenuBindings.Builder bindings) {
        MenuStrings strings() {
            return context.strings();
        }
    }
}

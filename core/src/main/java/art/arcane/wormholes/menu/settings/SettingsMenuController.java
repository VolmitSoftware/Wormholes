package art.arcane.wormholes.menu.settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.MenuMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.ChestClick;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.ChestTarget;
import art.arcane.wormholes.menu.CycleElement;
import art.arcane.wormholes.menu.LabelElement;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuActionOrigin;
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
import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.MenuLines;
import art.arcane.wormholes.menu.MenuNotice;
import art.arcane.wormholes.menu.MenuNotices;
import art.arcane.wormholes.menu.MenuResult;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSideAction;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuStateKey;
import art.arcane.wormholes.menu.MenuStrings;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.NavigateMode;
import art.arcane.wormholes.menu.NumberElement;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.cosmetics.CosmeticsRoutes;
import art.arcane.wormholes.menu.extensions.ExtensionsMenuHost;
import art.arcane.wormholes.menu.portal.NetworkNumberField;
import art.arcane.wormholes.menu.portal.PortalEdit;
import art.arcane.wormholes.menu.portal.PortalEditOutcome;
import art.arcane.wormholes.menu.portal.PortalMenuHost;
import art.arcane.wormholes.menu.portal.PortalMenuText;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.ApertureShapePresets;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.NetworkViewQuality;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.PortalTravelMode;
import art.arcane.wormholes.portal.ProjectionRenderMode;

public final class SettingsMenuController implements MenuController {
    public static final Map<LinesKey, Integer> CHEST_HINTS = Map.ofEntries(
        Map.entry(WormholesMessages.PORTAL_MENU_SETTINGS_PLACARD_GATEWAY, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_ADVANCED_SETTINGS, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_PERMISSION, 2),
        Map.entry(WormholesMessages.PORTAL_MENU_TRAVEL, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_TRAVEL_MANAGED, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_TRAVEL_MIRROR, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_STREAM_QUALITY, 2),
        Map.entry(WormholesMessages.PORTAL_MENU_SETTINGS_SYNC, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_NETWORK_NUMBER, 4),
        Map.entry(WormholesMessages.PORTAL_MENU_BLACKOUT, 2),
        Map.entry(WormholesMessages.PORTAL_MENU_ACTIVATION_RANGE, 5),
        Map.entry(WormholesMessages.PORTAL_MENU_RENDER_MODE, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_AMBIENT_PARTICLES, 2),
        Map.entry(WormholesMessages.PORTAL_MENU_APERTURE_SHAPE, 3),
        Map.entry(WormholesMessages.PORTAL_MENU_SURFACE_SKIN, 2),
        Map.entry(WormholesMessages.PORTAL_MENU_FALLBACK_BLOCK, 2),
        Map.entry(WormholesMessages.PORTAL_MENU_PUBLIC_LOOK_LABEL, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_COST_OPENER, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_EXTENSIONS, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_BACK, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_BACK_SETTINGS, 0));

    private static final Set<MenuInteraction> PRESS = Set.of(MenuInteraction.PRIMARY);
    private static final Set<MenuInteraction> PRESS_AND_SECONDARY = Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY);
    private static final Set<MenuInteraction> NUMBER = Set.of(MenuInteraction.INCREMENT, MenuInteraction.DECREMENT,
        MenuInteraction.BIG_INCREMENT, MenuInteraction.BIG_DECREMENT, MenuInteraction.SET_VALUE);
    private static final MenuIcon SETTINGS_SIDE_ICON = MenuIcon.sprite("small/settings");
    private static final MenuIcon ROTATE_SIDE_ICON = MenuIcon.sprite("small/rotate");
    private static final MenuIcon RESET_SIDE_ICON = MenuIcon.sprite("small/reset");
    private static final MenuIcon CLEAR_SIDE_ICON = MenuIcon.sprite("small/delete");
    private static final MenuStateKey<RefusedShape> REFUSED_SHAPE = new MenuStateKey<>("settings.aperture-shape.refused", RefusedShape.class);
    private static final MenuStateKey<LastSteps> LAST_STEPS = new MenuStateKey<>("settings.number.last-step", LastSteps.class);
    private static final String DEFAULT_FALLBACK_BLOCK = "minecraft:air";
    private static final int ACTIVATION_STEP = 8;
    private static final int ACTIVATION_BIG_STEP = 32;
    private static final int ACTIVATION_MINIMUM = 8;
    private static final int ACTIVATION_MAX = 256;
    private static final List<NumberControl> NUMBERS = List.of(
        new NumberControl("network-view-depth", NetworkNumberField.DEPTH, WormholesMessages.PORTAL_NETWORK_LABEL_CAPTURE_RADIUS,
            WormholesMessages.PORTAL_NETWORK_DESCRIPTION_CAPTURE_RADIUS, "minecraft:spyglass", 1, 128, 4, 16),
        new NumberControl("network-view-heartbeat", NetworkNumberField.HEARTBEAT_TICKS, WormholesMessages.PORTAL_NETWORK_LABEL_FULL_REFRESH,
            WormholesMessages.PORTAL_NETWORK_DESCRIPTION_FULL_REFRESH, "minecraft:clock", 2, 600, 10, 60),
        new NumberControl("network-view-entities", NetworkNumberField.ENTITY_INTERVAL_TICKS, WormholesMessages.PORTAL_NETWORK_LABEL_ENTITY_UPDATE,
            WormholesMessages.PORTAL_NETWORK_DESCRIPTION_ENTITY_UPDATE, "minecraft:ender_eye", 2, 600, 2, 20),
        new NumberControl("network-view-grace", NetworkNumberField.UNSUBSCRIBE_GRACE_SECONDS, WormholesMessages.PORTAL_NETWORK_LABEL_VIEW_GRACE,
            WormholesMessages.PORTAL_NETWORK_DESCRIPTION_VIEW_GRACE, "minecraft:redstone", 5, 600, 5, 30));

    private final PortalMenuHost portals;
    private final ExtensionsMenuHost extensions;

    public SettingsMenuController(PortalMenuHost portals, ExtensionsMenuHost extensions) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.extensions = Objects.requireNonNull(extensions, "extensions");
    }

    @Override
    public String id() {
        return PortalRoutes.SETTINGS_CONTROLLER;
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
            case PortalRoutes.SETTINGS_MAIN -> main(context, view);
            case SettingsRoutes.ADVANCED -> advanced(context, view);
            default -> throw new IllegalArgumentException("settings menus have no screen " + context.route().screen());
        };
    }

    @Override
    public void entered(MenuContext context) {
        forget(context);
    }

    @Override
    public boolean touches(MenuRoute route, MenuScope scope) {
        return scope instanceof MenuScope.Portal portal && portal.portalId().equals(PortalRoutes.portalId(route));
    }

    private MenuBuild main(MenuContext context, PortalMenuView view) {
        Parts parts = parts(context, view);
        boolean custom = custom(view);
        placard(parts, "settings-placard", WormholesMessages.PORTAL_MENU_SETTINGS_PLACARD_GATEWAY, "minecraft:lever");
        permission(parts, new MenuSlot(-3, 1));
        travel(parts, new MenuSlot(-1, 1));
        quality(parts, new MenuSlot(1, 1));
        settingsSync(parts, new MenuSlot(3, 1));
        if (custom) {
            numbers(parts, 2);
            blackout(parts, new MenuSlot(-4, 3));
            ambient(parts, new MenuSlot(-2, 3));
            aperture(parts, new MenuSlot(-1, 3));
            fallback(parts, new MenuSlot(0, 3));
            surfaceSkin(parts, new MenuSlot(2, 3));
            activation(parts, new MenuSlot(4, 3));
            renderMode(parts, new MenuSlot(-2, 4));
            lookLabel(parts, new MenuSlot(0, 4));
            cost(parts, new MenuSlot(2, 4));
            extensions(parts, new MenuSlot(4, 4));
        } else {
            blackout(parts, new MenuSlot(-4, 2));
            activation(parts, new MenuSlot(-2, 2));
            renderMode(parts, new MenuSlot(0, 2));
            ambient(parts, new MenuSlot(2, 2));
            aperture(parts, new MenuSlot(3, 2));
            surfaceSkin(parts, new MenuSlot(4, 2));
            lookLabel(parts, new MenuSlot(-1, 3));
            cost(parts, new MenuSlot(1, 3));
            extensions(parts, new MenuSlot(3, 3));
        }
        back(parts, "back-to-portal", WormholesMessages.PORTAL_MENU_BACK, new MenuSlot(0, custom ? 5 : 4), PortalRoutes.home(view.id()));
        return build(parts, PortalRoutes.SETTINGS_MAIN, MenuMessages.TITLE_SETTINGS, new ChestLayout(view.routerTitle(), custom ? 6 : 5, ChestPane.GRAY, null));
    }

    private MenuBuild advanced(MenuContext context, PortalMenuView view) {
        Parts parts = parts(context, view);
        placard(parts, "advanced-settings-placard", WormholesMessages.PORTAL_MENU_ADVANCED_SETTINGS, "minecraft:comparator");
        numbers(parts, 1);
        ambient(parts, new MenuSlot(-2, 2));
        fallback(parts, new MenuSlot(0, 2));
        surfaceSkin(parts, new MenuSlot(2, 2));
        blackout(parts, new MenuSlot(-2, 3));
        renderMode(parts, new MenuSlot(0, 3));
        activation(parts, new MenuSlot(2, 3));
        back(parts, "back-to-settings", WormholesMessages.PORTAL_MENU_BACK_SETTINGS, new MenuSlot(0, 4), PortalRoutes.settings(view.id()));
        return build(parts, SettingsRoutes.ADVANCED, MenuMessages.TITLE_ADVANCED, new ChestLayout(view.routerTitle(), 5, ChestPane.BLACK, null));
    }

    private void placard(Parts parts, String id, LinesKey key, String itemId) {
        parts.elements().add(new LabelElement(common(id, lines(parts.strings(), key, MessageArgs.empty()), MenuIcon.item(itemId), "settings",
            new MenuSlot(0, 0)).build()));
    }

    private void permission(Parts parts, MenuSlot slot) {
        MenuStrings strings = parts.strings();
        PortalPermissionMode mode = parts.view().permissionMode();
        boolean whitelist = mode == PortalPermissionMode.WHITELIST;
        MessageArgs arguments = MessageArgs.builder()
            .untrusted("mode", PortalMenuText.permissionMode(strings, mode))
            .untrusted("description", PortalMenuText.plain(strings, whitelist
                ? WormholesMessages.PORTAL_PERMISSION_DESCRIPTION_WHITELIST
                : WormholesMessages.PORTAL_PERMISSION_DESCRIPTION_BLACKLIST))
            .untrusted("node", parts.view().permissionNode())
            .build();
        MenuLines lines = lines(strings, WormholesMessages.PORTAL_MENU_PERMISSION, arguments);
        List<String> options = new ArrayList<>(2);
        for (PortalPermissionMode option : PortalPermissionMode.values()) {
            options.add(PortalMenuText.permissionMode(strings, option));
        }
        MenuIcon icon = MenuIcon.item(whitelist ? "minecraft:golden_helmet" : "minecraft:iron_helmet", 1, whitelist);
        parts.elements().add(new CycleElement(common("permission-mode", lines, icon, "access", slot)
            .nativeLabel(strings.settingName(WormholesMessages.PORTAL_MENU_PERMISSION, arguments, "mode")).interactions(PRESS).build(), options,
            mode.ordinal()));
        parts.bindings().guard("permission-mode", parts.manage())
            .on("permission-mode", MenuInteraction.PRIMARY, (ctx, action) -> change(ctx,
                view -> new PortalEdit.PermissionMode(view.permissionMode().next()),
                (before, after) -> before.permissionMode() == after.permissionMode() ? null : notice(ctx, after,
                    WormholesMessages.PORTAL_ACCESS_CHANGED, args("mode", PortalMenuText.permissionMode(ctx.strings(), after.permissionMode())))));
    }

    private void travel(Parts parts, MenuSlot slot) {
        CyclePresentation presentation = travelPresentation(parts.strings(), parts.view());
        MessageArgs arguments = travelArguments(parts.strings(), parts.view().travelMode());
        parts.elements().add(new CycleElement(common("travel-direction", presentation.lines(), presentation.icon(), "transit", slot)
            .nativeLabel(parts.strings().settingName(WormholesMessages.PORTAL_MENU_TRAVEL, arguments, "mode"))
            .interactions(PRESS).build(), presentation.options(), presentation.index()));
        parts.bindings().guard("travel-direction", parts.manage().and(this::travelUnlocked))
            .on("travel-direction", MenuInteraction.PRIMARY, (ctx, action) -> change(ctx,
                view -> new PortalEdit.TravelMode(view.travelMode().next()),
                (before, after) -> notice(ctx, after, WormholesMessages.PORTAL_TRAVEL_CHANGED, args("mode", travelLabel(ctx.strings(), after.travelMode())))));
    }

    private void quality(Parts parts, MenuSlot slot) {
        MenuStrings strings = parts.strings();
        PortalMenuView view = parts.view();
        NetworkViewQuality quality = view.quality();
        MessageArgs arguments = MessageArgs.builder()
            .untrusted("quality", PortalMenuText.plain(strings, quality.labelKey()))
            .untrusted("depth", view.networkViewDepth())
            .untrusted("entities", view.networkViewEntityIntervalTicks())
            .untrusted("refresh", view.networkViewHeartbeatTicks())
            .untrusted("grace", view.networkViewUnsubscribeGraceSeconds())
            .build();
        MenuLines lines = lines(strings, WormholesMessages.PORTAL_MENU_STREAM_QUALITY, arguments);
        List<String> options = new ArrayList<>(NetworkViewQuality.values().length);
        for (NetworkViewQuality option : NetworkViewQuality.values()) {
            options.add(PortalMenuText.plain(strings, option.labelKey()));
        }
        MenuIcon icon = MenuIcon.item(qualityItem(quality), 1, quality == NetworkViewQuality.CINEMATIC);
        parts.elements().add(new CycleElement(common("network-view-quality", lines, icon, "projection", slot)
            .nativeLabel(strings.settingName(WormholesMessages.PORTAL_MENU_STREAM_QUALITY, arguments, "quality"))
            .interactions(Set.of(MenuInteraction.PRIMARY, MenuInteraction.TERTIARY))
            .sideActions(List.of(new MenuSideAction(MenuInteraction.TERTIARY, strings.text(MenuMessages.ACTION_ADVANCED), SETTINGS_SIDE_ICON)))
            .build(), options, quality.ordinal()));
        UUID portalId = view.id();
        parts.bindings().guard("network-view-quality", parts.manage())
            .on("network-view-quality", MenuInteraction.PRIMARY, (ctx, action) -> cycleQuality(ctx))
            .on("network-view-quality", MenuInteraction.TERTIARY, (ctx, action) -> leave(ctx, MenuResult.push(SettingsRoutes.advanced(portalId))));
    }

    private void settingsSync(Parts parts, MenuSlot slot) {
        MenuStrings strings = parts.strings();
        boolean enabled = parts.view().settingsSync();
        MessageArgs arguments = args("state", PortalMenuText.onOff(strings, enabled));
        MenuLines lines = lines(strings, WormholesMessages.PORTAL_MENU_SETTINGS_SYNC, arguments);
        MenuIcon icon = MenuIcon.item(enabled ? "minecraft:soul_lantern" : "minecraft:lantern", 1, enabled);
        parts.elements().add(new ToggleElement(common("settings-sync", lines, icon, "network", slot)
            .nativeLabel(strings.settingName(WormholesMessages.PORTAL_MENU_SETTINGS_SYNC, arguments, "state")).interactions(PRESS).build(), enabled));
        parts.bindings().guard("settings-sync", parts.manage())
            .on("settings-sync", MenuInteraction.PRIMARY, (ctx, action) -> change(ctx,
                view -> new PortalEdit.SettingsSync(!view.settingsSync()),
                (before, after) -> notice(ctx, after, WormholesMessages.PORTAL_SETTINGS_SYNC_CHANGED,
                    args("state", PortalMenuText.onOff(ctx.strings(), after.settingsSync())))));
    }

    private void numbers(Parts parts, int row) {
        for (int index = 0; index < NUMBERS.size(); index++) {
            number(parts, NUMBERS.get(index), new MenuSlot(-3 + index * 2, row));
        }
    }

    private void number(Parts parts, NumberControl control, MenuSlot slot) {
        MenuStrings strings = parts.strings();
        int value = Math.max(control.min(), Math.min(control.max(), number(parts.view(), control.field())));
        int lastStep = lastStep(parts.context(), control.id());
        MenuLines lines = lines(strings, WormholesMessages.PORTAL_MENU_NETWORK_NUMBER, MessageArgs.builder()
            .untrusted("label", PortalMenuText.plain(strings, control.label()))
            .untrusted("description", PortalMenuText.plain(strings, control.description()))
            .untrusted("value", value)
            .untrusted("step", lastStep > 0 ? lastStep : control.step())
            .untrusted("large_step", lastStep > 0 ? lastStep : control.bigStep())
            .build());
        parts.elements().add(new NumberElement(common(control.id(), lines, MenuIcon.item(control.itemId()), null, slot).interactions(NUMBER).build(),
            value, control.min(), control.max(), control.step(), control.bigStep(), Integer.toString(value)));
        parts.bindings().guard(control.id(), parts.manage());
        for (MenuInteraction interaction : NUMBER) {
            parts.bindings().on(control.id(), interaction, (ctx, action) -> adjustNumber(ctx, action, control));
        }
    }

    private void blackout(Parts parts, MenuSlot slot) {
        MenuStrings strings = parts.strings();
        PortalMenuView view = parts.view();
        MenuLines lines = lines(strings, WormholesMessages.PORTAL_MENU_BLACKOUT, MessageArgs.builder()
            .untrusted("state", PortalMenuText.onOff(strings, view.blackout()))
            .untrusted("color", view.blackoutColor().displayName())
            .build());
        MenuIcon icon = MenuIcon.item(view.blackout() ? view.blackoutColor().blockState() : "minecraft:glass", 1, view.blackout());
        parts.elements().add(new ToggleElement(common("blackout-background", lines, icon, null, slot)
            .interactions(PRESS_AND_SECONDARY)
            .sideActions(List.of(new MenuSideAction(MenuInteraction.SECONDARY, strings.text(MenuMessages.ACTION_COLORS), SETTINGS_SIDE_ICON)))
            .build(), view.blackout()));
        UUID portalId = view.id();
        parts.bindings().guard("blackout-background", parts.manage())
            .on("blackout-background", MenuInteraction.PRIMARY, (ctx, action) -> change(ctx,
                current -> new PortalEdit.Blackout(!current.blackout()),
                (before, after) -> changed(ctx, after, WormholesMessages.PORTAL_NETWORK_LABEL_BLACKOUT, PortalMenuText.onOff(ctx.strings(), after.blackout()))))
            .on("blackout-background", MenuInteraction.SECONDARY, (ctx, action) -> leave(ctx, MenuResult.push(CosmeticsRoutes.blackout(portalId))));
    }

    private void activation(Parts parts, MenuSlot slot) {
        MenuStrings strings = parts.strings();
        PortalMenuView view = parts.view();
        int value = Math.max(0, Math.min(ACTIVATION_MAX, view.activationRange()));
        String display = activationDisplay(strings, view);
        MenuLines lines = lines(strings, WormholesMessages.PORTAL_MENU_ACTIVATION_RANGE, MessageArgs.builder()
            .untrusted("value", display)
            .untrusted("step", ACTIVATION_STEP)
            .untrusted("large_step", ACTIVATION_BIG_STEP)
            .build());
        parts.elements().add(new NumberElement(common("activation-range", lines, MenuIcon.item("minecraft:lodestone", 1, value > 0), null, slot)
            .interactions(NUMBER).build(), value, 0, ACTIVATION_MAX, ACTIVATION_STEP, ACTIVATION_BIG_STEP, display));
        parts.bindings().guard("activation-range", parts.manage());
        for (MenuInteraction interaction : NUMBER) {
            parts.bindings().on("activation-range", interaction, this::adjustActivation);
        }
    }

    private void renderMode(Parts parts, MenuSlot slot) {
        ProjectionRenderMode mode = parts.view().renderMode();
        MessageArgs arguments = args("mode", mode.displayName());
        MenuLines lines = lines(parts.strings(), WormholesMessages.PORTAL_MENU_RENDER_MODE, arguments);
        List<String> options = new ArrayList<>(ProjectionRenderMode.values().length);
        for (ProjectionRenderMode option : ProjectionRenderMode.values()) {
            options.add(option.displayName());
        }
        MenuIcon icon = MenuIcon.item(itemId(mode.iconMaterialName()), 1, mode != ProjectionRenderMode.PANOPTIC);
        parts.elements().add(new CycleElement(common("render-mode", lines, icon, "projection", slot)
            .nativeLabel(parts.strings().settingName(WormholesMessages.PORTAL_MENU_RENDER_MODE, arguments, "mode"))
            .interactions(PRESS).build(), options, mode.ordinal()));
        parts.bindings().guard("render-mode", parts.manage())
            .on("render-mode", MenuInteraction.PRIMARY, (ctx, action) -> change(ctx,
                view -> new PortalEdit.RenderMode(view.renderMode().next()),
                (before, after) -> changed(ctx, after, WormholesMessages.PORTAL_NETWORK_LABEL_RENDER_MODE, after.renderMode().displayName())));
    }

    private void ambient(Parts parts, MenuSlot slot) {
        MenuStrings strings = parts.strings();
        PortalMenuView view = parts.view();
        AmbientParticleStyle style = view.ambientStyle();
        MenuLines lines = lines(strings, WormholesMessages.PORTAL_MENU_AMBIENT_PARTICLES, MessageArgs.builder()
            .untrusted("style", ambientLabel(strings, style))
            .untrusted("color", hex(view.ambientColor()))
            .build());
        List<String> options = new ArrayList<>(AmbientParticleStyle.values().length);
        for (AmbientParticleStyle option : AmbientParticleStyle.values()) {
            options.add(ambientLabel(strings, option));
        }
        MenuIcon icon = MenuIcon.item(itemId(style.iconMaterialName()), 1, style != AmbientParticleStyle.OFF);
        parts.elements().add(new CycleElement(common("ambient-particles", lines, icon, "cosmetics", slot)
            .interactions(PRESS_AND_SECONDARY)
            .sideActions(List.of(new MenuSideAction(MenuInteraction.SECONDARY, strings.text(MenuMessages.ACTION_COLORS), SETTINGS_SIDE_ICON)))
            .build(), options, style.ordinal()));
        UUID portalId = view.id();
        parts.bindings().guard("ambient-particles", parts.manage())
            .on("ambient-particles", MenuInteraction.PRIMARY, (ctx, action) -> change(ctx,
                current -> new PortalEdit.AmbientStyle(current.ambientStyle().next()),
                (before, after) -> changed(ctx, after, WormholesMessages.PORTAL_NETWORK_LABEL_AMBIENT_STYLE, ambientLabel(ctx.strings(), after.ambientStyle()))))
            .on("ambient-particles", MenuInteraction.SECONDARY, (ctx, action) -> leave(ctx, MenuResult.push(CosmeticsRoutes.ambient(portalId))));
    }

    private void aperture(Parts parts, MenuSlot slot) {
        MenuStrings strings = parts.strings();
        PortalMenuView view = parts.view();
        ShapeDescriptor shape = view.apertureShape();
        MenuLines lines = lines(strings, WormholesMessages.PORTAL_MENU_APERTURE_SHAPE, MessageArgs.builder()
            .untrusted("shape", shape.format())
            .untrusted("cells", view.openCellCount())
            .build());
        ShapeDescriptor refused = refusedShape(parts.context()).orElse(null);
        if (refused != null) {
            lines = lines.withChestHints(List.of(strings.text(WormholesMessages.PORTAL_APERTURE_SHAPE_TOO_SMALL, args("shape", refused.format()))));
        }
        List<ShapeDescriptor> presets = ApertureShapePresets.presets();
        List<String> options = new ArrayList<>(presets.size() + 1);
        for (ShapeDescriptor preset : presets) {
            options.add(preset.format());
        }
        int index = presets.indexOf(shape);
        if (index < 0) {
            index = options.size();
            options.add(shape.format());
        }
        MenuIcon icon = shape.isFull() ? MenuIcon.item("minecraft:glass_pane") : MenuIcon.item("minecraft:cyan_stained_glass_pane", 1, true);
        MenuSlot clicks = slot.withClick(ChestClick.LEFT, ChestTarget.self(MenuInteraction.PRIMARY))
            .withClick(ChestClick.RIGHT, ChestTarget.self(MenuInteraction.SECONDARY))
            .withClick(ChestClick.SHIFT_RIGHT, ChestTarget.self(MenuInteraction.TERTIARY));
        parts.elements().add(new CycleElement(common("aperture-shape", lines, icon, "portal", clicks)
            .interactions(Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY, MenuInteraction.SELECT))
            .sideActions(List.of(
                new MenuSideAction(MenuInteraction.SECONDARY, strings.text(MenuMessages.ACTION_ROTATE), ROTATE_SIDE_ICON),
                new MenuSideAction(MenuInteraction.TERTIARY, strings.text(MenuMessages.ACTION_RESET), RESET_SIDE_ICON)))
            .build(), options, index));
        parts.bindings().guard("aperture-shape", parts.manage())
            .on("aperture-shape", MenuInteraction.PRIMARY, (ctx, action) -> changeShape(ctx,
                ApertureShapePresets.next(refusedShape(ctx).orElse(require(ctx).apertureShape()))))
            .on("aperture-shape", MenuInteraction.SECONDARY, (ctx, action) -> changeShape(ctx, ApertureShapePresets.rotated(require(ctx).apertureShape())))
            .on("aperture-shape", MenuInteraction.TERTIARY, (ctx, action) -> changeShape(ctx, ShapeDescriptor.FULL))
            .on("aperture-shape", MenuInteraction.SELECT, this::selectShape);
    }

    private void surfaceSkin(Parts parts, MenuSlot slot) {
        MenuStrings strings = parts.strings();
        PortalMenuView view = parts.view();
        MenuLines lines = lines(strings, WormholesMessages.PORTAL_MENU_SURFACE_SKIN, args("skin", skinDisplay(strings, view)));
        MenuSlot clicks = slot.withClick(ChestClick.LEFT, ChestTarget.self(MenuInteraction.SECONDARY))
            .withClick(ChestClick.RIGHT, ChestTarget.self(MenuInteraction.PRIMARY));
        parts.elements().add(new ButtonElement(common("surface-skin", lines, MenuIcon.item(view.surfaceSkinItemId(), 1, hasSkin(view)), null, clicks)
            .interactions(PRESS_AND_SECONDARY)
            .sideActions(List.of(new MenuSideAction(MenuInteraction.SECONDARY, strings.text(MenuMessages.ACTION_CLEAR), CLEAR_SIDE_ICON)))
            .build()));
        UUID portalId = view.id();
        parts.bindings().guard("surface-skin", parts.manage().and(this::skinClearAllowed))
            .on("surface-skin", MenuInteraction.PRIMARY, (ctx, action) -> leave(ctx, MenuResult.push(CosmeticsRoutes.skin(portalId))))
            .on("surface-skin", MenuInteraction.SECONDARY, this::clearSkin);
    }

    private void fallback(Parts parts, MenuSlot slot) {
        MenuStrings strings = parts.strings();
        UUID portalId = parts.view().id();
        String block = parts.view().fallbackBlock();
        MenuLines lines = lines(strings, WormholesMessages.PORTAL_MENU_FALLBACK_BLOCK, args("block", block));
        parts.elements().add(new TextElement(common("network-view-fallback", lines, MenuIcon.item("minecraft:glass"), null, slot)
            .interactions(Set.of(MenuInteraction.SET_TEXT, MenuInteraction.SECONDARY))
            .sideActions(List.of(new MenuSideAction(MenuInteraction.SECONDARY, strings.text(MenuMessages.ACTION_RESET), RESET_SIDE_ICON)))
            .build(), PortalMenuText.clip(block, MenuLimits.MAX_TEXT_INPUT_LENGTH), MenuLimits.MAX_TEXT_INPUT_LENGTH,
            strings.text(MenuMessages.PLACEHOLDER_BLOCK_STATE),
            List.of(strings.text(WormholesMessages.PORTAL_PROMPT_BLOCK_STATE,
                args("cancel", PortalMenuText.plain(strings, WormholesMessages.PORTAL_INPUT_CANCEL)))), ChestReopen.ALWAYS, PortalRoutes.settings(portalId)));
        parts.bindings().guard("network-view-fallback", parts.manage())
            .on("network-view-fallback", MenuInteraction.SET_TEXT, (ctx, action) -> submitFallback(ctx, portalId, ((MenuValue.Text) action.value()).text()))
            .on("network-view-fallback", MenuInteraction.SECONDARY, (ctx, action) -> MenuResult.stay(fallbackNotice(ctx, DEFAULT_FALLBACK_BLOCK,
                WormholesMessages.PORTAL_FALLBACK_RESET)));
    }

    private void lookLabel(Parts parts, MenuSlot slot) {
        MenuStrings strings = parts.strings();
        boolean enabled = parts.view().publicLookLabel();
        MessageArgs arguments = args("state", PortalMenuText.onOff(strings, enabled));
        MenuLines lines = lines(strings, WormholesMessages.PORTAL_MENU_PUBLIC_LOOK_LABEL, arguments);
        parts.elements().add(new ToggleElement(common("public-look-label", lines, MenuIcon.item("minecraft:name_tag", 1, enabled), "rename", slot)
            .nativeLabel(strings.settingName(WormholesMessages.PORTAL_MENU_PUBLIC_LOOK_LABEL, arguments, "state")).interactions(PRESS).build(), enabled));
        parts.bindings().guard("public-look-label", parts.manage())
            .on("public-look-label", MenuInteraction.PRIMARY, (ctx, action) -> change(ctx,
                view -> new PortalEdit.PublicLookLabel(!view.publicLookLabel()),
                (before, after) -> changed(ctx, after, WormholesMessages.PORTAL_LABEL_PUBLIC_LOOK_LABEL, PortalMenuText.onOff(ctx.strings(), after.publicLookLabel()))));
    }

    private void cost(Parts parts, MenuSlot slot) {
        MenuStrings strings = parts.strings();
        PortalMenuView.Cost cost = parts.view().cost();
        TextKey mode = switch (cost.mode()) {
            case FREE -> WormholesMessages.PORTAL_LABEL_COST_FREE;
            case VANILLA -> WormholesMessages.PORTAL_LABEL_COST_VANILLA;
            case VAULT -> WormholesMessages.PORTAL_LABEL_COST_VAULT;
        };
        String summary = switch (cost.mode()) {
            case FREE -> PortalMenuText.plain(strings, WormholesMessages.PORTAL_LABEL_COST_FREE);
            case VANILLA -> cost.quantity() + "x " + cost.itemLabel();
            case VAULT -> cost.vaultAmount();
        };
        MenuIcon icon = switch (cost.mode()) {
            case FREE -> MenuIcon.item("minecraft:hopper");
            case VANILLA -> MenuIcon.template(cost.itemId(), 1, true, cost.templateRef());
            case VAULT -> MenuIcon.item("minecraft:emerald", 1, true);
        };
        MenuLines lines = lines(strings, WormholesMessages.PORTAL_MENU_COST_OPENER, MessageArgs.builder()
            .untrusted("mode", PortalMenuText.plain(strings, mode))
            .untrusted("cost", summary)
            .build());
        String sprite = cost.mode() == PortalMenuView.CostMode.VANILLA ? null : "cost";
        parts.elements().add(new ButtonElement(common("travel-cost", lines, icon, sprite, slot).interactions(PRESS).build()));
        UUID portalId = parts.view().id();
        parts.bindings().guard("travel-cost", parts.manage())
            .on("travel-cost", MenuInteraction.PRIMARY, (ctx, action) -> leave(ctx, MenuResult.push(PortalRoutes.cost(portalId))));
    }

    private void extensions(Parts parts, MenuSlot slot) {
        if (extensions.entries().isEmpty()) {
            return;
        }
        parts.elements().add(new ButtonElement(common("extensions", lines(parts.strings(), WormholesMessages.PORTAL_MENU_EXTENSIONS, MessageArgs.empty()),
            MenuIcon.item("minecraft:comparator"), "settings", slot).interactions(PRESS).build()));
        UUID portalId = parts.view().id();
        parts.bindings().guard("extensions", parts.manage())
            .on("extensions", MenuInteraction.PRIMARY, (ctx, action) -> leave(ctx, MenuResult.push(PortalRoutes.extensions(portalId))));
    }

    private void back(Parts parts, String id, LinesKey key, MenuSlot slot, MenuRoute target) {
        parts.elements().add(new ButtonElement(common(id, lines(parts.strings(), key, MessageArgs.empty()), MenuIcon.item("minecraft:arrow"), null, slot)
            .interactions(PRESS).nativeHidden(true).build()));
        parts.bindings().guard(id, parts.manage())
            .on(id, MenuInteraction.PRIMARY, (ctx, action) -> leave(ctx, MenuResult.navigate(target, NavigateMode.POP_TO, null)));
    }

    private MenuResult change(MenuContext context, Function<PortalMenuView, PortalEdit> edit,
                              BiFunction<PortalMenuView, PortalMenuView, MenuNotice> announcement) {
        return MenuResult.stay(applyEdit(context, edit, announcement));
    }

    private MenuNotice applyEdit(MenuContext context, Function<PortalMenuView, PortalEdit> edit,
                                 BiFunction<PortalMenuView, PortalMenuView, MenuNotice> announcement) {
        PortalMenuView before = require(context);
        PortalEditOutcome outcome = portals.edit(context.viewer(), before.id(), edit.apply(before));
        if (outcome instanceof PortalEditOutcome.Refused refused) {
            return notice(context, before, refused.reason(), refused.arguments(), MenuFeedbackStyle.WARNING);
        }
        PortalMenuView after = portals.portal(before.id()).orElse(before);
        return announcement.apply(before, after);
    }

    private MenuResult cycleQuality(MenuContext context) {
        UUID portalId = PortalRoutes.portalId(context.route());
        boolean wasCustom = custom(require(context));
        MenuResult result = change(context, view -> new PortalEdit.Quality(view.quality().next()),
            (before, after) -> notice(context, after, WormholesMessages.PORTAL_STREAM_QUALITY_CHANGED,
                args("quality", PortalMenuText.plain(context.strings(), after.quality().labelKey()))));
        if (portals.portal(portalId).map(SettingsMenuController::custom).orElse(wasCustom) != wasCustom) {
            forget(context);
        }
        return result;
    }

    private MenuResult adjustNumber(MenuContext context, MenuAction action, NumberControl control) {
        rememberStep(context, control, action.interaction());
        return change(context,
            view -> new PortalEdit.NetworkNumber(control.field(), target(number(view, control.field()), action, control.step(), control.bigStep())),
            (before, after) -> number(before, control.field()) == number(after, control.field()) ? null
                : changed(context, after, control.label(), Integer.toString(number(after, control.field()))));
    }

    private MenuResult adjustActivation(MenuContext context, MenuAction action) {
        return change(context, view -> new PortalEdit.ActivationRange(activationTarget(view.activationRange(), action)),
            (before, after) -> before.activationRange() == after.activationRange() ? null
                : changed(context, after, WormholesMessages.PORTAL_NETWORK_LABEL_ACTIVATION_RANGE, activationDisplay(context.strings(), after)));
    }

    private MenuResult selectShape(MenuContext context, MenuAction action) {
        int index = ((MenuValue.Index) action.value()).index();
        List<ShapeDescriptor> presets = ApertureShapePresets.presets();
        return changeShape(context, index < presets.size() ? presets.get(index) : require(context).apertureShape());
    }

    private MenuResult changeShape(MenuContext context, ShapeDescriptor requested) {
        PortalMenuView view = require(context);
        PortalEditOutcome outcome = portals.edit(context.viewer(), view.id(), new PortalEdit.ApertureShape(requested));
        if (outcome instanceof PortalEditOutcome.Refused refused) {
            if (WormholesMessages.PORTAL_APERTURE_SHAPE_TOO_SMALL.equals(refused.reason())) {
                context.state().put(REFUSED_SHAPE, new RefusedShape(view.id(), requested));
            }
            return MenuResult.stay(notice(context, view, refused.reason(), refused.arguments(), MenuFeedbackStyle.WARNING));
        }
        context.state().remove(REFUSED_SHAPE);
        return MenuResult.stay(changed(context, view, WormholesMessages.PORTAL_LABEL_APERTURE_SHAPE, requested.format()));
    }

    private MenuResult clearSkin(MenuContext context, MenuAction action) {
        if (!hasSkin(require(context))) {
            return MenuResult.stay();
        }
        return change(context, view -> new PortalEdit.SurfaceSkin(""),
            (before, after) -> changed(context, after, WormholesMessages.PORTAL_NETWORK_LABEL_SURFACE_SKIN, skinDisplay(context.strings(), after)));
    }

    private MenuResult submitFallback(MenuContext context, UUID portalId, String block) {
        MenuNotice notice = fallbackNotice(context, block, WormholesMessages.PORTAL_FALLBACK_SET);
        if (context.origin() != MenuActionOrigin.CHEST) {
            return MenuResult.stay(notice);
        }
        return leave(context, MenuResult.navigate(PortalRoutes.settings(portalId), NavigateMode.POP_TO, notice));
    }

    private MenuNotice fallbackNotice(MenuContext context, String block, TextKey announcement) {
        return applyEdit(context, view -> new PortalEdit.FallbackBlock(block),
            (before, after) -> before.fallbackBlock().equals(after.fallbackBlock()) ? null
                : notice(context, after, announcement, args("block", after.fallbackBlock())));
    }

    private MenuDecision travelUnlocked(MenuContext context, MenuAction action) {
        PortalMenuView view = require(context);
        if (view.managed()) {
            return MenuDecision.deny(notice(context, view, WormholesMessages.PORTAL_TRAVEL_MANAGED, MessageArgs.empty(), MenuFeedbackStyle.WARNING));
        }
        if (view.mirror()) {
            return MenuDecision.deny(notice(context, view, WormholesMessages.PORTAL_TRAVEL_MIRROR_LOCKED, MessageArgs.empty(), MenuFeedbackStyle.WARNING));
        }
        return MenuDecision.allow();
    }

    private MenuDecision skinClearAllowed(MenuContext context, MenuAction action) {
        if (action.interaction() != MenuInteraction.SECONDARY || !hasSkin(require(context))) {
            return MenuDecision.allow();
        }
        return MenuGuards.surfaceSkin(portals, PortalRoutes.portalId(context.route())).check(context, action);
    }

    private int activationTarget(int previous, MenuAction action) {
        int base = previous > 0 ? previous : portals.globalActivationRange();
        int target = target(base, action, ACTIVATION_STEP, ACTIVATION_BIG_STEP);
        return target < ACTIVATION_MINIMUM ? 0 : target;
    }

    private String activationDisplay(MenuStrings strings, PortalMenuView view) {
        if (view.activationRange() > 0) {
            return Integer.toString(view.activationRange());
        }
        return PortalMenuText.plain(strings, WormholesMessages.PORTAL_LABEL_ACTIVATION_GLOBAL, args("range", portals.globalActivationRange()));
    }

    private MenuNotice changed(MenuContext context, PortalMenuView view, TextKey label, String value) {
        return notice(context, view, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED, MessageArgs.builder()
            .untrusted("label", PortalMenuText.plain(context.strings(), label))
            .untrusted("value", value)
            .build());
    }

    private MenuNotice notice(MenuContext context, PortalMenuView view, TextKey key, MessageArgs arguments) {
        return notice(context, view, key, arguments, MenuFeedbackStyle.SUCCESS);
    }

    private MenuNotice notice(MenuContext context, PortalMenuView view, TextKey key, MessageArgs arguments, MenuFeedbackStyle style) {
        return PortalMenuText.settingNotice(context.viewer(), view, key, arguments, style);
    }

    private PortalMenuView require(MenuContext context) {
        UUID portalId = PortalRoutes.portalId(context.route());
        return portals.portal(portalId).orElseThrow(() -> new IllegalStateException("portal " + portalId + " is gone"));
    }

    private Parts parts(MenuContext context, PortalMenuView view) {
        return new Parts(context, view, MenuGuards.manage(portals, view.id()), new ArrayList<>(24), MenuBindings.builder());
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

    private static CyclePresentation travelPresentation(MenuStrings strings, PortalMenuView view) {
        DimensionalPortalKind kind = view.dimensionalKind();
        if (kind.isManagedPortal()) {
            PortalTravelMode managed = view.managedTravel();
            boolean both = managed == PortalTravelMode.BOTH;
            String direction = PortalMenuText.plain(strings, both
                ? WormholesMessages.PORTAL_LABEL_BOTH_WAYS
                : managed == PortalTravelMode.INBOUND ? WormholesMessages.PORTAL_LABEL_ARRIVAL_ONLY : WormholesMessages.PORTAL_LABEL_DEPARTURE_ONLY);
            String detail = PortalMenuText.plain(strings, both
                ? WormholesMessages.PORTAL_LABEL_DIMENSIONAL_BOTH_ACTIVE
                : WormholesMessages.PORTAL_LABEL_DIMENSIONAL_RETURN_DISABLED);
            String item = kind.isNetherPortal() ? "minecraft:obsidian" : managed == PortalTravelMode.INBOUND ? "minecraft:ender_eye" : "minecraft:ender_pearl";
            return new CyclePresentation(lines(strings, WormholesMessages.PORTAL_MENU_TRAVEL_MANAGED, MessageArgs.builder()
                .untrusted("direction", direction)
                .untrusted("detail", detail)
                .build()), MenuIcon.item(item, 1, true), List.of(direction), 0);
        }
        if (view.mirror()) {
            return new CyclePresentation(lines(strings, WormholesMessages.PORTAL_MENU_TRAVEL_MIRROR, MessageArgs.empty()),
                MenuIcon.item("minecraft:barrier"), List.of(PortalMenuText.plain(strings, WormholesMessages.PORTAL_LABEL_LOCKED)), 0);
        }
        PortalTravelMode mode = view.travelMode();
        List<String> options = new ArrayList<>(PortalTravelMode.values().length);
        for (PortalTravelMode option : PortalTravelMode.values()) {
            options.add(travelLabel(strings, option));
        }
        MenuLines lines = lines(strings, WormholesMessages.PORTAL_MENU_TRAVEL, travelArguments(strings, mode));
        return new CyclePresentation(lines, MenuIcon.item(travelItem(mode), 1, mode != PortalTravelMode.LOCKED), options, mode.ordinal());
    }

    private static MessageArgs travelArguments(MenuStrings strings, PortalTravelMode mode) {
        return MessageArgs.builder()
            .untrusted("mode", travelLabel(strings, mode))
            .untrusted("outgoing", PortalMenuText.onOff(strings, mode.allowsOutgoing()))
            .untrusted("incoming", PortalMenuText.onOff(strings, mode.allowsIncoming()))
            .build();
    }

    private static String travelLabel(MenuStrings strings, PortalTravelMode mode) {
        TextKey key = switch (mode) {
            case BOTH -> WormholesMessages.PORTAL_LABEL_BOTH_WAYS;
            case OUTBOUND -> WormholesMessages.PORTAL_LABEL_OUTBOUND_ONLY;
            case INBOUND -> WormholesMessages.PORTAL_LABEL_INBOUND_ONLY;
            case LOCKED -> WormholesMessages.PORTAL_LABEL_LOCKED;
        };
        return PortalMenuText.plain(strings, key);
    }

    private static String travelItem(PortalTravelMode mode) {
        return switch (mode) {
            case BOTH -> "minecraft:recovery_compass";
            case OUTBOUND -> "minecraft:ender_pearl";
            case INBOUND -> "minecraft:ender_eye";
            case LOCKED -> "minecraft:barrier";
        };
    }

    private static String qualityItem(NetworkViewQuality quality) {
        return switch (quality) {
            case STANDARD -> "minecraft:conduit";
            case PERFORMANCE -> "minecraft:feather";
            case BALANCED -> "minecraft:spyglass";
            case CINEMATIC -> "minecraft:beacon";
            case CUSTOM -> "minecraft:comparator";
        };
    }

    private static String ambientLabel(MenuStrings strings, AmbientParticleStyle style) {
        TextKey key = switch (style) {
            case SPARKS -> WormholesMessages.PORTAL_LABEL_AMBIENT_STYLE_SPARKS;
            case OUTLINE -> WormholesMessages.PORTAL_LABEL_AMBIENT_STYLE_OUTLINE;
            case CORNERS -> WormholesMessages.PORTAL_LABEL_AMBIENT_STYLE_CORNERS;
            case OFF -> WormholesMessages.PORTAL_LABEL_AMBIENT_STYLE_OFF;
        };
        return PortalMenuText.plain(strings, key);
    }

    private static String skinDisplay(MenuStrings strings, PortalMenuView view) {
        return hasSkin(view) ? view.surfaceSkin() : PortalMenuText.plain(strings, WormholesMessages.PORTAL_LABEL_SKIN_NONE);
    }

    private static MenuResult leave(MenuContext context, MenuResult result) {
        forget(context);
        return result;
    }

    private static void forget(MenuContext context) {
        context.state().remove(LAST_STEPS);
        context.state().remove(REFUSED_SHAPE);
    }

    private static void rememberStep(MenuContext context, NumberControl control, MenuInteraction interaction) {
        int size = switch (interaction) {
            case INCREMENT, DECREMENT -> control.step();
            case BIG_INCREMENT, BIG_DECREMENT -> control.bigStep();
            default -> 0;
        };
        if (size == 0) {
            return;
        }
        MenuRoute route = context.route();
        Map<String, Integer> sizes = new HashMap<>(NUMBERS.size());
        context.state().get(LAST_STEPS).filter(previous -> previous.matches(route)).ifPresent(previous -> sizes.putAll(previous.sizes()));
        sizes.put(control.id(), size);
        context.state().put(LAST_STEPS, new LastSteps(PortalRoutes.portalId(route), route.screen(), Map.copyOf(sizes)));
    }

    private static int lastStep(MenuContext context, String elementId) {
        return context.state().get(LAST_STEPS).filter(steps -> steps.matches(context.route()))
            .map(steps -> steps.sizes().getOrDefault(elementId, 0))
            .orElse(0);
    }

    private static Optional<ShapeDescriptor> refusedShape(MenuContext context) {
        return context.state().get(REFUSED_SHAPE).filter(refused -> refused.matches(context.route())).map(RefusedShape::shape);
    }

    private static boolean custom(PortalMenuView view) {
        return view.quality() == NetworkViewQuality.CUSTOM;
    }

    private static int number(PortalMenuView view, NetworkNumberField field) {
        return switch (field) {
            case DEPTH -> view.networkViewDepth();
            case HEARTBEAT_TICKS -> view.networkViewHeartbeatTicks();
            case ENTITY_INTERVAL_TICKS -> view.networkViewEntityIntervalTicks();
            case UNSUBSCRIBE_GRACE_SECONDS -> view.networkViewUnsubscribeGraceSeconds();
        };
    }

    private static boolean hasSkin(PortalMenuView view) {
        return !view.surfaceSkin().isEmpty();
    }

    private static int target(int previous, MenuAction action, int step, int bigStep) {
        return switch (action.interaction()) {
            case INCREMENT -> previous + step;
            case DECREMENT -> previous - step;
            case BIG_INCREMENT -> previous + bigStep;
            case BIG_DECREMENT -> previous - bigStep;
            case SET_VALUE -> (int) Math.round(((MenuValue.Number) action.value()).number());
            default -> previous;
        };
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb);
    }

    private static String itemId(String materialName) {
        return "minecraft:" + materialName.toLowerCase(Locale.ROOT);
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

    private record Parts(MenuContext context, PortalMenuView view, MenuGuard manage, List<MenuElement> elements, MenuBindings.Builder bindings) {
        MenuStrings strings() {
            return context.strings();
        }
    }

    private record NumberControl(String id, NetworkNumberField field, TextKey label, TextKey description, String itemId, int min, int max, int step,
                                 int bigStep) {
    }

    private record CyclePresentation(MenuLines lines, MenuIcon icon, List<String> options, int index) {
    }

    private record RefusedShape(UUID portalId, ShapeDescriptor shape) {
        boolean matches(MenuRoute route) {
            return portalId.equals(PortalRoutes.portalId(route));
        }
    }

    private record LastSteps(UUID portalId, String screen, Map<String, Integer> sizes) {
        boolean matches(MenuRoute route) {
            return portalId.equals(PortalRoutes.portalId(route)) && screen.equals(route.screen());
        }
    }
}

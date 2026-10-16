package art.arcane.wormholes.menu.portal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.DoorViewMessages;
import art.arcane.wormholes.localization.MenuMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.ChestClick;
import art.arcane.wormholes.menu.ChestConfirmLayout;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.ChestTarget;
import art.arcane.wormholes.menu.LabelElement;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.LookCaptureElement;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuBindings;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuConfirmRequest;
import art.arcane.wormholes.menu.MenuContext;
import art.arcane.wormholes.menu.MenuController;
import art.arcane.wormholes.menu.MenuDecision;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuElementCommon;
import art.arcane.wormholes.menu.MenuFeedbackStyle;
import art.arcane.wormholes.menu.MenuGuard;
import art.arcane.wormholes.menu.MenuGuards;
import art.arcane.wormholes.menu.MenuHandler;
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
import art.arcane.wormholes.menu.MenuStrings;
import art.arcane.wormholes.menu.MenuStyle;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.NavigateMode;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;

public final class PortalMenuController implements MenuController {
    public static final Map<LinesKey, Integer> CHEST_HINTS = Map.ofEntries(
        Map.entry(WormholesMessages.PORTAL_MENU_PLACARD_RTP, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_PLACARD_LINKED, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_PLACARD_UNLINKED, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_GATEWAY_DESTINATION, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_RTP_DESTINATION, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_PROJECTION_ON, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_PROJECTION_OFF, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_SETTINGS_GATEWAY, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_RENAME, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_ORIENTATION, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_MODE_OPENER, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_DELETE, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_MODE_PLACARD, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_MODE_OPTION_SELECTED, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_MODE_OPTION_AVAILABLE, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_MIRROR_SELECTED, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_MIRROR_AVAILABLE, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_MIRROR_FLIP, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_BACK, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_ORIENTATION_PLACARD, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_DIRECTION, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_FLIP_FACE, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_ROTATE_COUNTERCLOCKWISE, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_ROTATE_CLOCKWISE, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_GATEWAY_UNPAIRED, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_GATEWAY_PAIRED, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_GATEWAY_EXPORT, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_GATEWAY_CHOOSE, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_GATEWAY_IMPORT, 1));
    public static final int GATEWAY_REFRESH_TICKS = 20;

    private static final List<PortalType> MODE_ORDER = List.of(PortalType.PORTAL, PortalType.WORMHOLE, PortalType.GATEWAY, PortalType.RTP);
    private static final int[] MODE_POSITIONS = {-4, -2, 0, 2};
    private static final Set<MenuInteraction> PRESS = Set.of(MenuInteraction.PRIMARY);
    private static final Set<MenuInteraction> TEXT = Set.of(MenuInteraction.SET_TEXT);
    private static final MenuIcon ROTATE_SIDE_ICON = MenuIcon.sprite("small/rotate");

    private final PortalMenuHost portals;
    private final GatewayMenuHost gateways;

    public PortalMenuController(PortalMenuHost portals, GatewayMenuHost gateways) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
    }

    @Override
    public String id() {
        return PortalRoutes.CONTROLLER;
    }

    @Override
    public MenuDecision routeGuard(MenuContext context) {
        UUID portalId = PortalRoutes.portalId(context.route());
        Optional<PortalMenuView> portal = portals.portal(portalId);
        if (portal.isEmpty() || !portals.canManage(context.viewer(), portalId)) {
            return MenuDecision.denyAndClose(MenuNotices.hud(context.viewer(), WormholesMessages.PORTAL_EDIT_DENIED));
        }
        if (context.route().screen().equals(PortalRoutes.MODE) && portal.get().managed()) {
            return MenuDecision.deny(PortalMenuText.settingNotice(context.viewer(), portal.get(), WormholesMessages.PORTAL_MANAGED_MODE, MessageArgs.empty(),
                MenuFeedbackStyle.WARNING));
        }
        return MenuDecision.allow();
    }

    @Override
    public MenuBuild build(MenuContext context) {
        UUID portalId = PortalRoutes.portalId(context.route());
        PortalMenuView portal = portals.portal(portalId).orElseThrow(() -> new IllegalStateException("portal " + portalId + " is gone"));
        return switch (context.route().screen()) {
            case PortalRoutes.HOME -> home(context, portal);
            case PortalRoutes.MODE -> mode(context, portal);
            case PortalRoutes.ORIENTATION -> orientation(context, portal);
            case PortalRoutes.GATEWAY -> gateway(context, portal);
            default -> throw new IllegalArgumentException("portal menus have no screen " + context.route().screen());
        };
    }

    @Override
    public int refreshIntervalTicks(MenuRoute route) {
        return route.screen().equals(PortalRoutes.GATEWAY) ? GATEWAY_REFRESH_TICKS : 0;
    }

    @Override
    public boolean touches(MenuRoute route, MenuScope scope) {
        return scope instanceof MenuScope.Portal portal && portal.portalId().equals(PortalRoutes.portalId(route));
    }

    private MenuBuild home(MenuContext context, PortalMenuView portal) {
        MenuStrings strings = context.strings();
        UUID portalId = portal.id();
        MenuGuard manage = MenuGuards.manage(portals, portalId);
        List<MenuElement> elements = new ArrayList<>(8);
        MenuBindings.Builder bindings = MenuBindings.builder();

        elements.add(new LabelElement(common("portal-placard", placardLines(strings, portal), MenuIcon.item("minecraft:book"),
            sprite(portal.type()), new MenuSlot(0, 0)).build()));

        boolean rtp = portal.rtp() != null;
        LinesKey destinationKey = rtp
            ? WormholesMessages.PORTAL_MENU_RTP_DESTINATION
            : portal.gateway() ? WormholesMessages.PORTAL_MENU_GATEWAY_DESTINATION : WormholesMessages.PORTAL_MENU_DESTINATION;
        MessageArgs destinationArguments = rtp
            ? args("rotation", PortalMenuText.rtpRotation(strings, portal.rtp()))
            : args("destination", portal.destination() == null ? PortalMenuText.plain(strings, WormholesMessages.LABEL_NONE) : portal.destination().name());
        MenuIcon destinationIcon = rtp
            ? MenuIcon.item("minecraft:compass")
            : MenuIcon.item(portal.gateway() ? "minecraft:end_crystal" : "minecraft:ender_eye", destinationCount(portal), false);
        String destinationSprite = rtp ? "rtp" : portal.gateway() ? "gateway" : "link";
        elements.add(new ButtonElement(common("set-destination", lines(strings, destinationKey, destinationArguments), destinationIcon,
            destinationSprite, new MenuSlot(-2, 1)).interactions(PRESS).build()));
        bindings.guard("set-destination", manage.and(this::linkNotManaged))
            .on("set-destination", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.push(destinationRoute(portal)));

        boolean projected = portal.projection() == ProjectionMode.ON;
        elements.add(new ToggleElement(common("toggle-projections",
            lines(strings, projected ? WormholesMessages.PORTAL_MENU_PROJECTION_ON : WormholesMessages.PORTAL_MENU_PROJECTION_OFF, MessageArgs.empty()),
            MenuIcon.item(projected ? "minecraft:redstone_torch" : "minecraft:torch", 1, projected), "projection", new MenuSlot(0, 1))
            .nativeLabel(strings.settingName(DoorViewMessages.MENU_PROJECTION, args("state", PortalMenuText.onOff(strings, projected)), "state"))
            .interactions(PRESS).build(), projected));
        bindings.guard("toggle-projections", manage.and(this::projectionActive))
            .on("toggle-projections", MenuInteraction.PRIMARY, this::toggleProjection);

        elements.add(new ButtonElement(common("portal-settings", lines(strings, WormholesMessages.PORTAL_MENU_SETTINGS_GATEWAY, MessageArgs.builder()
            .untrusted("access", PortalMenuText.permissionMode(strings, portal.permissionMode()))
            .untrusted("send", PortalMenuText.onOff(strings, portal.travelMode().allowsOutgoing()))
            .untrusted("receive", PortalMenuText.onOff(strings, portal.travelMode().allowsIncoming()))
            .untrusted("depth", portal.networkViewDepth())
            .untrusted("entity", portal.networkViewEntityIntervalTicks())
            .build()), MenuIcon.item("minecraft:lever"), "settings", new MenuSlot(2, 1)).interactions(PRESS).build()));
        bindings.guard("portal-settings", manage)
            .on("portal-settings", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.push(PortalRoutes.settings(portalId)));

        elements.add(new TextElement(common("set-name", lines(strings, WormholesMessages.PORTAL_MENU_RENAME, args("portal", portal.name())),
            MenuIcon.item("minecraft:name_tag"), "rename", new MenuSlot(-2, 2)).interactions(TEXT).build(),
            PortalMenuText.clip(portal.name(), MenuLimits.MAX_TEXT_INPUT_LENGTH), MenuLimits.MAX_TEXT_INPUT_LENGTH, strings.text(MenuMessages.PLACEHOLDER_NAME),
            List.of(strings.text(WormholesMessages.PORTAL_PROMPT_NAME, cancelArgs(strings))), ChestReopen.ALWAYS, null));
        bindings.guard("set-name", manage)
            .on("set-name", MenuInteraction.SET_TEXT, this::rename);

        elements.add(new ButtonElement(common("portal-orientation", lines(strings, WormholesMessages.PORTAL_MENU_ORIENTATION, orientationArgs(strings, portal)),
            MenuIcon.item("minecraft:compass"), "rotate", new MenuSlot(0, 2)).interactions(PRESS).build()));
        bindings.guard("portal-orientation", manage)
            .on("portal-orientation", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.push(PortalRoutes.orientation(portalId)));

        String modeDescription = portal.mirror()
            ? PortalMenuText.plain(strings, WormholesMessages.PORTAL_MODE_DESCRIPTION_MIRROR)
            : PortalMenuText.modeDescription(strings, portal.type());
        elements.add(new ButtonElement(common("set-mode", lines(strings, WormholesMessages.PORTAL_MENU_MODE_OPENER, MessageArgs.builder()
                .untrusted("description", modeDescription)
                .untrusted("mode", PortalMenuText.currentMode(strings, portal))
                .build()),
            MenuIcon.item(portal.mirror() ? "minecraft:copper_torch" : modeItem(portal.type()), 1, true),
            portal.mirror() ? "mirror" : sprite(portal.type()), new MenuSlot(2, 2)).interactions(PRESS).build()));
        bindings.guard("set-mode", manage)
            .on("set-mode", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.push(PortalRoutes.mode(portalId)));

        elements.add(new ButtonElement(common("destroy", lines(strings, WormholesMessages.PORTAL_MENU_DELETE, MessageArgs.empty()),
            MenuIcon.item("minecraft:gunpowder"), "delete",
            new MenuSlot(0, 3).withClick(ChestClick.SHIFT_LEFT, ChestTarget.preconfirmed(MenuInteraction.PRIMARY)))
            .style(MenuStyle.DESTRUCTIVE).interactions(PRESS).build()));
        bindings.guard("destroy", manage)
            .on("destroy", MenuInteraction.PRIMARY, (ctx, action) -> {
                portals.destroy(ctx.viewer(), portalId);
                return MenuResult.close();
            })
            .confirm("destroy", (ctx, onConfirm) -> deleteConfirmation(ctx, portal, onConfirm));

        return new MenuBuild(screen(PortalRoutes.HOME, strings, MenuMessages.TITLE_PORTAL, portal, elements),
            new ChestLayout(portal.routerTitle(), 4, ChestPane.GRAY, null), bindings.build());
    }

    private MenuBuild mode(MenuContext context, PortalMenuView portal) {
        MenuStrings strings = context.strings();
        UUID portalId = portal.id();
        MenuGuard manage = MenuGuards.manage(portals, portalId);
        List<MenuElement> elements = new ArrayList<>(7);
        MenuBindings.Builder bindings = MenuBindings.builder();

        elements.add(new LabelElement(common("mode-placard", lines(strings, WormholesMessages.PORTAL_MENU_MODE_PLACARD,
            args("mode", PortalMenuText.currentMode(strings, portal))), MenuIcon.item("minecraft:beacon"), null, new MenuSlot(0, 0)).build()));
        for (int index = 0; index < MODE_ORDER.size(); index++) {
            PortalType target = MODE_ORDER.get(index);
            boolean selected = portal.type() == target && !portal.mirror();
            String id = "mode-" + target.name().toLowerCase(Locale.ROOT);
            elements.add(new ListEntryElement(common(id, lines(strings,
                    selected ? WormholesMessages.PORTAL_MENU_MODE_OPTION_SELECTED : WormholesMessages.PORTAL_MENU_MODE_OPTION_AVAILABLE,
                    MessageArgs.builder()
                        .untrusted("mode", PortalMenuText.type(strings, target))
                        .untrusted("description", PortalMenuText.modeDescription(strings, target))
                        .build()),
                MenuIcon.item(modeItem(target), 1, selected), sprite(target), new MenuSlot(MODE_POSITIONS[index], 1)).interactions(PRESS).build(),
                selected));
            bindings.guard(id, manage.and(typeAllowed(portalId, target)))
                .on(id, MenuInteraction.PRIMARY, (ctx, action) -> chooseType(ctx, portalId, target));
        }

        elements.add(mirrorOption(strings, portal));
        bindings.guard("mode-mirror", manage)
            .on("mode-mirror", MenuInteraction.PRIMARY, (ctx, action) -> chooseMirror(ctx, portalId))
            .on("mode-mirror", MenuInteraction.SECONDARY, (ctx, action) -> rotateMirror(ctx, portalId, true))
            .on("mode-mirror", MenuInteraction.TERTIARY, (ctx, action) -> rotateMirror(ctx, portalId, false));

        elements.add(backElement(strings));
        bindings.guard("back-to-portal", manage).on("back-to-portal", MenuInteraction.PRIMARY, (ctx, action) -> homeAgain(portalId, null));

        return new MenuBuild(screen(PortalRoutes.MODE, strings, MenuMessages.TITLE_MODE, portal, elements),
            new ChestLayout(portal.routerTitle(), 3, ChestPane.GRAY, PortalRoutes.home(portalId)), bindings.build());
    }

    private MenuBuild orientation(MenuContext context, PortalMenuView portal) {
        MenuStrings strings = context.strings();
        UUID portalId = portal.id();
        MenuGuard manage = MenuGuards.manage(portals, portalId);
        MessageArgs up = args("up", PortalMenuText.direction(strings, portal.frame().getUp()));
        List<MenuElement> elements = new ArrayList<>(6);
        MenuBindings.Builder bindings = MenuBindings.builder();

        elements.add(new LabelElement(common("orientation-placard", lines(strings, WormholesMessages.PORTAL_MENU_ORIENTATION_PLACARD,
            orientationArgs(strings, portal)), MenuIcon.item("minecraft:compass"), null, new MenuSlot(0, 0)).build()));
        elements.add(new LookCaptureElement(common("set-direction", lines(strings, WormholesMessages.PORTAL_MENU_DIRECTION,
            args("direction", PortalMenuText.direction(strings, portal.frame().getNormal()))), MenuIcon.item("minecraft:compass"), "rotate",
            new MenuSlot(-3, 1)).interactions(PRESS).build()));
        bindings.guard("set-direction", manage)
            .on("set-direction", MenuInteraction.PRIMARY, (ctx, action) -> portals.beginDirectionPrompt(ctx.viewer(), portalId)
                ? MenuResult.suspend()
                : MenuResult.stay(MenuNotices.nativeOnly(ctx.viewer(), MenuMessages.FEEDBACK_UNAVAILABLE, MessageArgs.empty(), MenuFeedbackStyle.WARNING)));
        frameElement(elements, bindings, manage, strings, portal, "flip-face", WormholesMessages.PORTAL_MENU_FLIP_FACE, "minecraft:target", -1,
            FrameOperation.FLIP_FACE, up);
        frameElement(elements, bindings, manage, strings, portal, "rotate-counter-clockwise", WormholesMessages.PORTAL_MENU_ROTATE_COUNTERCLOCKWISE,
            "minecraft:repeater", 1, FrameOperation.ROTATE_COUNTER_CLOCKWISE, up);
        frameElement(elements, bindings, manage, strings, portal, "rotate-clockwise", WormholesMessages.PORTAL_MENU_ROTATE_CLOCKWISE,
            "minecraft:lever", 3, FrameOperation.ROTATE_CLOCKWISE, up);
        elements.add(backElement(strings));
        bindings.guard("back-to-portal", manage).on("back-to-portal", MenuInteraction.PRIMARY, (ctx, action) -> homeAgain(portalId, null));

        return new MenuBuild(screen(PortalRoutes.ORIENTATION, strings, MenuMessages.TITLE_ORIENTATION, portal, elements),
            new ChestLayout(portal.routerTitle(), 3, ChestPane.BLUE, null), bindings.build());
    }

    private MenuBuild gateway(MenuContext context, PortalMenuView portal) {
        MenuStrings strings = context.strings();
        UUID portalId = portal.id();
        MenuGuard manage = MenuGuards.manage(portals, portalId);
        GatewayView gateway = gateways.gateway(portalId);
        List<MenuElement> elements = new ArrayList<>(5);
        MenuBindings.Builder bindings = MenuBindings.builder();

        elements.add(new LabelElement(common("gateway-pair-placard", gatewayLines(strings, gateway), MenuIcon.item("minecraft:respawn_anchor"),
            "gateway", new MenuSlot(0, 0)).build()));
        elements.add(new ButtonElement(common("export-portal", lines(strings, WormholesMessages.PORTAL_MENU_GATEWAY_EXPORT, MessageArgs.empty()),
            MenuIcon.item("minecraft:paper"), "network", new MenuSlot(-2, 1)).interactions(PRESS).build()));
        bindings.guard("export-portal", manage)
            .on("export-portal", MenuInteraction.PRIMARY, (ctx, action) -> {
                gateways.exportToChat(ctx.viewer(), portalId);
                return MenuResult.close();
            });
        elements.add(new ButtonElement(common("choose-gateway-destination", lines(strings, WormholesMessages.PORTAL_MENU_GATEWAY_CHOOSE,
            MessageArgs.empty()), MenuIcon.item("minecraft:end_crystal"), "link", new MenuSlot(0, 1)).interactions(PRESS).build()));
        bindings.guard("choose-gateway-destination", manage)
            .on("choose-gateway-destination", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.push(PortalRoutes.destinationPicker(portalId)));
        elements.add(new TextElement(common("import-portal", lines(strings, WormholesMessages.PORTAL_MENU_GATEWAY_IMPORT, MessageArgs.empty()),
            MenuIcon.item("minecraft:writable_book"), "network", new MenuSlot(2, 1)).interactions(TEXT).build(),
            "", MenuLimits.MAX_TEXT_INPUT_LENGTH, strings.text(MenuMessages.PLACEHOLDER_CODE),
            List.of(strings.text(WormholesMessages.PORTAL_PROMPT_INVITE, cancelArgs(strings))), ChestReopen.ON_CANCEL, null));
        bindings.guard("import-portal", manage)
            .on("import-portal", MenuInteraction.SET_TEXT, (ctx, action) -> importCode(ctx, portalId, action));
        elements.add(backElement(strings));
        bindings.guard("back-to-portal", manage).on("back-to-portal", MenuInteraction.PRIMARY, (ctx, action) -> homeAgain(portalId, null));

        return new MenuBuild(screen(PortalRoutes.GATEWAY, strings, MenuMessages.TITLE_GATEWAY, portal, elements),
            new ChestLayout(portal.routerTitle(), 3, ChestPane.BLACK, null), bindings.build());
    }

    private MenuResult toggleProjection(MenuContext context, MenuAction action) {
        PortalMenuView portal = require(context);
        ProjectionMode next = portal.projection().next();
        PortalEditOutcome outcome = portals.edit(context.viewer(), portal.id(), new PortalEdit.Projection(next));
        if (outcome instanceof PortalEditOutcome.Refused refused) {
            return MenuResult.stay(PortalMenuText.settingNotice(context.viewer(), portal, refused.reason(), refused.arguments(), MenuFeedbackStyle.WARNING));
        }
        PortalMenuView updated = portals.portal(portal.id()).orElse(portal);
        if (updated.projection() == portal.projection()) {
            return MenuResult.stay();
        }
        return MenuResult.stay(PortalMenuText.settingNotice(context.viewer(), updated, WormholesMessages.PORTAL_PROJECTION_CHANGED,
            args("mode", PortalMenuText.projection(context.strings(), updated.projection())), MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult rename(MenuContext context, MenuAction action) {
        String name = ((MenuValue.Text) action.value()).text();
        if (name.isBlank()) {
            return MenuResult.stay(MenuNotices.nativeOnly(context.viewer(), MenuMessages.FEEDBACK_INVALID_VALUE, MessageArgs.empty(),
                MenuFeedbackStyle.ERROR));
        }
        PortalMenuView portal = require(context);
        PortalEditOutcome outcome = portals.edit(context.viewer(), portal.id(), new PortalEdit.Name(name));
        if (outcome instanceof PortalEditOutcome.Refused refused) {
            return MenuResult.stay(PortalMenuText.settingNotice(context.viewer(), portal, refused.reason(), refused.arguments(), MenuFeedbackStyle.WARNING));
        }
        return MenuResult.stay();
    }

    private MenuResult chooseType(MenuContext context, UUID portalId, PortalType target) {
        PortalMenuView portal = require(context);
        boolean changed = false;
        if (portal.mirror()) {
            portals.edit(context.viewer(), portalId, new PortalEdit.Mirror(false));
            changed = true;
        }
        if (portal.type() != target) {
            PortalEditOutcome outcome = portals.edit(context.viewer(), portalId, new PortalEdit.Type(target));
            if (outcome instanceof PortalEditOutcome.Refused refused) {
                return MenuResult.close(PortalMenuText.settingNotice(context.viewer(), portal, refused.reason(), refused.arguments(), MenuFeedbackStyle.WARNING));
            }
            changed = true;
        }
        if (!changed) {
            return MenuResult.close();
        }
        MessageArgs arguments = MessageArgs.builder()
            .untrusted("portal", portal.name())
            .untrusted("mode", PortalMenuText.type(context.strings(), target))
            .build();
        portals.announce(portalId, WormholesMessages.PORTAL_MODE_CHANGED, arguments, true);
        return MenuResult.close(MenuNotices.nativeOnly(context.viewer(), WormholesMessages.PORTAL_MODE_CHANGED, arguments, MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult chooseMirror(MenuContext context, UUID portalId) {
        PortalMenuView portal = require(context);
        if (portal.mirror()) {
            return MenuResult.close();
        }
        PortalEditOutcome outcome = portals.edit(context.viewer(), portalId, new PortalEdit.Mirror(true));
        if (outcome instanceof PortalEditOutcome.Refused refused) {
            return MenuResult.close(PortalMenuText.settingNotice(context.viewer(), portal, refused.reason(), refused.arguments(), MenuFeedbackStyle.WARNING));
        }
        MessageArgs arguments = MessageArgs.builder()
            .untrusted("portal", portal.name())
            .untrusted("mode", PortalMenuText.plain(context.strings(), WormholesMessages.PORTAL_LABEL_MIRROR))
            .build();
        portals.announce(portalId, WormholesMessages.PORTAL_MODE_CHANGED, arguments, true);
        return MenuResult.close(MenuNotices.nativeOnly(context.viewer(), WormholesMessages.PORTAL_MODE_CHANGED, arguments, MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult rotateMirror(MenuContext context, UUID portalId, boolean clockwise) {
        PortalMenuView portal = require(context);
        if (!portal.mirror()) {
            return MenuResult.stay(PortalMenuText.settingNotice(context.viewer(), portal, WormholesMessages.PORTAL_MIRROR_SELECT_FIRST, MessageArgs.empty(),
                MenuFeedbackStyle.WARNING));
        }
        PortalEditOutcome outcome = portals.edit(context.viewer(), portalId, new PortalEdit.MirrorRotation(clockwise));
        if (outcome instanceof PortalEditOutcome.Refused refused) {
            return MenuResult.stay(PortalMenuText.settingNotice(context.viewer(), portal, refused.reason(), refused.arguments(), MenuFeedbackStyle.WARNING));
        }
        PortalMenuView updated = portals.portal(portalId).orElse(portal);
        return MenuResult.stay(PortalMenuText.settingNotice(context.viewer(), updated, WormholesMessages.PORTAL_MIRROR_ROTATION_CHANGED,
            args("degrees", mirrorDegrees(updated)), MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult changeFrame(MenuContext context, UUID portalId, FrameOperation operation) {
        PortalMenuView portal = require(context);
        PortalEditOutcome outcome = portals.edit(context.viewer(), portalId, new PortalEdit.FrameChange(operation));
        if (outcome instanceof PortalEditOutcome.Refused refused) {
            return MenuResult.stay(PortalMenuText.settingNotice(context.viewer(), portal, refused.reason(), refused.arguments(), MenuFeedbackStyle.WARNING));
        }
        PortalMenuView updated = portals.portal(portalId).orElse(portal);
        TextKey key = switch (operation) {
            case FLIP_FACE -> WormholesMessages.PORTAL_FACE_FLIPPED;
            case ROTATE_CLOCKWISE -> WormholesMessages.PORTAL_ROTATED_CLOCKWISE;
            case ROTATE_COUNTER_CLOCKWISE -> WormholesMessages.PORTAL_ROTATED_COUNTERCLOCKWISE;
        };
        MessageArgs.Builder arguments = MessageArgs.builder().untrusted("portal", updated.name());
        if (operation == FrameOperation.FLIP_FACE) {
            arguments.untrusted("direction", PortalMenuText.direction(context.strings(), updated.frame().getNormal()));
        }
        MessageArgs built = arguments.build();
        portals.announce(portalId, key, built, true);
        return homeAgain(portalId, MenuNotices.nativeOnly(context.viewer(), key, built, MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult importCode(MenuContext context, UUID portalId, MenuAction action) {
        String code = ((MenuValue.Text) action.value()).text();
        ImportOutcome outcome = gateways.importCode(context.viewer(), portalId, code);
        if (outcome instanceof ImportOutcome.Rejected rejected) {
            return MenuResult.stay(MenuNotices.of(context.viewer(), rejected.reason(), rejected.arguments(), MenuFeedbackStyle.ERROR, NoticeChannel.NONE));
        }
        return MenuResult.stay();
    }

    private MenuDecision linkNotManaged(MenuContext context, MenuAction action) {
        PortalMenuView portal = require(context);
        if (!portal.managed()) {
            return MenuDecision.allow();
        }
        return MenuDecision.deny(PortalMenuText.settingNotice(context.viewer(), portal, WormholesMessages.PORTAL_DIMENSIONAL_LINK_MANAGED, MessageArgs.empty(),
            MenuFeedbackStyle.WARNING));
    }

    private MenuDecision projectionActive(MenuContext context, MenuAction action) {
        PortalMenuView portal = require(context);
        if (!portal.receiverOnly()) {
            return MenuDecision.allow();
        }
        return MenuDecision.deny(PortalMenuText.settingNotice(context.viewer(), portal, WormholesMessages.PORTAL_PROJECTION_RECEIVER_INACTIVE, MessageArgs.empty(),
            MenuFeedbackStyle.WARNING));
    }

    private MenuGuard typeAllowed(UUID portalId, PortalType target) {
        return (context, action) -> {
            PortalMenuView portal = require(context);
            if (portal.type() == target || portals.typeAllowed(context.viewer(), target)) {
                return MenuDecision.allow();
            }
            return MenuDecision.denyAndClose(MenuNotices.failure(context.viewer(), WormholesMessages.COMMAND_NO_PERMISSION, MessageArgs.empty()));
        };
    }

    private MenuConfirmRequest deleteConfirmation(MenuContext context, PortalMenuView portal, MenuHandler onConfirm) {
        MenuStrings strings = context.strings();
        MenuLines confirm = strings.lines(MenuMessages.CONFIRM_DELETE_PORTAL, args("portal", portal.name()), 0);
        return new MenuConfirmRequest(confirm.label(), confirm.description(), strings.text(MenuMessages.CONFIRM_DELETE_PORTAL_ACCEPT),
            strings.text(MenuMessages.CHROME_CANCEL), onConfirm, ChestConfirmLayout.standard(portal.routerTitle()));
    }

    private ListEntryElement mirrorOption(MenuStrings strings, PortalMenuView portal) {
        boolean mirror = portal.mirror();
        MenuLines lines = lines(strings, mirror ? WormholesMessages.PORTAL_MENU_MIRROR_SELECTED : WormholesMessages.PORTAL_MENU_MIRROR_AVAILABLE,
            MessageArgs.empty());
        if (mirror) {
            lines = lines.withDescription(List.of(strings.text(WormholesMessages.PORTAL_MENU_MIRROR_ROTATION, args("degrees", mirrorDegrees(portal)))));
            if (QuarterTurn.supportsQuarterTurns(portal.frame())) {
                lines = lines.withChestHints(List.of(
                    strings.text(WormholesMessages.PORTAL_MENU_MIRROR_ROTATE_CLOCKWISE),
                    strings.text(WormholesMessages.PORTAL_MENU_MIRROR_ROTATE_COUNTERCLOCKWISE)));
            } else {
                MenuLines flip = lines(strings, WormholesMessages.PORTAL_MENU_MIRROR_FLIP, MessageArgs.empty());
                List<String> flipLore = new ArrayList<>(flip.description().size() + 1);
                flipLore.add(flip.label());
                flipLore.addAll(flip.description());
                lines = lines.withDescription(flipLore).withChestHints(flip.chestHints());
            }
        }
        MenuElementCommon common = common("mode-mirror", lines, MenuIcon.item("minecraft:copper_torch", 1, mirror), "mirror",
            new MenuSlot(4, 1)
                .withClick(ChestClick.LEFT, ChestTarget.self(MenuInteraction.PRIMARY))
                .withClick(ChestClick.RIGHT, ChestTarget.self(MenuInteraction.SECONDARY))
                .withClick(ChestClick.SHIFT_RIGHT, ChestTarget.self(MenuInteraction.TERTIARY)))
            .interactions(Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY))
            .sideActions(List.of(
                new MenuSideAction(MenuInteraction.SECONDARY, strings.text(MenuMessages.ACTION_ROTATE_CLOCKWISE), ROTATE_SIDE_ICON),
                new MenuSideAction(MenuInteraction.TERTIARY, strings.text(MenuMessages.ACTION_ROTATE_COUNTERCLOCKWISE), ROTATE_SIDE_ICON)))
            .build();
        return new ListEntryElement(common, mirror);
    }

    private void frameElement(List<MenuElement> elements, MenuBindings.Builder bindings, MenuGuard manage, MenuStrings strings, PortalMenuView portal,
                              String id, LinesKey key, String item, int position, FrameOperation operation, MessageArgs up) {
        elements.add(new ButtonElement(common(id, lines(strings, key, up), MenuIcon.item(item), "rotate", new MenuSlot(position, 1))
            .interactions(PRESS).build()));
        UUID portalId = portal.id();
        bindings.guard(id, manage).on(id, MenuInteraction.PRIMARY, (ctx, action) -> changeFrame(ctx, portalId, operation));
    }

    private ButtonElement backElement(MenuStrings strings) {
        return new ButtonElement(common("back-to-portal", lines(strings, WormholesMessages.PORTAL_MENU_BACK, MessageArgs.empty()),
            MenuIcon.item("minecraft:arrow"), null, new MenuSlot(0, 2)).interactions(PRESS).nativeHidden(true).build());
    }

    private MenuScreen screen(String screenId, MenuStrings strings, TextKey title, PortalMenuView portal, List<MenuElement> elements) {
        return MenuScreen.builder(screenId)
            .title(strings.text(title))
            .context(strings.text(MenuMessages.CONTEXT_PORTAL, MessageArgs.builder()
                .untrusted("portal", portal.name())
                .untrusted("type", PortalMenuText.currentMode(strings, portal))
                .build()))
            .elements(elements)
            .build();
    }

    private MenuLines placardLines(MenuStrings strings, PortalMenuView portal) {
        String type = PortalMenuText.currentMode(strings, portal);
        String facing = PortalMenuText.direction(strings, portal.frame().getNormal());
        if (portal.rtp() != null) {
            return lines(strings, WormholesMessages.PORTAL_MENU_PLACARD_RTP, MessageArgs.builder()
                .untrusted("portal", portal.name())
                .untrusted("type", type)
                .untrusted("facing", facing)
                .untrusted("allocation", PortalMenuText.rtpAllocation(strings, portal.rtp().allocation()))
                .untrusted("rotation", PortalMenuText.rtpRotation(strings, portal.rtp()))
                .build());
        }
        if (portal.destination() != null) {
            return lines(strings, WormholesMessages.PORTAL_MENU_PLACARD_LINKED, MessageArgs.builder()
                .untrusted("portal", portal.name())
                .untrusted("type", type)
                .untrusted("facing", facing)
                .untrusted("destination", portal.destination().name())
                .build());
        }
        return lines(strings, WormholesMessages.PORTAL_MENU_PLACARD_UNLINKED, MessageArgs.builder()
            .untrusted("portal", portal.name())
            .untrusted("type", type)
            .untrusted("facing", facing)
            .untrusted("none", PortalMenuText.plain(strings, WormholesMessages.LABEL_NONE))
            .build());
    }

    private MenuLines gatewayLines(MenuStrings strings, GatewayView gateway) {
        if (!gateway.paired()) {
            return lines(strings, WormholesMessages.PORTAL_MENU_GATEWAY_UNPAIRED, MessageArgs.empty());
        }
        MenuLines lines = lines(strings, WormholesMessages.PORTAL_MENU_GATEWAY_PAIRED, args("destination", gateway.destinationName()));
        if (gateway.server() == null) {
            return lines;
        }
        TextKey state = switch (gateway.link()) {
            case DIRECT -> WormholesMessages.PORTAL_LABEL_DIRECT;
            case SIDEBAND -> WormholesMessages.PORTAL_LABEL_SIDEBAND;
            case RECONNECTING -> WormholesMessages.PORTAL_LABEL_RECONNECTING;
        };
        return lines.withDescription(List.of(
            strings.text(WormholesMessages.PORTAL_MENU_GATEWAY_SERVER, args("server", gateway.server())),
            strings.text(WormholesMessages.PORTAL_MENU_GATEWAY_LINK, args("state", PortalMenuText.plain(strings, state)))));
    }

    private PortalMenuView require(MenuContext context) {
        UUID portalId = PortalRoutes.portalId(context.route());
        return portals.portal(portalId).orElseThrow(() -> new IllegalStateException("portal " + portalId + " is gone"));
    }

    private MenuResult homeAgain(UUID portalId, MenuNotice notice) {
        return MenuResult.navigate(PortalRoutes.home(portalId), NavigateMode.POP_TO, notice);
    }

    private MenuRoute destinationRoute(PortalMenuView portal) {
        if (portal.rtp() != null) {
            return PortalRoutes.rtpOverview(portal.id());
        }
        return portal.gateway() ? PortalRoutes.gateway(portal.id()) : PortalRoutes.destinationPicker(portal.id());
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

    private static MessageArgs orientationArgs(MenuStrings strings, PortalMenuView portal) {
        Frame frame = portal.frame();
        return MessageArgs.builder()
            .untrusted("facing", PortalMenuText.direction(strings, frame.getNormal()))
            .untrusted("up", PortalMenuText.direction(strings, frame.getUp()))
            .build();
    }

    private static MessageArgs cancelArgs(MenuStrings strings) {
        return args("cancel", PortalMenuText.plain(strings, WormholesMessages.PORTAL_INPUT_CANCEL));
    }

    private static int mirrorDegrees(PortalMenuView portal) {
        return portal.mirrorRotation().coherentFor(portal.frame()).getDegrees();
    }

    private static int destinationCount(PortalMenuView portal) {
        return Math.max(1, Math.min(MenuLimits.MAX_ITEM_COUNT, portal.accessibleCount() - 1));
    }

    private static String modeItem(PortalType type) {
        return switch (type) {
            case GATEWAY -> "minecraft:end_crystal";
            case WORMHOLE -> "minecraft:ender_pearl";
            case PORTAL -> "minecraft:ender_eye";
            case RTP -> "minecraft:compass";
        };
    }

    private static String sprite(PortalType type) {
        return switch (type) {
            case GATEWAY -> "gateway";
            case WORMHOLE -> "wormhole";
            case PORTAL -> "portal";
            case RTP -> "rtp";
        };
    }
}

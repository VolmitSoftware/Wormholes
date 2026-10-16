package art.arcane.wormholes.menu.transit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import art.arcane.optics.crossing.ScaleRule;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.MenuMessages;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.ChestClick;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.ChestTarget;
import art.arcane.wormholes.menu.CycleElement;
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
import art.arcane.wormholes.menu.MenuHandler;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.MenuLines;
import art.arcane.wormholes.menu.MenuNotices;
import art.arcane.wormholes.menu.MenuResult;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuStrings;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.NavigateMode;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.portal.PortalMenuHost;
import art.arcane.wormholes.menu.portal.PortalMenuText;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import art.arcane.wormholes.transit.ScaleRuleChange;
import art.arcane.wormholes.transit.ScaleRuleSettings;
import art.arcane.wormholes.transit.TransitionProfile;

public final class TransitMenuController implements MenuController {
    public static final Map<LinesKey, Integer> CHEST_HINTS = Map.ofEntries(
        Map.entry(TransitMessages.MENU_GRAVITY, 1),
        Map.entry(TransitMessages.MENU_MOMENTUM, 2),
        Map.entry(TransitMessages.MENU_ORIENTATION, 1),
        Map.entry(TransitMessages.MENU_MEMBRANE, 1),
        Map.entry(TransitMessages.MENU_BOUNCE, 1),
        Map.entry(TransitMessages.MENU_PROFILE, 2),
        Map.entry(TransitMessages.MENU_SCALE, 2),
        Map.entry(WormholesMessages.PORTAL_MENU_BACK_SETTINGS, 0));

    private static final Set<MenuInteraction> PRESS = Set.of(MenuInteraction.PRIMARY);
    private static final Set<MenuInteraction> TEXT = Set.of(MenuInteraction.SET_TEXT);
    private static final String MOMENTUM_FACTOR = "transit-momentum-factor";
    private static final String THRESHOLD = "transit-threshold";
    private static final String ARRIVAL = "transit-arrival";
    private static final String MASK = "transit-mask";
    private static final String SCALE_MIN = "transit-scale-min";
    private static final String SCALE_MAX = "transit-scale-max";

    private final PortalMenuHost portals;
    private final TransitMenuHost transit;

    public TransitMenuController(PortalMenuHost portals, TransitMenuHost transit) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.transit = Objects.requireNonNull(transit, "transit");
    }

    @Override
    public String id() {
        return TransitRoutes.CONTROLLER;
    }

    @Override
    public MenuDecision routeGuard(MenuContext context) {
        UUID portalId = PortalRoutes.portalId(context.route());
        if (portals.portal(portalId).isEmpty() || !portals.canManage(context.viewer(), portalId)) {
            return MenuDecision.denyAndClose(MenuNotices.hud(context.viewer(), WormholesMessages.PORTAL_EDIT_DENIED));
        }
        if (transit.transit(portalId).isEmpty()) {
            return MenuDecision.denyAndClose(MenuNotices.nativeOnly(context.viewer(), MenuMessages.FEEDBACK_UNAVAILABLE, MessageArgs.empty(),
                MenuFeedbackStyle.WARNING));
        }
        return MenuDecision.allow();
    }

    @Override
    public MenuBuild build(MenuContext context) {
        UUID portalId = PortalRoutes.portalId(context.route());
        PortalMenuView portal = portals.portal(portalId).orElseThrow(() -> new IllegalStateException("portal " + portalId + " is gone"));
        TransitMenuView view = require(context);
        MenuStrings strings = context.strings();
        Layout layout = new Layout(strings, MenuGuards.manage(portals, portalId), List.of(strings.text(TransitMessages.MENU_PROMPT,
            args("cancel", PortalMenuText.plain(strings, WormholesMessages.PORTAL_INPUT_CANCEL)))));
        if (transit.supportsGravityRotation()) {
            boolean gravity = view.gravityRotation();
            MessageArgs gravityState = args("state", gravity ? "on" : "off");
            layout.add(new ToggleElement(common("transit-gravity", lines(strings, TransitMessages.MENU_GRAVITY, gravityState),
                "minecraft:feather", gravity, "transit", new MenuSlot(-3, 1))
                .nativeLabel(strings.settingName(TransitMessages.MENU_GRAVITY, gravityState, "state")).interactions(PRESS).build(), gravity),
                (ctx, action) -> apply(ctx, new TransitEdit.Gravity(!require(ctx).gravityRotation())));
        }
        momentum(layout, view);
        OrientationPolicy orientation = view.effectiveOrientation();
        MessageArgs orientationMode = args("mode", orientation.label());
        layout.add(new CycleElement(common("transit-orientation", lines(strings, TransitMessages.MENU_ORIENTATION, orientationMode),
            "minecraft:compass", view.orientation() != null, "rotate", new MenuSlot(-1, 1))
            .nativeLabel(strings.settingName(TransitMessages.MENU_ORIENTATION, orientationMode, "mode")).interactions(PRESS).build(),
            labels(OrientationPolicy.values(), OrientationPolicy::label), orientation.ordinal()),
            (ctx, action) -> apply(ctx, new TransitEdit.Orientation(require(ctx).effectiveOrientation().next())));
        layout.add(toggle(strings, "transit-membrane", TransitMessages.MENU_MEMBRANE, "minecraft:phantom_membrane", "portal", 0, view.membrane()),
            (ctx, action) -> apply(ctx, new TransitEdit.Membrane(!require(ctx).membrane())));
        layout.add(toggle(strings, "transit-bounce", TransitMessages.MENU_BOUNCE, "minecraft:slime_block", "transit", 1, view.bounce()),
            (ctx, action) -> apply(ctx, new TransitEdit.Bounce(!require(ctx).bounce())));
        profile(layout, view.profile());
        scale(layout, view.scaleRule());
        layout.add(new ButtonElement(common("transit-back", lines(strings, WormholesMessages.PORTAL_MENU_BACK_SETTINGS, MessageArgs.empty()),
            "minecraft:arrow", false, null, new MenuSlot(0, 2)).interactions(PRESS).nativeHidden(true).build()),
            (ctx, action) -> MenuResult.navigate(PortalRoutes.home(portalId), NavigateMode.POP_TO, null));
        MenuScreen screen = MenuScreen.builder(TransitRoutes.MAIN)
            .title(strings.text(MenuMessages.TITLE_TRANSIT))
            .context(strings.text(MenuMessages.CONTEXT_PORTAL, MessageArgs.builder()
                .untrusted("portal", portal.name())
                .untrusted("type", PortalMenuText.currentMode(strings, portal))
                .build()))
            .elements(layout.elements)
            .build();
        return new MenuBuild(screen, new ChestLayout(portal.routerTitle(), 3, ChestPane.CYAN, null), layout.bindings.build());
    }

    @Override
    public boolean touches(MenuRoute route, MenuScope scope) {
        return scope instanceof MenuScope.Portal portal && portal.portalId().equals(PortalRoutes.portalId(route));
    }

    private void momentum(Layout layout, TransitMenuView view) {
        MomentumPolicy momentum = view.effectiveMomentum();
        MessageArgs arguments = MessageArgs.builder()
            .untrusted("mode", momentum.mode().label())
            .untrusted("value", TransitInputs.formatFactor(momentum.factor()))
            .build();
        layout.add(new CycleElement(common("transit-momentum", lines(layout.strings, TransitMessages.MENU_MOMENTUM, arguments),
            "minecraft:slime_ball", view.momentum() != null, "transit",
            new MenuSlot(-2, 1)
                .withClick(ChestClick.LEFT, ChestTarget.self(MenuInteraction.PRIMARY))
                .withClick(ChestClick.RIGHT, ChestTarget.delegate(MOMENTUM_FACTOR, MenuInteraction.SET_TEXT)))
            .nativeLabel(layout.strings.settingName(TransitMessages.MENU_MOMENTUM, arguments, "mode"))
            .interactions(PRESS).build(), labels(MomentumPolicy.Mode.values(), MomentumPolicy.Mode::label), momentum.mode().ordinal()),
            (ctx, action) -> cycleMomentum(ctx));
        layout.add(text(layout, MOMENTUM_FACTOR, MenuMessages.TRANSIT_FACTOR, MenuMessages.PLACEHOLDER_FACTOR, "minecraft:slime_ball",
            TransitInputs.formatFactor(momentum.factor())), this::momentumFactor);
    }

    private void profile(Layout layout, TransitionProfile profile) {
        layout.add(new ButtonElement(common("transit-profile", lines(layout.strings, TransitMessages.MENU_PROFILE, MessageArgs.builder()
                .untrusted("mode", TransitInputs.labelOrDefault(profile.thresholdEffect()))
                .untrusted("state", TransitInputs.labelOrDefault(profile.arrivalSound())
                    + (profile.overridesMask() ? " / " + profile.maskOverrideTicks() + "t" : ""))
                .build()), "minecraft:note_block", !profile.isNone(), null,
            new MenuSlot(2, 1)
                .withClick(ChestClick.LEFT, ChestTarget.delegate(THRESHOLD, MenuInteraction.SET_TEXT))
                .withClick(ChestClick.RIGHT, ChestTarget.delegate(ARRIVAL, MenuInteraction.SET_TEXT))
                .withClick(ChestClick.SHIFT_LEFT, ChestTarget.delegate(MASK, MenuInteraction.SET_TEXT)))
            .nativeHidden(true).build()));
        layout.add(text(layout, THRESHOLD, MenuMessages.TRANSIT_THRESHOLD_EFFECT, MenuMessages.TRANSIT_THRESHOLD_EFFECT, "minecraft:note_block",
                profile.thresholdEffect()),
            (ctx, action) -> apply(ctx, new TransitEdit.Profile(require(ctx).profile().withThresholdEffect(TransitInputs.soundOrEmpty(input(action.value()))))));
        layout.add(text(layout, ARRIVAL, MenuMessages.TRANSIT_ARRIVAL_SOUND, MenuMessages.PLACEHOLDER_SOUND, "minecraft:note_block",
                profile.arrivalSound()),
            (ctx, action) -> apply(ctx, new TransitEdit.Profile(require(ctx).profile().withArrivalSound(TransitInputs.soundOrEmpty(input(action.value()))))));
        layout.add(text(layout, MASK, MenuMessages.TRANSIT_MASK_TICKS, MenuMessages.PLACEHOLDER_TICKS, "minecraft:note_block",
            profile.overridesMask() ? Integer.toString(profile.maskOverrideTicks()) : ""), this::maskTicks);
    }

    private void scale(Layout layout, ScaleRule scale) {
        MessageArgs arguments = MessageArgs.builder()
            .untrusted("mode", ScaleRuleChange.mode(scale))
            .untrusted("value", ScaleRuleChange.range(scale))
            .build();
        layout.add(new CycleElement(common("transit-scale", lines(layout.strings, TransitMessages.MENU_SCALE, arguments),
            "minecraft:spyglass", !ScaleRuleSettings.isDefault(scale), "transit",
            new MenuSlot(3, 1)
                .withClick(ChestClick.LEFT, ChestTarget.self(MenuInteraction.PRIMARY))
                .withClick(ChestClick.RIGHT, ChestTarget.delegate(SCALE_MIN, MenuInteraction.SET_TEXT))
                .withClick(ChestClick.SHIFT_RIGHT, ChestTarget.delegate(SCALE_MAX, MenuInteraction.SET_TEXT)))
            .nativeLabel(layout.strings.settingName(TransitMessages.MENU_SCALE, arguments, "mode"))
            .interactions(PRESS).build(), labels(ScaleRule.Mode.values(), ScaleRuleSettings::format), scale.mode().ordinal()),
            (ctx, action) -> cycleScale(ctx));
        layout.add(text(layout, SCALE_MIN, MenuMessages.TRANSIT_SCALE_MIN, MenuMessages.PLACEHOLDER_SCALE, "minecraft:spyglass",
                ScaleRuleSettings.format(scale.min())),
            (ctx, action) -> scaleBound(ctx, action.value(), true));
        layout.add(text(layout, SCALE_MAX, MenuMessages.TRANSIT_SCALE_MAX, MenuMessages.PLACEHOLDER_SCALE, "minecraft:spyglass",
                ScaleRuleSettings.format(scale.max())),
            (ctx, action) -> scaleBound(ctx, action.value(), false));
    }

    private MenuResult cycleMomentum(MenuContext context) {
        MomentumPolicy current = require(context).effectiveMomentum();
        return apply(context, new TransitEdit.Momentum(current.withMode(current.mode().next())));
    }

    private MenuResult momentumFactor(MenuContext context, MenuAction action) {
        Double factor = TransitInputs.parseFactor(input(action.value()));
        if (factor == null) {
            return invalid(context);
        }
        return apply(context, new TransitEdit.Momentum(require(context).effectiveMomentum().withFactor(factor.doubleValue())));
    }

    private MenuResult maskTicks(MenuContext context, MenuAction action) {
        Integer ticks = TransitInputs.parseMaskTicks(input(action.value()));
        if (ticks == null) {
            return invalid(context);
        }
        return apply(context, new TransitEdit.Profile(require(context).profile().withMaskOverrideTicks(ticks.intValue())));
    }

    private MenuResult cycleScale(MenuContext context) {
        ScaleRule current = require(context).scaleRule();
        return apply(context, new TransitEdit.Scale(ScaleRuleSettings.withMode(current, ScaleRuleSettings.next(current.mode()))));
    }

    private MenuResult scaleBound(MenuContext context, MenuValue value, boolean minimum) {
        Double bound = ScaleRuleSettings.bound(input(value));
        if (bound == null) {
            return invalid(context);
        }
        ScaleRule current = require(context).scaleRule();
        ScaleRule next = minimum ? ScaleRuleSettings.withMin(current, bound.doubleValue()) : ScaleRuleSettings.withMax(current, bound.doubleValue());
        return apply(context, new TransitEdit.Scale(next));
    }

    private MenuResult apply(MenuContext context, TransitEdit edit) {
        transit.apply(context.viewer(), PortalRoutes.portalId(context.route()), edit);
        return MenuResult.stay();
    }

    private TransitMenuView require(MenuContext context) {
        UUID portalId = PortalRoutes.portalId(context.route());
        Optional<TransitMenuView> view = transit.transit(portalId);
        return view.orElseThrow(() -> new IllegalStateException("portal " + portalId + " has no transit settings"));
    }

    private static MenuResult invalid(MenuContext context) {
        return MenuResult.stay(MenuNotices.nativeOnly(context.viewer(), MenuMessages.FEEDBACK_INVALID_VALUE, MessageArgs.empty(),
            MenuFeedbackStyle.ERROR));
    }

    private static String input(MenuValue value) {
        return ((MenuValue.Text) value).text().trim();
    }

    private static ToggleElement toggle(MenuStrings strings, String id, LinesKey key, String itemId, String sprite, int position, boolean enabled) {
        MessageArgs state = args("state", PortalMenuText.onOff(strings, enabled));
        return new ToggleElement(common(id, lines(strings, key, state), itemId, enabled, sprite, new MenuSlot(position, 1))
            .nativeLabel(strings.settingName(key, state, "state")).interactions(PRESS).build(), enabled);
    }

    private static TextElement text(Layout layout, String id, TextKey label, TextKey placeholder, String itemId, String value) {
        return new TextElement(MenuElementCommon.builder(id)
            .label(layout.strings.text(label))
            .icon(MenuIcon.item(itemId))
            .interactions(TEXT)
            .build(), PortalMenuText.clip(value, MenuLimits.MAX_TEXT_INPUT_LENGTH), MenuLimits.MAX_TEXT_INPUT_LENGTH, layout.strings.text(placeholder),
            layout.prompt, ChestReopen.ALWAYS, null);
    }

    private static MenuElementCommon.Builder common(String id, MenuLines lines, String itemId, boolean glint, String sprite, MenuSlot slot) {
        return MenuElementCommon.builder(id)
            .lines(lines)
            .icon(MenuIcon.item(itemId, 1, glint))
            .nativeIcon(sprite == null ? null : MenuIcon.sprite(sprite))
            .chestSlot(slot);
    }

    private static <T> List<String> labels(T[] values, Function<T, String> label) {
        List<String> labels = new ArrayList<>(values.length);
        for (T value : values) {
            labels.add(label.apply(value));
        }
        return labels;
    }

    private static MenuLines lines(MenuStrings strings, LinesKey key, MessageArgs arguments) {
        return strings.lines(key, arguments, CHEST_HINTS.getOrDefault(key, 0));
    }

    private static MessageArgs args(String name, Object value) {
        return MessageArgs.builder().untrusted(name, value).build();
    }

    private static final class Layout {
        private final MenuStrings strings;
        private final MenuGuard manage;
        private final List<String> prompt;
        private final List<MenuElement> elements = new ArrayList<>(14);
        private final MenuBindings.Builder bindings = MenuBindings.builder();

        private Layout(MenuStrings strings, MenuGuard manage, List<String> prompt) {
            this.strings = strings;
            this.manage = manage;
            this.prompt = prompt;
        }

        private void add(MenuElement element) {
            elements.add(element);
        }

        private void add(MenuElement element, MenuHandler handler) {
            elements.add(element);
            MenuInteraction interaction = element instanceof TextElement ? MenuInteraction.SET_TEXT : MenuInteraction.PRIMARY;
            bindings.guard(element.id(), manage).on(element.id(), interaction, handler);
        }
    }
}

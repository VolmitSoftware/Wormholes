package art.arcane.wormholes.menu.fidelity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.optics.volume.LodProfile;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.FidelityMessages;
import art.arcane.wormholes.localization.MenuMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.ChestClick;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestTarget;
import art.arcane.wormholes.menu.CycleElement;
import art.arcane.wormholes.menu.LabelElement;
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
import art.arcane.wormholes.menu.MenuNotices;
import art.arcane.wormholes.menu.MenuResult;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSideAction;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuStrings;
import art.arcane.wormholes.menu.NavigateMode;
import art.arcane.wormholes.menu.portal.PortalMenuHost;
import art.arcane.wormholes.menu.portal.PortalMenuText;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.menu.portal.PortalRoutes;

public final class FidelityMenuController implements MenuController {
    public static final Map<LinesKey, Integer> CHEST_HINTS = Map.of(
        FidelityMessages.MENU_PLACARD, 0,
        FidelityMessages.MENU_HINT, 1,
        FidelityMessages.MENU_BACK, 0);

    private static final Set<MenuInteraction> CYCLE = Set.of(MenuInteraction.PRIMARY, MenuInteraction.TERTIARY);
    private static final MenuIcon RESET_SIDE_ICON = MenuIcon.sprite("small/reset");
    private static final Channel ATMOSPHERE = new Channel("fidelity-atmosphere", FidelityMessages.MENU_ATMOSPHERE, "mode",
        "minecraft:light_blue_stained_glass", -3);
    private static final Channel ACOUSTICS = new Channel("fidelity-acoustics", FidelityMessages.MENU_ACOUSTICS, "mode", "minecraft:note_block", -1);
    private static final Channel LOD = new Channel("fidelity-lod", FidelityMessages.MENU_LOD, "mode", "minecraft:spyglass", 1);
    private static final Channel BLOCK_ENTITIES = new Channel("fidelity-block-entities", FidelityMessages.MENU_BLOCK_ENTITIES, "state",
        "minecraft:oak_sign", 3);

    private final PortalMenuHost portals;
    private final FidelityMenuHost fidelity;

    public FidelityMenuController(PortalMenuHost portals, FidelityMenuHost fidelity) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.fidelity = Objects.requireNonNull(fidelity, "fidelity");
    }

    @Override
    public String id() {
        return FidelityRoutes.CONTROLLER;
    }

    @Override
    public MenuDecision routeGuard(MenuContext context) {
        UUID portalId = PortalRoutes.portalId(context.route());
        if (portals.portal(portalId).isEmpty() || !portals.canManage(context.viewer(), portalId)) {
            return MenuDecision.denyAndClose(MenuNotices.hud(context.viewer(), WormholesMessages.PORTAL_EDIT_DENIED));
        }
        if (fidelity.fidelity(portalId).isEmpty()) {
            return MenuDecision.denyAndClose(MenuNotices.nativeOnly(context.viewer(), MenuMessages.FEEDBACK_UNAVAILABLE, MessageArgs.empty(),
                MenuFeedbackStyle.WARNING));
        }
        return MenuDecision.allow();
    }

    @Override
    public MenuBuild build(MenuContext context) {
        UUID portalId = PortalRoutes.portalId(context.route());
        PortalMenuView portal = portals.portal(portalId).orElseThrow(() -> new IllegalStateException("portal " + portalId + " is gone"));
        FidelityMenuView view = require(portalId);
        MenuStrings strings = context.strings();
        MenuGuard manage = MenuGuards.manage(portals, portalId);
        List<MenuElement> elements = new ArrayList<>(6);
        MenuBindings.Builder bindings = MenuBindings.builder();

        MenuLines placard = lines(strings, FidelityMessages.MENU_PLACARD, MessageArgs.empty());
        int bedrockViewers = fidelity.bedrockViewers();
        if (bedrockViewers > 0) {
            placard = placard.withDescription(List.of(strings.text(FidelityMessages.BEDROCK_PROFILE, args("count", Integer.valueOf(bedrockViewers)))));
        }
        elements.add(new LabelElement(MenuElementCommon.builder("fidelity-placard")
            .lines(placard)
            .icon(MenuIcon.item("minecraft:comparator"))
            .chestSlot(new MenuSlot(0, 0))
            .build()));

        String fallback = PortalMenuText.plain(strings, FidelityMessages.MENU_DEFAULT);
        AtmosphereMode atmosphere = view.atmosphere();
        elements.add(cycler(strings, ATMOSPHERE, atmosphere == null ? null : atmosphere.configName(),
            options(fallback, AtmosphereMode.values(), AtmosphereMode::configName), atmosphere == null ? 0 : atmosphere.ordinal() + 1));
        bind(bindings, manage, ATMOSPHERE, current -> new FidelityEdit.Atmosphere(current.effectiveAtmosphere().next()),
            new FidelityEdit.Atmosphere(null));
        AcousticsProfile acoustics = view.acoustics();
        elements.add(cycler(strings, ACOUSTICS, acoustics == null ? null : acoustics.configName(),
            options(fallback, AcousticsProfile.values(), AcousticsProfile::configName), acoustics == null ? 0 : acoustics.ordinal() + 1));
        bind(bindings, manage, ACOUSTICS, current -> new FidelityEdit.Acoustics(current.effectiveAcoustics().next()),
            new FidelityEdit.Acoustics(null));
        LodProfile lod = view.lod();
        elements.add(cycler(strings, LOD, lod == null ? null : lod.configName(), options(fallback, LodProfile.values(), LodProfile::configName),
            lod == null ? 0 : lod.ordinal() + 1));
        bind(bindings, manage, LOD, current -> new FidelityEdit.Lod(current.effectiveLod().next()), new FidelityEdit.Lod(null));
        Boolean blockEntities = view.blockEntities();
        elements.add(cycler(strings, BLOCK_ENTITIES, blockEntities == null ? null : blockEntities.toString(),
            List.of(fallback, PortalMenuText.onOff(strings, true), PortalMenuText.onOff(strings, false)),
            blockEntities == null ? 0 : blockEntities.booleanValue() ? 1 : 2));
        bind(bindings, manage, BLOCK_ENTITIES, current -> new FidelityEdit.BlockEntities(Boolean.valueOf(!current.effectiveBlockEntities())),
            new FidelityEdit.BlockEntities(null));

        elements.add(new ButtonElement(MenuElementCommon.builder("fidelity-back")
            .lines(lines(strings, FidelityMessages.MENU_BACK, MessageArgs.empty()))
            .icon(MenuIcon.item("minecraft:arrow"))
            .interactions(Set.of(MenuInteraction.PRIMARY))
            .chestSlot(new MenuSlot(0, 2))
            .nativeHidden(true)
            .build()));
        bindings.guard("fidelity-back", manage)
            .on("fidelity-back", MenuInteraction.PRIMARY,
                (ctx, action) -> MenuResult.navigate(PortalRoutes.home(portalId), NavigateMode.POP_TO, null));

        MenuScreen screen = MenuScreen.builder(FidelityRoutes.MAIN)
            .title(strings.text(MenuMessages.TITLE_FIDELITY))
            .context(strings.text(MenuMessages.CONTEXT_PORTAL, MessageArgs.builder()
                .untrusted("portal", portal.name())
                .untrusted("type", PortalMenuText.currentMode(strings, portal))
                .build()))
            .elements(elements)
            .build();
        return new MenuBuild(screen, new ChestLayout(portal.routerTitle(), 3, ChestPane.CYAN, null), bindings.build());
    }

    @Override
    public boolean touches(MenuRoute route, MenuScope scope) {
        return scope instanceof MenuScope.Portal portal && portal.portalId().equals(PortalRoutes.portalId(route));
    }

    private MenuResult apply(MenuContext context, FidelityEdit edit) {
        fidelity.apply(context.viewer(), PortalRoutes.portalId(context.route()), edit);
        return MenuResult.stay();
    }

    private FidelityMenuView require(UUID portalId) {
        return fidelity.fidelity(portalId).orElseThrow(() -> new IllegalStateException("portal " + portalId + " has no fidelity settings"));
    }

    private void bind(MenuBindings.Builder bindings, MenuGuard manage, Channel channel, Function<FidelityMenuView, FidelityEdit> next,
                      FidelityEdit reset) {
        bindings.guard(channel.id(), manage)
            .on(channel.id(), MenuInteraction.PRIMARY, (ctx, action) -> apply(ctx, next.apply(require(PortalRoutes.portalId(ctx.route())))))
            .on(channel.id(), MenuInteraction.TERTIARY, (ctx, action) -> apply(ctx, reset));
    }

    private static CycleElement cycler(MenuStrings strings, Channel channel, String value, List<String> options, int index) {
        String shown = value == null ? options.get(0) : value;
        MessageArgs shownArguments = args(channel.placeholder(), shown);
        MenuElementCommon common = MenuElementCommon.builder(channel.id())
            .lines(lines(strings, FidelityMessages.MENU_HINT, MessageArgs.empty()))
            .label(strings.text(channel.name(), shownArguments))
            .nativeLabel(strings.settingName(channel.name(), shownArguments, channel.placeholder()))
            .icon(MenuIcon.item(channel.itemId(), 1, value != null))
            .interactions(CYCLE)
            .sideActions(List.of(new MenuSideAction(MenuInteraction.TERTIARY, strings.text(MenuMessages.ACTION_USE_DEFAULT), RESET_SIDE_ICON)))
            .chestSlot(new MenuSlot(channel.position(), 1)
                .withClick(ChestClick.LEFT, ChestTarget.self(MenuInteraction.PRIMARY))
                .withClick(ChestClick.SHIFT_LEFT, ChestTarget.self(MenuInteraction.TERTIARY)))
            .build();
        return new CycleElement(common, options, index);
    }

    private static <E> List<String> options(String fallback, E[] values, Function<E, String> name) {
        List<String> options = new ArrayList<>(values.length + 1);
        options.add(fallback);
        for (E value : values) {
            options.add(name.apply(value));
        }
        return options;
    }

    private static MenuLines lines(MenuStrings strings, LinesKey key, MessageArgs arguments) {
        return strings.lines(key, arguments, CHEST_HINTS.getOrDefault(key, 0));
    }

    private static MessageArgs args(String name, Object value) {
        return MessageArgs.builder().untrusted(name, value).build();
    }

    private record Channel(String id, TextKey name, String placeholder, String itemId, int position) {
    }
}

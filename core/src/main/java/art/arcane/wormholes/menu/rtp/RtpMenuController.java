package art.arcane.wormholes.menu.rtp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.DoubleFunction;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.MenuMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.ChestClick;
import art.arcane.wormholes.menu.ChestConfirmLayout;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestTarget;
import art.arcane.wormholes.menu.LabelElement;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.MenuActionOrigin;
import art.arcane.wormholes.menu.MenuBindings;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuConfirmFactory;
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
import art.arcane.wormholes.menu.MenuLines;
import art.arcane.wormholes.menu.MenuNotice;
import art.arcane.wormholes.menu.MenuNotices;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuResult;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuStrings;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.NavigateMode;
import art.arcane.wormholes.menu.NumberElement;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.portal.PortalMenuHost;
import art.arcane.wormholes.menu.portal.PortalMenuText;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.portal.rtp.RtpAllocationMode;
import art.arcane.wormholes.portal.rtp.RtpCenterMode;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.AllocationMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.BiomeOption;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.EditorSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ManualAction;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.Mutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ResetCenterTargetMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.RimMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.RotationMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.SafetyModeMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.SettingsSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.SoundMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusState;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.TargetBiomeMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.TargetWorldMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.VerticalModeMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.WorldOption;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.YField;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;
import art.arcane.wormholes.portal.rtp.RtpSafetyMode;
import art.arcane.wormholes.portal.rtp.RtpVerticalMode;

public final class RtpMenuController implements MenuController {
    public static final Map<LinesKey, Integer> CHEST_HINTS = Map.ofEntries(
        Map.entry(WormholesMessages.RTP_STATUS_HEADER, 0),
        Map.entry(WormholesMessages.RTP_OVERVIEW_DESTINATION, 1),
        Map.entry(WormholesMessages.RTP_OVERVIEW_LANDING, 1),
        Map.entry(WormholesMessages.RTP_OVERVIEW_ROUTING, 1),
        Map.entry(WormholesMessages.RTP_OVERVIEW_EFFECTS, 1),
        Map.entry(WormholesMessages.RTP_RESET_DEFAULTS, 1),
        Map.entry(WormholesMessages.RTP_BACK_PORTAL, 1),
        Map.entry(WormholesMessages.RTP_BACK_OVERVIEW, 1),
        Map.entry(WormholesMessages.RTP_BACK_CATEGORY, 1),
        Map.entry(WormholesMessages.RTP_DESTINATION_HEADER, 1),
        Map.entry(WormholesMessages.RTP_WORLD_CURRENT, 0),
        Map.entry(WormholesMessages.RTP_WORLD_AVAILABLE, 1),
        Map.entry(WormholesMessages.RTP_PREVIOUS_WORLDS, 1),
        Map.entry(WormholesMessages.RTP_NEXT_WORLDS, 1),
        Map.entry(WormholesMessages.RTP_BIOME_LINK, 1),
        Map.entry(WormholesMessages.RTP_BIOME_HEADER, 0),
        Map.entry(WormholesMessages.RTP_BIOME_ANY_SELECTED, 0),
        Map.entry(WormholesMessages.RTP_BIOME_ANY_AVAILABLE, 1),
        Map.entry(WormholesMessages.RTP_BIOME_CURRENT, 0),
        Map.entry(WormholesMessages.RTP_BIOME_AVAILABLE, 1),
        Map.entry(WormholesMessages.RTP_BIOME_EMPTY, 0),
        Map.entry(WormholesMessages.RTP_PREVIOUS_BIOMES, 1),
        Map.entry(WormholesMessages.RTP_NEXT_BIOMES, 1),
        Map.entry(WormholesMessages.RTP_CENTER_PORTAL_SELECTED, 0),
        Map.entry(WormholesMessages.RTP_CENTER_PORTAL_AVAILABLE, 1),
        Map.entry(WormholesMessages.RTP_CENTER_CUSTOM_SELECTED, 0),
        Map.entry(WormholesMessages.RTP_CENTER_CUSTOM_AVAILABLE, 1),
        Map.entry(WormholesMessages.RTP_NUMERIC_LINK, 1),
        Map.entry(WormholesMessages.RTP_RESET_CENTER, 1),
        Map.entry(WormholesMessages.RTP_LANDING_HEADER, 0),
        Map.entry(WormholesMessages.RTP_SURFACE_SELECTED, 0),
        Map.entry(WormholesMessages.RTP_SURFACE_AVAILABLE, 1),
        Map.entry(WormholesMessages.RTP_PREFERRED_SELECTED, 0),
        Map.entry(WormholesMessages.RTP_PREFERRED_AVAILABLE, 1),
        Map.entry(WormholesMessages.RTP_SAFE_LANDING, 1),
        Map.entry(WormholesMessages.RTP_ROUTING_HEADER, 0),
        Map.entry(WormholesMessages.RTP_SHARED_SELECTED, 0),
        Map.entry(WormholesMessages.RTP_SHARED_AVAILABLE, 1),
        Map.entry(WormholesMessages.RTP_PRIVATE_SELECTED, 0),
        Map.entry(WormholesMessages.RTP_PRIVATE_AVAILABLE, 1),
        Map.entry(WormholesMessages.RTP_STATIC_SELECTED, 0),
        Map.entry(WormholesMessages.RTP_STATIC_AVAILABLE, 1),
        Map.entry(WormholesMessages.RTP_TIMED_SELECTED, 0),
        Map.entry(WormholesMessages.RTP_TIMED_AVAILABLE, 1),
        Map.entry(WormholesMessages.RTP_TRIP_SELECTED, 0),
        Map.entry(WormholesMessages.RTP_TRIP_AVAILABLE, 1),
        Map.entry(WormholesMessages.RTP_MANUAL_REROLL, 1),
        Map.entry(WormholesMessages.RTP_REBUILD_POOL, 1),
        Map.entry(WormholesMessages.RTP_EFFECTS_HEADER, 0),
        Map.entry(WormholesMessages.RTP_RIM_ON_SELECTED, 0),
        Map.entry(WormholesMessages.RTP_RIM_ON_AVAILABLE, 1),
        Map.entry(WormholesMessages.RTP_RIM_OFF_SELECTED, 0),
        Map.entry(WormholesMessages.RTP_RIM_OFF_AVAILABLE, 1),
        Map.entry(WormholesMessages.RTP_SOUND_ON_SELECTED, 0),
        Map.entry(WormholesMessages.RTP_SOUND_ON_AVAILABLE, 1),
        Map.entry(WormholesMessages.RTP_SOUND_OFF_SELECTED, 0),
        Map.entry(WormholesMessages.RTP_SOUND_OFF_AVAILABLE, 1),
        Map.entry(WormholesMessages.RTP_TARGET_UNAVAILABLE, 0),
        Map.entry(WormholesMessages.RTP_NUMERIC_VALUE, 0),
        Map.entry(WormholesMessages.RTP_NUMERIC_ADJUST, 1),
        Map.entry(WormholesMessages.RTP_NUMERIC_HEADER, 0),
        Map.entry(WormholesMessages.RTP_CONFIRM_REROLL, 0),
        Map.entry(WormholesMessages.RTP_CONFIRM_REBUILD, 0),
        Map.entry(WormholesMessages.RTP_CONFIRM, 1),
        Map.entry(WormholesMessages.RTP_CANCEL, 1));
    public static final String WINDOW_KEY = RtpRoutes.CONTROLLER;
    private static final int STATUS_REFRESH_TICKS = 20;
    private static final int WORLDS_PER_PAGE = 8;
    private static final int[] WORLD_POSITIONS = {-3, -1, 1, 3};
    private static final int BIOMES_PER_PAGE = 28;
    private static final int BIOME_ROW_WIDTH = 7;
    private static final String PAGE_TITLE = "rtp-page-title";
    private static final Set<MenuInteraction> PRESS = Set.of(MenuInteraction.PRIMARY);
    private static final Set<MenuInteraction> NUMBER = Set.of(MenuInteraction.INCREMENT, MenuInteraction.DECREMENT,
        MenuInteraction.BIG_INCREMENT, MenuInteraction.BIG_DECREMENT, MenuInteraction.SET_VALUE);

    private final PortalMenuHost portals;
    private final RtpMenuHost rtp;

    public RtpMenuController(PortalMenuHost portals, RtpMenuHost rtp) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.rtp = Objects.requireNonNull(rtp, "rtp");
    }

    @Override
    public String id() {
        return RtpRoutes.CONTROLLER;
    }

    @Override
    public MenuDecision routeGuard(MenuContext context) {
        UUID portalId = PortalRoutes.portalId(context.route());
        Optional<PortalMenuView> portal = portals.portal(portalId);
        if (portal.isEmpty() || !portals.canManage(context.viewer(), portalId)) {
            return MenuDecision.denyAndClose(MenuNotices.hud(context.viewer(), WormholesMessages.PORTAL_EDIT_DENIED));
        }
        if (portal.get().rtp() == null) {
            return MenuDecision.redirect(PortalRoutes.home(portalId), outcomeNotice(context.viewer(), portal.get(), RtpOutcome.notRtp()));
        }
        return MenuDecision.allow();
    }

    @Override
    public MenuBuild build(MenuContext context) {
        UUID portalId = PortalRoutes.portalId(context.route());
        PortalMenuView portal = portals.portal(portalId).orElseThrow(() -> new IllegalStateException("portal " + portalId + " is gone"));
        EditorSnapshot snapshot = rtp.snapshot(portalId)
            .orElseThrow(() -> new IllegalStateException("portal " + portalId + " is not a random teleport portal"));
        Page page = new Page(context, portal, snapshot, MenuGuards.manage(portals, portalId));
        return switch (context.route().screen()) {
            case RtpRoutes.OVERVIEW -> overview(page);
            case RtpRoutes.DESTINATION -> destination(page);
            case RtpRoutes.BIOME -> biome(page);
            case RtpRoutes.LANDING -> landing(page);
            case RtpRoutes.ROUTING -> routing(page);
            case RtpRoutes.EFFECTS -> effects(page);
            case RtpRoutes.NUMERIC -> numeric(page, RtpRoutes.field(context.route()));
            case RtpRoutes.CONFIRM -> confirmPage(page, RtpRoutes.action(context.route()));
            default -> throw new IllegalArgumentException("random teleport menus have no screen " + context.route().screen());
        };
    }

    @Override
    public int refreshIntervalTicks(MenuRoute route) {
        return route.screen().equals(RtpRoutes.OVERVIEW) ? STATUS_REFRESH_TICKS : 0;
    }

    @Override
    public boolean touches(MenuRoute route, MenuScope scope) {
        return scope instanceof MenuScope.Portal portal && portal.portalId().equals(PortalRoutes.portalId(route));
    }

    private MenuBuild overview(Page page) {
        UUID portalId = page.portal.id();
        page.add(status(page));
        pageLink(page, "rtp-open-destination", WormholesMessages.RTP_OVERVIEW_DESTINATION, "minecraft:recovery_compass", -3,
            RtpRoutes.destination(portalId));
        pageLink(page, "rtp-open-landing", WormholesMessages.RTP_OVERVIEW_LANDING, "minecraft:grass_block", -1, RtpRoutes.landing(portalId));
        pageLink(page, "rtp-open-routing", WormholesMessages.RTP_OVERVIEW_ROUTING, "minecraft:clock", 1, RtpRoutes.routing(portalId));
        pageLink(page, "rtp-open-effects", WormholesMessages.RTP_OVERVIEW_EFFECTS, "minecraft:glowstone_dust", 3, RtpRoutes.effects(portalId));
        long revision = page.snapshot.configurationRevision();
        page.add(new ButtonElement(common("rtp-reset-defaults", lines(page.strings, WormholesMessages.RTP_RESET_DEFAULTS, MessageArgs.empty()),
            "minecraft:tnt_minecart", false, new MenuSlot(0, 4).withClick(ChestClick.LEFT, ChestTarget.preconfirmed(MenuInteraction.PRIMARY)))
            .interactions(PRESS).build()));
        page.on("rtp-reset-defaults", (context, action) -> reset(context, page.portal, revision));
        page.confirm("rtp-reset-defaults", (context, onConfirm) -> resetConfirmation(context, page.portal, onConfirm));
        page.add(hiddenButton("rtp-back", lines(page.strings, WormholesMessages.RTP_BACK_PORTAL, MessageArgs.empty()), "minecraft:arrow",
            new MenuSlot(0, 5)));
        page.on("rtp-back", (context, action) -> MenuResult.navigate(PortalRoutes.home(portalId), NavigateMode.POP_TO, null));
        return page.finish(RtpRoutes.OVERVIEW, MenuMessages.TITLE_RTP, null);
    }

    private MenuBuild destination(Page page) {
        SettingsSnapshot settings = page.snapshot.settings();
        UUID portalId = page.portal.id();
        page.add(header(page, WormholesMessages.RTP_DESTINATION_HEADER, "minecraft:recovery_compass"));
        List<WorldOption> worlds = page.snapshot.loadedWorlds();
        int pageCount = pageCount(worlds.size(), WORLDS_PER_PAGE);
        int current = Math.min(page.context.page(), pageCount - 1);
        int start = current * WORLDS_PER_PAGE;
        int end = Math.min(worlds.size(), start + WORLDS_PER_PAGE);
        for (int index = start; index < end; index++) {
            WorldOption world = worlds.get(index);
            int pageIndex = index - start;
            boolean selected = world.key().equalsIgnoreCase(settings.targetWorldKey());
            choice(page, "rtp-world-" + pageIndex, selected ? WormholesMessages.RTP_WORLD_CURRENT : WormholesMessages.RTP_WORLD_AVAILABLE,
                args("world", world.displayName()), "minecraft:ender_eye", selected,
                new MenuSlot(WORLD_POSITIONS[pageIndex % WORLD_POSITIONS.length], 1 + pageIndex / WORLD_POSITIONS.length),
                new TargetWorldMutation(world.key()));
        }
        pager(page, current, pageCount, "rtp-world-previous", WormholesMessages.RTP_PREVIOUS_WORLDS, "rtp-world-next",
            WormholesMessages.RTP_NEXT_WORLDS, 3);
        boolean portalRelative = settings.centerMode() == RtpCenterMode.PORTAL_RELATIVE;
        choice(page, "rtp-center-portal", portalRelative ? WormholesMessages.RTP_CENTER_PORTAL_SELECTED : WormholesMessages.RTP_CENTER_PORTAL_AVAILABLE,
            MessageArgs.empty(), "minecraft:compass", portalRelative, new MenuSlot(-2, 3),
            RtpPortalEditorModel.centerMode(page.snapshot, RtpCenterMode.PORTAL_RELATIVE));
        boolean custom = settings.centerMode() == RtpCenterMode.CUSTOM;
        choice(page, "rtp-center-custom", custom ? WormholesMessages.RTP_CENTER_CUSTOM_SELECTED : WormholesMessages.RTP_CENTER_CUSTOM_AVAILABLE,
            MessageArgs.empty(), "minecraft:lodestone", custom, new MenuSlot(2, 3), RtpPortalEditorModel.centerMode(page.snapshot, RtpCenterMode.CUSTOM));
        if (custom) {
            number(page, RtpNumericField.CENTER_X, new MenuSlot(-3, 4));
            number(page, RtpNumericField.CENTER_Z, new MenuSlot(-1, 4));
        }
        number(page, RtpNumericField.MINIMUM_RADIUS, new MenuSlot(1, 4));
        number(page, RtpNumericField.MAXIMUM_RADIUS, new MenuSlot(3, 4));
        action(page, "rtp-center-reset", WormholesMessages.RTP_RESET_CENTER, MessageArgs.empty(), "minecraft:barrier", new MenuSlot(-3, 5),
            new ResetCenterTargetMutation());
        page.add(new ButtonElement(common("rtp-target-biome", lines(page.strings, WormholesMessages.RTP_BIOME_LINK,
            args("value", selectedBiomeName(page.strings, portalId, settings.targetBiomeKey()))), "minecraft:oak_sapling", false,
            new MenuSlot(3, 5)).interactions(PRESS).build()));
        page.on("rtp-target-biome", (context, action) -> MenuResult.push(RtpRoutes.biome(portalId)));
        overviewBack(page);
        return page.finish(RtpRoutes.DESTINATION, MenuMessages.TITLE_RTP_DESTINATION, paging(current, pageCount));
    }

    private MenuBuild biome(Page page) {
        SettingsSnapshot settings = page.snapshot.settings();
        UUID portalId = page.portal.id();
        page.add(header(page, WormholesMessages.RTP_BIOME_HEADER, "minecraft:oak_sapling"));
        boolean anySelected = settings.targetBiomeKey() == null;
        choice(page, "rtp-biome-any", anySelected ? WormholesMessages.RTP_BIOME_ANY_SELECTED : WormholesMessages.RTP_BIOME_ANY_AVAILABLE,
            MessageArgs.empty(), "minecraft:glass", anySelected, new MenuSlot(-4, 1), new TargetBiomeMutation(null));
        List<BiomeOption> biomes = rtp.biomeOptions(portalId);
        if (biomes.isEmpty()) {
            page.add(new LabelElement(common("rtp-biome-empty", lines(page.strings, WormholesMessages.RTP_BIOME_EMPTY, MessageArgs.empty()),
                "minecraft:barrier", false, new MenuSlot(0, 2)).build()));
        }
        int pageCount = pageCount(biomes.size(), BIOMES_PER_PAGE);
        int current = Math.min(page.context.page(), pageCount - 1);
        int start = current * BIOMES_PER_PAGE;
        int end = Math.min(biomes.size(), start + BIOMES_PER_PAGE);
        for (int index = start; index < end; index++) {
            BiomeOption biome = biomes.get(index);
            int pageIndex = index - start;
            boolean selected = biome.key().equalsIgnoreCase(settings.targetBiomeKey());
            choice(page, "rtp-biome-" + pageIndex, selected ? WormholesMessages.RTP_BIOME_CURRENT : WormholesMessages.RTP_BIOME_AVAILABLE,
                MessageArgs.builder().untrusted("biome", biome.displayName()).untrusted("key", biome.key()).build(),
                biome.irisBiome() ? "minecraft:amethyst_shard" : "minecraft:fern", selected,
                new MenuSlot(pageIndex % BIOME_ROW_WIDTH - 3, 1 + pageIndex / BIOME_ROW_WIDTH), new TargetBiomeMutation(biome.key()));
        }
        pager(page, current, pageCount, "rtp-biome-previous", WormholesMessages.RTP_PREVIOUS_BIOMES, "rtp-biome-next",
            WormholesMessages.RTP_NEXT_BIOMES, 5);
        page.add(hiddenButton("rtp-biome-back", lines(page.strings, WormholesMessages.RTP_BACK_CATEGORY, MessageArgs.empty()), "minecraft:arrow",
            new MenuSlot(0, 5)));
        page.on("rtp-biome-back", (context, action) -> MenuResult.navigate(RtpRoutes.destination(portalId), NavigateMode.POP_TO, null));
        return page.finish(RtpRoutes.BIOME, MenuMessages.TITLE_RTP_BIOME, paging(current, pageCount));
    }

    private MenuBuild landing(Page page) {
        SettingsSnapshot settings = page.snapshot.settings();
        page.add(header(page, WormholesMessages.RTP_LANDING_HEADER, "minecraft:grass_block"));
        boolean surface = settings.verticalMode() == RtpVerticalMode.SURFACE;
        choice(page, "rtp-vertical-surface", surface ? WormholesMessages.RTP_SURFACE_SELECTED : WormholesMessages.RTP_SURFACE_AVAILABLE,
            MessageArgs.empty(), "minecraft:grass_block", surface, new MenuSlot(-2, 1), new VerticalModeMutation(RtpVerticalMode.SURFACE));
        boolean preferred = settings.verticalMode() == RtpVerticalMode.PREFERRED_AVERAGE;
        choice(page, "rtp-vertical-preferred", preferred ? WormholesMessages.RTP_PREFERRED_SELECTED : WormholesMessages.RTP_PREFERRED_AVAILABLE,
            MessageArgs.empty(), "minecraft:amethyst_shard", preferred, new MenuSlot(2, 1),
            new VerticalModeMutation(RtpVerticalMode.PREFERRED_AVERAGE));
        boolean safe = settings.safetyMode() == RtpSafetyMode.SAFE;
        MessageArgs safetyMode = args("mode", settings.safetyMode().name());
        page.add(new ToggleElement(common("rtp-surface-policy", lines(page.strings, WormholesMessages.RTP_SAFE_LANDING, safetyMode),
            safe ? "minecraft:shield" : "minecraft:lava_bucket", safe, new MenuSlot(0, 2))
            .nativeLabel(page.strings.settingName(WormholesMessages.RTP_SAFE_LANDING, safetyMode, "mode")).interactions(PRESS).build(), safe));
        Mutation safety = new SafetyModeMutation(safe ? RtpSafetyMode.UNSAFE : RtpSafetyMode.SAFE);
        long revision = page.snapshot.configurationRevision();
        page.on("rtp-surface-policy", (context, action) -> mutate(context, page.portal, revision, safety));
        number(page, RtpNumericField.LOWER_Y, new MenuSlot(-2, 3));
        number(page, RtpNumericField.UPPER_Y, new MenuSlot(0, 3));
        if (preferred) {
            number(page, RtpNumericField.PREFERRED_Y, new MenuSlot(2, 3));
        }
        overviewBack(page);
        return page.finish(RtpRoutes.LANDING, MenuMessages.TITLE_RTP_LANDING, null);
    }

    private MenuBuild routing(Page page) {
        SettingsSnapshot settings = page.snapshot.settings();
        page.add(header(page, WormholesMessages.RTP_ROUTING_HEADER, "minecraft:clock"));
        boolean shared = settings.allocationMode() == RtpAllocationMode.SHARED;
        choice(page, "rtp-allocation-shared", shared ? WormholesMessages.RTP_SHARED_SELECTED : WormholesMessages.RTP_SHARED_AVAILABLE,
            MessageArgs.empty(), "minecraft:ender_chest", shared, new MenuSlot(-2, 1), new AllocationMutation(RtpAllocationMode.SHARED));
        choice(page, "rtp-allocation-private", shared ? WormholesMessages.RTP_PRIVATE_AVAILABLE : WormholesMessages.RTP_PRIVATE_SELECTED,
            MessageArgs.empty(), "minecraft:player_head", !shared, new MenuSlot(2, 1), new AllocationMutation(RtpAllocationMode.PER_PLAYER));
        if (shared) {
            rotation(page, "rtp-rotation-static", RtpRotationMode.STATIC, WormholesMessages.RTP_STATIC_SELECTED, WormholesMessages.RTP_STATIC_AVAILABLE,
                "minecraft:ender_pearl", -2);
            rotation(page, "rtp-rotation-timed", RtpRotationMode.TIMED, WormholesMessages.RTP_TIMED_SELECTED, WormholesMessages.RTP_TIMED_AVAILABLE,
                "minecraft:clock", 0);
            rotation(page, "rtp-rotation-traversal", RtpRotationMode.ON_TRAVERSAL, WormholesMessages.RTP_TRIP_SELECTED,
                WormholesMessages.RTP_TRIP_AVAILABLE, "minecraft:firework_rocket", 2);
        }
        if (!shared || settings.rotationMode() == RtpRotationMode.TIMED) {
            number(page, RtpNumericField.CYCLE_DURATION, new MenuSlot(-3, 3));
        }
        number(page, RtpNumericField.LEASE_IDLE, new MenuSlot(0, 3));
        number(page, RtpNumericField.RESERVATION_TIMEOUT, new MenuSlot(3, 3));
        ManualAction manual = shared ? ManualAction.REROLL : ManualAction.REBUILD_POOL;
        String manualId = shared ? "rtp-manual-reroll" : "rtp-rebuild-pool";
        page.add(new ButtonElement(common(manualId, lines(page.strings, shared ? WormholesMessages.RTP_MANUAL_REROLL : WormholesMessages.RTP_REBUILD_POOL,
            args("description", PortalMenuText.plain(page.strings, WormholesMessages.RTP_ACTION_CONFIRM))), "minecraft:fire_charge", false,
            new MenuSlot(0, 4)).interactions(PRESS).build()));
        long revision = page.snapshot.configurationRevision();
        page.on(manualId, (context, action) -> manualRequest(context, page.portal, revision, manual));
        overviewBack(page);
        return page.finish(RtpRoutes.ROUTING, MenuMessages.TITLE_RTP_ROUTING, null);
    }

    private MenuBuild effects(Page page) {
        SettingsSnapshot settings = page.snapshot.settings();
        boolean rim = settings.rimEnabled();
        boolean sound = settings.soundEnabled();
        page.add(header(page, WormholesMessages.RTP_EFFECTS_HEADER, "minecraft:glowstone_dust"));
        choice(page, "rtp-rim-on", rim ? WormholesMessages.RTP_RIM_ON_SELECTED : WormholesMessages.RTP_RIM_ON_AVAILABLE, MessageArgs.empty(),
            "minecraft:glowstone_dust", rim, new MenuSlot(-2, 1), new RimMutation(true));
        choice(page, "rtp-rim-off", rim ? WormholesMessages.RTP_RIM_OFF_AVAILABLE : WormholesMessages.RTP_RIM_OFF_SELECTED, MessageArgs.empty(),
            "minecraft:gunpowder", !rim, new MenuSlot(2, 1), new RimMutation(false));
        choice(page, "rtp-sound-on", sound ? WormholesMessages.RTP_SOUND_ON_SELECTED : WormholesMessages.RTP_SOUND_ON_AVAILABLE, MessageArgs.empty(),
            "minecraft:jukebox", sound, new MenuSlot(-2, 3), new SoundMutation(true));
        choice(page, "rtp-sound-off", sound ? WormholesMessages.RTP_SOUND_OFF_AVAILABLE : WormholesMessages.RTP_SOUND_OFF_SELECTED, MessageArgs.empty(),
            "minecraft:note_block", !sound, new MenuSlot(2, 3), new SoundMutation(false));
        overviewBack(page);
        return page.finish(RtpRoutes.EFFECTS, MenuMessages.TITLE_RTP_EFFECTS, null);
    }

    private MenuBuild numeric(Page page, RtpNumericField field) {
        NumericControl control = control(page, field);
        MenuStrings strings = page.strings;
        page.add(hiddenLabel(PAGE_TITLE, lines(strings, WormholesMessages.RTP_NUMERIC_HEADER, MessageArgs.builder()
            .untrusted("label", PortalMenuText.plain(strings, control.label()))
            .untrusted("description", PortalMenuText.plain(strings, control.description()))
            .build()), control.itemId(), false, new MenuSlot(0, 0)));
        if (control.enabled()) {
            step(page, control, "rtp-numeric-decrease-large", false, true, -4);
            step(page, control, "rtp-numeric-decrease-small", false, false, -2);
            page.add(hiddenLabel("rtp-numeric-value", lines(strings, WormholesMessages.RTP_NUMERIC_VALUE, args("value", control.display())),
                control.itemId(), true, new MenuSlot(0, 2)));
            step(page, control, "rtp-numeric-increase-small", true, false, 2);
            step(page, control, "rtp-numeric-increase-large", true, true, 4);
        } else {
            page.add(hiddenLabel("rtp-numeric-unavailable", lines(strings, WormholesMessages.RTP_TARGET_UNAVAILABLE, MessageArgs.empty()),
                "minecraft:barrier", false, new MenuSlot(0, 2)));
        }
        page.add(hiddenButton("rtp-numeric-back", lines(strings, WormholesMessages.RTP_BACK_CATEGORY, MessageArgs.empty()), "minecraft:arrow",
            new MenuSlot(0, 5)));
        MenuRoute parent = parent(page.portal.id(), field);
        page.on("rtp-numeric-back", (context, action) -> MenuResult.navigate(parent, NavigateMode.POP_TO, null));
        return page.finish(RtpRoutes.NUMERIC, MenuMessages.TITLE_RTP, null);
    }

    private MenuBuild confirmPage(Page page, ManualAction manual) {
        MenuStrings strings = page.strings;
        UUID portalId = page.portal.id();
        long revision = page.snapshot.configurationRevision();
        page.add(hiddenLabel(PAGE_TITLE, lines(strings, manualHeader(manual), MessageArgs.empty()), "minecraft:fire_charge", false,
            new MenuSlot(0, 0)));
        page.add(hiddenButton("rtp-manual-confirm", lines(strings, WormholesMessages.RTP_CONFIRM, MessageArgs.empty()), "minecraft:lime_dye",
            new MenuSlot(-2, 2)));
        page.on("rtp-manual-confirm", (context, action) -> manual(context, page.portal, revision, manual));
        page.add(hiddenButton("rtp-manual-cancel", lines(strings, WormholesMessages.RTP_CANCEL, MessageArgs.empty()), "minecraft:red_dye",
            new MenuSlot(2, 2)));
        page.on("rtp-manual-cancel", (context, action) -> MenuResult.navigate(RtpRoutes.routing(portalId), NavigateMode.POP_TO, null));
        return page.finish(RtpRoutes.CONFIRM, MenuMessages.TITLE_RTP, null);
    }

    private LabelElement status(Page page) {
        MenuStrings strings = page.strings;
        SettingsSnapshot settings = page.snapshot.settings();
        StatusSnapshot status = page.snapshot.status();
        List<String> details = new ArrayList<>(5);
        if (status.state() == StatusState.BACKOFF) {
            details.add(PortalMenuText.plain(strings, WormholesMessages.RTP_STATUS_RETRY,
                args("duration", PortalMenuText.duration(strings, status.remainingBackoffMillis()))));
        }
        if (settings.allocationMode() == RtpAllocationMode.SHARED) {
            details.add(PortalMenuText.plain(strings, WormholesMessages.RTP_STATUS_ACTIVE, args("readiness", readiness(strings, status.activeReady()))));
            details.add(PortalMenuText.plain(strings, WormholesMessages.RTP_STATUS_STANDBY, args("readiness", readiness(strings, status.standbyReady()))));
            details.add(PortalMenuText.plain(strings, WormholesMessages.RTP_STATUS_ROTATION,
                args("rotation", PortalMenuText.plain(strings, rotationKey(settings.rotationMode())))));
        } else {
            details.add(PortalMenuText.plain(strings, WormholesMessages.RTP_STATUS_POOL, MessageArgs.builder()
                .untrusted("free", status.freeCount())
                .untrusted("reserved", status.reservedCount())
                .build()));
        }
        if (!status.targetWorldAvailable()) {
            details.add(PortalMenuText.plain(strings, WormholesMessages.RTP_STATUS_TARGET_MISSING));
        }
        if (!status.integrationAvailable()) {
            details.add(PortalMenuText.plain(strings, WormholesMessages.RTP_STATUS_ACCESS_FAILED));
        }
        MenuLines lines = lines(strings, WormholesMessages.RTP_STATUS_HEADER, args("state", PortalMenuText.plain(strings, statusKey(status.state()))))
            .withDescription(details);
        return new LabelElement(common("rtp-status", lines, statusItem(status.state()), status.state() == StatusState.READY, new MenuSlot(0, 0))
            .build());
    }

    private void pageLink(Page page, String id, LinesKey key, String itemId, int position, MenuRoute target) {
        page.add(new ButtonElement(common(id, lines(page.strings, key, MessageArgs.empty()), itemId, false, new MenuSlot(position, 2))
            .interactions(PRESS).build()));
        page.on(id, (context, action) -> MenuResult.push(target));
    }

    private void choice(Page page, String id, LinesKey key, MessageArgs arguments, String itemId, boolean selected, MenuSlot slot,
                        Mutation mutation) {
        page.add(new ListEntryElement(common(id, lines(page.strings, key, arguments), itemId, selected, slot).interactions(PRESS).build(),
            selected));
        long revision = page.snapshot.configurationRevision();
        page.on(id, (context, action) -> mutate(context, page.portal, revision, mutation));
    }

    private void action(Page page, String id, LinesKey key, MessageArgs arguments, String itemId, MenuSlot slot, Mutation mutation) {
        page.add(new ButtonElement(common(id, lines(page.strings, key, arguments), itemId, false, slot).interactions(PRESS).build()));
        long revision = page.snapshot.configurationRevision();
        page.on(id, (context, action) -> mutate(context, page.portal, revision, mutation));
    }

    private void rotation(Page page, String id, RtpRotationMode mode, LinesKey selectedKey, LinesKey availableKey, String itemId, int position) {
        boolean selected = page.snapshot.settings().rotationMode() == mode;
        choice(page, id, selected ? selectedKey : availableKey, MessageArgs.empty(), itemId, selected, new MenuSlot(position, 2),
            new RotationMutation(mode));
    }

    private void pager(Page page, int current, int pageCount, String previousId, LinesKey previousKey, String nextId, LinesKey nextKey, int row) {
        if (current > 0) {
            page.add(hiddenButton(previousId, lines(page.strings, previousKey, MessageArgs.empty()), "minecraft:arrow", new MenuSlot(-4, row)));
            page.on(previousId, (context, action) -> MenuResult.page(current - 1));
        }
        if (current + 1 < pageCount) {
            page.add(hiddenButton(nextId, lines(page.strings, nextKey, MessageArgs.empty()), "minecraft:arrow", new MenuSlot(4, row)));
            page.on(nextId, (context, action) -> MenuResult.page(current + 1));
        }
    }

    private void overviewBack(Page page) {
        page.add(hiddenButton("rtp-submenu-back", lines(page.strings, WormholesMessages.RTP_BACK_OVERVIEW, MessageArgs.empty()), "minecraft:arrow",
            new MenuSlot(0, 5)));
        UUID portalId = page.portal.id();
        page.on("rtp-submenu-back", (context, action) -> MenuResult.navigate(RtpRoutes.overview(portalId), NavigateMode.POP_TO, null));
    }

    private void number(Page page, RtpNumericField field, MenuSlot slot) {
        NumericControl control = control(page, field);
        MenuStrings strings = page.strings;
        String linkId = linkId(field);
        UUID portalId = page.portal.id();
        page.add(hiddenButton(linkId, lines(strings, WormholesMessages.RTP_NUMERIC_LINK, MessageArgs.builder()
            .untrusted("label", PortalMenuText.plain(strings, control.label()))
            .untrusted("value", control.display())
            .build()), control.itemId(), slot));
        page.on(linkId, (context, action) -> MenuResult.push(RtpRoutes.numeric(portalId, field)));
        String numberId = linkId + "-number";
        page.add(new NumberElement(MenuElementCommon.builder(numberId)
            .label(PortalMenuText.plain(strings, control.label()))
            .description(List.of(PortalMenuText.plain(strings, control.description())))
            .icon(MenuIcon.item(control.itemId()))
            .enabled(control.enabled())
            .interactions(control.enabled() ? NUMBER : Set.of())
            .build(), control.value(), control.min(), control.max(), control.step(), control.bigStep(), control.display()));
        if (!control.enabled()) {
            return;
        }
        long revision = page.snapshot.configurationRevision();
        page.on(numberId, MenuInteraction.INCREMENT, (context, action) -> mutate(context, page.portal, revision, control.at(control.step())));
        page.on(numberId, MenuInteraction.DECREMENT, (context, action) -> mutate(context, page.portal, revision, control.at(-control.step())));
        page.on(numberId, MenuInteraction.BIG_INCREMENT, (context, action) -> mutate(context, page.portal, revision, control.at(control.bigStep())));
        page.on(numberId, MenuInteraction.BIG_DECREMENT, (context, action) -> mutate(context, page.portal, revision, control.at(-control.bigStep())));
        page.on(numberId, MenuInteraction.SET_VALUE, (context, action) -> mutate(context, page.portal, revision,
            control.mutation().apply(((MenuValue.Number) action.value()).number())));
    }

    private void step(Page page, NumericControl control, String id, boolean increase, boolean large, int position) {
        String direction = increase ? "+" : "-";
        double delta = large ? control.bigStep() : control.step();
        page.add(hiddenButton(id, lines(page.strings, WormholesMessages.RTP_NUMERIC_ADJUST, MessageArgs.builder()
            .untrusted("direction", direction)
            .untrusted("step", large ? control.bigStepLabel() : control.stepLabel())
            .build()), increase ? "minecraft:lime_dye" : "minecraft:red_dye", new MenuSlot(position, 2)));
        Mutation mutation = control.at(increase ? delta : -delta);
        long revision = page.snapshot.configurationRevision();
        page.on(id, (context, action) -> mutate(context, page.portal, revision, mutation));
    }

    private NumericControl control(Page page, RtpNumericField field) {
        SettingsSnapshot settings = page.snapshot.settings();
        MenuStrings strings = page.strings;
        return switch (field) {
            case CENTER_X -> center(settings, true);
            case CENTER_Z -> center(settings, false);
            case MINIMUM_RADIUS -> new NumericControl(WormholesMessages.RTP_LABEL_MIN_RADIUS, WormholesMessages.RTP_DESCRIPTION_MIN_RADIUS,
                "minecraft:spyglass", settings.minimumRadius(), 0.0D, RtpPortalEditorModel.MAXIMUM_RADIUS, RtpPortalEditorModel.RADIUS_STEP,
                RtpPortalEditorModel.RADIUS_BIG_STEP, Integer.toString(settings.minimumRadius()), Integer.toString(RtpPortalEditorModel.RADIUS_STEP),
                Integer.toString(RtpPortalEditorModel.RADIUS_BIG_STEP), requested -> RtpPortalEditorModel.minimumRadius(settings, whole(requested)), true);
            case MAXIMUM_RADIUS -> new NumericControl(WormholesMessages.RTP_LABEL_MAX_RADIUS, WormholesMessages.RTP_DESCRIPTION_MAX_RADIUS,
                "minecraft:target", settings.maximumRadius(), 0.0D, RtpPortalEditorModel.MAXIMUM_RADIUS, RtpPortalEditorModel.RADIUS_STEP,
                RtpPortalEditorModel.RADIUS_BIG_STEP, Integer.toString(settings.maximumRadius()), Integer.toString(RtpPortalEditorModel.RADIUS_STEP),
                Integer.toString(RtpPortalEditorModel.RADIUS_BIG_STEP), requested -> RtpPortalEditorModel.maximumRadius(settings, whole(requested)), true);
            case LOWER_Y -> height(page.snapshot, YField.LOWER, WormholesMessages.RTP_LABEL_LOWER_Y, "minecraft:ladder");
            case UPPER_Y -> height(page.snapshot, YField.UPPER, WormholesMessages.RTP_LABEL_UPPER_Y, "minecraft:ladder");
            case PREFERRED_Y -> height(page.snapshot, YField.PREFERRED, WormholesMessages.RTP_LABEL_PREFERRED_Y, "minecraft:amethyst_shard");
            case CYCLE_DURATION -> {
                boolean perPlayer = settings.allocationMode() == RtpAllocationMode.PER_PLAYER;
                yield duration(strings, perPlayer ? WormholesMessages.RTP_LABEL_PRIVATE_ROTATION : WormholesMessages.RTP_LABEL_CYCLE,
                    perPlayer ? WormholesMessages.RTP_DESCRIPTION_PRIVATE_ROTATION : WormholesMessages.RTP_DESCRIPTION_CYCLE, "minecraft:clock",
                    settings.cycleDurationMillis(), RtpPortalEditorModel.MINIMUM_CYCLE_MILLIS, RtpPortalEditorModel.MAXIMUM_CYCLE_MILLIS,
                    RtpPortalEditorModel.CYCLE_STEP_MILLIS, RtpPortalEditorModel.CYCLE_BIG_STEP_MILLIS, RtpPortalEditorModel::cycleDuration);
            }
            case LEASE_IDLE -> duration(strings, WormholesMessages.RTP_LABEL_LEASE, WormholesMessages.RTP_DESCRIPTION_LEASE, "minecraft:lead",
                settings.leaseIdleMillis(), RtpPortalEditorModel.MINIMUM_LEASE_MILLIS, RtpPortalEditorModel.MAXIMUM_LEASE_MILLIS,
                RtpPortalEditorModel.LEASE_STEP_MILLIS, RtpPortalEditorModel.LEASE_BIG_STEP_MILLIS, RtpPortalEditorModel::leaseIdle);
            case RESERVATION_TIMEOUT -> duration(strings, WormholesMessages.RTP_LABEL_RELEASE, WormholesMessages.RTP_DESCRIPTION_RELEASE,
                "minecraft:tripwire_hook", settings.reservationTimeoutMillis(), RtpPortalEditorModel.MINIMUM_RESERVATION_MILLIS,
                RtpPortalEditorModel.MAXIMUM_RESERVATION_MILLIS, RtpPortalEditorModel.RESERVATION_STEP_MILLIS,
                RtpPortalEditorModel.RESERVATION_BIG_STEP_MILLIS, RtpPortalEditorModel::reservationTimeout);
        };
    }

    private MenuResult mutate(MenuContext context, PortalMenuView portal, long revision, Mutation mutation) {
        Optional<MenuResult> refused = refused(context.viewer(), portal, revision, null);
        if (refused.isPresent()) {
            return refused.get();
        }
        return MenuResult.pending(rtp.mutate(context.viewer(), portal.id(), revision, mutation)
            .thenApply(outcome -> result(context.viewer(), portal, outcome, null)));
    }

    private MenuResult reset(MenuContext context, PortalMenuView portal, long revision) {
        Optional<MenuResult> refused = refused(context.viewer(), portal, revision, null);
        if (refused.isPresent()) {
            return refused.get();
        }
        return MenuResult.pending(rtp.reset(context.viewer(), portal.id(), revision)
            .thenApply(outcome -> result(context.viewer(), portal, outcome, null)));
    }

    private MenuResult manualRequest(MenuContext context, PortalMenuView portal, long revision, ManualAction manual) {
        if (context.origin() == MenuActionOrigin.CHEST) {
            return MenuResult.push(RtpRoutes.confirm(portal.id(), manual));
        }
        MenuStrings strings = context.strings();
        MenuLines header = lines(strings, manualHeader(manual), MessageArgs.empty());
        MenuConfirmRequest request = new MenuConfirmRequest(header.label(), header.description(), strings.text(MenuMessages.CHROME_CONFIRM),
            strings.text(MenuMessages.CHROME_CANCEL), (confirmed, action) -> manual(confirmed, portal, revision, manual),
            new ChestConfirmLayout(editorTitle(strings, portal), 6, ChestPane.BLACK, new MenuSlot(0, 0), "minecraft:fire_charge",
                new MenuSlot(-2, 2), "minecraft:lime_dye", new MenuSlot(2, 2), "minecraft:red_dye"));
        return MenuResult.confirm(request);
    }

    private MenuResult manual(MenuContext context, PortalMenuView portal, long revision, ManualAction manual) {
        MenuRoute routing = RtpRoutes.routing(portal.id());
        Optional<MenuResult> refused = refused(context.viewer(), portal, revision, routing);
        if (refused.isPresent()) {
            return refused.get();
        }
        return MenuResult.pending(rtp.manual(context.viewer(), portal.id(), revision, manual)
            .thenApply(outcome -> result(context.viewer(), portal, outcome, routing)));
    }

    private Optional<MenuResult> refused(MenuViewer viewer, PortalMenuView portal, long revision, MenuRoute after) {
        Optional<EditorSnapshot> current = rtp.snapshot(portal.id());
        if (current.isEmpty()) {
            return Optional.of(result(viewer, portal, RtpOutcome.notRtp(), after));
        }
        if (current.get().configurationRevision() != revision) {
            return Optional.of(result(viewer, portal, RtpOutcome.stale(), after));
        }
        return Optional.empty();
    }

    private MenuResult result(MenuViewer viewer, PortalMenuView portal, RtpOutcome outcome, MenuRoute after) {
        return switch (outcome.status()) {
            case NOT_RTP -> MenuResult.navigate(PortalRoutes.home(portal.id()), NavigateMode.POP_TO, outcomeNotice(viewer, portal, outcome));
            case DENIED -> MenuResult.close(MenuNotices.hud(viewer, WormholesMessages.PORTAL_EDIT_DENIED));
            case APPLIED, REJECTED, STALE -> after == null
                ? MenuResult.stay(outcomeNotice(viewer, portal, outcome))
                : MenuResult.navigate(after, NavigateMode.POP_TO, outcomeNotice(viewer, portal, outcome));
        };
    }

    private MenuConfirmRequest resetConfirmation(MenuContext context, PortalMenuView portal, MenuHandler onConfirm) {
        MenuStrings strings = context.strings();
        MenuLines confirm = strings.lines(MenuMessages.CONFIRM_RTP_RESET, args("portal", portal.name()), 0);
        return new MenuConfirmRequest(confirm.label(), confirm.description(), strings.text(MenuMessages.CONFIRM_RTP_RESET_ACCEPT),
            strings.text(MenuMessages.CHROME_CANCEL), onConfirm, ChestConfirmLayout.standard(editorTitle(strings, portal)));
    }

    private String selectedBiomeName(MenuStrings strings, UUID portalId, String biomeKey) {
        if (biomeKey == null) {
            return PortalMenuText.plain(strings, WormholesMessages.RTP_BIOME_ANY_LABEL);
        }
        for (BiomeOption biome : rtp.biomeOptions(portalId)) {
            if (biome.key().equalsIgnoreCase(biomeKey)) {
                return biome.displayName();
            }
        }
        return PortalMenuText.plain(strings, WormholesMessages.RTP_BIOME_UNAVAILABLE_LABEL);
    }

    private static NumericControl center(SettingsSnapshot settings, boolean xAxis) {
        TextKey label = xAxis ? WormholesMessages.RTP_LABEL_CENTER_X : WormholesMessages.RTP_LABEL_CENTER_Z;
        Double stored = xAxis ? settings.customCenterX() : settings.customCenterZ();
        double current = stored == null ? 0.0D : stored.doubleValue();
        return new NumericControl(label, WormholesMessages.RTP_DESCRIPTION_CENTER, "minecraft:compass", current,
            RtpPortalEditorModel.MINIMUM_COORDINATE, RtpPortalEditorModel.MAXIMUM_COORDINATE, RtpPortalEditorModel.CENTER_STEP,
            RtpPortalEditorModel.CENTER_BIG_STEP, Double.toString(current), wholeLabel(RtpPortalEditorModel.CENTER_STEP),
            wholeLabel(RtpPortalEditorModel.CENTER_BIG_STEP), requested -> RtpPortalEditorModel.customCenter(settings, xAxis, requested), stored != null);
    }

    private static NumericControl height(EditorSnapshot snapshot, YField field, TextKey label, String itemId) {
        SettingsSnapshot settings = snapshot.settings();
        WorldOption target = snapshot.world(settings.targetWorldKey());
        int current = switch (field) {
            case LOWER -> settings.lowerY();
            case UPPER -> settings.upperY();
            case PREFERRED -> settings.preferredY();
        };
        String stepLabel = Integer.toString(RtpPortalEditorModel.Y_STEP);
        String bigStepLabel = Integer.toString(RtpPortalEditorModel.Y_BIG_STEP);
        if (target == null) {
            return new NumericControl(label, WormholesMessages.RTP_DESCRIPTION_Y, itemId, current, current, current, RtpPortalEditorModel.Y_STEP,
                RtpPortalEditorModel.Y_BIG_STEP, Integer.toString(current), stepLabel, bigStepLabel, requested -> null, false);
        }
        int low = switch (field) {
            case LOWER -> target.minimumFeetY();
            case UPPER, PREFERRED -> settings.lowerY();
        };
        int high = switch (field) {
            case UPPER -> target.maximumFeetY();
            case LOWER, PREFERRED -> settings.upperY();
        };
        return new NumericControl(label, WormholesMessages.RTP_DESCRIPTION_Y, itemId, current, Math.min(low, current), Math.max(high, current),
            RtpPortalEditorModel.Y_STEP, RtpPortalEditorModel.Y_BIG_STEP, Integer.toString(current), stepLabel, bigStepLabel,
            requested -> RtpPortalEditorModel.yBound(settings, target, field, whole(requested)), true);
    }

    private static NumericControl duration(MenuStrings strings, TextKey label, TextKey description, String itemId, long currentMillis,
                                           long minimumMillis, long maximumMillis, long stepMillis, long bigStepMillis,
                                           DurationMutation factory) {
        return new NumericControl(label, description, itemId, seconds(currentMillis), seconds(minimumMillis), seconds(maximumMillis),
            seconds(stepMillis), seconds(bigStepMillis), PortalMenuText.duration(strings, currentMillis), PortalMenuText.duration(strings, stepMillis),
            PortalMenuText.duration(strings, bigStepMillis), requested -> factory.mutation(Math.round(requested * 1_000.0D)), true);
    }

    private static MenuRoute parent(UUID portalId, RtpNumericField field) {
        return switch (field) {
            case CENTER_X, CENTER_Z, MINIMUM_RADIUS, MAXIMUM_RADIUS -> RtpRoutes.destination(portalId);
            case LOWER_Y, UPPER_Y, PREFERRED_Y -> RtpRoutes.landing(portalId);
            case CYCLE_DURATION, LEASE_IDLE, RESERVATION_TIMEOUT -> RtpRoutes.routing(portalId);
        };
    }

    private static String linkId(RtpNumericField field) {
        return switch (field) {
            case CENTER_X -> "rtp-center-x";
            case CENTER_Z -> "rtp-center-z";
            case MINIMUM_RADIUS -> "rtp-minimum-radius";
            case MAXIMUM_RADIUS -> "rtp-maximum-radius";
            case LOWER_Y -> "rtp-lower-y";
            case UPPER_Y -> "rtp-upper-y";
            case PREFERRED_Y -> "rtp-preferred-y";
            case CYCLE_DURATION -> "rtp-cycle-duration";
            case LEASE_IDLE -> "rtp-lease-idle";
            case RESERVATION_TIMEOUT -> "rtp-reservation-timeout";
        };
    }

    private static LinesKey manualHeader(ManualAction manual) {
        return manual == ManualAction.REROLL ? WormholesMessages.RTP_CONFIRM_REROLL : WormholesMessages.RTP_CONFIRM_REBUILD;
    }

    private static String statusItem(StatusState state) {
        return switch (state) {
            case READY -> "minecraft:beacon";
            case WARMING, REROLLING -> "minecraft:clock";
            case BACKOFF, FAILED, INTEGRATION_FAILED, TARGET_WORLD_UNAVAILABLE -> "minecraft:barrier";
            case IDLE -> "minecraft:compass";
        };
    }

    private static TextKey statusKey(StatusState state) {
        return switch (state) {
            case READY -> WormholesMessages.RTP_STATUS_READY;
            case WARMING -> WormholesMessages.RTP_STATUS_WARMING;
            case REROLLING -> WormholesMessages.RTP_STATUS_REROLLING;
            case BACKOFF -> WormholesMessages.RTP_STATUS_BACKOFF;
            case TARGET_WORLD_UNAVAILABLE -> WormholesMessages.RTP_STATUS_WORLD_UNAVAILABLE;
            case INTEGRATION_FAILED -> WormholesMessages.RTP_STATUS_INTEGRATION_FAILED;
            case FAILED -> WormholesMessages.RTP_STATUS_FAILED;
            case IDLE -> WormholesMessages.RTP_STATUS_IDLE;
        };
    }

    private static TextKey rotationKey(RtpRotationMode mode) {
        return switch (mode) {
            case STATIC -> WormholesMessages.RTP_ROTATION_STATIC;
            case TIMED -> WormholesMessages.RTP_ROTATION_TIMED;
            case ON_TRAVERSAL -> WormholesMessages.RTP_ROTATION_TRIP;
        };
    }

    private static String readiness(MenuStrings strings, boolean ready) {
        return PortalMenuText.plain(strings, ready ? WormholesMessages.LABEL_READY : WormholesMessages.LABEL_PREPARING);
    }

    private static MenuNotice outcomeNotice(MenuViewer viewer, PortalMenuView portal, RtpOutcome outcome) {
        MenuFeedbackStyle style = outcome.status() == RtpOutcome.Status.APPLIED ? MenuFeedbackStyle.SUCCESS : MenuFeedbackStyle.WARNING;
        return PortalMenuText.settingNotice(viewer, portal, outcome.notice(), outcome.arguments(), style);
    }

    private static String editorTitle(MenuStrings strings, PortalMenuView portal) {
        return PortalMenuText.plain(strings, WormholesMessages.PORTAL_RTP_EDITOR_TITLE, args("portal", portal.name()));
    }

    private static LabelElement header(Page page, LinesKey key, String itemId) {
        return new LabelElement(common(PAGE_TITLE, lines(page.strings, key, MessageArgs.empty()), itemId, false, new MenuSlot(0, 0)).build());
    }

    private static LabelElement hiddenLabel(String id, MenuLines lines, String itemId, boolean glint, MenuSlot slot) {
        return new LabelElement(common(id, lines, itemId, glint, slot).nativeHidden(true).build());
    }

    private static ButtonElement hiddenButton(String id, MenuLines lines, String itemId, MenuSlot slot) {
        return new ButtonElement(common(id, lines, itemId, false, slot).interactions(PRESS).nativeHidden(true).build());
    }

    private static MenuElementCommon.Builder common(String id, MenuLines lines, String itemId, boolean glint, MenuSlot slot) {
        return MenuElementCommon.builder(id)
            .lines(lines)
            .icon(MenuIcon.item(itemId, 1, glint))
            .chestSlot(slot);
    }

    private static MenuLines lines(MenuStrings strings, LinesKey key, MessageArgs arguments) {
        return strings.lines(key, arguments, CHEST_HINTS.getOrDefault(key, 0));
    }

    private static MessageArgs args(String name, Object value) {
        return MessageArgs.builder().untrusted(name, value).build();
    }

    private static int pageCount(int entries, int perPage) {
        return Math.max(1, (entries + perPage - 1) / perPage);
    }

    private static MenuPaging paging(int current, int pageCount) {
        return pageCount > 1 ? new MenuPaging(current, pageCount) : null;
    }

    private static int whole(double value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(value)));
    }

    private static String wholeLabel(double value) {
        return Long.toString(Math.round(value));
    }

    private static double seconds(long millis) {
        return millis / 1_000.0D;
    }

    @FunctionalInterface
    private interface DurationMutation {
        Mutation mutation(long millis);
    }

    private record NumericControl(TextKey label, TextKey description, String itemId, double value, double min, double max, double step,
                                  double bigStep, String display, String stepLabel, String bigStepLabel, DoubleFunction<Mutation> mutation,
                                  boolean enabled) {
        private Mutation at(double delta) {
            return mutation.apply(value + delta);
        }
    }

    private static final class Page {
        private final MenuContext context;
        private final MenuStrings strings;
        private final PortalMenuView portal;
        private final EditorSnapshot snapshot;
        private final MenuGuard manage;
        private final List<MenuElement> elements = new ArrayList<>(32);
        private final MenuBindings.Builder bindings = MenuBindings.builder();

        private Page(MenuContext context, PortalMenuView portal, EditorSnapshot snapshot, MenuGuard manage) {
            this.context = context;
            this.strings = context.strings();
            this.portal = portal;
            this.snapshot = snapshot;
            this.manage = manage;
        }

        private void add(MenuElement element) {
            elements.add(element);
        }

        private void on(String id, MenuHandler handler) {
            on(id, MenuInteraction.PRIMARY, handler);
        }

        private void on(String id, MenuInteraction interaction, MenuHandler handler) {
            bindings.guard(id, manage).on(id, interaction, handler);
        }

        private void confirm(String id, MenuConfirmFactory factory) {
            bindings.confirm(id, factory);
        }

        private MenuBuild finish(String screenId, TextKey title, MenuPaging paging) {
            MenuScreen screen = MenuScreen.builder(screenId)
                .title(strings.text(title))
                .context(strings.text(MenuMessages.CONTEXT_PORTAL, MessageArgs.builder()
                    .untrusted("portal", portal.name())
                    .untrusted("type", PortalMenuText.currentMode(strings, portal))
                    .build()))
                .elements(elements)
                .paging(paging)
                .build();
            return new MenuBuild(screen, new ChestLayout(editorTitle(strings, portal), 6, ChestPane.BLACK, null, WINDOW_KEY),
                bindings.build());
        }
    }
}

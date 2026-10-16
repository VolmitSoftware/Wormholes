package art.arcane.wormholes.menu.settings;

import static art.arcane.wormholes.menu.settings.MenuLayouts.assertBound;
import static art.arcane.wormholes.menu.settings.MenuLayouts.assertClosedWithNotice;
import static art.arcane.wormholes.menu.settings.MenuLayouts.boundIds;
import static art.arcane.wormholes.menu.settings.MenuLayouts.chestLore;
import static art.arcane.wormholes.menu.settings.MenuLayouts.element;
import static art.arcane.wormholes.menu.settings.MenuLayouts.layout;
import static art.arcane.wormholes.menu.settings.MenuLayouts.mapOf;
import static art.arcane.wormholes.menu.settings.MenuLayouts.sideActions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.ChestClick;
import art.arcane.wormholes.menu.ChestClicks;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.ChestTarget;
import art.arcane.wormholes.menu.CycleElement;
import art.arcane.wormholes.menu.FakeMenuHost;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuBindings;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuChestAction;
import art.arcane.wormholes.menu.MenuContext;
import art.arcane.wormholes.menu.MenuController;
import art.arcane.wormholes.menu.MenuDecision;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuElementCommon;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuRegistry;
import art.arcane.wormholes.menu.MenuResult;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuScreens;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.NumberElement;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.cosmetics.CosmeticsMenuController;
import art.arcane.wormholes.menu.cosmetics.CosmeticsRoutes;
import art.arcane.wormholes.menu.portal.GatewayMenuHost;
import art.arcane.wormholes.menu.portal.GatewayView;
import art.arcane.wormholes.menu.portal.ImportOutcome;
import art.arcane.wormholes.menu.portal.MenuExtensionEntry;
import art.arcane.wormholes.menu.portal.PortalEditOutcome;
import art.arcane.wormholes.menu.portal.PortalMenuController;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.menu.portal.StubController;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import art.arcane.wormholes.portal.ApertureShapePresets;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.NetworkViewQuality;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.PortalTravelMode;
import art.arcane.wormholes.portal.ProjectionRenderMode;

final class SettingsMenuControllerTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final UUID PORTAL = FakePortalHosts.PORTAL;

    private FakeMenuHost host;
    private FakePortalHosts portals;
    private MenuService service;

    @BeforeEach
    void setUp() {
        host = new FakeMenuHost();
        host.viewer(VIEWER, "Owner");
        portals = new FakePortalHosts();
        portals.managers.add(VIEWER);
        service = new MenuService(host, new MenuRegistry(List.of(new SettingsMenuController(portals, portals),
            new CosmeticsMenuController(portals, portals), new StubController(PortalRoutes.CONTROLLER),
            new StubController(PortalRoutes.COST_CONTROLLER), new StubController(PortalRoutes.EXTENSIONS_CONTROLLER))), () -> 1_000L);
    }

    @Test
    void routesMatchTheEntryPointOnPortalHome() {
        assertEquals(PortalRoutes.SETTINGS_CONTROLLER, new SettingsMenuController(portals, portals).id());
        assertEquals(PortalRoutes.SETTINGS_CONTROLLER, SettingsRoutes.advanced(PORTAL).controllerId());
        assertEquals(PORTAL, PortalRoutes.portalId(SettingsRoutes.advanced(PORTAL)));
    }

    @Test
    void standardSettingsMatchTheChestWindowAndNativeTables() {
        open();
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("§0§lAlpha", 5, ChestPane.GRAY, null), build.chest());
        assertEquals(PortalRoutes.SETTINGS_MAIN, build.screen().screenId());
        assertEquals("Settings", build.screen().title());
        assertEquals("Alpha - Portal", build.screen().context());
        assertEquals(mapOf(
            "settings-placard", "Label 0,0 minecraft:lever settings {}",
            "permission-mode", "Cycle -3,1 minecraft:iron_helmet access {}",
            "travel-direction", "Cycle -1,1 minecraft:recovery_compass* transit {}",
            "network-view-quality", "Cycle 1,1 minecraft:conduit projection {}",
            "settings-sync", "Toggle 3,1 minecraft:lantern network {}",
            "blackout-background", "Toggle -4,2 minecraft:glass - {}",
            "activation-range", "Number -2,2 minecraft:lodestone - {}",
            "render-mode", "Cycle 0,2 minecraft:spyglass projection {}",
            "ambient-particles", "Cycle 2,2 minecraft:firework_star* cosmetics {}",
            "aperture-shape", "Cycle 3,2 minecraft:glass_pane portal {LEFT=P, RIGHT=S, SHIFT_RIGHT=T}",
            "surface-skin", "Button 4,2 minecraft:glass - {LEFT=S, RIGHT=P}",
            "public-look-label", "Toggle -1,3 minecraft:name_tag rename {}",
            "travel-cost", "Button 1,3 minecraft:hopper cost {}",
            "back-to-portal", "Button 0,4 minecraft:arrow - {} hidden"), layout(build.screen()));
        assertEquals(mapOf(
            "network-view-quality", "T Advanced small/settings",
            "blackout-background", "S Colors small/settings",
            "ambient-particles", "S Colors small/settings",
            "aperture-shape", "S Rotate small/rotate, T Reset small/reset",
            "surface-skin", "S Clear small/delete"), sideActions(build.screen()));
        assertBound(build);
    }

    @Test
    void customQualityUsesTheSixRowLayoutWithInlineNumbers() {
        portals.view = FakePortalHosts.base().quality(NetworkViewQuality.CUSTOM).build();
        portals.extensions = List.of(extension());
        open();
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("§0§lAlpha", 6, ChestPane.GRAY, null), build.chest());
        assertEquals(mapOf(
            "settings-placard", "Label 0,0 minecraft:lever settings {}",
            "permission-mode", "Cycle -3,1 minecraft:iron_helmet access {}",
            "travel-direction", "Cycle -1,1 minecraft:recovery_compass* transit {}",
            "network-view-quality", "Cycle 1,1 minecraft:comparator projection {}",
            "settings-sync", "Toggle 3,1 minecraft:lantern network {}",
            "network-view-depth", "Number -3,2 minecraft:spyglass - {}",
            "network-view-heartbeat", "Number -1,2 minecraft:clock - {}",
            "network-view-entities", "Number 1,2 minecraft:ender_eye - {}",
            "network-view-grace", "Number 3,2 minecraft:redstone - {}",
            "blackout-background", "Toggle -4,3 minecraft:glass - {}",
            "ambient-particles", "Cycle -2,3 minecraft:firework_star* cosmetics {}",
            "aperture-shape", "Cycle -1,3 minecraft:glass_pane portal {LEFT=P, RIGHT=S, SHIFT_RIGHT=T}",
            "network-view-fallback", "Text 0,3 minecraft:glass - {}",
            "surface-skin", "Button 2,3 minecraft:glass - {LEFT=S, RIGHT=P}",
            "activation-range", "Number 4,3 minecraft:lodestone - {}",
            "render-mode", "Cycle -2,4 minecraft:spyglass projection {}",
            "public-look-label", "Toggle 0,4 minecraft:name_tag rename {}",
            "travel-cost", "Button 2,4 minecraft:hopper cost {}",
            "extensions", "Button 4,4 minecraft:comparator settings {}",
            "back-to-portal", "Button 0,5 minecraft:arrow - {} hidden"), layout(build.screen()));
        assertEquals("S Reset small/reset", sideActions(build.screen()).get("network-view-fallback"));
        assertBound(build);
    }

    @Test
    void advancedTuningMatchesTheChestWindow() {
        open();
        chest("network-view-quality", MenuInteraction.TERTIARY);
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("§0§lAlpha", 5, ChestPane.BLACK, null), build.chest());
        assertEquals(SettingsRoutes.ADVANCED, build.screen().screenId());
        assertEquals("Advanced Stream Tuning", build.screen().title());
        assertTrue(build.screen().canGoBack());
        assertEquals(mapOf(
            "advanced-settings-placard", "Label 0,0 minecraft:comparator settings {}",
            "network-view-depth", "Number -3,1 minecraft:spyglass - {}",
            "network-view-heartbeat", "Number -1,1 minecraft:clock - {}",
            "network-view-entities", "Number 1,1 minecraft:ender_eye - {}",
            "network-view-grace", "Number 3,1 minecraft:redstone - {}",
            "ambient-particles", "Cycle -2,2 minecraft:firework_star* cosmetics {}",
            "network-view-fallback", "Text 0,2 minecraft:glass - {}",
            "surface-skin", "Button 2,2 minecraft:glass - {LEFT=S, RIGHT=P}",
            "blackout-background", "Toggle -2,3 minecraft:glass - {}",
            "render-mode", "Cycle 0,3 minecraft:spyglass projection {}",
            "activation-range", "Number 2,3 minecraft:lodestone - {}",
            "back-to-settings", "Button 0,4 minecraft:arrow - {} hidden"), layout(build.screen()));
        assertBound(build);

        chest("back-to-settings", MenuInteraction.PRIMARY);
        assertEquals(PortalRoutes.SETTINGS_MAIN, host.chest.screen().screenId());
        assertFalse(host.chest.screen().canGoBack());
    }

    @Test
    void effectiveChestClicksMatchTodaysClickHandlers() {
        portals.view = FakePortalHosts.base().quality(NetworkViewQuality.CUSTOM).build();
        portals.extensions = List.of(extension());
        open();
        MenuScreen screen = host.chest.screen();

        assertEquals(mapOf(
            "settings-placard", "{}",
            "permission-mode", "{LEFT=PRIMARY}",
            "travel-direction", "{LEFT=PRIMARY}",
            "network-view-quality", "{LEFT=PRIMARY, SHIFT_LEFT=TERTIARY}",
            "settings-sync", "{LEFT=PRIMARY}",
            "network-view-depth", "{LEFT=INCREMENT, RIGHT=DECREMENT, SHIFT_LEFT=BIG_INCREMENT, SHIFT_RIGHT=BIG_DECREMENT}",
            "network-view-heartbeat", "{LEFT=INCREMENT, RIGHT=DECREMENT, SHIFT_LEFT=BIG_INCREMENT, SHIFT_RIGHT=BIG_DECREMENT}",
            "network-view-entities", "{LEFT=INCREMENT, RIGHT=DECREMENT, SHIFT_LEFT=BIG_INCREMENT, SHIFT_RIGHT=BIG_DECREMENT}",
            "network-view-grace", "{LEFT=INCREMENT, RIGHT=DECREMENT, SHIFT_LEFT=BIG_INCREMENT, SHIFT_RIGHT=BIG_DECREMENT}",
            "blackout-background", "{LEFT=PRIMARY, RIGHT=SECONDARY}",
            "ambient-particles", "{LEFT=PRIMARY, RIGHT=SECONDARY}",
            "aperture-shape", "{LEFT=PRIMARY, RIGHT=SECONDARY, SHIFT_RIGHT=TERTIARY}",
            "network-view-fallback", "{LEFT=SET_TEXT, RIGHT=SECONDARY}",
            "surface-skin", "{LEFT=SECONDARY, RIGHT=PRIMARY}",
            "activation-range", "{LEFT=INCREMENT, RIGHT=DECREMENT, SHIFT_LEFT=BIG_INCREMENT, SHIFT_RIGHT=BIG_DECREMENT}",
            "render-mode", "{LEFT=PRIMARY}",
            "public-look-label", "{LEFT=PRIMARY}",
            "travel-cost", "{LEFT=PRIMARY}",
            "extensions", "{LEFT=PRIMARY}",
            "back-to-portal", "{LEFT=PRIMARY}"), effectiveClicks(screen));
    }

    @Test
    void elementsCarryTheirKindsValuesAndInteractions() {
        portals.view = FakePortalHosts.base().quality(NetworkViewQuality.CUSTOM).networkViewDepth(40).build();
        open();
        MenuScreen screen = host.chest.screen();

        CycleElement permission = (CycleElement) element(screen, "permission-mode");
        assertEquals(List.of("Blacklist", "Whitelist"), permission.options());
        assertEquals(0, permission.index());
        assertEquals(List.of(MenuInteraction.PRIMARY), List.copyOf(permission.interactions()));
        CycleElement travel = (CycleElement) element(screen, "travel-direction");
        assertEquals(List.of("Both Ways", "Outbound Only", "Inbound Only", "Locked"), travel.options());
        CycleElement quality = (CycleElement) element(screen, "network-view-quality");
        assertEquals(List.of("Standard", "Performance", "Balanced", "Cinematic", "Custom"), quality.options());
        assertEquals(4, quality.index());
        assertEquals(List.of("PanOptic", "Venticular"), ((CycleElement) element(screen, "render-mode")).options());
        assertEquals(List.of("Sparks", "Outline", "Corners", "Off"), ((CycleElement) element(screen, "ambient-particles")).options());
        CycleElement shape = (CycleElement) element(screen, "aperture-shape");
        assertEquals(ApertureShapePresets.presets().size(), shape.options().size());
        assertEquals(0, shape.index());
        assertTrue(shape.accepts(MenuInteraction.SELECT));

        NumberElement depth = (NumberElement) element(screen, "network-view-depth");
        assertEquals(List.of(40.0D, 1.0D, 128.0D, 4.0D, 16.0D), List.of(depth.value(), depth.min(), depth.max(), depth.step(), depth.bigStep()));
        assertEquals("40", depth.display());
        assertNumber(screen, "network-view-heartbeat", 2, 600, 10, 60);
        assertNumber(screen, "network-view-entities", 2, 600, 2, 20);
        assertNumber(screen, "network-view-grace", 5, 600, 5, 30);
        NumberElement range = assertNumber(screen, "activation-range", 0, 256, 8, 32);
        assertEquals("Global (64)", range.display());

        assertFalse(((ToggleElement) element(screen, "settings-sync")).value());
        assertFalse(((ToggleElement) element(screen, "blackout-background")).value());
        TextElement fallback = (TextElement) element(screen, "network-view-fallback");
        assertEquals("minecraft:air", fallback.value());
        assertEquals(256, fallback.maxLength());
        assertEquals("Block state, for example minecraft:glass", fallback.placeholder());
        assertEquals(List.of("§bEnter a block state for this portal's network view edge, or 'cancel':"), fallback.chestPrompt());
        assertEquals(List.of(MenuInteraction.SECONDARY, MenuInteraction.SET_TEXT), List.copyOf(fallback.interactions()));
        assertEquals(ChestReopen.ALWAYS, fallback.chestReopen());
        assertEquals(PortalRoutes.settings(PORTAL), fallback.chestCancelRoute());
    }

    @Test
    void loreSplitsChestHintsFromTheNativeDescription() {
        open();
        MenuScreen screen = host.chest.screen();

        MenuElement quality = element(screen, "network-view-quality");
        assertEquals("§b§lStream Quality: Standard§r", quality.label());
        assertEquals(List.of("§7One control for remote view range and cadence.", "", "§7Depth §b64§7  Entities §b10t",
            "§7Refresh §b60t§7  Grace §b30s"), quality.description());
        assertEquals(List.of("", "§8Click: cycle quality.", "§8Shift-click: advanced tuning."), quality.common().chestHints());
        assertEquals("§b§lActivation Range §f§lGlobal (64)§r", element(screen, "activation-range").label());
        assertEquals(List.of("§7Players with the node are blocked.", "§7Node: §fwormholes.portal.alpha", "", "§7Currently: §6Blacklist", "",
            "§8Click to toggle whitelist / blacklist.", "§8Operators always bypass."), chestLore(element(screen, "permission-mode")));
        assertEquals(List.of("", "§8Click to toggle whitelist / blacklist.", "§8Operators always bypass."),
            element(screen, "permission-mode").common().chestHints());
        assertEquals(List.of("§7Distance in blocks at which this portal starts projecting for nearby players.", "", "§7Currently: §bGlobal (64)"),
            element(screen, "activation-range").description());
        assertEquals(List.of("", "§8Left click: +8", "§8Right click: -8", "§8Shift-left: +32", "§8Shift-right: -32", "§8Below 8 resets to Global."),
            element(screen, "activation-range").common().chestHints());
        assertEquals(List.of("§7Cover the portal aperture with a rendered block or fluid pane.", "", "§7Skin: §fNone", "",
            "§8Left: clear the skin.", "§8Right: open skin options."), chestLore(element(screen, "surface-skin")));
        assertEquals(List.of("§7Require an exact item or currency", "§7when a player uses this portal.", "", "§7Mode: §fFree", "§7Cost: §fFree", "",
            "§8Click to configure."), chestLore(element(screen, "travel-cost")));
    }

    @Test
    void managedAndMirroredPortalsShowLockedTravel() {
        portals.view = FakePortalHosts.base().dimensionalKind(DimensionalPortalKind.END_ARRIVAL).twoWayEnd(false).build();
        open();
        CycleElement managed = (CycleElement) element(host.chest.screen(), "travel-direction");
        assertEquals("§6§lTravel: Arrival Only§r", managed.label());
        assertEquals(List.of("Arrival Only"), managed.options());
        assertEquals(MenuIcon.item("minecraft:ender_eye", 1, true), managed.icon());

        chest("travel-direction", MenuInteraction.PRIMARY);
        assertTrue(portals.edits.isEmpty());
        assertEquals("§aAlpha: §rDimensional portal travel is managed automatically.", host.chest.lastNotice());

        portals.view = FakePortalHosts.base().mirror(true).build();
        open();
        CycleElement mirror = (CycleElement) element(host.chest.screen(), "travel-direction");
        assertEquals(MenuIcon.item("minecraft:barrier"), mirror.icon());
        assertEquals("Alpha - Mirror", host.chest.screen().context());
        chest("travel-direction", MenuInteraction.PRIMARY);
        assertTrue(portals.edits.isEmpty());
        assertEquals("§aAlpha: §rMirror mode never allows travel.", host.chest.lastNotice());
    }

    @Test
    void managedEndTravelFollowsTheTwoWaySetting() {
        portals.view = FakePortalHosts.base().dimensionalKind(DimensionalPortalKind.END_ARRIVAL).twoWayEnd(true).build();
        open();
        CycleElement arrival = (CycleElement) element(host.chest.screen(), "travel-direction");
        assertEquals("§6§lTravel: Both Ways§r", arrival.label());
        assertEquals(List.of("Both Ways"), arrival.options());
        assertEquals(List.of("§7Managed dimensional portal direction.", "§7Both linked halves stay active."), arrival.description());
        assertEquals(MenuIcon.item("minecraft:ender_pearl", 1, true), arrival.icon());

        portals.view = FakePortalHosts.base().dimensionalKind(DimensionalPortalKind.END_SOURCE).twoWayEnd(false).build();
        open();
        CycleElement source = (CycleElement) element(host.chest.screen(), "travel-direction");
        assertEquals("§6§lTravel: Departure Only§r", source.label());
        assertEquals(List.of("§7Managed dimensional portal direction.", "§7The return path stays disabled."), source.description());
        assertEquals(MenuIcon.item("minecraft:ender_pearl", 1, true), source.icon());
    }

    @Test
    void cyclesAndTogglesApplyTheirEditsAndNotify() {
        open();

        chest("permission-mode", MenuInteraction.PRIMARY);
        chest("travel-direction", MenuInteraction.PRIMARY);
        chest("settings-sync", MenuInteraction.PRIMARY);
        chest("blackout-background", MenuInteraction.PRIMARY);
        chest("render-mode", MenuInteraction.PRIMARY);
        chest("ambient-particles", MenuInteraction.PRIMARY);
        chest("public-look-label", MenuInteraction.PRIMARY);

        assertEquals(List.of("PermissionMode[mode=WHITELIST]", "TravelMode[mode=OUTBOUND]", "SettingsSync[enabled=true]", "Blackout[enabled=true]",
            "RenderMode[mode=VENTICULAR]", "AmbientStyle[style=OUTLINE]", "PublicLookLabel[enabled=true]"), portals.edits);
        assertEquals(List.of("§aAlpha: §rAccess Whitelist", "§aAlpha: §rTravel Outbound Only", "§aAlpha: §rSettings Sync On",
            "§aAlpha: §rBlackout Background On", "§aAlpha: §rRender Mode Venticular", "§aAlpha: §rAmbient Particles Outline",
            "§aAlpha: §rPublic Look Label On"), noticeTexts());
        assertTrue(host.chest.notices.stream().allMatch(notice -> notice.chestChannel() == NoticeChannel.SUCCESS_EFFECT));
        MenuScreen screen = host.chest.screen();
        assertEquals(PortalRoutes.SETTINGS_MAIN, screen.screenId());
        assertEquals(MenuIcon.item("minecraft:golden_helmet", 1, true), element(screen, "permission-mode").icon());
        assertEquals(MenuIcon.item("minecraft:black_concrete", 1, true), element(screen, "blackout-background").icon());
        assertEquals(MenuIcon.item("minecraft:soul_lantern", 1, true), element(screen, "settings-sync").icon());
        assertEquals(MenuIcon.item("minecraft:tinted_glass", 1, true), element(screen, "render-mode").icon());
        assertEquals(MenuIcon.item("minecraft:blaze_rod", 1, true), element(screen, "ambient-particles").icon());
        assertTrue(((ToggleElement) element(screen, "public-look-label")).value());
        assertEquals(1, ((CycleElement) element(screen, "permission-mode")).index());
    }

    @Test
    void unchangedPermissionModeStaysQuiet() {
        portals.nextOutcome = PortalEditOutcome.applied();
        open();

        chest("permission-mode", MenuInteraction.PRIMARY);

        assertEquals(List.of("PermissionMode[mode=WHITELIST]"), portals.edits);
        assertTrue(host.chest.notices.isEmpty());
    }

    @Test
    void qualityCyclesAndReopensWhenTheLayoutChanges() {
        portals.view = FakePortalHosts.base().quality(NetworkViewQuality.CINEMATIC).build();
        open();

        chest("network-view-quality", MenuInteraction.PRIMARY);

        assertEquals(List.of("Quality[quality=CUSTOM]"), portals.edits);
        assertEquals("§aAlpha: §rStream Quality Custom", host.chest.lastNotice());
        assertEquals(6, host.chest.last().chest().rows());
        assertTrue(host.chest.screen().element("network-view-depth").isPresent());

        chest("network-view-quality", MenuInteraction.PRIMARY);
        assertEquals(5, host.chest.last().chest().rows());
        assertTrue(host.chest.screen().element("network-view-depth").isEmpty());
    }

    @Test
    void networkNumbersStepAndAnnounceOnlyRealChanges() {
        portals.view = FakePortalHosts.base().quality(NetworkViewQuality.CUSTOM).networkViewDepth(126).build();
        open();

        chest("network-view-depth", MenuInteraction.INCREMENT);
        chest("network-view-depth", MenuInteraction.INCREMENT);
        chest("network-view-heartbeat", MenuInteraction.BIG_DECREMENT);
        chest("network-view-entities", MenuInteraction.DECREMENT);
        chest("network-view-grace", MenuInteraction.BIG_INCREMENT);

        assertEquals(List.of("NetworkNumber[field=DEPTH, value=130]", "NetworkNumber[field=DEPTH, value=132]",
            "NetworkNumber[field=HEARTBEAT_TICKS, value=0]", "NetworkNumber[field=ENTITY_INTERVAL_TICKS, value=8]",
            "NetworkNumber[field=UNSUBSCRIBE_GRACE_SECONDS, value=60]"), portals.edits);
        assertEquals(List.of("§aAlpha: §rCapture Radius 128", "§aAlpha: §rFull Refresh 2", "§aAlpha: §rEntity Update 8",
            "§aAlpha: §rView Grace 60"), noticeTexts());
        NumberElement depth = (NumberElement) element(host.chest.screen(), "network-view-depth");
        assertEquals(128.0D, depth.value());
        assertEquals(List.of("§7Blocks streamed around the destination portal.", "", "§7Currently: §b128"), depth.description());
        assertEquals(List.of("", "§8Left click: +4", "§8Right click: -4", "§8Shift-left: +4", "§8Shift-right: -4"), depth.common().chestHints());
    }

    @Test
    void activationRangeStartsFromTheGlobalRangeAndDropsBelowEightToGlobal() {
        open();

        chest("activation-range", MenuInteraction.INCREMENT);
        assertEquals("ActivationRange[blocks=72]", portals.edits.get(0));
        assertEquals("§aAlpha: §rActivation Range 72", host.chest.lastNotice());
        assertEquals("72", ((NumberElement) element(host.chest.screen(), "activation-range")).display());

        portals.view = portals.view.toBuilder().activationRange(8).build();
        chest("activation-range", MenuInteraction.DECREMENT);
        assertEquals("ActivationRange[blocks=0]", portals.edits.get(1));
        assertEquals("§aAlpha: §rActivation Range Global (64)", host.chest.lastNotice());

        chest("activation-range", MenuInteraction.BIG_DECREMENT);
        assertEquals("ActivationRange[blocks=32]", portals.edits.get(2));
    }

    @Test
    void nativeValuesUseTheSameRules() throws MenuProtocolException {
        portals.view = FakePortalHosts.base().quality(NetworkViewQuality.CUSTOM).build();
        hello();
        open();

        nativeAction("activation-range", MenuInteraction.SET_VALUE, MenuValue.number(5.0D));
        nativeAction("network-view-depth", MenuInteraction.SET_VALUE, MenuValue.number(99.4D));
        nativeAction("aperture-shape", MenuInteraction.SELECT, MenuValue.index(1));

        assertEquals(List.of("ActivationRange[blocks=0]", "NetworkNumber[field=DEPTH, value=99]",
            "ApertureShape[shape=" + ApertureShapePresets.presets().get(1) + "]"), portals.edits);
        MenuScreen screen = lastScreen();
        assertEquals(1, ((CycleElement) screen.element("aperture-shape").orElseThrow()).index());
        assertTrue(screen.element("back-to-portal").isEmpty());
        assertEquals("§aAlpha: §rAperture Shape " + ApertureShapePresets.presets().get(1).format(),
            assertInstanceOf(MenuServerMessage.Feedback.class, host.lastMessage()).feedback().text());
    }

    @Test
    void apertureShapeCyclesRotatesResetsAndRemembersARefusedShape() {
        open();
        ShapeDescriptor circle = ApertureShapePresets.presets().get(1);
        ShapeDescriptor rounded = ApertureShapePresets.presets().get(2);

        chest("aperture-shape", MenuInteraction.PRIMARY);
        assertEquals(circle, portals.view.apertureShape());
        assertEquals("§aAlpha: §rAperture Shape " + circle.format(), host.chest.lastNotice());
        assertEquals(MenuIcon.item("minecraft:cyan_stained_glass_pane", 1, true), element(host.chest.screen(), "aperture-shape").icon());

        portals.nextOutcome = PortalEditOutcome.refused(WormholesMessages.PORTAL_APERTURE_SHAPE_TOO_SMALL,
            MessageArgs.builder().untrusted("shape", rounded.format()).build());
        chest("aperture-shape", MenuInteraction.PRIMARY);
        assertEquals(circle, portals.view.apertureShape());
        assertEquals("§aAlpha: §r" + rounded.format() + " leaves no open cell in this frame.", host.chest.lastNotice());
        List<String> lore = chestLore(element(host.chest.screen(), "aperture-shape"));
        assertEquals("§c" + rounded.format() + " leaves no open cell in this frame.", lore.get(lore.size() - 1));

        chest("aperture-shape", MenuInteraction.PRIMARY);
        assertEquals(ApertureShapePresets.presets().get(3), portals.view.apertureShape());
        List<String> cleared = chestLore(element(host.chest.screen(), "aperture-shape"));
        assertEquals("§8Shift-right: reset to full.", cleared.get(cleared.size() - 1));

        chest("aperture-shape", MenuInteraction.SECONDARY);
        assertEquals(ApertureShapePresets.rotated(ApertureShapePresets.presets().get(3)), portals.view.apertureShape());
        CycleElement rotated = (CycleElement) element(host.chest.screen(), "aperture-shape");
        assertEquals(ApertureShapePresets.presets().size() + 1, rotated.options().size());
        assertEquals(ApertureShapePresets.presets().size(), rotated.index());

        chest("aperture-shape", MenuInteraction.TERTIARY);
        assertEquals(ShapeDescriptor.FULL, portals.view.apertureShape());
        assertEquals(5, portals.edits.size());
    }

    @Test
    void numberHintsShowTheLastClickSizeUntilSettingsNavigates() {
        portals.view = FakePortalHosts.base().quality(NetworkViewQuality.CINEMATIC).networkViewDepth(96).networkViewHeartbeatTicks(20)
            .networkViewEntityIntervalTicks(2).networkViewUnsubscribeGraceSeconds(45).build();
        open();
        chest("network-view-quality", MenuInteraction.PRIMARY);
        assertEquals(List.of(steps(4, 16), steps(10, 60), steps(2, 20), steps(5, 30)), numberHints());

        chest("network-view-depth", MenuInteraction.INCREMENT);
        assertEquals(List.of(steps(4, 4), steps(10, 60), steps(2, 20), steps(5, 30)), numberHints());
        assertEquals("§7Depth §b100§7  Entities §b2t", element(host.chest.screen(), "network-view-quality").description().get(2));

        chest("network-view-heartbeat", MenuInteraction.INCREMENT);
        chest("network-view-entities", MenuInteraction.INCREMENT);
        chest("network-view-grace", MenuInteraction.INCREMENT);
        chest("network-view-fallback", MenuInteraction.SECONDARY);
        chest("aperture-shape", MenuInteraction.PRIMARY);
        chest("aperture-shape", MenuInteraction.TERTIARY);
        assertEquals(List.of(steps(4, 4), steps(10, 10), steps(2, 2), steps(5, 5)), numberHints());
        NumberElement depth = (NumberElement) element(host.chest.screen(), "network-view-depth");
        assertEquals(List.of(100.0D, 4.0D, 16.0D), List.of(depth.value(), depth.step(), depth.bigStep()));

        chest("network-view-depth", MenuInteraction.BIG_DECREMENT);
        assertEquals(steps(16, 16), numberHints().get(0));

        chest("network-view-quality", MenuInteraction.TERTIARY);
        assertEquals(List.of(steps(4, 16), steps(10, 60), steps(2, 20), steps(5, 30)), numberHints());
        chest("network-view-grace", MenuInteraction.DECREMENT);
        assertEquals(steps(5, 5), numberHints().get(3));

        chest("back-to-settings", MenuInteraction.PRIMARY);
        assertEquals(PortalRoutes.SETTINGS_MAIN, host.chest.screen().screenId());
        assertEquals(List.of(steps(4, 16), steps(10, 60), steps(2, 20), steps(5, 30)), numberHints());
    }

    @Test
    void refusedShapeClearsWhenSettingsNavigatesOrReopens() {
        open();
        refuseNextShape();
        chest("aperture-shape", MenuInteraction.PRIMARY);
        assertTrue(showsRefusedShape());
        chest("network-view-quality", MenuInteraction.TERTIARY);
        chest("back-to-settings", MenuInteraction.PRIMARY);
        assertFalse(showsRefusedShape());
        chest("aperture-shape", MenuInteraction.PRIMARY);
        assertEquals("ApertureShape[shape=" + ApertureShapePresets.presets().get(1) + "]", portals.edits.get(portals.edits.size() - 1));

        refuseNextShape();
        chest("aperture-shape", MenuInteraction.PRIMARY);
        chest("blackout-background", MenuInteraction.SECONDARY);
        chest("back-to-settings", MenuInteraction.PRIMARY);
        assertEquals(PortalRoutes.SETTINGS_MAIN, host.chest.screen().screenId());
        assertFalse(showsRefusedShape());

        refuseNextShape();
        chest("aperture-shape", MenuInteraction.PRIMARY);
        chest("network-view-quality", MenuInteraction.PRIMARY);
        assertEquals(NetworkViewQuality.PERFORMANCE, portals.view.quality());
        assertTrue(showsRefusedShape());

        portals.view = portals.view.toBuilder().quality(NetworkViewQuality.CINEMATIC).build();
        chest("network-view-quality", MenuInteraction.PRIMARY);
        assertEquals(6, host.chest.last().chest().rows());
        assertFalse(showsRefusedShape());

        refuseNextShape();
        chest("aperture-shape", MenuInteraction.PRIMARY);
        service.chestAction(VIEWER, new MenuChestAction("network-view-fallback", MenuInteraction.SET_TEXT, MenuValue.text("minecraft:stone"), false, null));
        assertFalse(showsRefusedShape());

        refuseNextShape();
        chest("aperture-shape", MenuInteraction.PRIMARY);
        chest("network-view-quality", MenuInteraction.PRIMARY);
        assertEquals(5, host.chest.last().chest().rows());
        assertFalse(showsRefusedShape());
    }

    @Test
    void nativeBackThenReenteringTheSamePortalStartsFresh() throws MenuProtocolException {
        portals.view = FakePortalHosts.base().quality(NetworkViewQuality.CUSTOM).build();
        service = new MenuService(host, new MenuRegistry(List.of(new SettingsMenuController(portals, portals),
            new PortalMenuController(portals, new UnusedGateways()))), () -> 1_000L);
        hello();
        service.open(VIEWER, PortalRoutes.home(PORTAL));
        nativeAction("portal-settings", MenuInteraction.PRIMARY, null);
        rememberRefusedShapeAndClickSize();

        receive(new MenuClientMessage.Back(revision()));
        host.transportOpen = false;
        nativeAction("portal-settings", MenuInteraction.PRIMARY, null);

        assertEquals(PortalRoutes.SETTINGS_MAIN, host.chest.screen().screenId());
        assertFreshSettings();
    }

    @Test
    void nativeBackThenOpeningAnotherPortalsSettingsLeaksNothing() throws MenuProtocolException {
        UUID other = UUID.fromString("00000000-0000-0000-0000-00000000b2fa");
        portals.view = FakePortalHosts.base().quality(NetworkViewQuality.CUSTOM).build();
        portals.others.put(other, FakePortalHosts.base(other, "Beta").quality(NetworkViewQuality.CUSTOM).build());
        service = new MenuService(host, new MenuRegistry(List.of(new SettingsMenuController(portals, portals),
            new Launcher(List.of(PORTAL, other)))), () -> 1_000L);
        hello();
        service.open(VIEWER, Launcher.ROUTE);
        nativeAction("open-0", MenuInteraction.PRIMARY, null);
        rememberRefusedShapeAndClickSize();

        receive(new MenuClientMessage.Back(revision()));
        host.transportOpen = false;
        nativeAction("open-1", MenuInteraction.PRIMARY, null);

        assertEquals("Beta - Portal", host.chest.screen().context());
        assertFreshSettings();
    }

    @Test
    void surfaceSkinClearsForAdministratorsAndOpensThePicker() {
        portals.view = FakePortalHosts.base().surfaceSkin("minecraft:stone").surfaceSkinItemId("minecraft:stone").build();
        open();
        assertEquals(MenuIcon.item("minecraft:stone", 1, true), element(host.chest.screen(), "surface-skin").icon());

        chest("surface-skin", MenuInteraction.SECONDARY);
        assertTrue(portals.edits.isEmpty());
        assertEquals("§cOnly the portal owner or an administrator can edit this portal.", host.chest.lastNotice());
        assertEquals(NoticeChannel.HUD, host.chest.notices.get(0).chestChannel());
        assertEquals(PortalRoutes.SETTINGS_MAIN, host.chest.screen().screenId());
        assertFalse("close".equals(host.chest.lastEvent()));

        portals.skinEditors.add(VIEWER);
        chest("surface-skin", MenuInteraction.SECONDARY);
        assertEquals(List.of("SurfaceSkin[skin=]"), portals.edits);
        assertEquals("§aAlpha: §rSurface Skin None", host.chest.lastNotice());

        int notices = host.chest.notices.size();
        portals.skinEditors.clear();
        chest("surface-skin", MenuInteraction.SECONDARY);
        assertEquals(1, portals.edits.size());
        assertEquals(notices, host.chest.notices.size());

        chest("surface-skin", MenuInteraction.PRIMARY);
        assertEquals(CosmeticsRoutes.SKIN, host.chest.screen().screenId());
    }

    @Test
    void fallbackBlockTakesTypedInputAndResets() {
        portals.view = FakePortalHosts.base().quality(NetworkViewQuality.CUSTOM).build();
        open();

        service.chestAction(VIEWER, new MenuChestAction("network-view-fallback", MenuInteraction.SET_TEXT, MenuValue.text("minecraft:stone"), false, null));
        assertEquals("§aAlpha: §rFallback set to minecraft:stone", host.chest.lastNotice());
        assertEquals("minecraft:stone", ((TextElement) element(host.chest.screen(), "network-view-fallback")).value());

        service.chestAction(VIEWER, new MenuChestAction("network-view-fallback", MenuInteraction.SET_TEXT, MenuValue.text("minecraft:stone"), false, null));
        assertEquals(1, host.chest.notices.size());

        chest("network-view-fallback", MenuInteraction.SECONDARY);
        assertEquals(List.of("FallbackBlock[blockState=minecraft:stone]", "FallbackBlock[blockState=minecraft:stone]",
            "FallbackBlock[blockState=minecraft:air]"), portals.edits);
        assertEquals("§aAlpha: §rFallback reset to minecraft:air", host.chest.lastNotice());
        assertEquals(PortalRoutes.SETTINGS_MAIN, host.chest.screen().screenId());
    }

    @Test
    void fallbackPromptFromAdvancedReopensMainOnSubmitAndCancel() {
        open();
        chest("network-view-quality", MenuInteraction.TERTIARY);
        TextElement fallback = (TextElement) element(host.chest.screen(), "network-view-fallback");
        assertEquals(ChestReopen.ALWAYS, fallback.chestReopen());
        assertEquals(PortalRoutes.settings(PORTAL), fallback.chestCancelRoute());

        service.chestAction(VIEWER, new MenuChestAction("network-view-fallback", MenuInteraction.SET_TEXT, MenuValue.text("minecraft:stone"), false, null));

        assertEquals(List.of("FallbackBlock[blockState=minecraft:stone]"), portals.edits);
        assertEquals(PortalRoutes.SETTINGS_MAIN, host.chest.screen().screenId());
        assertFalse(host.chest.screen().canGoBack());
        assertEquals("§aAlpha: §rFallback set to minecraft:stone", host.chest.lastNotice());

        chest("network-view-quality", MenuInteraction.TERTIARY);
        service.chestAction(VIEWER, new MenuChestAction("network-view-fallback", MenuInteraction.SET_TEXT, MenuValue.text("minecraft:stone"), false, null));
        assertEquals(PortalRoutes.SETTINGS_MAIN, host.chest.screen().screenId());
        assertEquals(1, host.chest.notices.size());
    }

    @Test
    void nativeFallbackEditsStayOnAdvanced() throws MenuProtocolException {
        hello();
        open();
        nativeAction("network-view-quality", MenuInteraction.TERTIARY, null);

        nativeAction("network-view-fallback", MenuInteraction.SET_TEXT, MenuValue.text("minecraft:stone"));

        assertEquals(List.of("FallbackBlock[blockState=minecraft:stone]"), portals.edits);
        assertEquals(SettingsRoutes.ADVANCED, lastScreen().screenId());
        assertEquals("§aAlpha: §rFallback set to minecraft:stone",
            assertInstanceOf(MenuServerMessage.Feedback.class, host.lastMessage()).feedback().text());
    }

    @Test
    void openersNavigateToTheirFamilies() {
        portals.extensions = List.of(extension());
        open();
        assertEquals("Button 3,3 minecraft:comparator settings {}", layout(host.chest.screen()).get("extensions"));

        assertNavigates("blackout-background", MenuInteraction.SECONDARY, CosmeticsRoutes.BLACKOUT);
        assertNavigates("ambient-particles", MenuInteraction.SECONDARY, CosmeticsRoutes.AMBIENT);
        assertNavigates("surface-skin", MenuInteraction.PRIMARY, CosmeticsRoutes.SKIN);
        assertNavigates("travel-cost", MenuInteraction.PRIMARY, PortalRoutes.COST_MAIN);
        assertNavigates("extensions", MenuInteraction.PRIMARY, PortalRoutes.EXTENSIONS_MAIN);
        assertNavigates("network-view-quality", MenuInteraction.TERTIARY, SettingsRoutes.ADVANCED);

        open();
        chest("back-to-portal", MenuInteraction.PRIMARY);
        assertEquals(PortalRoutes.HOME, host.chest.screen().screenId());
        assertTrue(portals.edits.isEmpty());
    }

    @Test
    void costOpenerShowsTheConfiguredCost() {
        portals.view = FakePortalHosts.base()
            .cost(new PortalMenuView.Cost(PortalMenuView.CostMode.VANILLA, "minecraft:diamond", "Diamond", 3, null, "cost-alpha"))
            .build();
        open();
        MenuElement vanilla = element(host.chest.screen(), "travel-cost");
        assertEquals(MenuIcon.template("minecraft:diamond", 1, true, "cost-alpha"), vanilla.icon());
        assertNull(vanilla.common().nativeIcon());
        assertTrue(vanilla.description().contains("§7Cost: §f3x Diamond"));
        assertTrue(vanilla.description().contains("§7Mode: §fVanilla Item"));

        portals.view = FakePortalHosts.base()
            .cost(new PortalMenuView.Cost(PortalMenuView.CostMode.VAULT, null, null, 0, "$25.00", null))
            .build();
        open();
        MenuElement vault = element(host.chest.screen(), "travel-cost");
        assertEquals(MenuIcon.item("minecraft:emerald", 1, true), vault.icon());
        assertEquals(MenuIcon.sprite("cost"), vault.common().nativeIcon());
        assertTrue(vault.description().contains("§7Cost: §f$25.00"));
    }

    @Test
    void refusedEditsAnswerWithTheirReason() {
        portals.nextOutcome = PortalEditOutcome.refused(WormholesMessages.PORTAL_EDIT_DENIED, MessageArgs.empty());
        open();

        chest("settings-sync", MenuInteraction.PRIMARY);

        assertEquals("§aAlpha: §rOnly the portal owner or an administrator can edit this portal.", host.chest.lastNotice());
        assertFalse(((ToggleElement) element(host.chest.screen(), "settings-sync")).value());
    }

    @Test
    void everyBoundActionReachesTheHostForAManager() {
        portals.view = FakePortalHosts.base().quality(NetworkViewQuality.CUSTOM).surfaceSkin("minecraft:stone").build();
        portals.extensions = List.of(extension());
        portals.skinEditors.add(VIEWER);
        for (MenuRoute route : List.of(PortalRoutes.settings(PORTAL), SettingsRoutes.advanced(PORTAL))) {
            service.open(VIEWER, route);
            MenuScreen screen = host.chest.screen();
            for (String id : boundIds(screen)) {
                for (MenuInteraction interaction : element(screen, id).interactions()) {
                    service.open(VIEWER, route);
                    int edits = portals.edits.size();
                    int events = host.chest.events.size();
                    service.chestAction(VIEWER, new MenuChestAction(id, interaction, value(interaction), false, null));
                    boolean edited = portals.edits.size() > edits;
                    boolean moved = !host.chest.screen().screenId().equals(route.screen());
                    assertTrue(edited || moved, route.screen() + "/" + id + "/" + interaction);
                    assertTrue(host.chest.events.size() > events, route.screen() + "/" + id + "/" + interaction);
                    portals.view = FakePortalHosts.base().quality(NetworkViewQuality.CUSTOM).surfaceSkin("minecraft:stone").build();
                }
            }
        }
    }

    @Test
    void viewersWhoCannotManageAreRejectedEverywhere() {
        portals.view = FakePortalHosts.base().quality(NetworkViewQuality.CUSTOM).surfaceSkin("minecraft:stone").build();
        portals.extensions = List.of(extension());
        portals.skinEditors.add(VIEWER);
        portals.managers.clear();
        service.open(VIEWER, PortalRoutes.settings(PORTAL));
        assertEquals("§cOnly the portal owner or an administrator can edit this portal.", host.chest.lastNotice());
        assertTrue(host.chest.builds.isEmpty());

        for (MenuRoute route : List.of(PortalRoutes.settings(PORTAL), SettingsRoutes.advanced(PORTAL))) {
            portals.managers.add(VIEWER);
            service.open(VIEWER, route);
            List<String> ids = boundIds(host.chest.screen());
            for (String id : ids) {
                for (MenuInteraction interaction : element(host.chest.screen(), id).interactions()) {
                    portals.managers.add(VIEWER);
                    service.open(VIEWER, route);
                    portals.managers.clear();
                    int notices = host.chest.notices.size();
                    service.chestAction(VIEWER, new MenuChestAction(id, interaction, value(interaction), false, null));
                    assertTrue(portals.edits.isEmpty(), route.screen() + "/" + id + "/" + interaction);
                    assertEquals(notices + 1, host.chest.notices.size(), route.screen() + "/" + id);
                    assertEquals("§cOnly the portal owner or an administrator can edit this portal.", host.chest.lastNotice());
                    assertClosedWithNotice(host.chest, route.screen() + "/" + id);
                    service.chestAction(VIEWER, new MenuChestAction(id, interaction, value(interaction), false, null));
                    assertEquals(notices + 1, host.chest.notices.size(), route.screen() + "/" + id);
                }
            }
        }
    }

    @Test
    void vanishedPortalsCloseTheMenu() {
        open();
        portals.present = false;

        chest("settings-sync", MenuInteraction.PRIMARY);

        assertTrue(portals.edits.isEmpty());
        assertClosedWithNotice(host.chest, "vanished portal");
    }

    @Test
    void staleNativeRevisionsRefreshWithoutTouchingTheHost() throws MenuProtocolException {
        hello();
        open();
        long stale = revision();
        nativeAction("settings-sync", MenuInteraction.PRIMARY, null);
        assertEquals(List.of("SettingsSync[enabled=true]"), portals.edits);

        receive(new MenuClientMessage.Action(new MenuAction(stale, "settings-sync", MenuInteraction.PRIMARY, null)));

        assertEquals(1, portals.edits.size());
        MenuServerMessage.Update update = assertInstanceOf(MenuServerMessage.Update.class, host.lastMessage());
        assertEquals("The menu changed and was refreshed.", update.screen().feedback().text());
        assertTrue(((ToggleElement) update.screen().element("settings-sync").orElseThrow()).value());
    }

    @Test
    void nativeResetsApplyWithoutAConfirmation() throws MenuProtocolException {
        portals.view = FakePortalHosts.base().quality(NetworkViewQuality.CUSTOM).fallbackBlock("minecraft:stone")
            .apertureShape(ApertureShapePresets.presets().get(1)).build();
        hello();
        open();

        nativeAction("network-view-fallback", MenuInteraction.SECONDARY, null);
        nativeAction("aperture-shape", MenuInteraction.TERTIARY, null);

        assertEquals(List.of("FallbackBlock[blockState=minecraft:air]", "ApertureShape[shape=" + ShapeDescriptor.FULL + "]"), portals.edits);
        for (MenuServerMessage message : host.serverMessages()) {
            if (message instanceof MenuServerMessage.Open opened) {
                assertNull(opened.screen().confirmation());
            }
        }
    }

    @Test
    void refreshAndInvalidationScopes() {
        SettingsMenuController controller = new SettingsMenuController(portals, portals);

        assertEquals(0, controller.refreshIntervalTicks(PortalRoutes.settings(PORTAL)));
        assertEquals(0, controller.refreshIntervalTicks(SettingsRoutes.advanced(PORTAL)));
        assertTrue(controller.touches(SettingsRoutes.advanced(PORTAL), new MenuScope.Portal(PORTAL)));
        assertFalse(controller.touches(PortalRoutes.settings(PORTAL), new MenuScope.Portal(UUID.randomUUID())));
        assertFalse(controller.touches(PortalRoutes.settings(PORTAL), new MenuScope.Viewer(VIEWER)));
    }

    @Test
    void invalidationRepaintsSettings() {
        open();
        portals.view = portals.view.toBuilder().permissionMode(PortalPermissionMode.WHITELIST).travelMode(PortalTravelMode.LOCKED)
            .renderMode(ProjectionRenderMode.VENTICULAR).build();

        service.invalidate(new MenuScope.Portal(PORTAL));

        assertEquals("update:settings.main@2", host.chest.lastEvent());
        assertEquals(MenuIcon.item("minecraft:barrier"), element(host.chest.screen(), "travel-direction").icon());
    }

    private List<List<String>> numberHints() {
        List<List<String>> hints = new ArrayList<>(4);
        for (String id : List.of("network-view-depth", "network-view-heartbeat", "network-view-entities", "network-view-grace")) {
            List<String> plain = new ArrayList<>(5);
            for (String line : element(host.chest.screen(), id).common().chestHints()) {
                plain.add(MenuText.stripColors(line));
            }
            hints.add(plain);
        }
        return hints;
    }

    private static List<String> steps(int step, int bigStep) {
        return List.of("", "Left click: +" + step, "Right click: -" + step, "Shift-left: +" + bigStep, "Shift-right: -" + bigStep);
    }

    private void refuseNextShape() {
        portals.nextOutcome = PortalEditOutcome.refused(WormholesMessages.PORTAL_APERTURE_SHAPE_TOO_SMALL,
            MessageArgs.builder().untrusted("shape", ApertureShapePresets.presets().get(2).format()).build());
    }

    private void rememberRefusedShapeAndClickSize() throws MenuProtocolException {
        refuseNextShape();
        nativeAction("aperture-shape", MenuInteraction.PRIMARY, null);
        nativeAction("network-view-depth", MenuInteraction.INCREMENT, null);
        assertEquals(List.of("ApertureShape[shape=" + ApertureShapePresets.presets().get(1) + "]", "NetworkNumber[field=DEPTH, value="
            + (NetworkViewQuality.STANDARD.getDepth() + 4) + "]"), portals.edits);
    }

    private void assertFreshSettings() {
        assertFalse(showsRefusedShape());
        assertEquals(List.of(steps(4, 16), steps(10, 60), steps(2, 20), steps(5, 30)), numberHints());
        chest("aperture-shape", MenuInteraction.PRIMARY);
        assertEquals("ApertureShape[shape=" + ApertureShapePresets.presets().get(1) + "]", portals.edits.get(portals.edits.size() - 1));
    }

    private boolean showsRefusedShape() {
        List<String> lore = chestLore(element(host.chest.screen(), "aperture-shape"));
        return lore.get(lore.size() - 1).contains("leaves no open cell");
    }

    private void assertNavigates(String id, MenuInteraction interaction, String screen) {
        open();
        chest(id, interaction);
        assertEquals(screen, host.chest.screen().screenId(), id);
    }

    private static Map<String, String> effectiveClicks(MenuScreen screen) {
        Map<String, String> clicks = new LinkedHashMap<>();
        for (MenuElement element : screen.elements()) {
            Map<ChestClick, MenuInteraction> resolved = new EnumMap<>(ChestClick.class);
            for (Map.Entry<ChestClick, ChestTarget> entry : ChestClicks.resolve(element).entrySet()) {
                resolved.put(entry.getKey(), entry.getValue().interaction());
            }
            clicks.put(element.id(), resolved.toString());
        }
        return clicks;
    }

    private NumberElement assertNumber(MenuScreen screen, String id, double min, double max, double step, double bigStep) {
        NumberElement number = (NumberElement) element(screen, id);
        assertEquals(List.of(min, max, step, bigStep), List.of(number.min(), number.max(), number.step(), number.bigStep()), id);
        return number;
    }

    private MenuExtensionEntry extension() {
        Function<UUID, MessageArgs> arguments = ignored -> MessageArgs.empty();
        BiPredicate<MenuViewer, UUID> never = (candidate, portalId) -> false;
        return new MenuExtensionEntry("access", "minecraft:name_tag", "access", WormholesMessages.PORTAL_MENU_EXTENSIONS, 1, arguments, never, never,
            portalId -> PortalRoutes.extensions(portalId));
    }

    @Test
    void nativeLabelsNameTheSettingWithoutItsValue() {
        open();
        MenuScreen main = host.chest.screen();
        MenuScreens.assertSettingLabels(main);
        assertEquals("Travel", MenuText.stripColors(MenuScreens.element(main, "travel-direction").common().wireLabel()));
        assertEquals("Settings Sync", MenuText.stripColors(MenuScreens.element(main, "settings-sync").common().wireLabel()));

        service.open(VIEWER, SettingsRoutes.advanced(PORTAL));
        MenuScreens.assertSettingLabels(host.chest.screen());
    }

    private void open() {
        service.open(VIEWER, PortalRoutes.settings(PORTAL));
    }

    private void chest(String elementId, MenuInteraction interaction) {
        service.chestAction(VIEWER, new MenuChestAction(elementId, interaction, null, false, null));
    }

    private void hello() {
        receive(new MenuClientMessage.Hello(1));
    }

    private void nativeAction(String elementId, MenuInteraction interaction, MenuValue value) throws MenuProtocolException {
        receive(new MenuClientMessage.Action(new MenuAction(revision(), elementId, interaction, value)));
    }

    private void receive(MenuClientMessage message) {
        try {
            service.receive(VIEWER, MenuCodec.encodeClient(message));
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long revision() throws MenuProtocolException {
        return lastScreen().revision();
    }

    private MenuScreen lastScreen() throws MenuProtocolException {
        MenuScreen screen = null;
        for (MenuServerMessage message : host.serverMessages()) {
            if (message instanceof MenuServerMessage.Open opened) {
                screen = opened.screen();
            } else if (message instanceof MenuServerMessage.Update updated) {
                screen = updated.screen();
            }
        }
        return screen;
    }

    private List<String> noticeTexts() {
        List<String> texts = new ArrayList<>();
        host.chest.notices.forEach(notice -> texts.add(notice.feedback().text()));
        return texts;
    }

    private static MenuValue value(MenuInteraction interaction) {
        return switch (interaction) {
            case SET_TEXT -> MenuValue.text("minecraft:stone");
            case SET_VALUE -> MenuValue.number(16.0D);
            case SELECT -> MenuValue.index(2);
            default -> null;
        };
    }

    private record Launcher(List<UUID> portalIds) implements MenuController {
        private static final MenuRoute ROUTE = MenuRoute.of("launcher", "launcher.main");

        @Override
        public String id() {
            return ROUTE.controllerId();
        }

        @Override
        public MenuDecision routeGuard(MenuContext context) {
            return MenuDecision.allow();
        }

        @Override
        public MenuBuild build(MenuContext context) {
            List<MenuElement> elements = new ArrayList<>(portalIds.size());
            MenuBindings.Builder bindings = MenuBindings.builder();
            for (int index = 0; index < portalIds.size(); index++) {
                String id = "open-" + index;
                MenuRoute target = PortalRoutes.settings(portalIds.get(index));
                elements.add(new ButtonElement(MenuElementCommon.builder(id).label(id).interactions(Set.of(MenuInteraction.PRIMARY))
                    .chestSlot(new MenuSlot(index, 0)).build()));
                bindings.guard(id, (ctx, action) -> MenuDecision.allow()).on(id, MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.push(target));
            }
            MenuScreen screen = MenuScreen.builder(ROUTE.screen()).title("Launcher").context("Launcher").elements(elements).build();
            return new MenuBuild(screen, new ChestLayout("Launcher", 1, ChestPane.GRAY, null), bindings.build());
        }
    }

    private static final class UnusedGateways implements GatewayMenuHost {
        @Override
        public GatewayView gateway(UUID portalId) {
            throw new UnsupportedOperationException("gateways are not part of the settings families");
        }

        @Override
        public void exportToChat(MenuViewer viewer, UUID portalId) {
            throw new UnsupportedOperationException("gateways are not part of the settings families");
        }

        @Override
        public ImportOutcome importCode(MenuViewer viewer, UUID portalId, String code) {
            throw new UnsupportedOperationException("gateways are not part of the settings families");
        }
    }
}

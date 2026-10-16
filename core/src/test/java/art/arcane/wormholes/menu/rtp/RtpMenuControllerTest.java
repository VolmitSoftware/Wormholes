package art.arcane.wormholes.menu.rtp;

import static art.arcane.wormholes.menu.MenuTestViews.assertBound;
import static art.arcane.wormholes.menu.MenuTestViews.chestLore;
import static art.arcane.wormholes.menu.MenuTestViews.element;
import static art.arcane.wormholes.menu.MenuTestViews.layout;
import static art.arcane.wormholes.menu.MenuTestViews.mapOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.FakeMenuHost;
import art.arcane.wormholes.menu.FakeMenuViewer;
import art.arcane.wormholes.menu.FakePortalAccess;
import art.arcane.wormholes.menu.FakeRouteController;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuChestAction;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuRegistry;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuScreens;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuTestViews;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.NumberElement;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.RtpAllocationMode;
import art.arcane.wormholes.portal.rtp.RtpCenterMode;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.AllocationMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.BiomeOption;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.CenterModeMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.CustomCenterMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.CycleDurationMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.LeaseIdleMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.RadiiMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ReservationTimeoutMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ResetCenterTargetMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.RimMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.RotationMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.SafetyModeMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.SoundMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusState;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.TargetBiomeMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.TargetWorldMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.VerticalModeMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.WorldOption;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.YMutation;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;
import art.arcane.wormholes.portal.rtp.RtpSafetyMode;
import art.arcane.wormholes.portal.rtp.RtpVerticalMode;

final class RtpMenuControllerTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000c0");
    private static final UUID PORTAL = FakePortalAccess.PORTAL;
    private static final String APPLIED = "§aAlpha: §rRandom destination settings applied.";
    private static final String REFRESHED = "§aAlpha: §rSettings changed; the editor was refreshed.";
    private static final String NOT_RTP = "§aAlpha: §rThis portal is no longer in random teleport mode.";

    private FakeMenuHost host;
    private FakeMenuViewer viewer;
    private FakePortalAccess portals;
    private FakeRtpMenuHost rtp;
    private RtpMenuController controller;
    private MenuService service;
    private long now = 1_000L;

    @BeforeEach
    void setUp() {
        host = new FakeMenuHost();
        viewer = host.viewer(VIEWER, "Owner");
        portals = new FakePortalAccess();
        portals.managers.add(VIEWER);
        rtp = new FakeRtpMenuHost();
        enterRtp();
        controller = new RtpMenuController(portals, rtp);
        service = new MenuService(host, new MenuRegistry(List.of(controller, new FakeRouteController(PortalRoutes.CONTROLLER))), () -> now);
    }

    @Test
    void overviewMatchesTodaysPage() {
        open(RtpRoutes.overview(PORTAL));
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("RTP: Alpha", 6, ChestPane.BLACK, null, RtpMenuController.WINDOW_KEY), build.chest());
        assertEquals(RtpRoutes.OVERVIEW, build.screen().screenId());
        assertEquals("Random Teleport", build.screen().title());
        assertEquals("Alpha - RTP", build.screen().context());
        assertEquals(mapOf(
            "rtp-status", "Label 0,0 minecraft:beacon* - {}",
            "rtp-open-destination", "Button -3,2 minecraft:recovery_compass - {}",
            "rtp-open-landing", "Button -1,2 minecraft:grass_block - {}",
            "rtp-open-routing", "Button 1,2 minecraft:clock - {}",
            "rtp-open-effects", "Button 3,2 minecraft:glowstone_dust - {}",
            "rtp-reset-defaults", "Button 0,4 minecraft:tnt_minecart - {LEFT=P!}",
            "rtp-back", "Button 0,5 minecraft:arrow - {} hidden"), layout(build.screen()));
        MenuElement status = element(build.screen(), "rtp-status");
        assertEquals("§6§lRandom Destination Status§r", status.label());
        assertEquals(List.of("§7State: Ready", "Active: Ready", "Standby: Ready", "Draft rotation: Static"), status.description());
        assertEquals(List.of("§7Immediately restore this portal's random teleport settings to their defaults.", "§eLeft click"),
            chestLore(element(build.screen(), "rtp-reset-defaults")));
        assertEquals(List.of("§7World, center, and radius.", "§eLeft click"), chestLore(element(build.screen(), "rtp-open-destination")));
        assertBound(build);
    }

    @Test
    void overviewStatusListsPoolAndFailures() {
        rtp.settings = FakeRtpMenuHost.settings().allocation(RtpAllocationMode.PER_PLAYER).build();
        rtp.status = new StatusSnapshot(StatusState.BACKOFF, false, false, false, false, 0L, 5_000L, 3, 1, 0, 0);

        open(RtpRoutes.overview(PORTAL));

        MenuElement status = element(host.chest.screen(), "rtp-status");
        assertEquals("Label 0,0 minecraft:barrier - {}", MenuTestViews.describe(status));
        assertEquals(List.of("§7State: Retry Backoff", "Retry in: 5s", "Free: 3  Reserved: 1", "Target world is not loaded.",
            "Destination access checks failed closed."), status.description());
    }

    @Test
    void overviewStatusRefreshesEveryTwentyTicks() {
        open(RtpRoutes.overview(PORTAL));
        int pushes = host.chest.events.size();
        for (int tick = 0; tick < 20; tick++) {
            service.tick();
        }
        assertEquals(pushes, host.chest.events.size());

        rtp.status = new StatusSnapshot(StatusState.WARMING, true, true, false, false, 0L, 0L, 0, 0, 1, 0);
        for (int tick = 0; tick < 20; tick++) {
            service.tick();
        }

        assertEquals("update:rtp.overview@2", host.chest.lastEvent());
        assertEquals("§7State: Warming", element(host.chest.screen(), "rtp-status").description().get(0));
        assertEquals(20, controller.refreshIntervalTicks(RtpRoutes.overview(PORTAL)));
        assertEquals(0, controller.refreshIntervalTicks(RtpRoutes.destination(PORTAL)));
        assertTrue(controller.touches(RtpRoutes.routing(PORTAL), new MenuScope.Portal(PORTAL)));
        assertFalse(controller.touches(RtpRoutes.routing(PORTAL), new MenuScope.Portal(UUID.randomUUID())));
        assertFalse(controller.touches(RtpRoutes.routing(PORTAL), new MenuScope.Viewer(VIEWER)));
    }

    @Test
    void destinationMatchesTodaysPage() {
        open(RtpRoutes.overview(PORTAL));
        chest("rtp-open-destination");
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("RTP: Alpha", 6, ChestPane.BLACK, null, RtpMenuController.WINDOW_KEY), build.chest());
        assertEquals("Random Destination", build.screen().title());
        assertTrue(build.screen().canGoBack());
        assertNull(build.screen().paging());
        assertEquals(mapOf(
            "rtp-page-title", "Label 0,0 minecraft:recovery_compass - {}",
            "rtp-world-0", "ListEntry -3,1 minecraft:ender_eye* - {}",
            "rtp-world-1", "ListEntry -1,1 minecraft:ender_eye - {}",
            "rtp-world-2", "ListEntry 1,1 minecraft:ender_eye - {}",
            "rtp-center-portal", "ListEntry -2,3 minecraft:compass* - {}",
            "rtp-center-custom", "ListEntry 2,3 minecraft:lodestone - {}",
            "rtp-minimum-radius", "Button 1,4 minecraft:spyglass - {} hidden",
            "rtp-minimum-radius-number", "Number - minecraft:spyglass - {}",
            "rtp-maximum-radius", "Button 3,4 minecraft:target - {} hidden",
            "rtp-maximum-radius-number", "Number - minecraft:target - {}",
            "rtp-center-reset", "Button -3,5 minecraft:barrier - {}",
            "rtp-target-biome", "Button 3,5 minecraft:oak_sapling - {}",
            "rtp-submenu-back", "Button 0,5 minecraft:arrow - {} hidden"), layout(build.screen()));
        assertTrue(((ListEntryElement) element(build.screen(), "rtp-world-0")).selected());
        assertEquals("§a§lworld§r", element(build.screen(), "rtp-world-0").label());
        assertEquals("§b§lworld_the_end§r", element(build.screen(), "rtp-world-1").label());
        assertEquals("§e§lMinimum Radius §f§l512§r", element(build.screen(), "rtp-minimum-radius").label());
        assertEquals(List.of("§7Open clear decrease/increase controls.", "§eLeft click"), chestLore(element(build.screen(), "rtp-minimum-radius")));
        assertEquals("§e§lTarget Biome §f§lAny§r", element(build.screen(), "rtp-target-biome").label());
        assertNumber(build.screen(), "rtp-minimum-radius-number", 512.0D, 0.0D, RtpPortalEditorModel.MAXIMUM_RADIUS, 128.0D, 1024.0D, "512");
        assertNumber(build.screen(), "rtp-maximum-radius-number", 4096.0D, 0.0D, RtpPortalEditorModel.MAXIMUM_RADIUS, 128.0D, 1024.0D, "4096");
        assertEquals("Minimum Radius", element(build.screen(), "rtp-minimum-radius-number").label());
        assertEquals(List.of("Inner edge of the destination annulus."), element(build.screen(), "rtp-minimum-radius-number").description());
        assertBound(build);
    }

    @Test
    void customCentresAddCoordinateControls() {
        rtp.settings = FakeRtpMenuHost.settings().custom(100.0D, -50.0D).biome("minecraft:plains").build();
        open(RtpRoutes.destination(PORTAL));
        MenuScreen screen = host.chest.screen();

        assertEquals("Button -3,4 minecraft:compass - {} hidden", MenuTestViews.describe(element(screen, "rtp-center-x")));
        assertEquals("Button -1,4 minecraft:compass - {} hidden", MenuTestViews.describe(element(screen, "rtp-center-z")));
        assertEquals("ListEntry 2,3 minecraft:lodestone* - {}", MenuTestViews.describe(element(screen, "rtp-center-custom")));
        assertNumber(screen, "rtp-center-x-number", 100.0D, RtpPortalEditorModel.MINIMUM_COORDINATE, RtpPortalEditorModel.MAXIMUM_COORDINATE,
            16.0D, 256.0D, "100.0");
        assertNumber(screen, "rtp-center-z-number", -50.0D, RtpPortalEditorModel.MINIMUM_COORDINATE, RtpPortalEditorModel.MAXIMUM_COORDINATE,
            16.0D, 256.0D, "-50.0");
        assertEquals("§e§lTarget Biome §f§lPlains§r", element(screen, "rtp-target-biome").label());

        rtp.settings = FakeRtpMenuHost.settings().biome("minecraft:deep_dark").build();
        open(RtpRoutes.destination(PORTAL));
        assertEquals("§e§lTarget Biome §f§lUnavailable§r", element(host.chest.screen(), "rtp-target-biome").label());
    }

    @Test
    void worldsPageEightAtATime() {
        List<WorldOption> worlds = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            worlds.add(new WorldOption("minecraft:w" + index, "World " + index, 0, 255));
        }
        rtp.worlds = List.copyOf(worlds);
        open(RtpRoutes.destination(PORTAL));

        MenuScreen first = host.chest.screen();
        assertEquals(new MenuPaging(0, 2), first.paging());
        assertEquals("ListEntry 3,2 minecraft:ender_eye - {}", MenuTestViews.describe(element(first, "rtp-world-7")));
        assertEquals("Button 4,3 minecraft:arrow - {} hidden", MenuTestViews.describe(element(first, "rtp-world-next")));
        assertTrue(first.element("rtp-world-previous").isEmpty());

        chest("rtp-world-next");

        MenuScreen second = host.chest.screen();
        assertEquals(new MenuPaging(1, 2), second.paging());
        assertEquals("§b§lWorld 8§r", element(second, "rtp-world-0").label());
        assertEquals("ListEntry -1,1 minecraft:ender_eye - {}", MenuTestViews.describe(element(second, "rtp-world-1")));
        assertTrue(second.element("rtp-world-2").isEmpty());
        assertEquals("Button -4,3 minecraft:arrow - {} hidden", MenuTestViews.describe(element(second, "rtp-world-previous")));
        chest("rtp-world-1");
        assertEquals(List.of(new TargetWorldMutation("minecraft:w9")), rtp.mutations);

        chest("rtp-world-previous");
        assertEquals(new MenuPaging(0, 2), host.chest.screen().paging());
    }

    @Test
    void destinationChoicesMutateWithTheBuildRevision() {
        open(RtpRoutes.destination(PORTAL));

        chest("rtp-world-2");
        assertEquals(APPLIED, host.chest.lastNotice());
        assertEquals(NoticeChannel.SUCCESS_EFFECT, host.chest.notices.get(0).chestChannel());
        assertEquals(RtpRoutes.DESTINATION, host.chest.screen().screenId());
        chest("rtp-center-custom");
        chest("rtp-center-reset");

        assertEquals(List.of(new TargetWorldMutation("minecraft:the_nether"), new CenterModeMutation(RtpCenterMode.CUSTOM, 12.5D, -40.5D),
            new ResetCenterTargetMutation()), rtp.mutations);
        assertEquals(10L, rtp.revision);
    }

    @Test
    void staleSnapshotsAreRefusedBeforeTheHostRuns() {
        open(RtpRoutes.destination(PORTAL));
        int pushes = host.chest.builds.size();
        rtp.revision = 99L;

        chest("rtp-world-1");

        assertTrue(rtp.calls.isEmpty());
        assertEquals(REFRESHED, host.chest.lastNotice());
        assertEquals(pushes + 1, host.chest.builds.size());

        chest("rtp-world-1");
        assertEquals(List.of("mutate"), rtp.calls);
    }

    @Test
    void hostRevisionRacesAnswerRefreshed() {
        open(RtpRoutes.destination(PORTAL));
        rtp.driftBeforeApply = true;

        chest("rtp-world-1");

        assertEquals(List.of("mutate"), rtp.calls);
        assertEquals(REFRESHED, host.chest.lastNotice());
        assertEquals(RtpRoutes.DESTINATION, host.chest.screen().screenId());
    }

    @Test
    void outstandingMutationsDropClicksUntilTheyComplete() {
        open(RtpRoutes.destination(PORTAL));
        rtp.deferred = new CompletableFuture<>();
        chest("rtp-world-1");
        int events = host.chest.events.size();

        chest("rtp-world-2");
        chest("rtp-center-reset");

        assertEquals(List.of("mutate"), rtp.calls);
        assertEquals(events, host.chest.events.size());

        rtp.deferred.complete(RtpOutcome.applied(WormholesMessages.PORTAL_RTP_APPLIED));

        assertEquals(APPLIED, host.chest.lastNotice());
        assertEquals(RtpRoutes.DESTINATION, host.chest.screen().screenId());
        assertTrue(host.chest.events.size() > events);

        rtp.deferred = new CompletableFuture<>();
        chest("rtp-world-2");
        chest("rtp-center-reset");
        assertEquals(List.of("mutate", "mutate"), rtp.calls);

        rtp.deferred.completeExceptionally(new TimeoutException());

        assertEquals("The server took too long to answer.", host.chest.lastNotice());
        assertEquals(NoticeChannel.HUD, host.chest.notices.get(host.chest.notices.size() - 1).chestChannel());
        assertEquals(RtpRoutes.DESTINATION, host.chest.screen().screenId());

        rtp.deferred = null;
        chest("rtp-center-reset");
        assertEquals(List.of("mutate", "mutate", "mutate"), rtp.calls);
    }

    @Test
    void rejectedSettingsShowTheirReason() {
        open(RtpRoutes.destination(PORTAL));
        rtp.nextOutcome = RtpOutcome.rejected(WormholesMessages.PORTAL_RTP_SETTING_REJECTED,
            MessageArgs.builder().untrusted("reason", "target world is not loaded").build());

        chest("rtp-world-1");

        assertEquals("§aAlpha: §rCould not apply that setting: target world is not loaded", host.chest.lastNotice());
    }

    @Test
    void staleNativeRevisionsNeverReachTheController() throws MenuProtocolException {
        hello();
        open(RtpRoutes.destination(PORTAL));
        long stale = nativeScreen().revision() - 1L;

        receive(new MenuClientMessage.Action(new MenuAction(stale, "rtp-minimum-radius-number", MenuInteraction.INCREMENT, null)));

        assertTrue(rtp.calls.isEmpty());
        MenuServerMessage.Update update = assertInstanceOf(MenuServerMessage.Update.class, host.lastMessage());
        assertEquals("The menu changed and was refreshed.", update.screen().feedback().text());
    }

    @Test
    void nativeNumbersBuildTodaysMutations() throws MenuProtocolException {
        hello();
        open(RtpRoutes.destination(PORTAL));
        nativeAction("rtp-minimum-radius-number", MenuInteraction.INCREMENT, null);
        nativeAction("rtp-maximum-radius-number", MenuInteraction.BIG_DECREMENT, null);
        nativeAction("rtp-maximum-radius-number", MenuInteraction.SET_VALUE, MenuValue.number(100_000.0D));
        nativeAction("rtp-minimum-radius-number", MenuInteraction.SET_VALUE, MenuValue.number(9_000.0D));

        assertEquals(List.of(new RadiiMutation(640, 4096), new RadiiMutation(512, 3072), new RadiiMutation(512, 100_000),
            new RadiiMutation(9_000, 9_001)), rtp.mutations);

        rtp.mutations.clear();
        rtp.settings = FakeRtpMenuHost.settings().rotation(RtpRotationMode.TIMED).build();
        open(RtpRoutes.routing(PORTAL));
        nativeAction("rtp-cycle-duration-number", MenuInteraction.SET_VALUE, MenuValue.number(600.0D));
        nativeAction("rtp-cycle-duration-number", MenuInteraction.DECREMENT, null);
        nativeAction("rtp-lease-idle-number", MenuInteraction.INCREMENT, null);
        nativeAction("rtp-reservation-timeout-number", MenuInteraction.BIG_INCREMENT, null);
        assertEquals(List.of(new CycleDurationMutation(600_000L), new CycleDurationMutation(270_000L), new LeaseIdleMutation(35_000L),
            new ReservationTimeoutMutation(45_000L)), rtp.mutations);

        rtp.mutations.clear();
        rtp.settings = FakeRtpMenuHost.settings().custom(100.0D, -50.0D).build();
        open(RtpRoutes.destination(PORTAL));
        nativeAction("rtp-center-z-number", MenuInteraction.BIG_INCREMENT, null);
        open(RtpRoutes.landing(PORTAL));
        nativeAction("rtp-lower-y-number", MenuInteraction.DECREMENT, null);
        nativeAction("rtp-upper-y-number", MenuInteraction.SET_VALUE, MenuValue.number(100.0D));
        assertEquals(List.of(new CustomCenterMutation(100.0D, 206.0D), new YMutation(59, 120, 64), new YMutation(60, 100, 64)), rtp.mutations);
    }

    @Test
    void numericChestPageStepsLikeToday() {
        open(RtpRoutes.destination(PORTAL));
        chest("rtp-minimum-radius");
        MenuBuild build = host.chest.last();

        assertEquals(RtpRoutes.NUMERIC, build.screen().screenId());
        assertEquals(new ChestLayout("RTP: Alpha", 6, ChestPane.BLACK, null, RtpMenuController.WINDOW_KEY), build.chest());
        assertEquals(mapOf(
            "rtp-page-title", "Label 0,0 minecraft:spyglass - {} hidden",
            "rtp-numeric-decrease-large", "Button -4,2 minecraft:red_dye - {} hidden",
            "rtp-numeric-decrease-small", "Button -2,2 minecraft:red_dye - {} hidden",
            "rtp-numeric-value", "Label 0,2 minecraft:spyglass* - {} hidden",
            "rtp-numeric-increase-small", "Button 2,2 minecraft:lime_dye - {} hidden",
            "rtp-numeric-increase-large", "Button 4,2 minecraft:lime_dye - {} hidden",
            "rtp-numeric-back", "Button 0,5 minecraft:arrow - {} hidden"), layout(build.screen()));
        assertEquals("§b§lMinimum Radius§r", element(build.screen(), "rtp-page-title").label());
        assertEquals(List.of("§7Inner edge of the destination annulus."), element(build.screen(), "rtp-page-title").description());
        assertEquals("§e§l+128§r", element(build.screen(), "rtp-numeric-increase-small").label());
        assertEquals(List.of("§7Adjust the draft by -1024.", "§eLeft click"), chestLore(element(build.screen(), "rtp-numeric-decrease-large")));
        assertEquals("§b§l512§r", element(build.screen(), "rtp-numeric-value").label());
        assertBound(build);

        chest("rtp-numeric-increase-small");
        chest("rtp-numeric-decrease-large");
        assertEquals(List.of(new RadiiMutation(640, 4096), new RadiiMutation(0, 4096)), rtp.mutations);
        assertEquals(RtpRoutes.NUMERIC, host.chest.screen().screenId());

        chest("rtp-numeric-back");
        assertEquals(RtpRoutes.DESTINATION, host.chest.screen().screenId());

        open(RtpRoutes.routing(PORTAL));
        rtp.settings = FakeRtpMenuHost.settings().allocation(RtpAllocationMode.PER_PLAYER).build();
        open(RtpRoutes.numeric(PORTAL, RtpNumericField.CYCLE_DURATION));
        assertEquals("§b§lPrivate Rotation Time§r", element(host.chest.screen(), "rtp-page-title").label());
        assertEquals("§e§l+5m§r", element(host.chest.screen(), "rtp-numeric-increase-large").label());
        assertEquals("§b§l5m§r", element(host.chest.screen(), "rtp-numeric-value").label());
    }

    @Test
    void heightControlsNeedTheTargetWorld() {
        rtp.settings = FakeRtpMenuHost.settings().target("minecraft:unloaded").build();
        open(RtpRoutes.landing(PORTAL));
        NumberElement lower = (NumberElement) element(host.chest.screen(), "rtp-lower-y-number");

        assertFalse(lower.enabled());
        assertTrue(lower.interactions().isEmpty());

        chest("rtp-lower-y");
        assertEquals(mapOf(
            "rtp-page-title", "Label 0,0 minecraft:ladder - {} hidden",
            "rtp-numeric-unavailable", "Label 0,2 minecraft:barrier - {} hidden",
            "rtp-numeric-back", "Button 0,5 minecraft:arrow - {} hidden"), layout(host.chest.screen()));
        chest("rtp-numeric-back");
        assertEquals(RtpRoutes.LANDING, host.chest.screen().screenId());
    }

    @Test
    void landingMatchesTodaysPage() {
        open(RtpRoutes.landing(PORTAL));
        MenuBuild build = host.chest.last();

        assertEquals("Landing", build.screen().title());
        assertEquals(mapOf(
            "rtp-page-title", "Label 0,0 minecraft:grass_block - {}",
            "rtp-vertical-surface", "ListEntry -2,1 minecraft:grass_block* - {}",
            "rtp-vertical-preferred", "ListEntry 2,1 minecraft:amethyst_shard - {}",
            "rtp-surface-policy", "Toggle 0,2 minecraft:shield* - {}",
            "rtp-lower-y", "Button -2,3 minecraft:ladder - {} hidden",
            "rtp-lower-y-number", "Number - minecraft:ladder - {}",
            "rtp-upper-y", "Button 0,3 minecraft:ladder - {} hidden",
            "rtp-upper-y-number", "Number - minecraft:ladder - {}",
            "rtp-submenu-back", "Button 0,5 minecraft:arrow - {} hidden"), layout(build.screen()));
        assertTrue(((ToggleElement) element(build.screen(), "rtp-surface-policy")).value());
        assertEquals("§b§lLanding Safety: §f§lSAFE§r", element(build.screen(), "rtp-surface-policy").label());
        assertNumber(build.screen(), "rtp-lower-y-number", 60.0D, -63.0D, 120.0D, 1.0D, 8.0D, "60");
        assertNumber(build.screen(), "rtp-upper-y-number", 120.0D, 60.0D, 317.0D, 1.0D, 8.0D, "120");
        assertBound(build);

        chest("rtp-surface-policy");
        chest("rtp-vertical-preferred");
        assertEquals(List.of(new SafetyModeMutation(RtpSafetyMode.UNSAFE), new VerticalModeMutation(RtpVerticalMode.PREFERRED_AVERAGE)),
            rtp.mutations);

        rtp.settings = FakeRtpMenuHost.settings().vertical(RtpVerticalMode.PREFERRED_AVERAGE).safety(RtpSafetyMode.UNSAFE).build();
        open(RtpRoutes.landing(PORTAL));
        MenuScreen preferred = host.chest.screen();
        assertEquals("Toggle 0,2 minecraft:lava_bucket - {}", MenuTestViews.describe(element(preferred, "rtp-surface-policy")));
        assertEquals("Button 2,3 minecraft:amethyst_shard - {} hidden", MenuTestViews.describe(element(preferred, "rtp-preferred-y")));
        assertNumber(preferred, "rtp-preferred-y-number", 64.0D, 60.0D, 120.0D, 1.0D, 8.0D, "64");
    }

    @Test
    void routingMatchesTodaysPage() {
        open(RtpRoutes.routing(PORTAL));
        MenuBuild shared = host.chest.last();

        assertEquals("Routing", shared.screen().title());
        assertEquals(mapOf(
            "rtp-page-title", "Label 0,0 minecraft:clock - {}",
            "rtp-allocation-shared", "ListEntry -2,1 minecraft:ender_chest* - {}",
            "rtp-allocation-private", "ListEntry 2,1 minecraft:player_head - {}",
            "rtp-rotation-static", "ListEntry -2,2 minecraft:ender_pearl* - {}",
            "rtp-rotation-timed", "ListEntry 0,2 minecraft:clock - {}",
            "rtp-rotation-traversal", "ListEntry 2,2 minecraft:firework_rocket - {}",
            "rtp-lease-idle", "Button 0,3 minecraft:lead - {} hidden",
            "rtp-lease-idle-number", "Number - minecraft:lead - {}",
            "rtp-reservation-timeout", "Button 3,3 minecraft:tripwire_hook - {} hidden",
            "rtp-reservation-timeout-number", "Number - minecraft:tripwire_hook - {}",
            "rtp-manual-reroll", "Button 0,4 minecraft:fire_charge - {}",
            "rtp-submenu-back", "Button 0,5 minecraft:arrow - {} hidden"), layout(shared.screen()));
        assertEquals(List.of("§7Open a separate confirmation screen.", "§eLeft click"), chestLore(element(shared.screen(), "rtp-manual-reroll")));
        assertNumber(shared.screen(), "rtp-lease-idle-number", 30.0D, 5.0D, 600.0D, 5.0D, 30.0D, "30s");
        assertNumber(shared.screen(), "rtp-reservation-timeout-number", 15.0D, 5.0D, 300.0D, 5.0D, 30.0D, "15s");
        assertBound(shared);

        chest("rtp-rotation-timed");
        chest("rtp-allocation-private");
        assertEquals(List.of(new RotationMutation(RtpRotationMode.TIMED), new AllocationMutation(RtpAllocationMode.PER_PLAYER)), rtp.mutations);

        rtp.settings = FakeRtpMenuHost.settings().rotation(RtpRotationMode.TIMED).build();
        open(RtpRoutes.routing(PORTAL));
        assertEquals("Button -3,3 minecraft:clock - {} hidden", MenuTestViews.describe(element(host.chest.screen(), "rtp-cycle-duration")));
        assertEquals("§e§lCycle Duration §f§l5m§r", element(host.chest.screen(), "rtp-cycle-duration").label());
        assertNumber(host.chest.screen(), "rtp-cycle-duration-number", 300.0D, 15.0D, 86_400.0D, 30.0D, 300.0D, "5m");

        rtp.settings = FakeRtpMenuHost.settings().allocation(RtpAllocationMode.PER_PLAYER).build();
        open(RtpRoutes.routing(PORTAL));
        MenuScreen privateRouting = host.chest.screen();
        assertTrue(privateRouting.element("rtp-rotation-static").isEmpty());
        assertTrue(privateRouting.element("rtp-manual-reroll").isEmpty());
        assertEquals("Button 0,4 minecraft:fire_charge - {}", MenuTestViews.describe(element(privateRouting, "rtp-rebuild-pool")));
        assertEquals("§e§lPrivate Rotation Time §f§l5m§r", element(privateRouting, "rtp-cycle-duration").label());
        assertTrue(((ListEntryElement) element(privateRouting, "rtp-allocation-private")).selected());
    }

    @Test
    void effectsMatchTodaysPage() {
        rtp.settings = FakeRtpMenuHost.settings().effects(true, false).build();
        open(RtpRoutes.effects(PORTAL));
        MenuBuild build = host.chest.last();

        assertEquals("Effects", build.screen().title());
        assertEquals(mapOf(
            "rtp-page-title", "Label 0,0 minecraft:glowstone_dust - {}",
            "rtp-rim-on", "ListEntry -2,1 minecraft:glowstone_dust* - {}",
            "rtp-rim-off", "ListEntry 2,1 minecraft:gunpowder - {}",
            "rtp-sound-on", "ListEntry -2,3 minecraft:jukebox - {}",
            "rtp-sound-off", "ListEntry 2,3 minecraft:note_block* - {}",
            "rtp-submenu-back", "Button 0,5 minecraft:arrow - {} hidden"), layout(build.screen()));
        assertEquals(List.of("§7Mute this RTP portal; particles remain enabled.", "§aSelected"), chestLore(element(build.screen(), "rtp-sound-off")));
        assertBound(build);

        chest("rtp-rim-off");
        chest("rtp-sound-on");
        assertEquals(List.of(new RimMutation(false), new SoundMutation(true)), rtp.mutations);

        chest("rtp-submenu-back");
        assertEquals(RtpRoutes.OVERVIEW, host.chest.screen().screenId());
    }

    @Test
    void biomePageListsTodaysEntries() {
        open(RtpRoutes.destination(PORTAL));
        chest("rtp-target-biome");
        MenuBuild build = host.chest.last();

        assertEquals("Target Biome", build.screen().title());
        assertEquals(mapOf(
            "rtp-page-title", "Label 0,0 minecraft:oak_sapling - {}",
            "rtp-biome-any", "ListEntry -4,1 minecraft:glass* - {}",
            "rtp-biome-0", "ListEntry -3,1 minecraft:fern - {}",
            "rtp-biome-1", "ListEntry -2,1 minecraft:amethyst_shard - {}",
            "rtp-biome-back", "Button 0,5 minecraft:arrow - {} hidden"), layout(build.screen()));
        assertEquals(List.of("§7iris:crystal_caves", "§eLeft click to select"), chestLore(element(build.screen(), "rtp-biome-1")));
        assertBound(build);

        chest("rtp-biome-1");
        chest("rtp-biome-any");
        assertEquals(List.of(new TargetBiomeMutation("iris:crystal_caves"), new TargetBiomeMutation(null)), rtp.mutations);
        chest("rtp-biome-back");
        assertEquals(RtpRoutes.DESTINATION, host.chest.screen().screenId());

        rtp.biomes = List.of();
        open(RtpRoutes.biome(PORTAL));
        assertEquals("Label 0,2 minecraft:barrier - {}", MenuTestViews.describe(element(host.chest.screen(), "rtp-biome-empty")));
    }

    @Test
    void biomesPageTwentyEightAtATime() {
        List<BiomeOption> biomes = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            biomes.add(new BiomeOption("minecraft:b" + index, "Biome " + index, false));
        }
        rtp.biomes = List.copyOf(biomes);
        open(RtpRoutes.biome(PORTAL));

        assertEquals(new MenuPaging(0, 2), host.chest.screen().paging());
        assertEquals("ListEntry 3,4 minecraft:fern - {}", MenuTestViews.describe(element(host.chest.screen(), "rtp-biome-27")));
        assertEquals("Button 4,5 minecraft:arrow - {} hidden", MenuTestViews.describe(element(host.chest.screen(), "rtp-biome-next")));

        chest("rtp-biome-next");
        assertEquals("§b§lBiome 29§r", element(host.chest.screen(), "rtp-biome-1").label());
        assertEquals("Button -4,5 minecraft:arrow - {} hidden", MenuTestViews.describe(element(host.chest.screen(), "rtp-biome-previous")));
    }

    @Test
    void resetDefaultsRunsAtOnceInTheChestAndAsksNatively() throws MenuProtocolException {
        open(RtpRoutes.overview(PORTAL));
        service.chestAction(VIEWER, new MenuChestAction("rtp-reset-defaults", MenuInteraction.PRIMARY, null, true, null));
        assertEquals(List.of("reset"), rtp.calls);
        assertEquals("§aAlpha: §rRandom teleport settings reset to defaults.", host.chest.lastNotice());

        rtp.calls.clear();
        hello();
        open(RtpRoutes.overview(PORTAL));
        nativeAction("rtp-reset-defaults", MenuInteraction.PRIMARY, null);
        MenuServerMessage.Open confirm = assertInstanceOf(MenuServerMessage.Open.class, host.lastMessage());
        assertTrue(rtp.calls.isEmpty());
        assertEquals("Reset Alpha?", confirm.screen().element("confirm-header").orElseThrow().label());
        assertEquals(List.of("Every random teleport setting returns to its default."), confirm.screen().confirmation().messageLines());
        assertEquals("Reset", confirm.screen().element("confirm-accept").orElseThrow().label());

        receive(new MenuClientMessage.Confirm(confirm.screen().revision(), true));
        assertEquals(List.of("reset"), rtp.calls);
        assertEquals(RtpRoutes.OVERVIEW, nativeScreen().screenId());
    }

    @Test
    void manualRerollUsesTodaysConfirmPageInTheChest() {
        open(RtpRoutes.routing(PORTAL));
        chest("rtp-manual-reroll");
        MenuBuild build = host.chest.last();

        assertEquals(RtpRoutes.CONFIRM, build.screen().screenId());
        assertEquals(new ChestLayout("RTP: Alpha", 6, ChestPane.BLACK, null, RtpMenuController.WINDOW_KEY), build.chest());
        assertEquals(mapOf(
            "rtp-page-title", "Label 0,0 minecraft:fire_charge - {} hidden",
            "rtp-manual-confirm", "Button -2,2 minecraft:lime_dye - {} hidden",
            "rtp-manual-cancel", "Button 2,2 minecraft:red_dye - {} hidden"), layout(build.screen()));
        assertEquals("§b§lReroll Shared Route?§r", element(build.screen(), "rtp-page-title").label());
        assertEquals(List.of("§7Run the action now.", "§eLeft click"), chestLore(element(build.screen(), "rtp-manual-confirm")));
        assertEquals(List.of("§7Return without changing runtime state.", "§eLeft click"), chestLore(element(build.screen(), "rtp-manual-cancel")));
        assertBound(build);

        chest("rtp-manual-cancel");
        assertEquals(RtpRoutes.ROUTING, host.chest.screen().screenId());
        assertTrue(rtp.calls.isEmpty());

        chest("rtp-manual-reroll");
        chest("rtp-manual-confirm");
        assertEquals(List.of("manual:REROLL"), rtp.calls);
        assertEquals(RtpRoutes.ROUTING, host.chest.screen().screenId());
        assertEquals("§aAlpha: §rPreparing a new random destination.", host.chest.lastNotice());
    }

    @Test
    void manualRebuildAsksNatively() throws MenuProtocolException {
        rtp.settings = FakeRtpMenuHost.settings().allocation(RtpAllocationMode.PER_PLAYER).build();
        hello();
        open(RtpRoutes.routing(PORTAL));

        nativeAction("rtp-rebuild-pool", MenuInteraction.PRIMARY, null);

        MenuServerMessage.Open confirm = assertInstanceOf(MenuServerMessage.Open.class, host.lastMessage());
        assertTrue(rtp.calls.isEmpty());
        assertEquals("§b§lRebuild Private Pool?§r", confirm.screen().element("confirm-header").orElseThrow().label());
        assertEquals(List.of("§7Existing reservations stay intact while free candidates rebuild."), confirm.screen().confirmation().messageLines());
        assertEquals("Confirm", confirm.screen().element("confirm-accept").orElseThrow().label());
        assertEquals("Cancel", confirm.screen().element("confirm-cancel").orElseThrow().label());

        receive(new MenuClientMessage.Confirm(confirm.screen().revision(), false));
        assertTrue(rtp.calls.isEmpty());
        assertEquals(RtpRoutes.ROUTING, nativeScreen().screenId());

        nativeAction("rtp-rebuild-pool", MenuInteraction.PRIMARY, null);
        receive(new MenuClientMessage.Confirm(nativeScreen().revision(), true));
        assertEquals(List.of("manual:REBUILD_POOL"), rtp.calls);
        assertEquals(RtpRoutes.ROUTING, nativeScreen().screenId());
    }

    @Test
    void navigationFollowsTodaysPages() {
        open(RtpRoutes.overview(PORTAL));
        for (String opener : List.of("rtp-open-destination", "rtp-open-landing", "rtp-open-routing", "rtp-open-effects")) {
            chest(opener);
            assertTrue(host.chest.screen().canGoBack(), opener);
            chest("rtp-submenu-back");
            assertEquals(RtpRoutes.OVERVIEW, host.chest.screen().screenId(), opener);
        }

        chest("rtp-back");
        assertEquals(PortalRoutes.HOME, host.chest.screen().screenId());
    }

    @Test
    void everyPageSharesOneChestWindow() {
        open(RtpRoutes.overview(PORTAL));
        List<String> screens = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        screens.add(host.chest.screen().screenId());
        keys.add(host.chest.last().windowKey());
        for (String step : List.of("rtp-open-destination", "rtp-minimum-radius", "rtp-numeric-back", "rtp-target-biome", "rtp-biome-back",
            "rtp-submenu-back", "rtp-open-landing", "rtp-submenu-back", "rtp-open-routing", "rtp-manual-reroll", "rtp-manual-cancel",
            "rtp-submenu-back", "rtp-open-effects")) {
            chest(step);
            screens.add(host.chest.screen().screenId());
            keys.add(host.chest.last().windowKey());
        }

        assertEquals(List.of(RtpRoutes.OVERVIEW, RtpRoutes.DESTINATION, RtpRoutes.NUMERIC, RtpRoutes.DESTINATION, RtpRoutes.BIOME,
            RtpRoutes.DESTINATION, RtpRoutes.OVERVIEW, RtpRoutes.LANDING, RtpRoutes.OVERVIEW, RtpRoutes.ROUTING, RtpRoutes.CONFIRM,
            RtpRoutes.ROUTING, RtpRoutes.OVERVIEW, RtpRoutes.EFFECTS), screens);
        assertEquals(List.of(RtpMenuController.WINDOW_KEY), keys.stream().distinct().toList());

        chest("rtp-submenu-back");
        chest("rtp-back");
        assertEquals(PortalRoutes.HOME, host.chest.last().windowKey());
    }

    @Test
    void portalsThatLeaveRtpModeGoHome() {
        open(RtpRoutes.destination(PORTAL));
        rtp.nextOutcome = RtpOutcome.notRtp();

        chest("rtp-world-1");

        assertEquals(PortalRoutes.HOME, host.chest.screen().screenId());
        assertEquals(NOT_RTP, host.chest.lastNotice());

        leaveRtp();
        host.chest.events.clear();
        open(RtpRoutes.overview(PORTAL));
        assertWentHome();
    }

    @Test
    void actionsRefreshesAndTheSweepSendNonRtpPortalsHome() {
        open(RtpRoutes.destination(PORTAL));
        leaveRtp();
        host.chest.events.clear();
        chest("rtp-world-1");
        assertWentHome();
        assertTrue(rtp.calls.isEmpty());

        enterRtp();
        open(RtpRoutes.overview(PORTAL));
        leaveRtp();
        host.chest.events.clear();
        service.invalidate(new MenuScope.Portal(PORTAL));
        assertWentHome();

        enterRtp();
        open(RtpRoutes.landing(PORTAL));
        leaveRtp();
        host.chest.events.clear();
        for (int tick = 0; tick < MenuLimits.REVOCATION_INTERVAL_TICKS; tick++) {
            service.tick();
        }
        assertWentHome();
    }

    @Test
    void nativeScreensOfNonRtpPortalsGoHome() throws MenuProtocolException {
        hello();
        open(RtpRoutes.routing(PORTAL));
        leaveRtp();
        int sent = host.frames.size();

        nativeAction("rtp-lease-idle-number", MenuInteraction.INCREMENT, null);

        List<MenuServerMessage> messages = host.serverMessages().subList(sent, host.frames.size());
        assertEquals(2, messages.size());
        assertEquals(PortalRoutes.HOME, assertInstanceOf(MenuServerMessage.Open.class, messages.get(0)).screen().screenId());
        assertEquals(NOT_RTP, assertInstanceOf(MenuServerMessage.Feedback.class, messages.get(1)).feedback().text());
        assertTrue(rtp.calls.isEmpty());
    }

    @Test
    void viewersWhoCannotManageAreRejectedEverywhere() throws MenuProtocolException {
        List<MenuRoute> routes = List.of(RtpRoutes.overview(PORTAL), RtpRoutes.destination(PORTAL), RtpRoutes.biome(PORTAL),
            RtpRoutes.landing(PORTAL), RtpRoutes.routing(PORTAL), RtpRoutes.effects(PORTAL),
            RtpRoutes.numeric(PORTAL, RtpNumericField.MINIMUM_RADIUS), RtpRoutes.confirm(PORTAL, RtpPortalEditorModel.ManualAction.REROLL));
        int checked = 0;
        for (MenuRoute route : routes) {
            for (MenuElement element : elements(route, false)) {
                if (element.chestSlot() == null || element.interactions().isEmpty()) {
                    continue;
                }
                checked += rejected(route, element, false);
            }
        }
        hello();
        for (MenuRoute route : routes) {
            for (MenuElement element : elements(route, true)) {
                if (element.common().nativeHidden() || element.interactions().isEmpty()) {
                    continue;
                }
                checked += rejected(route, element, true);
            }
        }
        assertTrue(checked > 40, "checked " + checked);
    }

    @Test
    void declaredChestHintsMatchTheEnglishClickInstructions() {
        for (Map.Entry<LinesKey, Integer> entry : RtpMenuController.CHEST_HINTS.entrySet()) {
            assertEquals(MenuTestViews.trailingClickLines(entry.getKey().english()), entry.getValue().intValue(), entry.getKey().id());
        }
    }

    private int rejected(MenuRoute route, MenuElement element, boolean nativeOrigin) throws MenuProtocolException {
        portals.managers.add(VIEWER);
        open(route);
        portals.managers.clear();
        host.chest.events.clear();
        int frames = host.frames.size();
        MenuInteraction interaction = element.interactions().iterator().next();
        MenuValue value = switch (interaction) {
            case SET_VALUE -> MenuValue.number(((NumberElement) element).value());
            case SET_TEXT -> MenuValue.text("x");
            default -> null;
        };
        if (nativeOrigin) {
            nativeAction(element.id(), interaction, value);
            assertInstanceOf(MenuServerMessage.Close.class, host.lastMessage(), route.screen() + "/" + element.id());
            assertTrue(host.frames.size() > frames);
        } else {
            service.chestAction(VIEWER, new MenuChestAction(element.id(), interaction, value, true, null));
            assertTrue(host.chest.events.contains("close"), route.screen() + "/" + element.id());
        }
        assertTrue(rtp.calls.isEmpty(), route.screen() + "/" + element.id());
        assertTrue(rtp.mutations.isEmpty(), route.screen() + "/" + element.id());
        return 1;
    }

    private List<MenuElement> elements(MenuRoute route, boolean nativeOrigin) throws MenuProtocolException {
        portals.managers.add(VIEWER);
        open(route);
        return List.copyOf(nativeOrigin ? nativeScreen().elements() : host.chest.screen().elements());
    }

    @Test
    void nativeLabelsNameTheSettingWithoutItsValue() {
        open(RtpRoutes.landing(PORTAL));
        MenuScreen screen = host.chest.screen();

        MenuScreens.assertSettingLabels(screen);
        assertEquals("Landing Safety", MenuText.stripColors(MenuScreens.element(screen, "rtp-surface-policy").common().wireLabel()));
    }

    private void open(MenuRoute route) {
        service.open(VIEWER, route);
    }

    private void leaveRtp() {
        portals.portal = FakePortalAccess.base().build();
        rtp.rtp = false;
    }

    private void enterRtp() {
        portals.portal = FakePortalAccess.base().type(PortalType.RTP)
            .rtp(new PortalMenuView.Rtp(RtpAllocationMode.SHARED, RtpRotationMode.STATIC, 0L)).build();
        rtp.rtp = true;
    }

    private void assertWentHome() {
        assertEquals(PortalRoutes.HOME, host.chest.screen().screenId());
        assertTrue(host.chest.events.get(0).startsWith("open:" + PortalRoutes.HOME + "@"), String.valueOf(host.chest.events));
        assertEquals("notice:SUCCESS_EFFECT", host.chest.lastEvent());
        assertEquals(2, host.chest.events.size(), String.valueOf(host.chest.events));
        assertEquals(NOT_RTP, host.chest.lastNotice());
    }

    private void chest(String elementId) {
        service.chestAction(VIEWER, new MenuChestAction(elementId, MenuInteraction.PRIMARY, null, false, null));
    }

    private void hello() {
        receive(new MenuClientMessage.Hello(1));
    }

    private void nativeAction(String elementId, MenuInteraction interaction, MenuValue value) throws MenuProtocolException {
        receive(new MenuClientMessage.Action(new MenuAction(nativeScreen().revision(), elementId, interaction, value)));
    }

    private MenuScreen nativeScreen() throws MenuProtocolException {
        List<MenuServerMessage> messages = host.serverMessages();
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof MenuServerMessage.Open opened) {
                return opened.screen();
            }
            if (messages.get(index) instanceof MenuServerMessage.Update updated) {
                return updated.screen();
            }
        }
        throw new AssertionError("no native screen was sent");
    }

    private void receive(MenuClientMessage message) {
        now += 1_000L;
        try {
            service.receive(VIEWER, MenuCodec.encodeClient(message));
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertNumber(MenuScreen screen, String id, double value, double min, double max, double step, double bigStep, String display) {
        NumberElement number = (NumberElement) element(screen, id);
        assertEquals(List.of(value, min, max, step, bigStep), List.of(number.value(), number.min(), number.max(), number.step(), number.bigStep()), id);
        assertEquals(display, number.display(), id);
        assertTrue(number.enabled(), id);
    }
}

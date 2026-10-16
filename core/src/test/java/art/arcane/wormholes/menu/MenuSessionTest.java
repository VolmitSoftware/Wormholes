package art.arcane.wormholes.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.localization.MenuMessages;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import art.arcane.wormholes.service.WormholesTelemetry;

final class MenuSessionTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Logger MENU_LOGGER = Logger.getLogger(MenuService.class.getName());

    private FakeMenuHost host;
    private TestController controller;
    private MenuService service;
    private long now;
    private RecordingHandler logs;

    @BeforeEach
    void setUp() {
        host = new FakeMenuHost();
        host.viewer(VIEWER, "Tester");
        controller = new TestController();
        now = 1_000L;
        service = new MenuService(host, new MenuRegistry(List.of(controller)), () -> now);
        logs = new RecordingHandler();
        MENU_LOGGER.addHandler(logs);
    }

    @AfterEach
    void tearDown() {
        MENU_LOGGER.removeHandler(logs);
    }

    @Test
    void chestSessionsOpenWithAFreshRevision() {
        service.open(VIEWER, TestController.main());

        assertEquals(List.of("open:test.main@1"), host.chest.events);
        assertFalse(host.chest.screen().canGoBack());
        assertEquals("Back", host.chest.screen().chrome().back());
        assertEquals("Page 1 / 3", host.chest.screen().chrome().pageLabel());
        assertEquals(new FakeStrings().text(MenuMessages.CHROME_TIMEOUT), host.chest.screen().chrome().timeout());
    }

    @Test
    void handledActionsAlwaysPushANewRevision() {
        service.open(VIEWER, TestController.main());

        chest("increment", MenuInteraction.PRIMARY);
        chest("guarded", MenuInteraction.PRIMARY);

        assertEquals(List.of("increment", "guarded"), controller.handled);
        assertEquals(List.of("open:test.main@1", "update:test.main@2", "update:test.main@3"), host.chest.events);
    }

    @Test
    void staleNativeRevisionsResendWithRefreshedFeedback() throws MenuProtocolException {
        openNative();

        nativeAction(0L, "increment", MenuInteraction.PRIMARY, null);

        assertTrue(controller.handled.isEmpty());
        MenuServerMessage.Update update = assertInstanceOf(MenuServerMessage.Update.class, host.lastMessage());
        assertEquals(1L, update.screen().revision());
        assertEquals(new FeedbackText().refreshed(), update.screen().feedback().text());
    }

    @Test
    void unknownElementsAndUndeclaredInteractionsAreDroppedAsViolations() throws MenuProtocolException {
        openNative();

        nativeAction(revision(), "missing", MenuInteraction.PRIMARY, null);
        nativeAction(revision(), "increment", MenuInteraction.SECONDARY, null);

        assertTrue(controller.handled.isEmpty());
        assertTrue(menuOpen());
        nativeAction(revision(), "label", MenuInteraction.PRIMARY, null);
        assertFalse(menuOpen());
        assertTrue(service.capabilities().capability(VIEWER).isEmpty());
        assertInstanceOf(MenuServerMessage.Close.class, host.lastMessage());
    }

    @Test
    void outOfRangeAndMalformedValuesAreRejected() throws MenuProtocolException {
        openNative();
        long revision = revision();

        nativeAction(revision, "number", MenuInteraction.SET_VALUE, MenuValue.number(11.0D));
        host.frames.clear();
        now += 20_000L;
        nativeAction(revision, "name", MenuInteraction.SET_TEXT, MenuValue.text("far too long"));
        now += 20_000L;
        nativeAction(revision, "name", MenuInteraction.SET_TEXT, MenuValue.text("§cRed"));
        now += 20_000L;
        nativeAction(revision, "name", MenuInteraction.SET_TEXT, MenuValue.text("tab\there"));
        now += 20_000L;
        nativeAction(revision, "cycle", MenuInteraction.SELECT, MenuValue.index(3));
        now += 20_000L;
        nativeAction(revision, "increment", MenuInteraction.PRIMARY, MenuValue.number(1.0D));

        assertTrue(controller.handled.isEmpty());
        assertEquals(5, host.serverMessages().size());
        for (MenuServerMessage message : host.serverMessages()) {
            MenuServerMessage.Feedback feedback = assertInstanceOf(MenuServerMessage.Feedback.class, message);
            assertEquals(new FeedbackText().invalidValue(), feedback.feedback().text());
        }
    }

    @Test
    void valuesInsideTheirBoundsReachTheHandler() throws MenuProtocolException {
        openNative();

        nativeAction(revision(), "number", MenuInteraction.SET_VALUE, MenuValue.number(10.0D));
        nativeAction(revision(), "name", MenuInteraction.SET_TEXT, MenuValue.text("Arch"));
        nativeAction(revision(), "cycle", MenuInteraction.SELECT, MenuValue.index(2));

        assertEquals(List.of("number", "name", "cycle"), controller.handled);
        assertEquals("Arch", controller.name);
        assertEquals(2, controller.cycle);
    }

    @Test
    void elementGuardsSkipTheHandlerAndDeliverTheirNotice() {
        service.open(VIEWER, TestController.main());
        controller.guardAllows = false;

        chest("guarded", MenuInteraction.PRIMARY);

        assertTrue(controller.handled.isEmpty());
        assertEquals("denied", host.chest.lastNotice());
        assertTrue(menuOpen());
    }

    @Test
    void routeGuardsRejectOpensAndCloseOnAction() {
        controller.accessAllowed = false;
        service.open(VIEWER, TestController.main());

        assertEquals(List.of("notice:HUD"), host.chest.events);
        assertFalse(menuOpen());

        controller.accessAllowed = true;
        service.open(VIEWER, TestController.main());
        controller.accessAllowed = false;
        chest("increment", MenuInteraction.PRIMARY);

        assertTrue(controller.handled.isEmpty());
        assertEquals(List.of("notice:HUD", "open:test.main@1", "close", "notice:HUD"), host.chest.events);
        assertFalse(menuOpen());
    }

    @Test
    void confirmationsShowASubScreenAndRunOnlyWhenAccepted() throws MenuProtocolException {
        openNative();

        nativeAction(revision(), "delete", MenuInteraction.PRIMARY, null);
        MenuServerMessage.Open confirm = assertInstanceOf(MenuServerMessage.Open.class, host.lastMessage());
        assertEquals("test.main.confirm", confirm.screen().screenId());
        assertEquals(List.of("Really delete it?"), confirm.screen().confirmation().messageLines());
        assertEquals(MenuStyle.DESTRUCTIVE, confirm.screen().element(MenuSession.CONFIRM_ACCEPT).orElseThrow().style());
        assertFalse(controller.deleted);

        receive(new MenuClientMessage.Confirm(revision(), false));
        assertFalse(controller.deleted);
        assertEquals("test.main", assertInstanceOf(MenuServerMessage.Open.class, host.lastMessage()).screen().screenId());

        nativeAction(revision(), "delete", MenuInteraction.PRIMARY, null);
        receive(new MenuClientMessage.Confirm(0L, true));
        assertFalse(controller.deleted);
        nativeAction(revision(), MenuSession.CONFIRM_ACCEPT, MenuInteraction.PRIMARY, null);
        assertTrue(controller.deleted);
        assertFalse(menuOpen());
    }

    @Test
    void preconfirmedChestClicksSkipTheConfirmation() {
        service.open(VIEWER, TestController.main());

        chest("delete", MenuInteraction.PRIMARY);
        assertFalse(controller.deleted);
        assertEquals("open:test.main.confirm@2", host.chest.lastEvent());
        assertEquals(new MenuSlot(-2, 1), host.chest.screen().element(MenuSession.CONFIRM_ACCEPT).orElseThrow().chestSlot());

        service.chestEscape(VIEWER);
        assertEquals("open:test.main@3", host.chest.lastEvent());

        service.chestAction(VIEWER, new MenuChestAction("delete", MenuInteraction.PRIMARY, null, true, null));
        assertTrue(controller.deleted);
        assertEquals("close", host.chest.lastEvent());
    }

    @Test
    void confirmationRechecksTheRouteGuard() throws MenuProtocolException {
        openNative();
        nativeAction(revision(), "delete", MenuInteraction.PRIMARY, null);
        controller.accessAllowed = false;

        receive(new MenuClientMessage.Confirm(revision(), true));

        assertFalse(controller.deleted);
        assertFalse(menuOpen());
    }

    @Test
    void backNavigatesAndClosesAtTheRoot() throws MenuProtocolException {
        openNative();

        nativeAction(revision(), "child", MenuInteraction.PRIMARY, null);
        MenuServerMessage.Open child = assertInstanceOf(MenuServerMessage.Open.class, host.lastMessage());
        assertEquals(TestController.CHILD, child.screen().screenId());
        assertTrue(child.screen().canGoBack());

        receive(new MenuClientMessage.Back(revision()));
        MenuServerMessage.Open main = assertInstanceOf(MenuServerMessage.Open.class, host.lastMessage());
        assertEquals(TestController.MAIN, main.screen().screenId());
        assertFalse(main.screen().canGoBack());

        receive(new MenuClientMessage.Back(revision()));
        assertInstanceOf(MenuServerMessage.Close.class, host.lastMessage());
        assertFalse(menuOpen());
    }

    @Test
    void chestEscapeFollowsTheEscapeRoute() {
        service.open(VIEWER, TestController.main());
        chest("child", MenuInteraction.PRIMARY);

        service.chestEscape(VIEWER);
        assertEquals("open:test.main@3", host.chest.lastEvent());

        service.chestEscape(VIEWER);
        assertFalse(menuOpen());
    }

    @Test
    void chestEndEndsTheSessionWithoutFollowingTheEscapeRoute() {
        service.open(VIEWER, TestController.main());
        chest("child", MenuInteraction.PRIMARY);
        List<String> shown = List.copyOf(host.chest.events);

        service.chestEnd(VIEWER);
        service.chestEscape(VIEWER);
        chest("increment", MenuInteraction.PRIMARY);

        assertEquals(shown, host.chest.events);
        assertTrue(controller.handled.isEmpty());
        assertFalse(menuOpen());
    }

    @Test
    void pagesInsideTheBoundsRebuildAndOthersCountAsViolations() throws MenuProtocolException {
        openNative();

        receive(new MenuClientMessage.Page(revision(), 2));
        assertEquals(2, assertInstanceOf(MenuServerMessage.Update.class, host.lastMessage()).screen().paging().page());

        receive(new MenuClientMessage.Page(revision(), 3));
        receive(new MenuClientMessage.Page(revision(), -1));
        assertTrue(menuOpen());
        receive(new MenuClientMessage.Page(revision(), 9));
        assertFalse(menuOpen());
    }

    @Test
    void clientCloseEndsTheSessionSilently() throws MenuProtocolException {
        openNative();
        int frames = host.frames.size();

        receive(new MenuClientMessage.Closed(12L));

        assertFalse(menuOpen());
        assertEquals(frames, host.frames.size());
    }

    @Test
    void pendingResultsCompleteOnTheViewerThread() {
        service.open(VIEWER, TestController.main());
        chest("slow", MenuInteraction.PRIMARY);
        chest("increment", MenuInteraction.PRIMARY);
        assertTrue(controller.handled.isEmpty());

        host.deferTasks = true;
        controller.slow.complete(MenuResult.stay(MenuNotices.text("Done", MenuFeedbackStyle.SUCCESS, NoticeChannel.CHAT)));
        assertEquals(1, host.deferred.size());
        host.runDeferred();
        host.deferTasks = false;

        assertEquals("Done", host.chest.lastNotice());
        chest("increment", MenuInteraction.PRIMARY);
        assertEquals(List.of("increment"), controller.handled);
    }

    @Test
    void pendingNoticesAreDeliveredBeforeTheWorkCompletes() {
        service.open(VIEWER, TestController.main());
        controller.slow.complete(MenuResult.stay(MenuNotices.text("Done", MenuFeedbackStyle.SUCCESS, NoticeChannel.CHAT)));

        chest("slow-notice", MenuInteraction.PRIMARY);

        assertEquals(List.of("Loading", "Done"), host.chest.notices.stream().map(notice -> notice.feedback().text()).toList());
        assertEquals(List.of(NoticeChannel.CHAT, NoticeChannel.CHAT), host.chest.notices.stream().map(MenuNotice::chestChannel).toList());
    }

    @Test
    void pendingNoticesDoNotUnblockTheScreen() {
        service.open(VIEWER, TestController.main());

        chest("slow-notice", MenuInteraction.PRIMARY);
        chest("increment", MenuInteraction.PRIMARY);

        assertEquals("Loading", host.chest.lastNotice());
        assertTrue(controller.handled.isEmpty());
        controller.slow.complete(MenuResult.stay());
        chest("increment", MenuInteraction.PRIMARY);
        assertEquals(List.of("increment"), controller.handled);
    }

    @Test
    void deferredResultsResolveWhenTheSessionAppliesThemOnTheViewerThread() {
        service.open(VIEWER, TestController.main());
        chest("slow", MenuInteraction.PRIMARY);
        host.deferTasks = true;

        controller.slow.complete(MenuResult.deferred(() -> {
            controller.handled.add("resolved");
            return MenuResult.stay(MenuNotices.text("Resolved", MenuFeedbackStyle.SUCCESS, NoticeChannel.CHAT));
        }));

        assertTrue(controller.handled.isEmpty());
        assertEquals(1, host.deferred.size());
        host.runDeferred();
        host.deferTasks = false;
        assertEquals(List.of("resolved"), controller.handled);
        assertEquals("Resolved", host.chest.lastNotice());
        assertTrue(menuOpen());
    }

    @Test
    void deferredResultExceptionsAreLoggedAndTheSessionSurvives() {
        service.open(VIEWER, TestController.main());
        chest("slow", MenuInteraction.PRIMARY);

        controller.slow.complete(MenuResult.deferred(() -> {
            throw new IllegalStateException("deferred boom");
        }));

        assertSevere("deferred boom");
        assertEquals(new FeedbackText().unavailable(), host.chest.lastNotice());
        assertTrue(menuOpen());
    }

    @Test
    void popToWithAnExplicitPageLandsOnThatPage() {
        service.open(VIEWER, TestController.main());
        chest("next-page", MenuInteraction.PRIMARY);
        chest("next-page", MenuInteraction.PRIMARY);
        chest("child", MenuInteraction.PRIMARY);

        chest("home-page", MenuInteraction.PRIMARY);

        assertEquals(TestController.MAIN, host.chest.screen().screenId());
        assertEquals(1, host.chest.screen().paging().page());
        assertFalse(host.chest.screen().canGoBack());
    }

    @Test
    void enteringARouteForgetsItsPageUnlessTheResultNamesOne() {
        service.open(VIEWER, TestController.main());
        chest("next-page", MenuInteraction.PRIMARY);
        chest("child", MenuInteraction.PRIMARY);
        chest("next-page", MenuInteraction.PRIMARY);
        service.chestEscape(VIEWER);
        assertEquals(TestController.MAIN, host.chest.screen().screenId());
        assertEquals(1, host.chest.screen().paging().page());

        chest("child", MenuInteraction.PRIMARY);
        assertEquals(TestController.CHILD, host.chest.screen().screenId());
        assertEquals(0, host.chest.screen().paging().page());

        service.chestEscape(VIEWER);
        chest("child-page", MenuInteraction.PRIMARY);
        assertEquals(TestController.CHILD, host.chest.screen().screenId());
        assertEquals(2, host.chest.screen().paging().page());
    }

    @Test
    void negativeNavigatePagesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> MenuResult.navigate(TestController.main(), NavigateMode.POP_TO, -1, null));
        service.open(VIEWER, TestController.main());
        chest("child", MenuInteraction.PRIMARY);

        chest("negative-page", MenuInteraction.PRIMARY);

        assertSevere("page -1 must not be negative");
        assertEquals(TestController.CHILD, host.chest.screen().screenId());
        assertEquals(new FeedbackText().unavailable(), host.chest.lastNotice());
    }

    @Test
    void pendingTimeoutsAnswerWithFeedback() {
        service.open(VIEWER, TestController.main());
        chest("slow", MenuInteraction.PRIMARY);

        controller.slow.completeExceptionally(new TimeoutException());

        assertEquals(new FeedbackText().timeout(), host.chest.lastNotice());
        assertEquals(NoticeChannel.HUD, host.chest.notices.get(host.chest.notices.size() - 1).chestChannel());
        chest("increment", MenuInteraction.PRIMARY);
        assertEquals(List.of("increment"), controller.handled);
    }

    @Test
    void periodicRefreshPushesOnlyChangedContent() {
        controller.refreshInterval = 2;
        service.open(VIEWER, TestController.main());

        service.tick();
        service.tick();
        service.tick();
        service.tick();
        assertEquals(List.of("open:test.main@1"), host.chest.events);

        controller.counter = 4;
        service.tick();
        service.tick();
        assertEquals("update:test.main@2", host.chest.lastEvent());
    }

    @Test
    void invalidationReachesOnlyTouchingSessions() {
        service.open(VIEWER, TestController.main());
        controller.counter = 3;

        service.invalidate(new MenuScope.Portal(UUID.randomUUID()));
        assertEquals(1, host.chest.events.size());

        service.invalidate(new MenuScope.Portal(TestController.TOUCHED));
        assertEquals("update:test.main@2", host.chest.lastEvent());
    }

    @Test
    void revocationSweepClosesSessionsThatLostAccess() {
        service.open(VIEWER, TestController.main());
        controller.accessAllowed = false;

        for (int tick = 0; tick < MenuLimits.REVOCATION_INTERVAL_TICKS - 1; tick++) {
            service.tick();
        }
        assertEquals(List.of("open:test.main@1"), host.chest.events);
        service.tick();

        assertFalse(menuOpen());
        assertEquals(List.of("open:test.main@1", "close", "notice:HUD"), host.chest.events);
    }

    @Test
    void redirectingRouteGuardsOpenTheirTargetWithTheNotice() {
        controller.childRedirects = true;

        service.open(VIEWER, TestController.child());

        assertEquals(List.of("open:test.main@1", "notice:CHAT"), host.chest.events);
        assertEquals("moved", host.chest.lastNotice());
        assertTrue(menuOpen());

        chest("child", MenuInteraction.PRIMARY);

        assertEquals(List.of("open:test.main@1", "notice:CHAT", "notice:CHAT"), host.chest.events);
        assertEquals(TestController.MAIN, host.chest.screen().screenId());
        assertTrue(menuOpen());
    }

    @Test
    void redirectsToARefusedTargetRefuseTheOpen() {
        controller.childRedirects = true;
        controller.accessAllowed = false;

        service.open(VIEWER, TestController.child());

        assertEquals(List.of("notice:CHAT"), host.chest.events);
        assertEquals("moved", host.chest.lastNotice());
        assertFalse(menuOpen());
    }

    @Test
    void actionsRefreshesAndTheSweepFollowRedirects() {
        service.open(VIEWER, TestController.main());
        chest("child", MenuInteraction.PRIMARY);
        controller.childRedirects = true;

        chest("increment", MenuInteraction.PRIMARY);

        assertTrue(controller.handled.isEmpty());
        assertEquals(List.of("open:test.main@1", "open:test.child@2", "open:test.main@3", "notice:CHAT"), host.chest.events);
        assertFalse(host.chest.screen().canGoBack());

        controller.childRedirects = false;
        chest("child", MenuInteraction.PRIMARY);
        controller.childRedirects = true;
        service.invalidate(new MenuScope.Portal(TestController.TOUCHED));

        assertEquals(List.of("open:test.child@4", "open:test.main@5", "notice:CHAT"), host.chest.events.subList(4, 7));

        controller.childRedirects = false;
        chest("child", MenuInteraction.PRIMARY);
        controller.childRedirects = true;
        for (int tick = 0; tick < MenuLimits.REVOCATION_INTERVAL_TICKS; tick++) {
            service.tick();
        }

        assertEquals(List.of("open:test.child@6", "open:test.main@7", "notice:CHAT"), host.chest.events.subList(7, 10));
        assertTrue(menuOpen());
    }

    @Test
    void acceptedConfirmationsFollowARedirectOnce() throws MenuProtocolException {
        openNative();
        nativeAction(revision(), "child", MenuInteraction.PRIMARY, null);
        nativeAction(revision(), "delete", MenuInteraction.PRIMARY, null);
        controller.childRedirects = true;
        int sent = host.frames.size();

        receive(new MenuClientMessage.Confirm(revision(), true));

        assertFalse(controller.deleted);
        List<MenuServerMessage> messages = host.serverMessages().subList(sent, host.frames.size());
        assertEquals(2, messages.size());
        assertEquals(TestController.MAIN, assertInstanceOf(MenuServerMessage.Open.class, messages.get(0)).screen().screenId());
        assertEquals("moved", assertInstanceOf(MenuServerMessage.Feedback.class, messages.get(1)).feedback().text());
        assertTrue(menuOpen());
    }

    @Test
    void handlerExceptionsAreAnsweredAndTheSessionSurvives() {
        service.open(VIEWER, TestController.main());

        chest("explode", MenuInteraction.PRIMARY);

        assertEquals(new FeedbackText().unavailable(), host.chest.lastNotice());
        assertEquals(NoticeChannel.HUD, host.chest.notices.get(host.chest.notices.size() - 1).chestChannel());
        assertSevere("boom");
        chest("increment", MenuInteraction.PRIMARY);
        assertEquals(List.of("increment"), controller.handled);
    }

    @Test
    void guardExceptionsAreLoggedAnsweredAndSkipTheHandler() {
        service.open(VIEWER, TestController.main());

        chest("guard-explode", MenuInteraction.PRIMARY);

        assertTrue(controller.handled.isEmpty());
        assertEquals(new FeedbackText().unavailable(), host.chest.lastNotice());
        assertSevere("guard boom");
        assertTrue(menuOpen());
    }

    @Test
    void routeGuardExceptionsRefuseOpensAndActions() {
        controller.routeGuardThrows = true;
        service.open(VIEWER, TestController.main());

        assertEquals(List.of("notice:HUD"), host.chest.events);
        assertEquals(new FeedbackText().unavailable(), host.chest.lastNotice());
        assertSevere("route guard boom");

        controller.routeGuardThrows = false;
        service.open(VIEWER, TestController.main());
        controller.routeGuardThrows = true;
        chest("increment", MenuInteraction.PRIMARY);

        assertTrue(controller.handled.isEmpty());
        assertEquals(new FeedbackText().unavailable(), host.chest.lastNotice());
        assertEquals(2, logs.severe().size());
    }

    @Test
    void confirmationFactoryExceptionsAreLoggedAndAnswered() {
        service.open(VIEWER, TestController.main());

        chest("confirm-explode", MenuInteraction.PRIMARY);

        assertTrue(controller.handled.isEmpty());
        assertEquals(List.of("open:test.main@1", "update:test.main@2", "notice:HUD"), host.chest.events);
        assertEquals(new FeedbackText().unavailable(), host.chest.lastNotice());
        assertSevere("confirm boom");
        chest("increment", MenuInteraction.PRIMARY);
        assertEquals(List.of("increment"), controller.handled);
    }

    @Test
    void missingResultsAreFailuresInsteadOfCrashes() {
        service.open(VIEWER, TestController.main());

        chest("null-result", MenuInteraction.PRIMARY);
        assertEquals(new FeedbackText().unavailable(), host.chest.lastNotice());
        assertEquals(1, logs.severe().size());

        chest("slow", MenuInteraction.PRIMARY);
        controller.slow.complete(null);
        assertEquals(new FeedbackText().unavailable(), host.chest.lastNotice());
        assertEquals(2, logs.severe().size());
        chest("increment", MenuInteraction.PRIMARY);
        assertEquals(List.of("increment"), controller.handled);
    }

    @Test
    void nativeClosesDeliverTheirNoticeThroughTheChestPath() throws MenuProtocolException {
        openNative();

        nativeAction(revision(), "farewell", MenuInteraction.PRIMARY, null);

        assertInstanceOf(MenuServerMessage.Close.class, host.lastMessage());
        assertEquals("Bye", host.chest.lastNotice());
        assertEquals(NoticeChannel.CHAT, host.chest.notices.get(0).chestChannel());
        assertFalse(menuOpen());

        openNative();
        controller.accessAllowed = false;
        nativeAction(revision(), "increment", MenuInteraction.PRIMARY, null);
        assertInstanceOf(MenuServerMessage.Close.class, host.lastMessage());
        assertEquals("no access", host.chest.lastNotice());
    }

    @Test
    void chestSessionsIgnoreNativeFrames() {
        receive(new MenuClientMessage.Hello(1));
        host.nativeEnabled = false;
        service.open(VIEWER, TestController.main());

        receive(new MenuClientMessage.Back(1L));
        receive(new MenuClientMessage.Page(1L, 1));
        receive(new MenuClientMessage.Confirm(1L, true));
        receive(new MenuClientMessage.Closed(1L));
        nativeAction(1L, "increment", MenuInteraction.PRIMARY, null);

        assertEquals(List.of("open:test.main@1"), host.chest.events);
        assertTrue(controller.handled.isEmpty());
        assertTrue(menuOpen());
    }

    @Test
    void skippedRefreshesWaitForTheNextInterval() {
        controller.refreshInterval = 2;
        service.open(VIEWER, TestController.main());
        chest("slow", MenuInteraction.PRIMARY);
        int scheduled = host.scheduled;

        for (int tick = 0; tick < 10; tick++) {
            service.tick();
        }

        assertEquals(5, host.scheduled - scheduled);
    }

    @Test
    void rejectedRefreshesCountOnceAndWarn() {
        controller.refreshInterval = 2;
        service.open(VIEWER, TestController.main());
        long before = WormholesTelemetry.failureBreakdown().getOrDefault("PORTAL_MENU_REFRESH_SCHEDULE_REJECTED", 0L);
        host.rejectTasks = true;

        for (int tick = 0; tick < 6; tick++) {
            service.tick();
        }

        assertEquals(1L, WormholesTelemetry.failureBreakdown().getOrDefault("PORTAL_MENU_REFRESH_SCHEDULE_REJECTED", 0L) - before);
        assertEquals(1, host.warnings.size());
        assertTrue(host.warnings.get(0).contains(VIEWER.toString()));
    }

    @Test
    void chestEscapeWhilePendingDiscardsTheLateResult() {
        service.open(VIEWER, TestController.main());
        chest("child", MenuInteraction.PRIMARY);
        chest("slow", MenuInteraction.PRIMARY);

        service.chestEscape(VIEWER);
        chest("increment", MenuInteraction.PRIMARY);
        controller.slow.complete(MenuResult.stay(MenuNotices.text("Done", MenuFeedbackStyle.SUCCESS, NoticeChannel.CHAT)));

        assertEquals(List.of("increment"), controller.handled);
        assertEquals(TestController.MAIN, host.chest.screen().screenId());
        assertTrue(host.chest.notices.stream().noneMatch(notice -> notice.feedback().text().equals("Done")));
    }

    @Test
    void visibilityLimitsWhichOriginMayReachAnElement() throws MenuProtocolException {
        openNative();
        nativeAction(revision(), "hidden", MenuInteraction.PRIMARY, null);
        nativeAction(revision(), "native-only", MenuInteraction.PRIMARY, null);
        assertEquals(List.of("native-only"), controller.handled);

        service.quit(VIEWER);
        service.open(VIEWER, TestController.main());
        chest("native-only", MenuInteraction.PRIMARY);
        chest("hidden", MenuInteraction.PRIMARY);
        assertEquals(List.of("native-only", "hidden"), controller.handled);
    }

    @Test
    void disabledElementsAnswerUnavailable() {
        service.open(VIEWER, TestController.main());

        chest("disabled", MenuInteraction.PRIMARY);

        assertTrue(controller.handled.isEmpty());
        assertEquals(new FeedbackText().unavailable(), host.chest.lastNotice());
    }

    @Test
    void itemCapturesReceiveTheHeldOrDroppedItem() throws MenuProtocolException {
        CapturedItem held = new TestItem("minecraft:diamond", "Diamond");
        CapturedItem dropped = new TestItem("minecraft:emerald", "Emerald");
        host.heldItem = held;
        openNative();
        nativeAction(revision(), "held", MenuInteraction.PRIMARY, null);
        assertEquals(held, controller.captured);

        service.quit(VIEWER);
        service.open(VIEWER, TestController.main());
        service.chestAction(VIEWER, new MenuChestAction("held", MenuInteraction.PRIMARY, null, false, dropped));
        assertEquals(dropped, controller.captured);
    }

    @Test
    void resumingASuspendedChestSessionEndsItWithoutReopening() {
        service.open(VIEWER, TestController.main());

        chest("suspend", MenuInteraction.PRIMARY);
        assertEquals(List.of("open:test.main@1", "close"), host.chest.events);
        assertTrue(menuOpen());

        service.resume(VIEWER);
        assertEquals(List.of("open:test.main@1", "close"), host.chest.events);
        assertFalse(menuOpen());
    }

    @Test
    void resumingASuspendedNativeSessionReopensTheSuspendedScreen() throws MenuProtocolException {
        openNative();
        long suspendedRevision = revision();

        nativeAction(suspendedRevision, "suspend", MenuInteraction.PRIMARY, null);
        assertInstanceOf(MenuServerMessage.Close.class, host.lastMessage());

        service.resume(VIEWER);
        MenuServerMessage.Open reopened = assertInstanceOf(MenuServerMessage.Open.class, host.lastMessage());
        assertEquals("test.main", reopened.screen().screenId());
        assertTrue(reopened.screen().revision() > suspendedRevision);
        assertTrue(host.chest.events.isEmpty());
    }

    @Test
    void pageResultsSetThePage() {
        service.open(VIEWER, TestController.main());

        chest("next-page", MenuInteraction.PRIMARY);

        assertEquals(1, host.chest.screen().paging().page());
        assertEquals("Page 2 / 3", host.chest.screen().chrome().pageLabel());
    }

    @Test
    void enteringARouteStartsOnTheFirstPageAndTellsTheController() {
        service.open(VIEWER, TestController.main());
        chest("next-page", MenuInteraction.PRIMARY);
        chest("child", MenuInteraction.PRIMARY);
        assertEquals(0, host.chest.screen().paging().page());
        chest("next-page", MenuInteraction.PRIMARY);
        assertEquals(1, host.chest.screen().paging().page());

        service.chestEscape(VIEWER);
        assertEquals(TestController.MAIN, host.chest.screen().screenId());
        assertEquals(1, host.chest.screen().paging().page());

        chest("child", MenuInteraction.PRIMARY);

        assertEquals(TestController.CHILD, host.chest.screen().screenId());
        assertEquals(0, host.chest.screen().paging().page());
        assertEquals(List.of("test.main@0", "test.child@0", "test.child@0"), controller.entered);
    }

    @Test
    void redirectsEnterTheirTargetWhenItBecomesANewStackEntry() {
        controller.childRedirects = true;
        service.open(VIEWER, TestController.child());

        assertEquals(List.of("test.main@0"), controller.entered);

        controller.childRedirects = false;
        service.open(VIEWER, TestController.child());
        controller.childRedirects = true;
        chest("increment", MenuInteraction.PRIMARY);

        assertEquals(TestController.MAIN, host.chest.screen().screenId());
        assertFalse(host.chest.screen().canGoBack());
        assertEquals(List.of("test.main@0", "test.child@0", "test.main@0"), controller.entered);
    }

    @Test
    void enterExceptionsCloseLikeBuildFailures() {
        controller.enterThrows = true;

        service.open(VIEWER, TestController.main());

        assertEquals(List.of("close", "notice:HUD"), host.chest.events);
        assertEquals(new FeedbackText().unavailable(), host.chest.lastNotice());
        assertSevere("enter boom");
        assertFalse(menuOpen());
    }

    @Test
    void closeFollowUpsRunAfterTheMenuEnds() throws MenuProtocolException {
        List<String> seen = new ArrayList<>();
        controller.handoff = () -> {
            seen.add(host.chest.lastEvent() + " open=" + menuOpen());
            return MenuNotices.text("Handed off", MenuFeedbackStyle.INFO, NoticeChannel.CHAT);
        };
        service.open(VIEWER, TestController.main());

        chest("handoff", MenuInteraction.PRIMARY);

        assertEquals(List.of("close open=false"), seen);
        assertEquals(List.of("open:test.main@1", "close", "notice:CHAT"), host.chest.events);
        assertEquals("Handed off", host.chest.lastNotice());

        controller.handoff = () -> {
            seen.add(lastMessageName());
            return null;
        };
        openNative();
        nativeAction(revision(), "handoff", MenuInteraction.PRIMARY, null);
        assertEquals(List.of("close open=false", "Close"), seen);
        assertInstanceOf(MenuServerMessage.Close.class, host.lastMessage());
        assertFalse(menuOpen());
    }

    @Test
    void closeFollowUpExceptionsAreLoggedAndAnswered() {
        controller.handoff = () -> {
            throw new IllegalStateException("handoff boom");
        };
        service.open(VIEWER, TestController.main());

        chest("handoff", MenuInteraction.PRIMARY);

        assertEquals(List.of("open:test.main@1", "close", "notice:HUD"), host.chest.events);
        assertEquals(new FeedbackText().unavailable(), host.chest.lastNotice());
        assertSevere("handoff boom");
    }

    @Test
    void chestPrecheckRunsTheGuardChainWithoutHandling() {
        service.open(VIEWER, TestController.main());

        assertTrue(service.chestPrecheck(VIEWER, "name", MenuInteraction.SET_TEXT));
        controller.guardAllows = false;
        assertFalse(service.chestPrecheck(VIEWER, "guarded", MenuInteraction.PRIMARY));
        assertFalse(service.chestPrecheck(VIEWER, "native-only", MenuInteraction.PRIMARY));
        assertTrue(controller.handled.isEmpty());
    }

    @Test
    void noticesAfterARebuildCloseReachTheChestPath() throws MenuProtocolException {
        openNative();

        nativeAction(revision(), "vanish", MenuInteraction.PRIMARY, null);

        assertInstanceOf(MenuServerMessage.Close.class, host.lastMessage());
        assertFalse(menuOpen());
        assertEquals(List.of("notice:HUD", "notice:SUCCESS_EFFECT"), host.chest.events);
        assertEquals("Removed", host.chest.lastNotice());
        assertSevere("build boom");
    }

    @Test
    void refreshIntervalExceptionsCloseLikeBuildFailures() {
        controller.refreshIntervalThrows = true;

        service.open(VIEWER, TestController.main());

        assertEquals(List.of("close", "notice:HUD"), host.chest.events);
        assertEquals(new FeedbackText().unavailable(), host.chest.lastNotice());
        assertSevere("interval boom");
        assertFalse(menuOpen());
    }

    @Test
    void invalidationSurvivesAThrowingTouchCheck() {
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000003");
        host.viewer(other, "Other");
        service.open(VIEWER, TestController.main());
        service.open(other, TestController.child());
        controller.childTouchesThrow = true;
        controller.counter = 3;

        service.invalidate(new MenuScope.Portal(TestController.TOUCHED));

        assertTrue(host.chest.events.contains("update:test.main@2"));
        assertFalse(host.chest.events.contains("update:test.child@2"));
        assertSevere("touches boom");
    }

    private void openNative() throws MenuProtocolException {
        receive(new MenuClientMessage.Hello(1));
        service.open(VIEWER, TestController.main());
        assertInstanceOf(MenuServerMessage.Open.class, host.lastMessage());
    }

    private long revision() throws MenuProtocolException {
        return host.screenRevision();
    }

    private String lastMessageName() {
        try {
            return host.lastMessage().getClass().getSimpleName();
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean menuOpen() {
        return service.chestPrecheck(VIEWER, "increment", MenuInteraction.PRIMARY);
    }

    private void assertSevere(String message) {
        List<LogRecord> severe = logs.severe();
        assertEquals(1, severe.size());
        assertEquals(message, severe.get(0).getThrown().getMessage());
    }

    private void chest(String elementId, MenuInteraction interaction) {
        service.chestAction(VIEWER, new MenuChestAction(elementId, interaction, null, false, null));
    }

    private void nativeAction(long revision, String elementId, MenuInteraction interaction, MenuValue value) {
        receive(new MenuClientMessage.Action(new MenuAction(revision, elementId, interaction, value)));
    }

    private void receive(MenuClientMessage message) {
        try {
            service.receive(VIEWER, MenuCodec.encodeClient(message));
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record TestItem(String itemId, String label) implements CapturedItem {
    }

    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        List<LogRecord> severe() {
            List<LogRecord> severe = new ArrayList<>();
            for (LogRecord record : records) {
                if (record.getLevel() == Level.SEVERE) {
                    severe.add(record);
                }
            }
            return severe;
        }
    }

    private static final class FeedbackText {
        private final FakeStrings strings = new FakeStrings();

        String refreshed() {
            return strings.text(MenuMessages.FEEDBACK_REFRESHED);
        }

        String invalidValue() {
            return strings.text(MenuMessages.FEEDBACK_INVALID_VALUE);
        }

        String unavailable() {
            return strings.text(MenuMessages.FEEDBACK_UNAVAILABLE);
        }

        String timeout() {
            return strings.text(MenuMessages.FEEDBACK_TIMEOUT);
        }
    }
}

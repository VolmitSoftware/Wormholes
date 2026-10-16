package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuFeedback;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocol;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import org.junit.Test;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class ClientMenuSessionTest {
    private static final byte[] MALFORMED = {(byte) 0xEE, 1, 2};
    private static final int THROTTLE_FRAMES = 20;

    private final List<byte[]> sent = new ArrayList<>();
    private final Logger logger = mock(Logger.class);
    private final RecordingListener listener = new RecordingListener();
    private long now = 10_000L;
    private final ClientMenuSession session = new ClientMenuSession(sent::add, () -> now, logger);

    @Test
    public void helloIsSentOnceWhenTheChannelIsReady() throws MenuProtocolException {
        session.connected();
        session.tick(() -> false);
        assertTrue(sent.isEmpty());

        session.tick(() -> true);
        session.tick(() -> true);

        assertEquals(List.of(new MenuClientMessage.Hello(1)), decoded());
    }

    @Test
    public void nothingIsSentBeforeConnecting() {
        session.tick(() -> true);

        assertTrue(sent.isEmpty());
    }

    @Test
    public void helloWaitsForALateChannelRegistration() throws MenuProtocolException {
        session.connected();
        for (int tick = 0; tick < 40; tick++) {
            now += 50L;
            session.tick(() -> false);
        }
        assertTrue(sent.isEmpty());

        session.tick(() -> true);
        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Accept(1)));

        assertEquals(List.of(new MenuClientMessage.Hello(1)), decoded());
        assertEquals(1, session.version());
    }

    @Test
    public void helloSurvivesAFullThrottleWindow() throws MenuProtocolException {
        accepted();
        open(3L);
        fillThrottle();
        sent.clear();

        session.connected();
        session.tick(() -> true);
        now += 1000L;
        session.tick(() -> true);

        assertEquals(List.of(new MenuClientMessage.Hello(1)), decoded());
    }

    @Test
    public void reconnectingResetsTheThrottle() throws MenuProtocolException {
        accepted();
        open(3L);
        fillThrottle();
        sent.clear();

        session.disconnected();
        session.connected();
        session.tick(() -> true);

        assertEquals(List.of(new MenuClientMessage.Hello(1)), decoded());
    }

    @Test
    public void acceptStoresTheNegotiatedVersion() throws MenuProtocolException {
        session.connected();
        assertEquals(0, session.version());

        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Accept(1)));

        assertEquals(1, session.version());
    }

    @Test
    public void acceptAboveTheProtocolVersionIsAProtocolFailure() throws MenuProtocolException {
        session.listener(listener);
        session.connected();
        session.tick(() -> true);

        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Accept(MenuProtocol.VERSION + 1)));
        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Open(screen("portal.home", 3L))));

        verify(logger, times(1)).warn(anyString(), any(Throwable.class));
        assertEquals(0, session.version());
        assertTrue(session.screen().isEmpty());
        assertTrue(listener.events.isEmpty());
    }

    @Test
    public void openStoresTheScreenAndNotifiesTheListener() throws MenuProtocolException {
        accepted();
        MenuScreen home = screen("portal.home", 3L);

        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Open(home)));

        assertEquals(home, session.screen().orElseThrow());
        assertEquals(List.of("opened:portal.home:3"), listener.events);
    }

    @Test
    public void updateReplacesTheOpenScreen() throws MenuProtocolException {
        accepted();
        open(3L);
        MenuScreen updated = screen("portal.home", 4L);

        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Update(updated)));

        assertEquals(updated, session.screen().orElseThrow());
        assertEquals(List.of("opened:portal.home:3", "updated:portal.home:4"), listener.events);
    }

    @Test
    public void updateWithoutAnOpenScreenIsIgnored() throws MenuProtocolException {
        accepted();

        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Update(screen("portal.home", 4L))));

        assertTrue(session.screen().isEmpty());
        assertTrue(listener.events.isEmpty());
    }

    @Test
    public void closeClearsTheScreenAndNotifiesTheListener() throws MenuProtocolException {
        accepted();
        open(3L);

        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Close()));

        assertTrue(session.screen().isEmpty());
        assertEquals(List.of("opened:portal.home:3", "closed"), listener.events);
    }

    @Test
    public void feedbackIsForwardedToTheListener() throws MenuProtocolException {
        accepted();

        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Feedback(MenuFeedback.success("Saved"))));

        assertEquals(List.of("feedback:SUCCESS:Saved"), listener.events);
    }

    @Test
    public void withoutAListenerTheSessionStillKeepsState() throws MenuProtocolException {
        session.connected();
        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Accept(1)));
        MenuScreen home = screen("portal.home", 3L);

        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Open(home)));
        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Feedback(MenuFeedback.info("Hi"))));

        assertEquals(home, session.screen().orElseThrow());
    }

    @Test
    public void malformedFrameIsLoggedOnceAndEndsNativeMenusForTheConnection() throws MenuProtocolException {
        accepted();
        open(3L);

        session.receive(MALFORMED);
        session.receive(MALFORMED);
        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Open(screen("portal.mode", 5L))));

        verify(logger, times(1)).warn(anyString(), any(Throwable.class));
        assertTrue(session.screen().isEmpty());
        assertEquals(List.of("opened:portal.home:3", "closed"), listener.events);
        assertFalse(session.back());
    }

    @Test
    public void decodeFailureWithdrawsNativeMenusFromTheServer() throws MenuProtocolException {
        accepted();
        open(3L);
        sent.clear();

        session.receive(MALFORMED);
        session.tick(() -> true);
        session.receive(MALFORMED);
        session.tick(() -> true);

        assertEquals(List.of(new MenuClientMessage.Hello(0)), decoded());
    }

    @Test
    public void decodeFailureBeforeHelloSendsNothing() {
        session.connected();

        session.receive(MALFORMED);
        session.tick(() -> true);

        assertTrue(sent.isEmpty());
    }

    @Test
    public void withdrawalIsRetriedAfterAFullThrottleWindow() throws MenuProtocolException {
        accepted();
        open(3L);
        fillThrottle();
        sent.clear();

        session.receive(MALFORMED);
        session.tick(() -> true);
        assertTrue(sent.isEmpty());
        now += 1000L;
        session.tick(() -> true);
        session.tick(() -> true);

        assertEquals(List.of(new MenuClientMessage.Hello(0)), decoded());
    }

    @Test
    public void reconnectingAfterAFailureStartsAFreshSession() throws MenuProtocolException {
        accepted();
        session.receive(MALFORMED);
        sent.clear();

        session.connected();
        session.tick(() -> true);
        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Accept(1)));
        open(1L);

        assertEquals(1, session.version());
        assertTrue(session.back());
        assertEquals(List.of(new MenuClientMessage.Hello(1), new MenuClientMessage.Back(1L)), decoded());
    }

    @Test
    public void framesAreRefusedBeforeAccept() throws MenuProtocolException {
        session.connected();
        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Open(screen("portal.home", 3L))));

        assertFalse(session.back());
        assertTrue(sent.isEmpty());
    }

    @Test
    public void actionsAreRefusedWithoutAnOpenScreen() throws MenuProtocolException {
        accepted();
        sent.clear();

        assertFalse(session.act("projection", MenuInteraction.PRIMARY, null));
        assertFalse(session.back());
        assertFalse(session.page(1));
        assertFalse(session.confirm(true));
        assertFalse(session.closed());
        assertTrue(sent.isEmpty());
    }

    @Test
    public void actionsCarryTheCurrentScreenRevision() throws MenuProtocolException {
        accepted();
        open(3L);
        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Update(screen("portal.home", 4L))));
        sent.clear();

        assertTrue(session.act("activation-range", MenuInteraction.SET_VALUE, MenuValue.number(24.0D)));
        assertTrue(session.back());
        assertTrue(session.page(2));
        assertTrue(session.confirm(true));
        assertTrue(session.closed());

        assertEquals(List.of(
            new MenuClientMessage.Action(new MenuAction(4L, "activation-range", MenuInteraction.SET_VALUE, MenuValue.number(24.0D))),
            new MenuClientMessage.Back(4L),
            new MenuClientMessage.Page(4L, 2),
            new MenuClientMessage.Confirm(4L, true),
            new MenuClientMessage.Closed(4L)), decoded());
    }

    @Test
    public void actingMarksTheSessionPendingUntilTheServerAnswers() throws MenuProtocolException {
        accepted();
        open(3L);

        assertTrue(session.act("projection", MenuInteraction.PRIMARY, null));

        assertEquals(new MenuClientMessage.Action(new MenuAction(3L, "projection", MenuInteraction.PRIMARY, null)),
            MenuCodec.decodeClient(sent.getLast()));
        assertTrue(session.pending());
        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Update(screen("portal.home", 4L))));
        assertFalse(session.pending());
    }

    @Test
    public void outboundFramesAreThrottledToTwentyPerSecond() throws MenuProtocolException {
        accepted();
        open(3L);
        now += 1000L;
        int before = sent.size();

        for (int index = 0; index < THROTTLE_FRAMES; index++) {
            assertTrue(session.back());
        }
        assertFalse(session.back());
        now += 1000L;
        assertTrue(session.back());

        assertEquals(before + THROTTLE_FRAMES + 1, sent.size());
    }

    @Test
    public void closingFromTheClientClearsTheScreenWithoutNotifying() throws MenuProtocolException {
        accepted();
        open(3L);

        assertTrue(session.closed());

        assertTrue(session.screen().isEmpty());
        assertEquals(List.of("opened:portal.home:3"), listener.events);
    }

    @Test
    public void disconnectingClearsTheSession() throws MenuProtocolException {
        accepted();
        open(3L);
        sent.clear();

        session.disconnected();
        session.tick(() -> true);

        assertEquals(0, session.version());
        assertTrue(session.screen().isEmpty());
        assertTrue(sent.isEmpty());
        assertFalse(session.back());
    }

    @Test
    public void disconnectingWithAnOpenScreenNotifiesTheListener() throws MenuProtocolException {
        accepted();
        open(3L);

        session.disconnected();
        session.disconnected();

        assertEquals(List.of("opened:portal.home:3", "closed"), listener.events);
    }

    @Test
    public void reconnectingWithAnOpenScreenNotifiesTheListener() throws MenuProtocolException {
        accepted();
        open(3L);

        session.connected();

        assertEquals(List.of("opened:portal.home:3", "closed"), listener.events);
    }

    private void accepted() throws MenuProtocolException {
        session.listener(listener);
        session.connected();
        session.tick(() -> true);
        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Accept(1)));
    }

    private void open(long revision) throws MenuProtocolException {
        session.receive(MenuCodec.encodeServer(new MenuServerMessage.Open(screen("portal.home", revision))));
    }

    private void fillThrottle() {
        now += 1000L;
        for (int index = 0; index < THROTTLE_FRAMES; index++) {
            assertTrue(session.back());
        }
        assertFalse(session.back());
    }

    private List<MenuClientMessage> decoded() throws MenuProtocolException {
        List<MenuClientMessage> messages = new ArrayList<>(sent.size());
        for (byte[] frame : sent) {
            messages.add(MenuCodec.decodeClient(frame));
        }
        return messages;
    }

    private static MenuScreen screen(String screenId, long revision) {
        return MenuScreen.builder(screenId).title("Portal").context("Gate").revision(revision).build();
    }

    private static final class RecordingListener implements ClientMenuListener {
        private final List<String> events = new ArrayList<>();

        @Override
        public void opened(MenuScreen screen) {
            events.add("opened:" + screen.screenId() + ":" + screen.revision());
        }

        @Override
        public void updated(MenuScreen screen) {
            events.add("updated:" + screen.screenId() + ":" + screen.revision());
        }

        @Override
        public void closed() {
            events.add("closed");
        }

        @Override
        public void feedback(MenuFeedback feedback) {
            events.add("feedback:" + feedback.style() + ":" + feedback.text());
        }
    }
}

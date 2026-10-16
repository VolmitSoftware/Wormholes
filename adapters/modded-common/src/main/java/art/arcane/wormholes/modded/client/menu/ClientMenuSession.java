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
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class ClientMenuSession {
    private static final int MAX_FRAMES_PER_SECOND = 20;
    private static final long SECOND_MILLIS = 1000L;
    private static final int WITHDRAWN_VERSION = 0;

    private final Consumer<byte[]> sender;
    private final LongSupplier clock;
    private final Logger logger;
    private final long[] sentTimes = new long[MAX_FRAMES_PER_SECOND];
    private int sentCursor;
    private ClientMenuListener listener;
    private boolean helloDue;
    private boolean helloSent;
    private boolean withdrawalDue;
    private boolean failed;
    private boolean pending;
    private int version;
    private MenuScreen screen;

    public ClientMenuSession(Consumer<byte[]> sender, LongSupplier clock, Logger logger) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        resetThrottle();
    }

    public synchronized void listener(ClientMenuListener next) {
        listener = next;
    }

    public synchronized void connected() {
        clear();
        helloDue = true;
    }

    public synchronized void disconnected() {
        clear();
    }

    public synchronized void tick(BooleanSupplier channelReady) {
        if ((!helloDue && !withdrawalDue) || !channelReady.getAsBoolean()) {
            return;
        }
        if (withdrawalDue) {
            withdrawalDue = !transmit(new MenuClientMessage.Hello(WITHDRAWN_VERSION));
            return;
        }
        if (transmit(new MenuClientMessage.Hello(MenuProtocol.VERSION))) {
            helloDue = false;
            helloSent = true;
        }
    }

    public synchronized void receive(byte[] frame) {
        if (failed) {
            return;
        }
        MenuServerMessage message;
        try {
            message = MenuCodec.decodeServer(frame);
        } catch (MenuProtocolException exception) {
            fail(exception);
            return;
        }
        switch (message) {
            case MenuServerMessage.Accept accept -> accepted(accept.version());
            case MenuServerMessage.Open open -> opened(open.screen());
            case MenuServerMessage.Update update -> updated(update.screen());
            case MenuServerMessage.Close ignored -> closedByServer();
            case MenuServerMessage.Feedback feedback -> feedback(feedback.feedback());
        }
    }

    public synchronized boolean act(String elementId, MenuInteraction interaction, MenuValue value) {
        if (screen == null) {
            return false;
        }
        return send(new MenuClientMessage.Action(new MenuAction(screen.revision(), elementId, interaction, value)));
    }

    public synchronized boolean back() {
        return screen != null && send(new MenuClientMessage.Back(screen.revision()));
    }

    public synchronized boolean page(int page) {
        return screen != null && send(new MenuClientMessage.Page(screen.revision(), page));
    }

    public synchronized boolean confirm(boolean accepted) {
        return screen != null && send(new MenuClientMessage.Confirm(screen.revision(), accepted));
    }

    public synchronized boolean closed() {
        return screen != null && send(new MenuClientMessage.Closed(screen.revision()));
    }

    public synchronized int version() {
        return version;
    }

    public synchronized Optional<MenuScreen> screen() {
        return Optional.ofNullable(screen);
    }

    public synchronized boolean pending() {
        return pending;
    }

    private boolean send(MenuClientMessage message) {
        if (version < 1 || !transmit(message)) {
            return false;
        }
        if (message instanceof MenuClientMessage.Closed) {
            screen = null;
            pending = false;
        } else {
            pending = true;
        }
        return true;
    }

    private boolean transmit(MenuClientMessage message) {
        long now = clock.getAsLong();
        long oldest = sentTimes[sentCursor];
        if (oldest != Long.MIN_VALUE && now - oldest < SECOND_MILLIS) {
            return false;
        }
        byte[] frame;
        try {
            frame = MenuCodec.encodeClient(message);
        } catch (MenuProtocolException exception) {
            logger.warn("Native menu message {} could not be encoded", message.getClass().getSimpleName(), exception);
            return false;
        }
        sentTimes[sentCursor] = now;
        sentCursor = (sentCursor + 1) % sentTimes.length;
        sender.accept(frame);
        return true;
    }

    private void accepted(int negotiated) {
        if (negotiated > MenuProtocol.VERSION) {
            fail(new MenuProtocolException("server accepted menu protocol version " + negotiated + " above client version " + MenuProtocol.VERSION));
            return;
        }
        version = negotiated;
    }

    private void opened(MenuScreen next) {
        screen = next;
        pending = false;
        if (listener != null) {
            listener.opened(next);
        }
    }

    private void updated(MenuScreen next) {
        if (screen == null) {
            return;
        }
        screen = next;
        pending = false;
        if (listener != null) {
            listener.updated(next);
        }
    }

    private void closedByServer() {
        pending = false;
        closeScreen();
    }

    private void feedback(MenuFeedback feedback) {
        pending = false;
        if (listener != null) {
            listener.feedback(feedback);
        }
    }

    private void fail(MenuProtocolException exception) {
        logger.warn("Native menu protocol failed; native menus stay off until the next connection", exception);
        failed = true;
        helloDue = false;
        pending = false;
        version = 0;
        withdrawalDue = helloSent && !transmit(new MenuClientMessage.Hello(WITHDRAWN_VERSION));
        closeScreen();
    }

    private void closeScreen() {
        if (screen == null) {
            return;
        }
        screen = null;
        if (listener != null) {
            listener.closed();
        }
    }

    private void clear() {
        helloDue = false;
        helloSent = false;
        withdrawalDue = false;
        failed = false;
        pending = false;
        version = 0;
        resetThrottle();
        closeScreen();
    }

    private void resetThrottle() {
        Arrays.fill(sentTimes, Long.MIN_VALUE);
        sentCursor = 0;
    }
}

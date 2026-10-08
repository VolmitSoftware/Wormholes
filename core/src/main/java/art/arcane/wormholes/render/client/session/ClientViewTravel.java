package art.arcane.wormholes.render.client.session;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import art.arcane.optics.stream.ViewStreamHooks;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamSession;
import art.arcane.wormholes.network.client.ClientViewExtensions;
import art.arcane.wormholes.network.client.TravelExtension;
import art.arcane.wormholes.network.client.TravelMessage;

public final class ClientViewTravel<P> implements ViewStreamHooks<P> {
    private static final int MAX_PENDING_ACKS = 64;
    private static final int MAX_PENDING_CROSSES = 16;

    private final ViewStreamSession<P, ?> session;
    private final ArrayDeque<TravelMessage.RemoteViewAck> acks = new ArrayDeque<>();
    private final ArrayDeque<TravelMessage.TravelCross> crosses = new ArrayDeque<>();
    private final ArrayDeque<TravelMessage.RemoteLevelReopen> reopens = new ArrayDeque<>();

    public ClientViewTravel(ViewStreamSession<P, ?> session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public static <P> ClientViewTravel<P> of(ViewStreamSession<P, ?> session) {
        if (session.hooks() instanceof ClientViewTravel<P> travel) {
            return travel;
        }
        throw new IllegalStateException("ClientView session for " + session.playerId() + " has no travel hooks");
    }

    public P player() {
        return session.player();
    }

    public UUID playerId() {
        return session.playerId();
    }

    public boolean remoteViewSelected() {
        return session.nativeRendererSelected() && (session.caps() & ClientViewExtensions.REMOTE_VIEW) != 0L;
    }

    public boolean seamlessSelected() {
        return remoteViewSelected() && (session.caps() & ClientViewExtensions.SEAMLESS_TRAVEL) != 0L;
    }

    public void drainAcks(Consumer<TravelMessage.RemoteViewAck> consumer) {
        while (true) {
            TravelMessage.RemoteViewAck ack;
            synchronized (acks) {
                ack = acks.pollFirst();
            }
            if (ack == null) {
                return;
            }
            consumer.accept(ack);
        }
    }

    public void drainReopens(Consumer<TravelMessage.RemoteLevelReopen> consumer) {
        while (true) {
            TravelMessage.RemoteLevelReopen reopen;
            synchronized (reopens) {
                reopen = reopens.pollFirst();
            }
            if (reopen == null) {
                return;
            }
            consumer.accept(reopen);
        }
    }

    public TravelMessage.TravelCross takeSeamlessCross() {
        synchronized (crosses) {
            return crosses.pollFirst();
        }
    }

    public boolean sendTravel(TravelMessage message) {
        return sendable(message) && session.send(TravelExtension.INSTANCE.wrap(message));
    }

    @Override
    public boolean onExtension(P peer, Object payload) {
        return switch (payload) {
            case TravelMessage.TravelCross cross -> seamlessSelected() && queue(cross);
            case TravelMessage.RemoteViewAck ack -> remoteViewSelected() && queue(ack);
            case TravelMessage.RemoteLevelReopen reopen -> remoteViewSelected() && queue(reopen);
            default -> false;
        };
    }

    @Override
    public void tickExtension(P peer, long nowMillis, Predicate<ViewStreamMessage> sender) {
    }

    @Override
    public void onReset(P peer) {
    }

    @Override
    public void onClose(P peer) {
        synchronized (acks) {
            acks.clear();
        }
        synchronized (crosses) {
            crosses.clear();
        }
        synchronized (reopens) {
            reopens.clear();
        }
    }

    private boolean sendable(TravelMessage message) {
        return switch (message) {
            case TravelMessage.TravelBegin ignored -> seamlessSelected();
            case TravelMessage.TravelCancel ignored -> seamlessSelected();
            case TravelMessage.TravelAccept ignored -> seamlessSelected();
            case TravelMessage.EntityCrossed ignored -> seamlessSelected();
            case TravelMessage.RemoteLevelOpen ignored -> remoteViewSelected();
            case TravelMessage.RemoteLevelClose ignored -> remoteViewSelected();
            case TravelMessage.RoutedPacket ignored -> remoteViewSelected();
            default -> false;
        };
    }

    private boolean queue(TravelMessage.TravelCross cross) {
        synchronized (crosses) {
            if (crosses.size() >= MAX_PENDING_CROSSES) {
                return false;
            }
            crosses.addLast(cross);
            return true;
        }
    }

    private boolean queue(TravelMessage.RemoteLevelReopen reopen) {
        synchronized (reopens) {
            if (reopens.size() >= MAX_PENDING_ACKS) {
                return false;
            }
            reopens.addLast(reopen);
            return true;
        }
    }

    private boolean queue(TravelMessage.RemoteViewAck ack) {
        synchronized (acks) {
            if (acks.size() >= MAX_PENDING_ACKS) {
                acks.pollFirst();
            }
            acks.addLast(ack);
        }
        return true;
    }
}

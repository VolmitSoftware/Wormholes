package art.arcane.wormholes.render.client.session;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import art.arcane.optics.aperture.ApertureDescriptor;
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
    private final ClientPreparedTravelServer server;
    private final ArrayDeque<TravelMessage.RemoteViewAck> acks = new ArrayDeque<>();
    private final ArrayDeque<TravelMessage.TravelCross> crosses = new ArrayDeque<>();
    private final ArrayDeque<TravelMessage.RemoteLevelReopen> reopens = new ArrayDeque<>();

    public ClientViewTravel(ViewStreamSession<P, ?> session) {
        this.session = Objects.requireNonNull(session, "session");
        this.server = new ClientPreparedTravelServer();
    }

    public static <P> ClientViewTravel<P> of(ViewStreamSession<P, ?> session) {
        if (session.hooks() instanceof ClientViewTravel<P> travel) {
            return travel;
        }
        throw new IllegalStateException("ClientView session for " + session.playerId() + " has no travel hooks");
    }

    public ClientPreparedTravelServer server() {
        return server;
    }

    public P player() {
        return session.player();
    }

    public UUID playerId() {
        return session.playerId();
    }

    public boolean preparedTravelSelected() {
        return session.nativeRendererSelected() && (session.caps() & ClientViewExtensions.PREPARED_TRAVEL) != 0L;
    }

    public boolean preparedTravelCacheSelected() {
        return preparedTravelSelected() && (session.caps() & ClientViewExtensions.PREPARED_TRAVEL_CACHE) != 0L;
    }

    public boolean remoteViewSelected() {
        return preparedTravelSelected() && (session.caps() & ClientViewExtensions.REMOTE_VIEW) != 0L;
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

    public ApertureDescriptor travelGeometry(UUID portal) {
        return session.endpointGeometry(portal);
    }

    public boolean sendTravel(TravelMessage message) {
        if (!preparedTravelSelected() || !sendable(message)) {
            return false;
        }
        return session.send(TravelExtension.PREPARED.wrap(message));
    }

    public void cancelTravel() {
        server.cancel().ifPresent(this::sendTravel);
    }

    @Override
    public boolean onExtension(P peer, Object payload) {
        return switch (payload) {
            case TravelMessage.TravelCross cross -> seamlessSelected() ? queue(cross)
                : preparedTravelSelected() && server.requestCross(cross, System.currentTimeMillis());
            case TravelMessage.TravelCancel cancel -> preparedTravelSelected() && server.cancel(cancel);
            case TravelMessage.TravelCached cached -> preparedTravelCacheSelected() && server.cached(cached);
            case TravelMessage.TravelReady ready -> preparedTravelSelected() && server.ready(ready);
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
        cancelTravel();
    }

    @Override
    public void onClose(P peer) {
        cancelTravel();
        server.close();
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
            case TravelMessage.TravelBegin ignored -> true;
            case TravelMessage.TravelChunk ignored -> true;
            case TravelMessage.TravelEnd ignored -> true;
            case TravelMessage.TravelCommit ignored -> true;
            case TravelMessage.TravelCancel ignored -> true;
            case TravelMessage.TravelReuse ignored -> preparedTravelCacheSelected();
            case TravelMessage.RemoteLevelOpen ignored -> remoteViewSelected();
            case TravelMessage.RemoteLevelClose ignored -> remoteViewSelected();
            case TravelMessage.RoutedPacket ignored -> remoteViewSelected();
            case TravelMessage.TravelAccept ignored -> seamlessSelected();
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

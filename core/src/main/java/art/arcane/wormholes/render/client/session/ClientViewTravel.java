package art.arcane.wormholes.render.client.session;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamSession;
import art.arcane.wormholes.network.client.TravelExtension;
import art.arcane.wormholes.network.client.TravelMessage;

public final class ClientViewTravel<P> implements ViewStreamSession.Hooks<P> {
    private final ViewStreamSession<P, ?> session;
    private final ClientPreparedTravelServer server;

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
        return session.nativeRendererSelected() && ViewStreamCapability.PREPARED_TRAVEL.in(session.caps());
    }

    public boolean preparedTravelCacheSelected() {
        return preparedTravelSelected() && ViewStreamCapability.PREPARED_TRAVEL_CACHE.in(session.caps());
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
            case TravelMessage.TravelCross cross -> preparedTravelSelected() && server.requestCross(cross, System.currentTimeMillis());
            case TravelMessage.TravelCancel cancel -> preparedTravelSelected() && server.cancel(cancel);
            case TravelMessage.TravelCached cached -> preparedTravelCacheSelected() && server.cached(cached);
            case TravelMessage.TravelReady ready -> preparedTravelSelected() && server.ready(ready);
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
    }

    private boolean sendable(TravelMessage message) {
        return switch (message) {
            case TravelMessage.TravelBegin ignored -> true;
            case TravelMessage.TravelChunk ignored -> true;
            case TravelMessage.TravelEnd ignored -> true;
            case TravelMessage.TravelCommit ignored -> true;
            case TravelMessage.TravelCancel ignored -> true;
            case TravelMessage.TravelReuse ignored -> preparedTravelCacheSelected();
            default -> false;
        };
    }
}

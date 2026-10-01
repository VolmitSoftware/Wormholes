package art.arcane.wormholes.render.client.session;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.PlateStreamEncoder;
import art.arcane.wormholes.network.client.SessionPalette;

public final class ClientViewSessionRegistry<P, B> {
    private final ClientViewPlatform<P, B> platform;
    private final SessionPalette palette;
    private final PlateStreamEncoder<B> litEncoder;
    private final PlateStreamEncoder<B> unlitEncoder;
    private final long hashSalt;
    private final AtomicInteger sessionIds;
    private final ConcurrentHashMap<UUID, ClientViewServerSession<P, B>> sessions;
    private volatile ClientViewOptions options;
    private volatile boolean runtimeEnabled;

    public ClientViewSessionRegistry(ClientViewPlatform<P, B> platform, ClientViewOptions options) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.options = Objects.requireNonNull(options, "options");
        this.palette = new SessionPalette();
        this.litEncoder = new PlateStreamEncoder<B>(palette, platform.stateStrings());
        this.unlitEncoder = new PlateStreamEncoder<B>(palette, platform.stateStrings());
        this.hashSalt = new SecureRandom().nextLong();
        this.sessionIds = new AtomicInteger();
        this.sessions = new ConcurrentHashMap<UUID, ClientViewServerSession<P, B>>();
        this.runtimeEnabled = true;
    }

    public ClientViewServerSession<P, B> open(UUID playerId, P player, long zeroCopyNonce) {
        ClientViewServerSession<P, B> session = new ClientViewServerSession<P, B>(this, playerId, player, zeroCopyNonce);
        ClientViewServerSession<P, B> previous = sessions.put(playerId, session);
        if (previous != null) {
            previous.close();
        }
        return session;
    }

    public ClientViewServerSession<P, B> session(UUID playerId) {
        return sessions.get(playerId);
    }

    public boolean owns(UUID playerId, UUID portal) {
        ClientViewServerSession<P, B> session = sessions.get(playerId);
        return session != null && session.owns(portal);
    }

    public boolean effectsReceiver(UUID playerId) {
        ClientViewServerSession<P, B> session = sessions.get(playerId);
        return session != null && session.effectsReceiver();
    }

    public boolean oneShot(UUID playerId, ClientViewMessage.FxEmitter emitter) {
        ClientViewServerSession<P, B> session = sessions.get(playerId);
        return session != null && session.oneShot(emitter);
    }

    public void forget(UUID playerId) {
        ClientViewServerSession<P, B> removed = sessions.remove(playerId);
        if (removed != null) {
            removed.close();
        }
    }

    public Collection<ClientViewServerSession<P, B>> sessions() {
        return sessions.values();
    }

    public List<ClientViewSessionStats> stats() {
        List<ClientViewSessionStats> out = new ArrayList<ClientViewSessionStats>(sessions.size());
        for (ClientViewServerSession<P, B> session : sessions.values()) {
            out.add(session.stats());
        }
        return out;
    }

    public ClientViewOptions options() {
        return options;
    }

    public boolean configure(ClientViewOptions updated) {
        boolean was = enabled();
        options = Objects.requireNonNull(updated, "options");
        return settle(was);
    }

    public boolean enabled() {
        return runtimeEnabled && options.enabled();
    }

    public boolean runtimeEnabled() {
        return runtimeEnabled;
    }

    public boolean runtimeEnabled(boolean value) {
        boolean was = enabled();
        runtimeEnabled = value;
        return settle(was);
    }

    public void shutdown() {
        for (ClientViewServerSession<P, B> session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }

    public SessionPalette palette() {
        return palette;
    }

    public long hashSalt() {
        return hashSalt;
    }

    public long encodes() {
        return litEncoder.encodes() + unlitEncoder.encodes();
    }

    ClientViewPlatform<P, B> platform() {
        return platform;
    }

    int nextSessionId() {
        return sessionIds.incrementAndGet();
    }

    PlateStreamEncoder<B> encoderFor(BrickLightSource light) {
        return light == null || light == BrickLightSource.NONE ? unlitEncoder : litEncoder;
    }

    private boolean settle(boolean wasEnabled) {
        if (enabled()) {
            return !wasEnabled;
        }
        endAll(ClientViewMessage.ResetReason.DISABLED);
        return false;
    }

    private void endAll(ClientViewMessage.ResetReason reason) {
        for (ClientViewServerSession<P, B> session : sessions.values()) {
            session.end(reason);
        }
    }
}

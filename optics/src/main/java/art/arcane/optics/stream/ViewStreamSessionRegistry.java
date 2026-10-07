package art.arcane.optics.stream;

import java.security.SecureRandom;
import art.arcane.optics.entity.EntityAnimation;
import art.arcane.optics.internal.stream.PlateStreamEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class ViewStreamSessionRegistry<O, B> {
    private final ViewStreamPlatform<O, B> platform;
    private final ViewStreamCodec codec;
    private final SessionPalette palette;
    private final PlateStreamEncoder<B> litEncoder;
    private final PlateStreamEncoder<B> unlitEncoder;
    private final long hashSalt;
    private final AtomicInteger sessionIds;
    private final ConcurrentHashMap<UUID, ViewStreamSession<O, B>> sessions;
    private volatile ViewStreamOptions options;
    private volatile boolean runtimeEnabled;

    public ViewStreamSessionRegistry(ViewStreamPlatform<O, B> platform, ViewStreamOptions options) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.options = Objects.requireNonNull(options, "options");
        this.codec = new ViewStreamCodec(platform.extensions());
        this.palette = new SessionPalette();
        this.litEncoder = new PlateStreamEncoder<B>(palette, platform.stateStrings());
        this.unlitEncoder = new PlateStreamEncoder<B>(palette, platform.stateStrings());
        this.hashSalt = new SecureRandom().nextLong();
        this.sessionIds = new AtomicInteger();
        this.sessions = new ConcurrentHashMap<UUID, ViewStreamSession<O, B>>();
        this.runtimeEnabled = true;
    }

    public ViewStreamSession<O, B> open(UUID playerId, O player, long zeroCopyNonce) {
        ViewStreamSession<O, B> session = new ViewStreamSession<O, B>(this, playerId, player, zeroCopyNonce);
        ViewStreamSession<O, B> previous = sessions.put(playerId, session);
        if (previous != null) {
            previous.close();
        }
        return session;
    }

    public ViewStreamSession<O, B> session(UUID playerId) {
        return sessions.get(playerId);
    }

    public boolean owns(UUID playerId, UUID portal) {
        ViewStreamSession<O, B> session = sessions.get(playerId);
        return session != null && session.owns(portal);
    }

    public boolean effectsReceiver(UUID playerId) {
        ViewStreamSession<O, B> session = sessions.get(playerId);
        return session != null && session.effectsReceiver();
    }

    public boolean burst(UUID playerId, ViewStreamMessage.Extension message) {
        ViewStreamSession<O, B> session = sessions.get(playerId);
        return session != null && session.burst(message);
    }

    public void entityEvent(EntityAnimation event) {
        platform.entities().event(event);
    }

    public void forget(UUID playerId) {
        ViewStreamSession<O, B> removed = sessions.remove(playerId);
        if (removed != null) {
            removed.close();
        }
    }

    public Collection<ViewStreamSession<O, B>> sessions() {
        return sessions.values();
    }

    public List<ViewStreamSessionStats> stats() {
        List<ViewStreamSessionStats> out = new ArrayList<ViewStreamSessionStats>(sessions.size());
        for (ViewStreamSession<O, B> session : sessions.values()) {
            out.add(session.stats());
        }
        return out;
    }

    public ViewStreamOptions options() {
        return options;
    }

    public boolean configure(ViewStreamOptions updated) {
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
        for (ViewStreamSession<O, B> session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }

    public ViewStreamCodec codec() {
        return codec;
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

    ViewStreamPlatform<O, B> platform() {
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
        endAll(ViewStreamMessage.ResetReason.DISABLED);
        return false;
    }

    private void endAll(ViewStreamMessage.ResetReason reason) {
        for (ViewStreamSession<O, B> session : sessions.values()) {
            session.end(reason);
        }
    }
}

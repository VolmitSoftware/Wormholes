package art.arcane.wormholes.network.view;

import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireMessage;

import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.concurrent.ConcurrentHashMap;

public final class ViewSubscriptionManager<B, M, E> {
    private record Key(String peerName, UUID portalId) {
    }

    private static final long RESUBSCRIBE_INTERVAL_MILLIS = 5_000L;

    private final LongSupplier clock;
    private final NetworkManager network;
    private final RemoteViewCache<B, M, E> cache;
    private final Map<Key, Long> lastTouchMillis = new ConcurrentHashMap<>();
    private final Map<Key, Long> lastSubscribeMillis = new ConcurrentHashMap<>();
    private final Map<Key, Long> unsubscribeGraceMillis = new ConcurrentHashMap<>();
    private final Map<Key, Long> meshTouchMillis = new ConcurrentHashMap<>();
    private final Map<Key, Integer> meshDistances = new ConcurrentHashMap<>();
    private final Map<Key, Boolean> subscribed = new ConcurrentHashMap<>();

    public ViewSubscriptionManager(NetworkManager network, RemoteViewCache<B, M, E> cache, LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
        this.network = network;
        this.cache = cache;
    }

    public RemoteViewCache.RemoteView<B, M, E> touch(String peerName, UUID portalId, Request request) {
        Key key = new Key(peerName, portalId);
        long now = clock.getAsLong();
        lastTouchMillis.put(key, now);
        unsubscribeGraceMillis.put(key, Long.valueOf(Math.max(5, request.graceSeconds()) * 1000L));
        int distance = Math.clamp(request.meshDistance(), 0, 512);
        Integer previousDistance = meshDistances.get(key);
        if (previousDistance != null && distance < previousDistance.intValue()
            && now - meshTouchMillis.getOrDefault(key, 0L) < Math.max(5, request.graceSeconds()) * 1000L) {
            distance = previousDistance.intValue();
        } else {
            meshTouchMillis.put(key, now);
        }
        meshDistances.put(key, distance);
        boolean resized = previousDistance != null && distance != previousDistance.intValue();
        RemoteViewCache.RemoteView<B, M, E> view = cache.getOrCreate(peerName, portalId);
        if (subscribed.putIfAbsent(key, Boolean.TRUE) == null) {
            lastSubscribeMillis.put(key, now);
            network.send(peerName, new WireMessage.ViewSubscribe(portalId, distance));
            return view;
        }
        if (resized || !view.hasData() && now - lastSubscribeMillis.getOrDefault(key, 0L) >= RESUBSCRIBE_INTERVAL_MILLIS) {
            lastSubscribeMillis.put(key, now);
            network.send(peerName, new WireMessage.ViewSubscribe(portalId, distance));
        }
        return view;
    }

    public void sweep() {
        long now = clock.getAsLong();
        for (Map.Entry<Key, Long> entry : lastTouchMillis.entrySet()) {
            Key key = entry.getKey();
            long graceMillis = unsubscribeGraceMillis.getOrDefault(key, Long.valueOf(30_000L)).longValue();
            if (now - entry.getValue() < graceMillis) {
                continue;
            }
            lastTouchMillis.remove(key, entry.getValue());
            lastSubscribeMillis.remove(key);
            unsubscribeGraceMillis.remove(key);
            meshDistances.remove(key);
            meshTouchMillis.remove(key);
            if (subscribed.remove(key) != null) {
                network.send(key.peerName(), new WireMessage.ViewUnsubscribe(key.portalId()));
                cache.remove(key.peerName(), key.portalId());
            }
        }
    }

    public void onPeerStateChanged(String peerName, boolean ready) {
        if (ready) {
            return;
        }
        for (Key key : subscribed.keySet()) {
            if (key.peerName().equals(peerName)) {
                subscribed.remove(key);
            }
        }
    }

    public record Request(int graceSeconds, int meshDistance) {
    }
}

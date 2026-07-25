package art.arcane.wormholes.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class SidebandPresence {
    static final long READY_TTL_MS = 12_000L;

    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final Map<String, Long> rttMillis = new ConcurrentHashMap<>();
    private final Map<String, String> reachableGameHosts = new ConcurrentHashMap<>();

    boolean isReady(String name) {
        Long seen = lastSeen.get(name);
        return seen != null && System.currentTimeMillis() - seen <= READY_TTL_MS;
    }

    void mark(String name, long nowMillis, long rtt) {
        lastSeen.put(name, nowMillis);
        if (rtt >= 0L) {
            rttMillis.put(name, rtt);
        }
    }

    long rttOrDefault(String name, long fallback) {
        return rttMillis.getOrDefault(name, fallback);
    }

    long lastSeenOrDefault(String name, long fallback) {
        return lastSeen.getOrDefault(name, fallback);
    }

    Map<String, Long> lastSeenView() {
        return lastSeen;
    }

    boolean isTracked(String name) {
        return lastSeen.containsKey(name);
    }

    String reachableGameHost(String name) {
        return reachableGameHosts.get(name);
    }

    void rememberReachableGameHost(String name, String host) {
        reachableGameHosts.put(name, host);
    }

    void forget(String name) {
        lastSeen.remove(name);
        rttMillis.remove(name);
    }

    void clear() {
        lastSeen.clear();
        rttMillis.clear();
        reachableGameHosts.clear();
    }

    List<String> expire(long now) {
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> entry : lastSeen.entrySet()) {
            String peerName = entry.getKey();
            long seen = entry.getValue();
            if (now - seen <= READY_TTL_MS) {
                continue;
            }
            if (!lastSeen.remove(peerName, seen)) {
                continue;
            }
            rttMillis.remove(peerName);
            expired.add(peerName);
        }
        return expired;
    }
}

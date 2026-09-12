package art.arcane.wormholes.proxy.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Backends that enrolled over the plugin channel, keyed by their Wormholes server name. A name belongs
 * to the proxy-side server that first claimed it until that backend goes quiet, so a cloned or
 * copy-configured backend cannot take another one's handoffs by enrolling under its name.
 */
public final class ProxyRegistry {
    public record Backend(String proxyServer, String serverName, String serverCode, long capabilities, List<String> portals, long lastSeenMillis) {
    }

    private final Map<String, Backend> backends = new ConcurrentHashMap<>();

    /** Records an enroll. Returns false when another proxy-side server already holds the name. */
    public boolean enroll(String proxyServer, String serverName, String serverCode, long capabilities, List<String> portals, long nowMillis) {
        if (serverName == null || serverName.isBlank() || serverCode == null || serverCode.isBlank()) {
            return false;
        }
        Backend existing = backends.get(serverName);
        if (existing != null && !existing.proxyServer().equals(proxyServer)) {
            return false;
        }
        backends.put(serverName, new Backend(proxyServer, serverName, serverCode, capabilities, List.copyOf(portals), nowMillis));
        return true;
    }

    /**
     * Drops backends that have not enrolled within {@code ttlMillis}; a backend that went down stops
     * attracting handoffs that would fail at connect time. Each removal is handed to {@code onExpired}.
     */
    public void expire(long nowMillis, long ttlMillis, BiConsumer<String, String> onExpired) {
        for (Backend backend : backends.values()) {
            if (nowMillis - backend.lastSeenMillis() > ttlMillis && backends.remove(backend.serverName(), backend)) {
                onExpired.accept(backend.serverName(), backend.proxyServer());
            }
        }
    }

    public Backend find(String serverName) {
        return serverName == null ? null : backends.get(serverName);
    }

    /** Proxy-side server name for a Wormholes server name, or null when it never enrolled. */
    public String proxyServerFor(String serverName) {
        Backend backend = find(serverName);
        return backend == null ? null : backend.proxyServer();
    }

    public List<Backend> backends() {
        List<Backend> sorted = new ArrayList<>(backends.values());
        sorted.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.serverName(), b.serverName()));
        return sorted;
    }

    /** Every enrolled server code, sorted by server name. */
    public List<String> roster() {
        return rosterExcluding(null);
    }

    public List<String> rosterExcluding(String serverName) {
        List<String> codes = new ArrayList<>();
        for (Backend backend : backends()) {
            if (serverName == null || !serverName.equals(backend.serverName())) {
                codes.add(backend.serverCode());
            }
        }
        return codes;
    }
}

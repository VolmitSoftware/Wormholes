package art.arcane.wormholes.proxy.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Backends that enrolled over the plugin channel, keyed by their Wormholes server name. A name belongs
 * to the proxy-side server that first claimed it until that backend stops answering, so a cloned or
 * copy-configured backend cannot take another one's handoffs by enrolling under its name.
 */
public final class ProxyRegistry {
    public record Backend(String proxyServer, String serverName, String serverCode, long capabilities, List<String> portals, long lastSeenMillis) {
        Backend seenAt(long nowMillis) {
            return new Backend(proxyServer, serverName, serverCode, capabilities, portals, nowMillis);
        }
    }

    public interface Reachability {
        CompletableFuture<Boolean> reachable(String proxyServer);
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
        backends.values().removeIf(backend -> backend.proxyServer().equals(proxyServer) && !backend.serverName().equals(serverName));
        backends.put(serverName, new Backend(proxyServer, serverName, serverCode, capabilities, List.copyOf(portals), nowMillis));
        return true;
    }

    /**
     * Checks every backend that has not enrolled within {@code quietMillis}. A backend can only enroll
     * through a connected player, so an empty one goes quiet while it is still up: it stays while its
     * proxy-side server answers and is dropped, through {@code onDropped}, once it does not.
     */
    public void sweep(long nowMillis, long quietMillis, Reachability reachability, BiConsumer<String, String> onDropped) {
        for (Backend backend : backends.values()) {
            if (nowMillis - backend.lastSeenMillis() <= quietMillis) {
                continue;
            }
            reachability.reachable(backend.proxyServer()).whenComplete((reachable, error) -> {
                if (error == null && Boolean.TRUE.equals(reachable)) {
                    backends.replace(backend.serverName(), backend, backend.seenAt(nowMillis));
                } else if (backends.remove(backend.serverName(), backend)) {
                    onDropped.accept(backend.serverName(), backend.proxyServer());
                }
            });
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

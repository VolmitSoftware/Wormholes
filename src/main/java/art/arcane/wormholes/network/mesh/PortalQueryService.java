package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.PortalInfo;
import art.arcane.wormholes.network.WireCapability;
import art.arcane.wormholes.network.WireMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * On-demand directory pulls (PORTAL_QUERY / PORTAL_QUERY_RESULT). The server side answers from the
 * shareable local portals with a name filter (blank = all, trailing '*' = prefix, otherwise exact,
 * all case-insensitive) and a result cap; the client side keeps one pending query per peer and fails
 * fast for peers without the PORTAL_QUERY capability.
 */
public final class PortalQueryService {
    static final long QUERY_TIMEOUT_SECONDS = 10L;

    private final Supplier<List<PortalInfo>> localPortals;
    private final Predicate<String> supports;
    private final BiPredicate<String, WireMessage> sender;
    private final Map<String, CompletableFuture<List<PortalInfo>>> pending = new ConcurrentHashMap<>();

    public PortalQueryService(Supplier<List<PortalInfo>> localPortals, Predicate<String> supports, BiPredicate<String, WireMessage> sender) {
        this.localPortals = localPortals;
        this.supports = supports;
        this.sender = sender;
    }

    public static PortalQueryService forNetwork(NetworkManager network, Supplier<List<PortalInfo>> localPortals) {
        return new PortalQueryService(localPortals, peer -> network.peerSupports(peer, WireCapability.PORTAL_QUERY), network::send);
    }

    public boolean handle(String peerName, WireMessage message) {
        if (message instanceof WireMessage.PortalQuery query) {
            if (supports.test(peerName)) {
                sender.test(peerName, answer(query));
            }
            return true;
        }
        if (message instanceof WireMessage.PortalQueryResult result) {
            CompletableFuture<List<PortalInfo>> future = pending.remove(peerName);
            if (future != null) {
                future.complete(result.portals());
            }
            return true;
        }
        return false;
    }

    public CompletableFuture<List<PortalInfo>> query(String peerName, String filter, int limit) {
        if (!supports.test(peerName)) {
            return CompletableFuture.failedFuture(new IllegalStateException(peerName + " does not support portal queries"));
        }
        CompletableFuture<List<PortalInfo>> future = new CompletableFuture<List<PortalInfo>>()
            .orTimeout(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<List<PortalInfo>> superseded = pending.put(peerName, future);
        if (superseded != null) {
            superseded.completeExceptionally(new IllegalStateException("superseded by a newer query to " + peerName));
        }
        future.whenComplete((portals, error) -> pending.remove(peerName, future));
        if (!sender.test(peerName, new WireMessage.PortalQuery(filter == null ? "" : filter, clampLimit(limit)))) {
            future.completeExceptionally(new IllegalStateException("could not send portal query to " + peerName));
        }
        return future;
    }

    public int pending() {
        return pending.size();
    }

    private WireMessage.PortalQueryResult answer(WireMessage.PortalQuery query) {
        int limit = clampLimit(query.limit());
        String filter = query.filter() == null ? "" : query.filter().trim().toLowerCase(Locale.ROOT);
        boolean prefix = filter.endsWith("*");
        String needle = prefix ? filter.substring(0, filter.length() - 1) : filter;
        List<PortalInfo> matches = new ArrayList<>();
        boolean truncated = false;
        for (PortalInfo info : localPortals.get()) {
            String name = info.name() == null ? "" : info.name().toLowerCase(Locale.ROOT);
            boolean match = needle.isEmpty() || (prefix ? name.startsWith(needle) : name.equals(needle));
            if (!match) {
                continue;
            }
            if (matches.size() >= limit) {
                truncated = true;
                break;
            }
            matches.add(info);
        }
        return new WireMessage.PortalQueryResult(matches, truncated);
    }

    private static int clampLimit(int limit) {
        return limit <= 0 ? WireMessage.PortalQuery.MAX_LIMIT : Math.min(WireMessage.PortalQuery.MAX_LIMIT, limit);
    }
}

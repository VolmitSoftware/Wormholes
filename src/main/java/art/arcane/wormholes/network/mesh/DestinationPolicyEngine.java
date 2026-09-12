package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.portal.RemotePortal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resolves a gateway's {@link DestinationPolicy} to one (server, portal) pair at handoff time. A
 * candidate is unavailable when its peer is not linked, its portal is unknown or closed, it is
 * draining, or a minimum TPS is set and its beacon is missing or stale; it is full when its beacon
 * shows less headroom than required or too little TPS. A candidate with no live beacon stays
 * eligible but scores below every reporting one, so least-loaded never prefers a silent server. Full-only outcomes queue when the policy
 * allows it. Work is O(candidates) per call; sticky choices and round-robin cursors live in this
 * instance.
 */
public final class DestinationPolicyEngine {
    public static final long STICKY_TTL_MILLIS = 600_000L;
    private static final int STICKY_SWEEP_THRESHOLD = 1_024;

    /** Live view of the network for one resolution; the production view reads the manager and registry. */
    public interface Inputs {
        boolean peerReady(String server);

        LoadBeacon load(String server);

        boolean loadStale(String server);

        long rttMillis(String server);

        /** Open destination portal id for the candidate, or null when unknown or closed. */
        UUID portal(DestinationCandidate candidate);
    }

    public record Resolution(Kind kind, String server, UUID portalId) {
        public enum Kind {
            CHOSEN,
            NONE,
            QUEUE
        }

        public static Resolution chosen(String server, UUID portalId) {
            return new Resolution(Kind.CHOSEN, server, portalId);
        }

        public static Resolution none() {
            return new Resolution(Kind.NONE, null, null);
        }

        public static Resolution queue() {
            return new Resolution(Kind.QUEUE, null, null);
        }
    }

    private record Sticky(String server, UUID portalId, long expiresAtMillis) {
    }

    private record Eligible(DestinationCandidate candidate, UUID portalId, int headroom, boolean liveBeacon, long rttMillis) {
    }

    private final Map<UUID, Sticky> sticky = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> roundRobin = new ConcurrentHashMap<>();

    public Resolution resolve(UUID playerId, DestinationPolicy policy, Inputs inputs, long nowMillis) {
        List<Eligible> eligible = new ArrayList<>(policy.candidates().size());
        boolean anyFull = false;
        int requiredHeadroom = Math.max(1, policy.minHeadroom());
        for (DestinationCandidate candidate : policy.candidates()) {
            String server = candidate.server();
            if (!inputs.peerReady(server)) {
                continue;
            }
            UUID portalId = inputs.portal(candidate);
            if (portalId == null) {
                continue;
            }
            LoadBeacon load = inputs.load(server);
            boolean stale = inputs.loadStale(server);
            if (load != null && load.drain()) {
                continue;
            }
            if (policy.minTps() > 0.0D && (load == null || stale)) {
                continue;
            }
            boolean liveBeacon = load != null && !stale;
            int headroom = liveBeacon ? load.headroom() : Integer.MAX_VALUE;
            if (headroom < requiredHeadroom || (policy.minTps() > 0.0D && load.tps() < policy.minTps())) {
                anyFull = true;
                continue;
            }
            eligible.add(new Eligible(candidate, portalId, headroom, liveBeacon, inputs.rttMillis(server)));
        }
        if (eligible.isEmpty()) {
            return anyFull && policy.queue() ? Resolution.queue() : Resolution.none();
        }
        Eligible chosen = switch (policy.strategy()) {
            case FIRST_AVAILABLE -> eligible.get(0);
            case LEAST_LOADED -> leastLoaded(eligible);
            case ROUND_ROBIN -> roundRobin(policy, eligible);
            case STICKY -> sticky(playerId, eligible, nowMillis);
            case NEAREST -> nearest(eligible);
        };
        return Resolution.chosen(chosen.candidate().server(), chosen.portalId());
    }

    /** Production view: link state and beacons from the manager, portals from the remote directory. */
    public static Inputs inputs(NetworkManager network, RemotePortalRegistry registry, long staleMillis, long nowMillis) {
        return new Inputs() {
            @Override
            public boolean peerReady(String server) {
                return network.isPeerReady(server);
            }

            @Override
            public LoadBeacon load(String server) {
                return network.loads().latest(server);
            }

            @Override
            public boolean loadStale(String server) {
                return network.loads().isStale(server, nowMillis, staleMillis);
            }

            @Override
            public long rttMillis(String server) {
                return network.peerRttMillis(server);
            }

            @Override
            public UUID portal(DestinationCandidate candidate) {
                if (registry == null) {
                    return null;
                }
                if (candidate.portalId() != null) {
                    RemotePortal portal = registry.get(candidate.server(), candidate.portalId());
                    return portal != null && portal.isOpen() ? candidate.portalId() : null;
                }
                for (RemotePortal portal : registry.all()) {
                    if (portal.isOpen() && candidate.server().equals(portal.getServer().getName())
                        && candidate.tag().equalsIgnoreCase(portal.getName())) {
                        return portal.getId();
                    }
                }
                return null;
            }
        };
    }

    private static Eligible leastLoaded(List<Eligible> eligible) {
        Eligible best = eligible.get(0);
        long bestScore = score(best);
        for (int index = 1; index < eligible.size(); index++) {
            long score = score(eligible.get(index));
            if (score > bestScore) {
                best = eligible.get(index);
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Weighted headroom, or -1 for a candidate whose beacon is missing or stale: unknown capacity
     * loses to every server that is still reporting, and only wins when nothing else is.
     */
    private static long score(Eligible candidate) {
        return candidate.liveBeacon() ? (long) candidate.headroom() * candidate.candidate().weight() : -1L;
    }

    private Eligible roundRobin(DestinationPolicy policy, List<Eligible> eligible) {
        List<Eligible> ring = new ArrayList<>();
        for (Eligible entry : eligible) {
            for (int copy = 0; copy < entry.candidate().weight(); copy++) {
                ring.add(entry);
            }
        }
        StringBuilder key = new StringBuilder();
        for (DestinationCandidate candidate : policy.candidates()) {
            key.append(candidate.text()).append(',');
        }
        int cursor = roundRobin.computeIfAbsent(key.toString(), ignored -> new AtomicInteger()).getAndIncrement();
        return ring.get(Math.floorMod(cursor, ring.size()));
    }

    private Eligible sticky(UUID playerId, List<Eligible> eligible, long nowMillis) {
        if (sticky.size() > STICKY_SWEEP_THRESHOLD) {
            sticky.entrySet().removeIf(entry -> nowMillis >= entry.getValue().expiresAtMillis());
        }
        Sticky previous = sticky.get(playerId);
        if (previous != null && nowMillis < previous.expiresAtMillis()) {
            for (Eligible entry : eligible) {
                if (previous.server().equals(entry.candidate().server()) && previous.portalId().equals(entry.portalId())) {
                    sticky.put(playerId, new Sticky(previous.server(), previous.portalId(), nowMillis + STICKY_TTL_MILLIS));
                    return entry;
                }
            }
        }
        Eligible chosen = leastLoaded(eligible);
        sticky.put(playerId, new Sticky(chosen.candidate().server(), chosen.portalId(), nowMillis + STICKY_TTL_MILLIS));
        return chosen;
    }

    private static Eligible nearest(List<Eligible> eligible) {
        Eligible best = eligible.get(0);
        long bestRtt = rttOrMax(best);
        for (int index = 1; index < eligible.size(); index++) {
            long rtt = rttOrMax(eligible.get(index));
            if (rtt < bestRtt) {
                best = eligible.get(index);
                bestRtt = rtt;
            }
        }
        return best;
    }

    private static long rttOrMax(Eligible candidate) {
        return candidate.rttMillis() < 0L ? Long.MAX_VALUE : candidate.rttMillis();
    }
}

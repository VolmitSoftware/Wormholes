package art.arcane.wormholes.network.mesh;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DestinationPolicyEngineTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID BETA_PORTAL = UUID.randomUUID();
    private static final UUID GAMMA_PORTAL = UUID.randomUUID();
    private static final UUID DELTA_PORTAL = UUID.randomUUID();

    /** Hand-built world: ready peers, beacons, RTTs and which candidates resolve to an open portal. */
    private static final class World implements DestinationPolicyEngine.Inputs {
        final Set<String> ready = new HashSet<>();
        final Map<String, LoadBeacon> loads = new HashMap<>();
        final Set<String> stale = new HashSet<>();
        final Map<String, Long> rtt = new HashMap<>();
        final Map<String, UUID> portals = new HashMap<>();

        World up(String server, int online, int max, boolean drain) {
            ready.add(server);
            loads.put(server, new LoadBeacon(online, max, 0, 20.0D, 10.0D, drain, 0L, 0L));
            return this;
        }

        World tps(String server, double tps) {
            LoadBeacon load = loads.get(server);
            loads.put(server, new LoadBeacon(load.online(), load.max(), load.reserved(), tps, load.msptP95(), load.drain(), 0L, 0L));
            return this;
        }

        @Override
        public boolean peerReady(String server) {
            return ready.contains(server);
        }

        @Override
        public LoadBeacon load(String server) {
            return loads.get(server);
        }

        @Override
        public boolean loadStale(String server) {
            return stale.contains(server) || !loads.containsKey(server);
        }

        @Override
        public long rttMillis(String server) {
            return rtt.getOrDefault(server, -1L);
        }

        @Override
        public UUID portal(DestinationCandidate candidate) {
            if (candidate.portalId() != null) {
                return portals.containsValue(candidate.portalId()) ? candidate.portalId() : null;
            }
            return portals.get(candidate.server() + "#" + candidate.tag());
        }
    }

    private static World world() {
        World world = new World();
        world.portals.put("beta#hub", BETA_PORTAL);
        world.portals.put("gamma#hub", GAMMA_PORTAL);
        world.portals.put("delta#hub", DELTA_PORTAL);
        return world;
    }

    private static DestinationPolicy policy(SelectionStrategy strategy, int minHeadroom, double minTps, boolean queue, DestinationCandidate... candidates) {
        return new DestinationPolicy(List.of(candidates), strategy, minHeadroom, minTps, queue);
    }

    private static DestinationCandidate tag(String server, int weight) {
        return new DestinationCandidate(server, null, "hub", weight);
    }

    @Test
    void firstAvailableFollowsListOrderAndFailsOverPastOfflineDrainingAndFullServers() {
        World world = world().up("beta", 20, 20, false).up("gamma", 5, 20, true).up("delta", 1, 20, false);
        DestinationPolicyEngine engine = new DestinationPolicyEngine();
        DestinationPolicy policy = policy(SelectionStrategy.FIRST_AVAILABLE, 1, 0.0D, true, tag("alpha-offline", 1), tag("beta", 1), tag("gamma", 1), tag("delta", 1));

        DestinationPolicyEngine.Resolution resolution = engine.resolve(PLAYER, policy, world, 1_000L);

        assertEquals(DestinationPolicyEngine.Resolution.Kind.CHOSEN, resolution.kind());
        assertEquals("delta", resolution.server());
        assertEquals(DELTA_PORTAL, resolution.portalId());
    }

    @Test
    void fullCandidatesQueueWhenAllowedAndOtherwiseResolveToNone() {
        World world = world().up("beta", 20, 20, false).up("gamma", 19, 20, false);
        DestinationPolicyEngine engine = new DestinationPolicyEngine();

        assertEquals(DestinationPolicyEngine.Resolution.Kind.QUEUE,
            engine.resolve(PLAYER, policy(SelectionStrategy.FIRST_AVAILABLE, 2, 0.0D, true, tag("beta", 1), tag("gamma", 1)), world, 0L).kind());
        assertEquals(DestinationPolicyEngine.Resolution.Kind.NONE,
            engine.resolve(PLAYER, policy(SelectionStrategy.FIRST_AVAILABLE, 2, 0.0D, false, tag("beta", 1), tag("gamma", 1)), world, 0L).kind());
        assertEquals(DestinationPolicyEngine.Resolution.Kind.NONE,
            engine.resolve(PLAYER, policy(SelectionStrategy.FIRST_AVAILABLE, 1, 0.0D, true, tag("offline", 1)), world, 0L).kind());
    }

    @Test
    void minimumTpsRequiresAFreshBeaconAndRejectsLaggingServers() {
        World world = world().up("beta", 1, 20, false).tps("beta", 12.0D).up("gamma", 1, 20, false).tps("gamma", 19.0D).up("delta", 1, 20, false);
        world.stale.add("delta");
        DestinationPolicyEngine engine = new DestinationPolicyEngine();
        DestinationPolicy policy = policy(SelectionStrategy.FIRST_AVAILABLE, 0, 18.0D, false, tag("beta", 1), tag("delta", 1), tag("gamma", 1));

        assertEquals("gamma", engine.resolve(PLAYER, policy, world, 0L).server());
        World noBeacons = world();
        noBeacons.ready.add("beta");
        assertEquals("beta", engine.resolve(PLAYER, policy(SelectionStrategy.FIRST_AVAILABLE, 0, 0.0D, false, tag("beta", 1)), noBeacons, 0L).server());
        assertEquals(DestinationPolicyEngine.Resolution.Kind.NONE,
            engine.resolve(PLAYER, policy(SelectionStrategy.FIRST_AVAILABLE, 0, 18.0D, false, tag("beta", 1)), noBeacons, 0L).kind());
    }

    @Test
    void leastLoadedPicksTheLargestWeightedHeadroom() {
        World world = world().up("beta", 10, 20, false).up("gamma", 2, 20, false).up("delta", 15, 20, false);
        DestinationPolicyEngine engine = new DestinationPolicyEngine();

        assertEquals("gamma", engine.resolve(PLAYER, policy(SelectionStrategy.LEAST_LOADED, 0, 0.0D, false, tag("beta", 1), tag("gamma", 1), tag("delta", 1)), world, 0L).server());
        assertEquals("delta", engine.resolve(PLAYER, policy(SelectionStrategy.LEAST_LOADED, 0, 0.0D, false, tag("beta", 1), tag("gamma", 1), tag("delta", 4)), world, 0L).server());
    }

    @Test
    void leastLoadedPrefersALiveBeaconOverAServerThatStoppedReporting() {
        World world = world().up("beta", 19, 20, false).up("gamma", 2, 20, false);
        world.stale.add("gamma");
        world.ready.add("delta");
        DestinationPolicyEngine engine = new DestinationPolicyEngine();

        assertEquals("beta", engine.resolve(PLAYER,
            policy(SelectionStrategy.LEAST_LOADED, 0, 0.0D, false, tag("beta", 1), tag("gamma", 1), tag("delta", 1)), world, 0L).server(),
            "a stale or missing beacon is unknown capacity, never the largest");

        World blind = world();
        blind.ready.add("gamma");
        blind.ready.add("delta");
        assertEquals("gamma", engine.resolve(PLAYER,
            policy(SelectionStrategy.LEAST_LOADED, 0, 0.0D, false, tag("gamma", 1), tag("delta", 1)), blind, 0L).server(),
            "with no live beacon anywhere the policy still resolves, in list order");
    }

    @Test
    void roundRobinCyclesEligibleCandidatesByWeight() {
        World world = world().up("beta", 0, 20, false).up("gamma", 0, 20, false);
        DestinationPolicyEngine engine = new DestinationPolicyEngine();
        DestinationPolicy policy = policy(SelectionStrategy.ROUND_ROBIN, 0, 0.0D, false, tag("beta", 2), tag("gamma", 1));

        assertEquals("beta", engine.resolve(PLAYER, policy, world, 0L).server());
        assertEquals("beta", engine.resolve(PLAYER, policy, world, 0L).server());
        assertEquals("gamma", engine.resolve(PLAYER, policy, world, 0L).server());
        assertEquals("beta", engine.resolve(PLAYER, policy, world, 0L).server());
    }

    @Test
    void stickyKeepsThePlayersLastChoiceForTenMinutesWhileItStaysEligible() {
        World world = world().up("beta", 10, 20, false).up("gamma", 2, 20, false);
        DestinationPolicyEngine engine = new DestinationPolicyEngine();
        DestinationPolicy policy = policy(SelectionStrategy.STICKY, 0, 0.0D, false, tag("beta", 1), tag("gamma", 1));

        assertEquals("gamma", engine.resolve(PLAYER, policy, world, 0L).server());
        world.loads.put("gamma", new LoadBeacon(19, 20, 0, 20.0D, 10.0D, false, 0L, 0L));
        assertEquals("gamma", engine.resolve(PLAYER, policy, world, 60_000L).server());
        assertEquals("beta", engine.resolve(UUID.randomUUID(), policy, world, 60_000L).server());
        world.ready.remove("gamma");
        assertEquals("beta", engine.resolve(PLAYER, policy, world, 120_000L).server());
        world.ready.add("gamma");
        assertEquals("beta", engine.resolve(PLAYER, policy, world, 180_000L).server());
        world.loads.put("beta", new LoadBeacon(19, 20, 0, 20.0D, 10.0D, false, 0L, 0L));
        world.loads.put("gamma", new LoadBeacon(0, 20, 0, 20.0D, 10.0D, false, 0L, 0L));
        assertEquals("gamma", engine.resolve(PLAYER, policy, world, 180_000L + DestinationPolicyEngine.STICKY_TTL_MILLIS + 1L).server());
    }

    @Test
    void nearestPrefersTheLowestRoundTripAndUnknownRttLoses() {
        World world = world().up("beta", 0, 20, false).up("gamma", 0, 20, false).up("delta", 0, 20, false);
        world.rtt.put("beta", 80L);
        world.rtt.put("gamma", 15L);
        DestinationPolicyEngine engine = new DestinationPolicyEngine();

        assertEquals("gamma", engine.resolve(PLAYER, policy(SelectionStrategy.NEAREST, 0, 0.0D, false, tag("delta", 1), tag("beta", 1), tag("gamma", 1)), world, 0L).server());
        assertEquals("delta", engine.resolve(PLAYER, policy(SelectionStrategy.NEAREST, 0, 0.0D, false, tag("delta", 1)), world, 0L).server());
    }

    @Test
    void candidatesWhosePortalIsUnknownOrClosedAreSkipped() {
        World world = world().up("beta", 0, 20, false);
        DestinationPolicyEngine engine = new DestinationPolicyEngine();
        DestinationPolicy policy = policy(SelectionStrategy.FIRST_AVAILABLE, 0, 0.0D, true,
            new DestinationCandidate("beta", UUID.randomUUID(), null, 1), new DestinationCandidate("beta", null, "missing", 1), tag("beta", 1));

        DestinationPolicyEngine.Resolution resolution = engine.resolve(PLAYER, policy, world, 0L);
        assertEquals(BETA_PORTAL, resolution.portalId());
        assertNull(engine.resolve(PLAYER, policy(SelectionStrategy.FIRST_AVAILABLE, 0, 0.0D, true), world, 0L).server());
    }
}

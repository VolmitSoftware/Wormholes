package art.arcane.wormholes.nexus;

import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.hook.TraversalPhase;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.nexus.DestinationEntry.TargetKind;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.TunnelType;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusDestinationResolverTest {
    @TempDir
    Path tempDir;

    private World world;
    private NetworkRegistry registry;
    private ReturnAddresses returns;
    private RecordingTunnels tunnels;
    private NexusDestinationResolver resolver;
    private long clock;

    @BeforeEach
    void setUp() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new NexusExtensionFactory(null)));
        world = NexusTestSupport.world("resolver");
        registry = new NetworkRegistry(tempDir);
        registry.load();
        clock = 1_000L;
        returns = new ReturnAddresses(() -> clock);
        tunnels = new RecordingTunnels();
        resolver = new NexusDestinationResolver(registry, returns, tunnels, portal -> 0L);
    }

    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void resolverRunsAfterAccessAndRulesGates() {
        assertEquals(200, resolver.order());
    }

    @Test
    void aPortalWithoutAPerTravelerPolicyKeepsTheTunnelItAlreadyHas() throws IOException {
        LocalPortal source = NexusTestSupport.portal(world, "source");
        LocalPortal target = NexusTestSupport.portal(world, "target", 40.0D, 0.0D);
        join(source, "AAAA", target, "BBBB");
        ITunnel current = new StubTunnel(target.getId());

        assertSame(current, resolver.resolve(source, NexusTestSupport.traveler(UUID.randomUUID(), world, 0, 64, 0), current));

        source.extension(NexusPortalExtension.class).setPolicy(new DestinationPolicy(DestinationMode.ORDERED,
                List.of(new DestinationEntry(TargetKind.ADDRESS, "BBBB", 1, 0, 0, "")), SelectionRule.ROUND_ROBIN));

        assertSame(current, resolver.resolve(source, NexusTestSupport.traveler(UUID.randomUUID(), world, 0, 64, 0), current),
                "schedule-driven policies belong to the scheduler, not the resolver");
        assertTrue(tunnels.created.isEmpty());
    }

    @Test
    void perPlayerModeGivesOneStableTunnelPerTravelerAndBuildsItOnlyOnce() throws IOException {
        LocalPortal source = NexusTestSupport.portal(world, "source");
        LocalPortal first = NexusTestSupport.portal(world, "first", 40.0D, 0.0D);
        LocalPortal second = NexusTestSupport.portal(world, "second", 80.0D, 0.0D);
        join(source, "AAAA", first, "BBBB");
        registry.save(registry.memberOf(source.getId())
                .withMember(second.getId(), new NetworkMember(second.getId(), "CCCC", "", 0L, null)));
        source.extension(NexusPortalExtension.class).setPolicy(new DestinationPolicy(DestinationMode.PER_PLAYER, List.of(
                new DestinationEntry(TargetKind.ADDRESS, "BBBB", 1, 0, 0, ""),
                new DestinationEntry(TargetKind.ADDRESS, "CCCC", 1, 0, 0, "")), SelectionRule.ROUND_ROBIN));

        UUID travelerId = UUID.randomUUID();
        Entity traveler = NexusTestSupport.traveler(travelerId, world, 0, 64, 0);
        ITunnel current = new StubTunnel(null);

        ITunnel resolved = resolver.resolve(source, traveler, current);
        assertNotNull(resolved);
        assertTrue(resolved != current);
        UUID chosen = resolved.getDestinationId();
        assertTrue(chosen.equals(first.getId()) || chosen.equals(second.getId()));

        ITunnel again = resolver.resolve(source, NexusTestSupport.traveler(travelerId, world, 3, 64, 3), current);
        assertSame(resolved, again, "per-player tunnels must be cached per portal and entry");
        assertEquals(1, tunnels.created.size());
    }

    @Test
    void returnModeSendsATravelerBackToThePortalTheyArrivedFrom() throws IOException {
        LocalPortal home = NexusTestSupport.portal(world, "home");
        LocalPortal away = NexusTestSupport.portal(world, "away", 40.0D, 0.0D);
        join(home, "AAAA", away, "BBBB");
        away.extension(NexusPortalExtension.class).setPolicy(new DestinationPolicy(DestinationMode.RETURN,
                List.of(new DestinationEntry(TargetKind.ADDRESS, "AAAA", 1, 0, 0, "")), SelectionRule.ROUND_ROBIN));

        UUID travelerId = UUID.randomUUID();
        Entity traveler = NexusTestSupport.traveler(travelerId, world, 0, 64, 0);
        ITunnel current = new StubTunnel(null);

        assertSame(current, resolver.resolve(away, traveler, current), "no recorded arrival yet");

        returns.onDeparted(new TraversalAttempt(TraversalPhase.DEPART, home, traveler, null, null, clock));
        returns.onArrived(away, traveler, null);

        ITunnel resolved = resolver.resolve(away, traveler, current);
        assertEquals(home.getId(), resolved.getDestinationId());
    }

    @Test
    void aRecordedArrivalIsForgottenAfterItsTimeToLive() throws IOException {
        LocalPortal home = NexusTestSupport.portal(world, "home");
        LocalPortal away = NexusTestSupport.portal(world, "away", 40.0D, 0.0D);
        join(home, "AAAA", away, "BBBB");
        UUID travelerId = UUID.randomUUID();
        Entity traveler = NexusTestSupport.traveler(travelerId, world, 0, 64, 0);

        returns.onDeparted(new TraversalAttempt(TraversalPhase.DEPART, home, traveler, null, null, clock));
        returns.onArrived(away, traveler, null);
        assertEquals(home.getId(), returns.sourceFor(travelerId));

        clock += ReturnAddresses.TIME_TO_LIVE_MILLIS - 1L;
        assertEquals(home.getId(), returns.sourceFor(travelerId));

        clock += 1L;
        assertNull(returns.sourceFor(travelerId));
    }

    @Test
    void entrySideIsDecidedFromTheEntityPositionRelativeToTheFramePlane() {
        LocalPortal portal = NexusTestSupport.portal(world, "sided");
        Vector origin = portal.getOrigin();
        Vector normal = new Vector(portal.getFrame().getNormal().x(), portal.getFrame().getNormal().y(),
                portal.getFrame().getNormal().z());

        assertTrue(NexusDestinationResolver.isFrontSide(portal, origin.clone().add(normal.clone().multiply(2.0D))));
        assertTrue(!NexusDestinationResolver.isFrontSide(portal, origin.clone().subtract(normal.clone().multiply(2.0D))));
    }

    private void join(LocalPortal first, String firstAddress, LocalPortal second, String secondAddress) throws IOException {
        PortalNetwork network = PortalNetwork.create(UUID.randomUUID(), "resolver", UUID.randomUUID())
                .withMember(first.getId(), new NetworkMember(first.getId(), firstAddress, "", 0L, null))
                .withMember(second.getId(), new NetworkMember(second.getId(), secondAddress, "", 0L, null));
        registry.save(network);
        stamp(first, network, firstAddress);
        stamp(second, network, secondAddress);
    }

    private static void stamp(LocalPortal portal, PortalNetwork network, String address) {
        NexusPortalExtension state = portal.extension(NexusPortalExtension.class);
        state.setNetworkId(network.id());
        state.setAddress(address);
    }

    private static final class RecordingTunnels implements NexusDestinationResolver.TunnelFactory {
        private final List<NetworkMember> created = new ArrayList<>();

        @Override
        public ITunnel create(LocalPortal source, NetworkMember member) {
            created.add(member);
            return new StubTunnel(member.portalId());
        }
    }

    private record StubTunnel(UUID destinationId) implements ITunnel {
        @Override
        public art.arcane.wormholes.portal.IPortal getDestination() {
            return null;
        }

        @Override
        public UUID getDestinationId() {
            return destinationId;
        }

        @Override
        public TunnelType getTunnelType() {
            return TunnelType.LOCAL;
        }

        @Override
        public boolean isValid() {
            return destinationId != null;
        }

        @Override
        public void saveJSON(art.arcane.volmlib.util.json.JSONObject json) {
        }

        @Override
        public void loadJSON(art.arcane.volmlib.util.json.JSONObject json) {
        }

        @Override
        public art.arcane.volmlib.util.json.JSONObject toJSON() {
            return new art.arcane.volmlib.util.json.JSONObject();
        }
    }
}

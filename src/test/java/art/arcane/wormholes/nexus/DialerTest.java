package art.arcane.wormholes.nexus;

import art.arcane.wormholes.config.toml.NexusConfig;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.nexus.DestinationEntry.TargetKind;
import art.arcane.wormholes.nexus.Dialer.DialResult;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.World;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialerTest {
    @TempDir
    Path tempDir;

    private World world;
    private NetworkRegistry registry;
    private NexusConfig config;
    private RecordingApplier applier;
    private Dialer dialer;

    @BeforeEach
    void setUp() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new NexusExtensionFactory(null)));
        world = NexusTestSupport.world("dialer");
        registry = new NetworkRegistry(tempDir);
        registry.load();
        config = new NexusConfig();
        applier = new RecordingApplier();
        dialer = new Dialer(registry, applier, () -> config);
    }

    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void dialingAKnownAddressAppliesTheTunnelAndStampsTheDialState() throws IOException {
        LocalPortal source = NexusTestSupport.portal(world, "source");
        LocalPortal target = NexusTestSupport.portal(world, "target", 40.0D, 0.0D);
        UUID dialedBy = UUID.randomUUID();
        joinNetwork("mesh", source, "AAAA", target, "BBBB");

        DialResult result = dialer.dial(source, "bbbb", dialedBy, 1_000L);

        assertEquals(DialResult.DIALED, result);
        assertEquals(1, applier.applied.size());
        assertEquals(target.getId(), applier.applied.getFirst().portalId());
        NexusPortalExtension state = source.extension(NexusPortalExtension.class);
        assertEquals("BBBB", state.dial().currentAddress());
        assertEquals(1_000L, state.dial().dialedAtMillis());
        assertEquals(dialedBy, state.dial().dialedBy());
        assertFalse(state.dial().sticky());
    }

    @Test
    void aSecondDialInsideTheDebounceWindowIsRefusedAndLeavesTheTunnelAlone() throws IOException {
        LocalPortal source = NexusTestSupport.portal(world, "source");
        LocalPortal target = NexusTestSupport.portal(world, "target", 40.0D, 0.0D);
        LocalPortal third = NexusTestSupport.portal(world, "third", 80.0D, 0.0D);
        joinNetwork("mesh", source, "AAAA", target, "BBBB");
        registry.save(registry.memberOf(source.getId())
                .withMember(third.getId(), new NetworkMember(third.getId(), "CCCC", "", 0L, null)));

        assertEquals(DialResult.DIALED, dialer.dial(source, "BBBB", null, 1_000L));
        assertEquals(DialResult.DEBOUNCED, dialer.dial(source, "CCCC", null, 1_000L + config.dialDebounceMillis - 1L));
        assertEquals(1, applier.applied.size());

        assertEquals(DialResult.DIALED, dialer.dial(source, "CCCC", null, 1_000L + config.dialDebounceMillis));
        assertEquals(2, applier.applied.size());
        assertEquals(third.getId(), applier.applied.get(1).portalId());
    }

    @Test
    void anUnknownAddressAndAPortalWithoutANetworkBothRefuseWithoutApplying() throws IOException {
        LocalPortal source = NexusTestSupport.portal(world, "source");
        LocalPortal target = NexusTestSupport.portal(world, "target", 40.0D, 0.0D);
        LocalPortal loner = NexusTestSupport.portal(world, "loner", 80.0D, 0.0D);
        joinNetwork("mesh", source, "AAAA", target, "BBBB");

        assertEquals(DialResult.UNKNOWN_ADDRESS, dialer.dial(source, "ZZZZ", null, 1_000L));
        assertEquals(DialResult.UNKNOWN_ADDRESS, dialer.dial(source, "AAAA", null, 1_000L));
        assertEquals(DialResult.NOT_ON_NETWORK, dialer.dial(loner, "BBBB", null, 1_000L));
        assertTrue(applier.applied.isEmpty());
    }

    @Test
    void dialedAddressReportsTheCurrentAddressForTheRulesEngine() throws IOException {
        LocalPortal source = NexusTestSupport.portal(world, "source");
        LocalPortal target = NexusTestSupport.portal(world, "target", 40.0D, 0.0D);
        joinNetwork("mesh", source, "AAAA", target, "BBBB");

        assertEquals("", Dialer.dialedAddress(source));
        assertEquals("", Dialer.dialedAddress(null));

        assertEquals(DialResult.DIALED, dialer.dial(source, "BBBB", null, 1_000L));
        assertEquals("BBBB", Dialer.dialedAddress(source));
    }

    @Test
    void nextCyclesThroughTheNetworkAddressesInOrderAndSkipsThePortalItself() throws IOException {
        LocalPortal source = NexusTestSupport.portal(world, "source");
        LocalPortal beta = NexusTestSupport.portal(world, "beta", 40.0D, 0.0D);
        LocalPortal gamma = NexusTestSupport.portal(world, "gamma", 80.0D, 0.0D);
        joinNetwork("ring", source, "BBBB", beta, "CCCC");
        registry.save(registry.memberOf(source.getId())
                .withMember(gamma.getId(), new NetworkMember(gamma.getId(), "DDDD", "", 0L, null)));
        NexusPortalExtension state = source.extension(NexusPortalExtension.class);

        assertEquals(DialResult.DIALED, dialer.next(source, 1, null, 1_000L));
        assertEquals("CCCC", state.dial().currentAddress());
        assertEquals(DialResult.DIALED, dialer.next(source, 1, null, 2_000L));
        assertEquals("DDDD", state.dial().currentAddress());
        assertEquals(DialResult.DIALED, dialer.next(source, 1, null, 3_000L));
        assertEquals("CCCC", state.dial().currentAddress());
        assertEquals(DialResult.DIALED, dialer.next(source, -1, null, 4_000L));
        assertEquals("DDDD", state.dial().currentAddress());
    }

    @Test
    void schedulerRevertsAnExpiredManualDialToTheNetworkHub() throws IOException {
        LocalPortal source = NexusTestSupport.portal(world, "source");
        LocalPortal hub = NexusTestSupport.portal(world, "hub", 40.0D, 0.0D);
        LocalPortal outpost = NexusTestSupport.portal(world, "outpost", 80.0D, 0.0D);
        joinNetwork("hubbed", source, "AAAA", hub, "BBBB");
        PortalNetwork network = registry.memberOf(source.getId())
                .withMember(outpost.getId(), new NetworkMember(outpost.getId(), "CCCC", "", 0L, null))
                .withHubPortalId(hub.getId());
        registry.save(network);
        config.dialHoldSeconds = 10;

        DestinationScheduler scheduler = scheduler(List.of(source, hub, outpost), 0L);
        assertEquals(DialResult.DIALED, dialer.dial(source, "CCCC", null, 1_000L));

        scheduler.tick(1_000L + 9_000L);
        assertEquals("CCCC", source.extension(NexusPortalExtension.class).dial().currentAddress());

        scheduler.tick(1_000L + 10_000L);
        assertEquals("BBBB", source.extension(NexusPortalExtension.class).dial().currentAddress());
        assertEquals(hub.getId(), applier.applied.getLast().portalId());
    }

    @Test
    void schedulerSwitchesAScheduledDestinationAtTheWindowBoundary() throws IOException {
        LocalPortal source = NexusTestSupport.portal(world, "source");
        LocalPortal day = NexusTestSupport.portal(world, "day", 40.0D, 0.0D);
        LocalPortal night = NexusTestSupport.portal(world, "night", 80.0D, 0.0D);
        joinNetwork("scheduled", source, "AAAA", day, "DAY1");
        registry.save(registry.memberOf(source.getId())
                .withMember(night.getId(), new NetworkMember(night.getId(), "NGT1", "", 0L, null)));
        source.extension(NexusPortalExtension.class).setPolicy(new DestinationPolicy(DestinationMode.SCHEDULED, List.of(
                new DestinationEntry(TargetKind.ADDRESS, "DAY1", 1, 0, 12000, "day"),
                new DestinationEntry(TargetKind.ADDRESS, "NGT1", 1, 12000, 24000, "night")), SelectionRule.ROUND_ROBIN));

        long[] worldTime = {6000L};
        DestinationScheduler scheduler = new DestinationScheduler(registry, dialer, () -> List.of(source, day, night),
                portal -> worldTime[0], () -> config);

        scheduler.tick(1_000L);
        assertEquals("DAY1", source.extension(NexusPortalExtension.class).dial().currentAddress());

        scheduler.tick(2_000L);
        assertEquals(1, applier.applied.size(), "scheduler redialed a destination that had not changed");

        worldTime[0] = 13000L;
        scheduler.tick(1_000_000L);
        assertEquals("NGT1", source.extension(NexusPortalExtension.class).dial().currentAddress());
        assertEquals(night.getId(), applier.applied.getLast().portalId());
    }

    private DestinationScheduler scheduler(List<LocalPortal> portals, long worldTime) {
        return new DestinationScheduler(registry, dialer, () -> portals, portal -> worldTime, () -> config);
    }

    private void joinNetwork(String name, LocalPortal first, String firstAddress, LocalPortal second, String secondAddress)
            throws IOException {
        PortalNetwork network = PortalNetwork.create(UUID.randomUUID(), name, UUID.randomUUID())
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

    private static final class RecordingApplier implements Dialer.TunnelApplier {
        private final List<NetworkMember> applied = new ArrayList<>();

        @Override
        public boolean apply(LocalPortal portal, NetworkMember member) {
            applied.add(member);
            return true;
        }
    }
}

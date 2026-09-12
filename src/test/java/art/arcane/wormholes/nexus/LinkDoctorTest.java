package art.arcane.wormholes.nexus;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.nexus.LinkDoctorReport.Finding;
import art.arcane.wormholes.nexus.LinkDoctorReport.Kind;
import art.arcane.wormholes.portal.DimensionalTunnel;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalTunnel;
import art.arcane.wormholes.portal.TunnelType;
import art.arcane.wormholes.portal.UniversalTunnel;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkDoctorTest {
    @TempDir
    Path tempDir;

    private World overworld;
    private World unloaded;
    private NetworkRegistry registry;
    private final Set<World> loadedWorlds = new HashSet<>();

    @BeforeEach
    void setUp() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new NexusExtensionFactory(null)));
        overworld = NexusTestSupport.world("doctor");
        unloaded = NexusTestSupport.world("doctor_attic");
        loadedWorlds.add(overworld);
        registry = new NetworkRegistry(tempDir);
        registry.load();
    }

    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void aHealthyPairOfPortalsProducesNoFindings() throws IOException {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        ILocalPortal secondStub = portal(secondId, "second", overworld, null);
        ILocalPortal firstStub = portal(firstId, "first", overworld, null);
        ILocalPortal first = portal(firstId, "first", overworld, new LocalTunnel(secondStub));
        ILocalPortal second = portal(secondId, "second", overworld, new LocalTunnel(firstStub));
        network("mesh", firstId, "AAAA", secondId, "BBBB");

        LinkDoctorReport report = LinkDoctor.inspect(List.of(first, second), registry, environment());

        assertTrue(report.isClean(), report.findings().toString());
    }

    @Test
    void aNetworkedPortalWhoseDestinationDoesNotPointBackIsReportedAsOneWay() throws IOException {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID thirdId = UUID.randomUUID();
        ILocalPortal thirdStub = portal(thirdId, "third", overworld, null);
        ILocalPortal secondStub = portal(secondId, "second", overworld, null);
        ILocalPortal first = portal(firstId, "first", overworld, new LocalTunnel(secondStub));
        ILocalPortal second = portal(secondId, "second", overworld, new LocalTunnel(thirdStub));
        ILocalPortal third = portal(thirdId, "third", overworld, new LocalTunnel(secondStub));
        network("mesh", firstId, "AAAA", secondId, "BBBB");

        LinkDoctorReport report = LinkDoctor.inspect(List.of(first, second, third), registry, environment());

        List<Finding> oneWay = report.of(Kind.ONE_WAY);
        assertEquals(1, oneWay.size(), report.findings().toString());
        assertEquals("first", oneWay.getFirst().portal());
        assertEquals("second", oneWay.getFirst().destination());
    }

    @Test
    void aTunnelWithNoDestinationLeftIsReportedAsDangling() {
        UUID orphanId = UUID.randomUUID();
        JSONObject encoded = new JSONObject();
        encoded.put("type", TunnelType.LOCAL.name());
        encoded.put("destination", UUID.randomUUID().toString());
        ILocalPortal orphan = portal(orphanId, "orphan", overworld, ITunnel.createTunnel(encoded));

        LinkDoctorReport report = LinkDoctor.inspect(List.of(orphan), registry, environment());

        assertEquals(1, report.of(Kind.DANGLING).size());
        assertEquals("orphan", report.of(Kind.DANGLING).getFirst().portal());
        assertTrue(report.of(Kind.ONE_WAY).isEmpty(), "a dangling link must not also be reported one-way");
    }

    @Test
    void aLinkIntoAnUnloadedWorldIsReportedWithThatWorldName() {
        UUID sourceId = UUID.randomUUID();
        UUID atticId = UUID.randomUUID();
        ILocalPortal attic = portal(atticId, "attic", unloaded, null);
        ILocalPortal source = portal(sourceId, "source", overworld, new DimensionalTunnel(attic));

        LinkDoctorReport report = LinkDoctor.inspect(List.of(source), registry, environment());

        assertEquals(1, report.of(Kind.UNLOADED_WORLD).size());
        assertEquals("source", report.of(Kind.UNLOADED_WORLD).getFirst().portal());
        assertEquals("doctor_attic", report.of(Kind.UNLOADED_WORLD).getFirst().world());
    }

    @Test
    void aGatewayPointingAtALongOfflinePeerIsReportedWithTheDayCount() {
        ILocalPortal gateway = portal(UUID.randomUUID(), "gateway", overworld,
                new UniversalTunnel("beta", UUID.randomUUID()), true);

        LinkDoctorReport report = LinkDoctor.inspect(List.of(gateway), registry, environment());

        assertEquals(1, report.of(Kind.PEER_OFFLINE).size());
        Finding finding = report.of(Kind.PEER_OFFLINE).getFirst();
        assertEquals("gateway", finding.portal());
        assertEquals("beta", finding.server());
        assertEquals(4L, finding.offlineDays());
        assertTrue(report.of(Kind.DANGLING).isEmpty(), "an offline peer is not a dangling link");
    }

    @Test
    void aGatewayPointingAtALivePeerIsLeftAlone() {
        ILocalPortal gateway = portal(UUID.randomUUID(), "gateway", overworld,
                new UniversalTunnel("alpha", UUID.randomUUID()), true);

        LinkDoctorReport report = LinkDoctor.inspect(List.of(gateway), registry, environment());

        assertTrue(report.isClean(), report.findings().toString());
    }

    private LinkDoctor.Environment environment() {
        return new LinkDoctor.Environment() {
            @Override
            public boolean isWorldLoaded(World world) {
                return world != null && loadedWorlds.contains(world);
            }

            @Override
            public long peerOfflineDays(String serverName) {
                return "beta".equals(serverName) ? 4L : -1L;
            }
        };
    }

    private void network(String name, UUID firstId, String firstAddress, UUID secondId, String secondAddress)
            throws IOException {
        registry.save(PortalNetwork.create(UUID.randomUUID(), name, UUID.randomUUID())
                .withMember(firstId, new NetworkMember(firstId, firstAddress, "", 0L, null))
                .withMember(secondId, new NetworkMember(secondId, secondAddress, "", 0L, null)));
    }

    private static ILocalPortal portal(UUID id, String name, World world, ITunnel tunnel) {
        return NexusTestSupport.linkedPortal(id, name, world, tunnel, false);
    }

    private static ILocalPortal portal(UUID id, String name, World world, ITunnel tunnel, boolean gateway) {
        return NexusTestSupport.linkedPortal(id, name, world, tunnel, gateway);
    }
}

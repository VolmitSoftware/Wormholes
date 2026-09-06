package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConnectServiceTest {
    private static final Logger LOGGER = Logger.getLogger("ServerConnectServiceTest");

    @TempDir
    Path tempDir;

    private NetworkManager network;
    private TraversalService previousTraversal;
    private WormholesSettings previousSettings;
    private Wormholes previousInstance;
    private ClientProtocolFixture protocolApi;

    @BeforeEach
    void setUp() {
        previousTraversal = Wormholes.traversalService;
        previousSettings = Wormholes.settings;
        previousInstance = Wormholes.instance;
        protocolApi = new ClientProtocolFixture(ClientVersion.V_26_2);
        Wormholes.traversalService = null;
    }

    @AfterEach
    void tearDown() {
        if (Wormholes.traversalService != null) {
            Wormholes.traversalService.shutdown();
        }
        Wormholes.traversalService = previousTraversal;
        Wormholes.settings = previousSettings;
        Wormholes.instance = previousInstance;
        protocolApi.close();
        if (network != null) {
            network.stop();
            network = null;
        }
    }

    @Test
    void resolveNameMatchesCaseInsensitively() {
        network = manager("alpha");
        network.savePeer(route("Beta-Survival"));

        assertEquals("Beta-Survival", ServerConnectService.resolveName(network, "Beta-Survival"));
        assertEquals("Beta-Survival", ServerConnectService.resolveName(network, "beta-survival"));
        assertNull(ServerConnectService.resolveName(network, "gamma"));
        assertNull(ServerConnectService.resolveName(network, ""));
        assertNull(ServerConnectService.resolveName(null, "beta"));
    }

    @Test
    void unknownServerIsReported() throws Exception {
        network = manager("alpha");
        Player player = player(new AtomicReference<TransferCall>(), new AtomicReference<String>());

        assertEquals(ServerConnectService.Result.UNKNOWN_SERVER,
            ServerConnectService.connect(network, player, "beta", "auto"));
    }

    @Test
    void directTransferRequiresReadyPeer() throws Exception {
        network = manager("alpha");
        network.savePeer(route("beta"));
        Player player = player(new AtomicReference<TransferCall>(), new AtomicReference<String>());

        assertEquals(ServerConnectService.Result.NOT_READY,
            ServerConnectService.connect(network, player, "beta", "auto"));
    }

    @Test
    void readyPeerWithoutTraversalRuntimeDoesNotTransfer() throws Exception {
        network = manager("alpha");
        network.savePeer(route("beta"));
        network.presence().mark("beta", System.currentTimeMillis(), 5L);
        AtomicReference<TransferCall> transfer = new AtomicReference<TransferCall>();
        AtomicReference<String> pluginMessageChannel = new AtomicReference<String>();
        Player player = player(transfer, pluginMessageChannel);

        assertEquals(ServerConnectService.Result.TRANSFER_FAILED,
            ServerConnectService.connect(network, player, "beta", "auto"));
        assertNull(transfer.get());
        assertNull(pluginMessageChannel.get());
    }

    @Test
    void proxyPeerRequiresReadySidebandBeforeAdmission() throws Exception {
        network = manager("alpha");
        NetworkConfig.PeerEntry peer = route("beta");
        peer.useProxy = true;
        network.savePeer(peer);
        AtomicReference<String> pluginMessageChannel = new AtomicReference<String>();
        Player player = player(new AtomicReference<TransferCall>(), pluginMessageChannel);

        assertEquals(ServerConnectService.Result.NOT_READY,
            ServerConnectService.connect(network, player, "beta", "auto"));
        assertNull(pluginMessageChannel.get());
    }

    @Test
    void directConnectWaitsForEndpointValidationAndDestinationAdmission() throws Exception {
        TestNetwork ready = readyNetwork(false);
        List<ScheduledTask> scheduled = startTraversal(ready);
        AtomicReference<TransferCall> transfer = new AtomicReference<TransferCall>();
        AtomicReference<String> pluginMessageChannel = new AtomicReference<String>();
        Player player = player(transfer, pluginMessageChannel);

        assertEquals(ServerConnectService.Result.QUEUED,
            ServerConnectService.connect(ready, player, "beta", "auto"));
        assertEquals(1, Wormholes.traversalService.statsSnapshot().inFlight());
        assertTrue(ready.sent.isEmpty());
        assertEquals(1, scheduled.size());
        assertTrue(scheduled.getFirst().delayTicks() > 0L);
        assertNull(transfer.get());
        assertNull(pluginMessageChannel.get());

        ready.validation.complete(new EndpointValidation(EndpointValidation.State.VERIFIED, "available"));
        assertTrue(ready.sent.isEmpty());
        assertEquals(2, scheduled.size());
        ScheduledTask continuation = scheduled.getLast();
        assertEquals(0L, continuation.delayTicks());
        continuation.task().run();

        WireMessage.HandoffRequest request = ready.firstRequest();
        assertEquals(player.getUniqueId(), request.playerId());
        assertNull(request.destPortalId());
        assertNull(request.traversive());
        assertTrue(request.directTransfer());
        assertTrue(request.onlineMode());
        assertNull(transfer.get());
        assertNull(pluginMessageChannel.get());
    }

    @Test
    void proxyConnectQueuesAdmissionWithoutSendingAConnectPluginMessage() throws Exception {
        TestNetwork ready = readyNetwork(true);
        startTraversal(ready);
        AtomicReference<TransferCall> transfer = new AtomicReference<TransferCall>();
        AtomicReference<String> pluginMessageChannel = new AtomicReference<String>();
        Player player = player(transfer, pluginMessageChannel);

        assertEquals(ServerConnectService.Result.QUEUED,
            ServerConnectService.connect(ready, player, "beta", "auto"));

        WireMessage.HandoffRequest request = ready.firstRequest();
        assertFalse(request.directTransfer());
        assertNull(request.destPortalId());
        assertEquals(0, ready.validationRequests);
        assertNull(transfer.get());
        assertNull(pluginMessageChannel.get());
    }

    @Test
    void configuredProxyDestinationQueuesAutoAdmissionWithoutADirectEndpoint() throws Exception {
        TestNetwork ready = readyNetwork(false);
        ready.peer.host = "";
        ready.peer.publicHost = "";
        ready.endpoint = null;
        NetworkConfig config = new NetworkConfig();
        config.proxyServers = List.of(" Beta ");
        startTraversal(ready, config);
        AtomicReference<TransferCall> transfer = new AtomicReference<TransferCall>();
        AtomicReference<String> pluginMessageChannel = new AtomicReference<String>();
        Player player = player(transfer, pluginMessageChannel);

        assertEquals(ServerConnectService.Result.QUEUED,
            ServerConnectService.connect(ready, player, "beta", "auto"));

        WireMessage.HandoffRequest request = ready.firstRequest();
        assertFalse(request.directTransfer());
        assertEquals(player.getUniqueId(), request.playerId());
        assertEquals(0, ready.validationRequests);
        assertFalse(ready.peer.useProxy);
        assertNull(transfer.get());
        assertNull(pluginMessageChannel.get());
    }

    @Test
    void explicitDirectModeProbesAConfiguredProxyDestination() throws Exception {
        TestNetwork ready = readyNetwork(false);
        NetworkConfig config = new NetworkConfig();
        config.proxyServers = List.of("beta");
        List<ScheduledTask> scheduled = startTraversal(ready, config);
        AtomicReference<TransferCall> transfer = new AtomicReference<TransferCall>();
        AtomicReference<String> pluginMessageChannel = new AtomicReference<String>();
        Player player = player(transfer, pluginMessageChannel);

        assertEquals(ServerConnectService.Result.QUEUED,
            ServerConnectService.connect(ready, player, "beta", "direct"));
        assertEquals(1, ready.validationRequests);
        assertTrue(ready.sent.isEmpty());

        ready.validation.complete(new EndpointValidation(EndpointValidation.State.VERIFIED, "available"));
        scheduled.getLast().task().run();

        assertTrue(ready.firstRequest().directTransfer());
        assertNull(transfer.get());
        assertNull(pluginMessageChannel.get());
    }

    @Test
    void endpointValidationFinishingAfterShutdownCannotQueueAnAdmission() throws Exception {
        TestNetwork ready = readyNetwork(false);
        List<ScheduledTask> scheduled = startTraversal(ready);
        AtomicReference<TransferCall> transfer = new AtomicReference<TransferCall>();
        AtomicReference<String> pluginMessageChannel = new AtomicReference<String>();
        Player player = player(transfer, pluginMessageChannel);
        assertEquals(ServerConnectService.Result.QUEUED,
            ServerConnectService.connect(ready, player, "beta", "auto"));

        Wormholes.traversalService.shutdown();
        ready.validation.complete(new EndpointValidation(EndpointValidation.State.VERIFIED, "available"));
        scheduled.getLast().task().run();

        assertTrue(ready.sent.stream().noneMatch(WireMessage.HandoffRequest.class::isInstance));
        assertEquals(0, Wormholes.traversalService.statsSnapshot().inFlight());
        assertNull(transfer.get());
        assertNull(pluginMessageChannel.get());
    }

    @Test
    void unsupportedDirectClientIsRejectedBeforeAdmission() throws Exception {
        TestNetwork ready = readyNetwork(false);
        List<ScheduledTask> scheduled = startTraversal(ready);
        protocolApi.version(ClientVersion.V_1_20_3);
        AtomicReference<TransferCall> transfer = new AtomicReference<TransferCall>();
        AtomicReference<String> pluginMessageChannel = new AtomicReference<String>();

        assertEquals(ServerConnectService.Result.TRANSFER_FAILED,
            ServerConnectService.connect(ready, player(transfer, pluginMessageChannel), "beta", "auto"));

        assertTrue(scheduled.isEmpty());
        assertTrue(ready.sent.isEmpty());
        assertEquals(0, ready.validationRequests);
        assertEquals(0, Wormholes.traversalService.statsSnapshot().inFlight());
        assertNull(transfer.get());
        assertNull(pluginMessageChannel.get());
    }

    @Test
    void missingClientRouteIsRejectedBeforeAdmission() throws Exception {
        TestNetwork ready = readyNetwork(false);
        ready.endpoint = null;
        List<ScheduledTask> scheduled = startTraversal(ready);
        AtomicReference<TransferCall> transfer = new AtomicReference<TransferCall>();
        AtomicReference<String> pluginMessageChannel = new AtomicReference<String>();

        assertEquals(ServerConnectService.Result.TRANSFER_FAILED,
            ServerConnectService.connect(ready, player(transfer, pluginMessageChannel), "beta", "auto"));

        assertTrue(scheduled.isEmpty());
        assertTrue(ready.sent.isEmpty());
        assertEquals(0, ready.validationRequests);
        assertEquals(0, Wormholes.traversalService.statsSnapshot().inFlight());
        assertNull(transfer.get());
        assertNull(pluginMessageChannel.get());
    }

    private TestNetwork readyNetwork(boolean proxy) {
        NetworkConfig.PeerEntry peer = route("beta");
        peer.useProxy = proxy;
        TestNetwork ready = new TestNetwork(tempDir.resolve("ready"), peer);
        network = ready;
        return ready;
    }

    private List<ScheduledTask> startTraversal(TestNetwork ready) throws ReflectiveOperationException {
        return startTraversal(ready, new NetworkConfig());
    }

    private List<ScheduledTask> startTraversal(TestNetwork ready, NetworkConfig config) throws ReflectiveOperationException {
        Wormholes.settings = new WormholesSettings(new MainConfig(), new ProjectionConfig(), new RenderConfig(), config);
        Wormholes.instance = serverPlugin();
        List<ScheduledTask> scheduled = new ArrayList<ScheduledTask>();
        Wormholes.traversalService = new TraversalService(ready, (entity, task, retired, delayTicks) -> {
            scheduled.add(new ScheduledTask(task, retired, delayTicks));
            return true;
        });
        return scheduled;
    }

    private static Wormholes serverPlugin() throws ReflectiveOperationException {
        Class<?> allocatorType = Class.forName("sun.misc.Unsafe");
        Field singleton = allocatorType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object allocator = singleton.get(null);
        Wormholes plugin = (Wormholes) allocatorType.getMethod("allocateInstance", Class.class).invoke(allocator, Wormholes.class);
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "getOnlineMode" -> Boolean.TRUE;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        Field serverField = JavaPlugin.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(plugin, server);
        Field loggerField = JavaPlugin.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        loggerField.set(plugin, LOGGER);
        return plugin;
    }

    private NetworkManager manager(String serverName) {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = serverName;
        config.advertiseHostOverride = "127.0.0.1";
        config.listenPort = 8901;
        return new NetworkManager(LOGGER, config, "26.2", "test", 25565, tempDir.resolve(serverName));
    }

    private static NetworkConfig.PeerEntry route(String name) {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = name;
        peer.host = "204.111.10.237";
        peer.publicHost = "204.111.10.237";
        peer.publicPort = 25566;
        return peer;
    }

    private static Player player(AtomicReference<TransferCall> transfer, AtomicReference<String> pluginMessageChannel) throws Exception {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("198.51.100.7"), 60123);
        UUID playerId = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getAddress" -> address;
                case "getName" -> "Traveler";
                case "getUniqueId" -> playerId;
                case "isOnline", "isValid" -> Boolean.TRUE;
                case "transfer" -> {
                    transfer.set(new TransferCall((String) arguments[0], ((Integer) arguments[1]).intValue()));
                    yield null;
                }
                case "sendPluginMessage" -> {
                    pluginMessageChannel.set((String) arguments[1]);
                    yield null;
                }
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "toString" -> "ServerConnectTestPlayer";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private record TransferCall(String host, int port) {
    }

    private record ScheduledTask(Runnable task, Runnable retired, long delayTicks) {
    }

    private static final class TestNetwork extends NetworkManager {
        private final NetworkConfig.PeerEntry peer;
        private final List<WireMessage> sent = new ArrayList<WireMessage>();
        private final CompletableFuture<EndpointValidation> validation = new CompletableFuture<EndpointValidation>();
        private GameEndpoint endpoint;
        private int validationRequests;

        private TestNetwork(Path directory, NetworkConfig.PeerEntry peer) {
            super(LOGGER, new NetworkConfig(), "26.2", "test", 25565, directory);
            this.peer = peer;
            this.endpoint = new GameEndpoint(peer.publicHost, peer.publicPort);
        }

        @Override
        public NetworkConfig.PeerEntry getPeer(String name) {
            return peer.name.equals(name) ? peer : null;
        }

        @Override
        public boolean isPeerReady(String name) {
            return peer.name.equals(name);
        }

        @Override
        public GameEndpoint playerEndpoint(String name, InetSocketAddress clientAddress) {
            return endpoint;
        }

        @Override
        public CompletableFuture<EndpointValidation> validatePlayerEndpoint(String peerName, GameEndpoint endpoint) {
            validationRequests++;
            return validation;
        }

        @Override
        public boolean send(String peerName, WireMessage message) {
            sent.add(message);
            return true;
        }

        private WireMessage.HandoffRequest firstRequest() {
            assertEquals(1L, sent.stream().filter(WireMessage.HandoffRequest.class::isInstance).count());
            return sent.stream().filter(WireMessage.HandoffRequest.class::isInstance)
                .map(WireMessage.HandoffRequest.class::cast).findFirst().orElseThrow();
        }
    }
}

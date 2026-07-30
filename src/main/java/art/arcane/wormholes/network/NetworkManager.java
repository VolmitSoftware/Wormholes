package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.network.replication.HashProbeScheduler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NetworkManager implements PeerConnection.Listener, PeerConnection.CompressionProvider {
    public record PeerStatus(String name, String address, String state, boolean dialer, long rttMillis, long lastInboundAgeMillis, String lastError) {
    }

    public record PeerSnapshot(String name, String transport, String remoteAddress, String compressionMode,
                               int dictVersion, String dictHashHex, long lastInboundAgeMillis, long rttMillis,
                               boolean handshakeComplete, boolean disconnected) {
    }

    public record DebugSnapshot(long rawWriteQueueFrames, long sidebandQueuedBytes, long sidebandQueuedCount,
                                long sidebandDroppedBytes, long sidebandDroppedCount) {
    }

    private static final long KEEPALIVE_INTERVAL_MS = 5_000L;
    private static final long STATUS_BRIDGE_INTERVAL_MS = 600L;
    private static final long STATUS_BRIDGE_FAST_INTERVAL_MS = 75L;
    private static final long STATUS_BRIDGE_FAIL_BACKOFF_MS = 5_000L;
    private static final long STATUS_RESPONSE_ACK_TTL_MS = 30_000L;
    static final long STATUS_REQUEST_RETRY_TTL_MS = 30_000L;
    private static final int STATUS_NONCE_WINDOW_CAPACITY = 256;
    private static final int STATUS_BRIDGE_FRAME_BUDGET_BYTES = 20_000;
    static final int STATUS_BRIDGE_REQUEST_BUDGET_MIN_BYTES = 4_000;
    static final int STATUS_BRIDGE_REQUEST_BUDGET_MAX_BYTES = 20_000;
    private static final int STATUS_BRIDGE_MAX_CONTINUATIONS = 32;

    private final Logger logger;
    private final int gamePort;
    private final NetworkIdentity identity;
    final MinecraftStatusBridge statusBridge;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final PeerLinkRegistry links = new PeerLinkRegistry();
    private final PeerDirectory directory;
    private final PeerTrustGate trust;
    private final PeerDialer dialer;
    private final PeerListener listener;
    private final SidebandQueue sideband;
    private final SidebandFragmenter fragmenter;
    private final SidebandPresence presence;
    private final DictionaryExchange dictionary;
    private final RelayRouter relay;
    private final NetworkStatusReporter reporter;
    final Map<String, PendingStatusRequest> pendingStatusRequests = new ConcurrentHashMap<>();
    private final Map<String, PendingStatusResponse> pendingStatusResponses = new ConcurrentHashMap<>();
    private final Map<String, Long> statusResponseAckNonces = new ConcurrentHashMap<>();
    private final Map<String, NonceWindow> statusRequestNonces = new ConcurrentHashMap<>();
    private final Map<String, NonceWindow> statusResponseNonces = new ConcurrentHashMap<>();
    private final Map<String, Object> statusPeerGates = new ConcurrentHashMap<>();
    final Map<String, Long> nextStatusAttempt = new ConcurrentHashMap<>();
    private final Set<String> statusPollFailing = ConcurrentHashMap.newKeySet();
    final Set<String> statusPollInFlight = ConcurrentHashMap.newKeySet();
    private final ChunkReplicationManager replicationManager;
    private final HashProbeScheduler hashProbeScheduler;

    private volatile NetworkConfig config;
    private volatile BiConsumer<String, WireMessage> messageSink;
    private volatile BiConsumer<String, Boolean> peerStateSink;
    private volatile ScheduledExecutorService scheduler;
    private volatile ExecutorService statusPollExecutor;
    private volatile int connectedPeers;

    public NetworkManager(Logger logger, NetworkConfig config, String mcVersion, String pluginVersion, int gamePort) {
        this(logger, config, mcVersion, pluginVersion, gamePort, Path.of("plugins", "Wormholes"));
    }

    public NetworkManager(Logger logger, NetworkConfig config, String mcVersion, String pluginVersion, int gamePort, Path dataDirectory) {
        this.logger = logger;
        this.config = config;
        this.gamePort = gamePort;
        this.dictionary = new DictionaryExchange(this, logger, dataDirectory, config);
        this.fragmenter = new SidebandFragmenter(this, logger);
        this.sideband = new SidebandQueue(this, logger);
        this.presence = new SidebandPresence();
        this.relay = new RelayRouter(this, logger);
        this.reporter = new NetworkStatusReporter(this);
        this.dialer = new PeerDialer(this, dataDirectory);
        this.listener = new PeerListener(this, logger, config.listenPort);
        try {
            this.identity = new NetworkIdentity(this, logger, IdentityStore.loadOrCreate(dataDirectory), mcVersion, pluginVersion);
            this.trust = new PeerTrustGate(this, logger, PeerTrustStore.loadOrCreate(dataDirectory));
            this.directory = new PeerDirectory(this, logger, PeerRouteStore.loadOrCreate(dataDirectory));
            this.statusBridge = new MinecraftStatusBridge(this);
        } catch (IOException e) {
            throw new IllegalStateException("Could not initialize Wormholes network identity", e);
        }
        this.replicationManager = new ChunkReplicationManager(this, new ChunkReplicationManager.ReplicationConfig(config.replication == null ? 4096L : config.replication.maxQueuedDiffsPerPeer));
        this.hashProbeScheduler = new HashProbeScheduler(this, replicationManager);
        if (config.replication != null) {
            this.hashProbeScheduler.configure(config.replication.hashProbeIntervalSec, config.replication.hashProbeChunksPerTick);
        }
    }

    NetworkConfig activeConfig() {
        return config;
    }

    int gamePort() {
        return gamePort;
    }

    PeerLinkRegistry links() {
        return links;
    }

    PeerDirectory directory() {
        return directory;
    }

    PeerTrustGate trust() {
        return trust;
    }

    PeerDialer dialer() {
        return dialer;
    }

    PeerListener listener() {
        return listener;
    }

    SidebandQueue sideband() {
        return sideband;
    }

    SidebandFragmenter fragmenter() {
        return fragmenter;
    }

    SidebandPresence presence() {
        return presence;
    }

    RelayRouter relay() {
        return relay;
    }

    public ChunkReplicationManager getReplicationManager() {
        return replicationManager;
    }

    public HashProbeScheduler getHashProbeScheduler() {
        return hashProbeScheduler;
    }

    @Override
    public WireCompression compression() {
        return dictionary.compression();
    }

    @Override
    public boolean compressionEnabled() {
        return dictionary.compressionEnabled();
    }

    @Override
    public CompressionDictionary currentDictionary() {
        return dictionary.currentDictionary();
    }

    @Override
    public void recordDictionarySample(WireMessageType type, byte[] payload) {
        dictionary.recordSample(type, payload);
    }

    @Override
    public void onDictionaryNegotiated(PeerConnection connection, int dictVersion) {
        dictionary.onDictionaryNegotiated(connection, dictVersion);
    }

    public String getAdvertiseHost() {
        return identity.advertiseHost();
    }

    public String getResolvedPublicHost() {
        return identity.resolvedPublicHost();
    }

    public int getBoundListenPort() {
        int bound = listener.boundPort();
        if (bound > 0) {
            return bound;
        }
        return config.listenPort;
    }

    public String getLocalName() {
        return identity.localName();
    }

    PrivateKey identityPrivateKey() {
        return identity.privateKey();
    }

    public String getPublicKey() {
        return identity.publicKey();
    }

    public String getPublicKeyFingerprint() {
        return identity.fingerprint();
    }

    public void setInferredAdvertiseHost(String host) {
        identity.setInferredAdvertiseHost(host);
    }

    public void trustPeer(String peerName, String publicKey) {
        trust.trustPeer(peerName, publicKey);
    }

    public void savePeer(NetworkConfig.PeerEntry peer) {
        if (peer == null || peer.name == null || peer.name.isBlank()) {
            return;
        }
        directory.store(peer);
        dialer.resetDialState(peer.name);
    }

    public String getListenAddress() {
        return "all interfaces:" + getBoundListenPort();
    }

    public void setMessageSink(BiConsumer<String, WireMessage> sink) {
        this.messageSink = sink;
    }

    public void setPeerStateSink(BiConsumer<String, Boolean> sink) {
        this.peerStateSink = sink;
    }

    public MinecraftStatusBridge statusBridge() {
        return statusBridge;
    }

    public void start() {
        NetworkConfig active = config;
        if (!active.enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }

        if (active.listenEnabled) {
            listener.bind(active);
        }

        statusPollExecutor = Executors.newVirtualThreadPerTaskExecutor();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "Wormholes-Net-Timer");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(dialer::scan, 250L, PeerDialer.SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(this::pollStatusBridges, 300L, STATUS_BRIDGE_FAST_INTERVAL_MS, TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(this::keepalive, KEEPALIVE_INTERVAL_MS, KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        long retrainSec = Math.max(30L, active.transport.compressionRetrainIntervalSec);
        executor.scheduleWithFixedDelay(this::maybeRetrainDictionary, retrainSec, retrainSec, TimeUnit.SECONDS);
        scheduler = executor;

        dictionary.loadPersisted(active);
        hashProbeScheduler.start();
        identity.resolvePublicHostAsync();

        int peerCount = directory.known().size();
        if (active.listenEnabled && listener.isRawListening()) {
            logger.info("net: " + getLocalName() + " listening on " + getListenAddress() + " (" + peerCount + " peer" + (peerCount == 1 ? "" : "s") + ")");
        } else if (active.listenEnabled) {
            logger.info("net: " + getLocalName() + " running sideband-only over game port " + gamePort + " (" + peerCount + " peer" + (peerCount == 1 ? "" : "s") + ")");
        } else {
            logger.info("net: " + getLocalName() + " running outbound-only (" + peerCount + " peer" + (peerCount == 1 ? "" : "s") + ")");
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            identity.shutdown();
            return;
        }
        identity.shutdown();
        hashProbeScheduler.stop();
        ScheduledExecutorService executor = scheduler;
        scheduler = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        ExecutorService pollExecutor = statusPollExecutor;
        statusPollExecutor = null;
        if (pollExecutor != null) {
            pollExecutor.shutdownNow();
        }
        listener.closeTransports();
        links.closeAll("shutdown");
        refreshConnectedPeers();
        presence.clear();
        nextStatusAttempt.clear();
        sideband.clear();
        pendingStatusRequests.clear();
        pendingStatusResponses.clear();
        statusResponseAckNonces.clear();
        statusRequestNonces.clear();
        statusResponseNonces.clear();
        statusPeerGates.clear();
        fragmenter.clear();
        statusPollInFlight.clear();
        dictionary.clearInboundTransfers();
        listener.clearBindings();
    }

    public void applyConfig(NetworkConfig next) {
        NetworkConfig previous = config;
        config = next;
        dictionary.applyConfig(next);
        if (next.replication != null) {
            replicationManager.applyConfig(new ChunkReplicationManager.ReplicationConfig(next.replication.maxQueuedDiffsPerPeer));
            hashProbeScheduler.configure(next.replication.hashProbeIntervalSec, next.replication.hashProbeChunksPerTick);
        }
        boolean overrideChanged = !blank(previous.advertiseHostOverride).equals(blank(next.advertiseHostOverride));
        boolean restartNeeded = previous.enabled != next.enabled
            || previous.listenEnabled != next.listenEnabled
            || previous.listenPort != next.listenPort
            || overrideChanged
            || !blank(previous.serverName).equals(blank(next.serverName))
            || previous.transport.udsEnabled != next.transport.udsEnabled
            || !blank(previous.transport.udsDir).equals(blank(next.transport.udsDir));
        if (overrideChanged) {
            identity.forgetDetectedPublicHost();
        }
        if (restartNeeded) {
            stop();
            start();
            return;
        }
        for (Map.Entry<String, PeerConnection> entry : links.readyEntries()) {
            if (directory.find(entry.getKey()) == null) {
                entry.getValue().close("peer removed from config");
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isPeerReady(String name) {
        return isRawPeerReady(name) || isStatusPeerReady(name);
    }

    public boolean isSidebandOnlyPeer(String name) {
        return !isRawPeerReady(name) && isStatusPeerReady(name);
    }

    String privatePlayerEndpoint(String name) {
        NetworkConfig.PeerEntry peer = directory.find(name);
        if (peer == null) {
            return null;
        }
        PeerConnection connection = links.ready(name);
        InetSocketAddress rawPeerAddress = null;
        boolean loopbackTransport = false;
        if (connection != null && connection.getState() == PeerConnection.State.READY) {
            loopbackTransport = connection.channel().isLoopback();
            SocketAddress remoteAddress = connection.channel().remoteAddress();
            if (remoteAddress instanceof InetSocketAddress inetAddress) {
                rawPeerAddress = inetAddress;
            }
        }
        String statusGameHost = !isRawPeerReady(name) && isStatusPeerReady(name)
            ? presence.reachableGameHost(name)
            : null;
        return PeerEndpointResolver.privateGameHost(
            peer,
            statusGameHost,
            rawPeerAddress,
            loopbackTransport
        );
    }

    boolean isRawPeerReady(String name) {
        return links.isRawReady(name);
    }

    boolean isStatusPeerReady(String name) {
        return presence.isReady(name);
    }

    public void sendToPeers(Collection<String> peerNames, WireMessage message) {
        if (message instanceof WireMessage.Routed) {
            return;
        }
        if (WireMessage.Routed.isRelayAnnouncement(message.type())) {
            for (String name : peerNames) {
                send(name, message);
            }
            return;
        }
        OutboundFrame frame = new OutboundFrame(message);
        for (String name : peerNames) {
            PeerConnection connection = links.ready(name);
            if (connection != null && connection.getState() == PeerConnection.State.READY) {
                connection.send(frame);
            } else {
                send(name, message);
            }
        }
    }

    public boolean send(String peerName, WireMessage message) {
        if (message instanceof WireMessage.Routed) {
            return false;
        }
        PeerConnection connection = links.ready(peerName);
        if (connection != null) {
            if (WireMessage.Routed.isRelayAnnouncement(message.type())) {
                return relay.sendRouted(connection, peerName, RelayRouter.ROUTE_TTL, message);
            }
            return connection.send(message);
        }
        NetworkConfig.PeerEntry peer = directory.find(peerName);
        if (canQueueStatusBridge(peer)) {
            if (WireMessage.Routed.isRelayAnnouncement(message.type())) {
                return relay.enqueueRouted(peerName, peerName, RelayRouter.ROUTE_TTL, message);
            }
            OutboundFrame frame = new OutboundFrame(message);
            dictionary.recordFrameSample(frame);
            if (!sideband.enqueue(peerName, frame)) {
                return false;
            }
            if (SidebandQueue.isLatencyCritical(message)) {
                nudgeStatusPoll(peerName);
            }
            return true;
        }
        String nextHop = relay.nextHop(peerName);
        if (nextHop == null || nextHop.equals(peerName) || nextHop.equals(getLocalName())) {
            return false;
        }
        PeerConnection route = links.ready(nextHop);
        if (route != null) {
            return relay.sendRouted(route, peerName, RelayRouter.ROUTE_TTL, message);
        }
        NetworkConfig.PeerEntry routedPeer = directory.find(nextHop);
        return routedPeer != null && canQueueStatusBridge(routedPeer)
            && relay.enqueueRouted(nextHop, peerName, RelayRouter.ROUTE_TTL, message);
    }

    public NetworkConfig.PeerEntry getPeer(String name) {
        return directory.find(name);
    }

    public List<PeerStatus> status() {
        return reporter.status();
    }

    public List<PeerSnapshot> peerSnapshots() {
        return reporter.peerSnapshots();
    }

    public int connectedPeers() {
        return connectedPeers;
    }

    public int knownPeerCount() {
        return directory.all().size();
    }

    private void refreshConnectedPeers() {
        connectedPeers = links.readyCount();
    }

    public WireCompression wireCompressionMetrics() {
        return dictionary.compression();
    }

    public DictionarySampleCollector dictionarySampleCollector() {
        return dictionary.sampleCollector();
    }

    public DebugSnapshot debugSnapshot() {
        return reporter.debugSnapshot();
    }

    public List<String> diagnostics() {
        return reporter.diagnostics();
    }

    public MinecraftStatusBridge.StatusPacket handleStatusBridgeRequest(MinecraftStatusBridge.StatusPacket request) {
        String sourceServer = request.sourceServer();
        if (isRawPeerReady(sourceServer) || !acceptStatusBridgePacket(request, null)) {
            return null;
        }
        synchronized (statusPeerGate(sourceServer)) {
            if (isRawPeerReady(sourceServer)) {
                return null;
            }
            markStatusBridgeReady(sourceServer, -1L);
            long now = System.currentTimeMillis();
            PendingStatusResponse pendingResponse = pendingStatusResponses.get(sourceServer);
            if (pendingResponse != null && request.ackNonce() == pendingResponse.packet().nonce()) {
                if (pendingStatusResponses.remove(sourceServer, pendingResponse)) {
                    pendingResponse.batch().commit();
                }
                pendingResponse = null;
            } else if (pendingResponse != null && pendingResponse.createdAtMillis() + STATUS_RESPONSE_ACK_TTL_MS < now) {
                if (pendingStatusResponses.remove(sourceServer, pendingResponse)) {
                    pendingResponse.batch().requeue();
                }
                pendingResponse = null;
            }
            if (pendingResponse != null) {
                return pendingResponse.packet();
            }

            NonceWindow requestNonces = statusRequestNonces.computeIfAbsent(sourceServer,
                ignored -> new NonceWindow(STATUS_NONCE_WINDOW_CAPACITY));
            if (!requestNonces.contains(request.nonce())) {
                if (!receiveStatusBridgeMessages(sourceServer, request.messages())) {
                    return null;
                }
                requestNonces.add(request.nonce());
            }

            SidebandOutbox.DrainBatch batch = sideband.drain(sourceServer, STATUS_BRIDGE_FRAME_BUDGET_BYTES);
            try {
                MinecraftStatusBridge.StatusPacket response = createStatusBridgePacket(sourceServer, batch.messages(), request.nonce());
                if (batch.messages().isEmpty()) {
                    batch.commit();
                } else {
                    pendingStatusResponses.put(sourceServer, new PendingStatusResponse(response, batch, now));
                }
                return response;
            } catch (RuntimeException e) {
                batch.requeue();
                throw e;
            }
        }
    }

    void logStatusBridgeFailure(String message, Throwable throwable) {
        logger.log(Level.WARNING, "net: " + message, throwable);
    }

    boolean handleStatusBridgeResponse(String expectedPeerName, MinecraftStatusBridge.StatusPacket response, long rttMillis) {
        return handleStatusBridgeResponse(expectedPeerName, response, rttMillis, null);
    }

    boolean handleStatusBridgeResponse(String expectedPeerName,
                                       MinecraftStatusBridge.StatusPacket response,
                                       long rttMillis, String reachableGameHost) {
        if (response == null || isRawPeerReady(expectedPeerName) || !acceptStatusBridgePacket(response, expectedPeerName)) {
            return false;
        }
        String sourceServer = response.sourceServer();
        if (reachableGameHost != null && !reachableGameHost.isBlank()) {
            presence.rememberReachableGameHost(sourceServer, reachableGameHost);
        }
        synchronized (statusPeerGate(sourceServer)) {
            if (isRawPeerReady(sourceServer)) {
                return false;
            }
            markStatusBridgeReady(sourceServer, rttMillis);
            NonceWindow responseNonces = statusResponseNonces.computeIfAbsent(sourceServer,
                ignored -> new NonceWindow(STATUS_NONCE_WINDOW_CAPACITY));
            if (!responseNonces.contains(response.nonce())) {
                if (!receiveStatusBridgeMessages(sourceServer, response.messages())) {
                    return false;
                }
                responseNonces.add(response.nonce());
            }
            statusResponseAckNonces.put(sourceServer, response.nonce());
            return true;
        }
    }

    MinecraftStatusBridge.StatusPacket createStatusBridgePacket(String targetServer, List<MinecraftStatusBridge.EncodedMessage> messages) {
        return createStatusBridgePacket(targetServer, messages, statusResponseAckNonces.getOrDefault(targetServer, 0L));
    }

    private MinecraftStatusBridge.StatusPacket createStatusBridgePacket(String targetServer,
                                                                         List<MinecraftStatusBridge.EncodedMessage> messages,
                                                                         long ackNonce) {
        return MinecraftStatusBridge.create(identity.localName(), targetServer, identity.mcVersion(), identity.pluginVersion(),
            identity.advertiseHost(), gamePort, identity.publicKeyBytes(), identity.privateKey(), ackNonce, messages);
    }

    @Override
    public boolean approvePeer(PeerConnection connection, String peerName, String peerMcVersion, String peerPluginVersion, byte[] publicKey) {
        if (peerName == null || peerName.isBlank() || peerName.equals(getLocalName())) {
            return false;
        }
        return trust.approveConnection(peerName, publicKey);
    }

    private boolean acceptStatusBridgePacket(MinecraftStatusBridge.StatusPacket packet, String expectedSource) {
        if (!running.get() || !config.enabled) {
            return false;
        }
        String sourceServer = packet.sourceServer();
        if (sourceServer == null || sourceServer.isBlank() || sourceServer.equals(getLocalName())) {
            return false;
        }
        if (expectedSource != null && !expectedSource.equals(sourceServer)) {
            return false;
        }
        String targetServer = packet.targetServer();
        if (targetServer != null && !targetServer.isBlank() && !targetServer.equals(getLocalName())) {
            return false;
        }
        if (!packet.verify()) {
            logger.warning("net: rejecting status sideband from " + sourceServer + " because authentication failed");
            return false;
        }
        if (!trust.approveSideband(sourceServer, packet.publicKey())) {
            return false;
        }
        directory.learnFromStatusPacket(packet);
        return true;
    }

    @Override
    public void onReady(PeerConnection connection) {
        links.removePending(connection);
        String name = connection.getPeerName();
        boolean sidebandWasReady = isStatusPeerReady(name);
        dialer.resetDialState(name);
        directory.learnFromConnection(connection);

        if (!links.promote(name, connection, this::initiatorName)) {
            refreshConnectedPeers();
            return;
        }

        refreshConnectedPeers();

        synchronized (statusPeerGate(name)) {
            clearStatusSidebandLocked(name);
        }

        logger.info("net: peer " + name + " connected (" + (connection.isDialer() ? "dialed" : "accepted") + " " + connection.describeRemote() + ")");
        BiConsumer<String, Boolean> sink = peerStateSink;
        if (sidebandWasReady && sink != null) {
            sink.accept(name, false);
        }
        dictionary.onPeerReady(connection);
        relay.sendRelayedDirectoriesTo(name);
        if (sink != null) {
            sink.accept(name, true);
        }
    }

    private String initiatorName(PeerConnection connection) {
        return connection.isDialer() ? getLocalName() : connection.getPeerName();
    }

    @Override
    public void onMessage(PeerConnection connection, WireMessage message) {
        if (dictionary.handleMessage(connection, message)) {
            return;
        }
        if (message instanceof WireMessage.Routed routed) {
            relay.handleRouted(connection.getPeerName(), routed);
            return;
        }
        deliverMessage(connection.getPeerName(), message);
    }

    void maybeRetrainDictionary() {
        dictionary.purgeExpired(System.currentTimeMillis());
        dictionary.maybeRetrain();
    }

    void retrainNow() {
        dictionary.retrainNow();
    }

    private boolean receiveStatusBridgeMessages(String peerName, List<WireMessage> messages) {
        for (WireMessage message : messages) {
            if (!receiveStatusBridgeMessage(peerName, message)) {
                return false;
            }
        }
        return true;
    }

    private boolean receiveStatusBridgeMessage(String peerName, WireMessage message) {
        SidebandFragmenter.Reassembled reassembledMessage = null;
        if (message instanceof WireMessage.SidebandFragment fragment) {
            SidebandFragmenter.Result fragmentResult = fragmenter.receive(peerName, fragment);
            if (!fragmentResult.accepted()) {
                return false;
            }
            reassembledMessage = fragmentResult.completed();
            if (reassembledMessage == null) {
                return true;
            }
            message = reassembledMessage.message();
        }
        boolean accepted;
        if (message instanceof WireMessage.Routed routed) {
            accepted = relay.handleRouted(peerName, routed);
        } else {
            deliverMessage(peerName, message);
            accepted = true;
        }
        if (accepted && reassembledMessage != null) {
            fragmenter.discard(reassembledMessage);
        }
        return accepted;
    }

    private void markStatusBridgeReady(String peerName, long rttMillis) {
        boolean wasReady = isPeerReady(peerName);
        long now = System.currentTimeMillis();
        presence.mark(peerName, now, rttMillis);
        dialer.clearFailures(peerName);
        if (!wasReady) {
            logger.info("net: peer " + peerName + " connected (game-port status sideband)");
            relay.sendRelayedDirectoriesTo(peerName);
            BiConsumer<String, Boolean> sink = peerStateSink;
            if (sink != null) {
                sink.accept(peerName, true);
            }
        }
    }

    void deliverMessage(String peerName, WireMessage message) {
        BiConsumer<String, WireMessage> sink = messageSink;
        if (sink != null) {
            sink.accept(peerName, message);
        }
    }

    @Override
    public void onClosed(PeerConnection connection, String reason) {
        dictionary.onPeerClosed(connection);
        links.removePending(connection);
        String name = connection.getPeerName();
        boolean wasReady = name != null && links.removeReady(name, connection);
        refreshConnectedPeers();
        if (wasReady) {
            logger.info("net: peer " + name + " disconnected: " + reason);
            relay.forgetVia(name);
            if (!isStatusPeerReady(name)) {
                BiConsumer<String, Boolean> sink = peerStateSink;
                if (sink != null) {
                    sink.accept(name, false);
                }
            }
        }
        if (name != null && connection.isDialer()) {
            dialer.registerFailure(name);
            if (!wasReady && reason != null && !reason.startsWith("duplicate connection") && !"shutdown".equals(reason)) {
                dialer.setLastError(name, connection.describeRemote() + " - " + reason);
            }
        }
    }

    void acceptInbound(PeerTransport.PeerChannel channel) {
        PeerConnection connection = new PeerConnection(channel, false, identity.snapshot(), null, null, this, this);
        links.addPending(connection);
        connection.start();
    }

    void startDialedConnection(PeerTransport.PeerChannel channel, String peerName) {
        PeerConnection connection = new PeerConnection(channel, true, identity.snapshot(), peerName, trust.key(peerName), this, this);
        links.addPending(connection);
        connection.start();
    }

    private void pollStatusBridges() {
        ExecutorService exec = statusPollExecutor;
        if (exec == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String localName = getLocalName();
        for (NetworkConfig.PeerEntry peer : directory.all()) {
            if (peer.name == null || peer.name.isBlank() || peer.name.equals(localName) || !PeerDirectory.isDialable(peer)) {
                continue;
            }
            if (isRawPeerReady(peer.name) || !PeerDirectory.canUseStatusBridge(peer)) {
                continue;
            }
            if (now < nextStatusAttempt.getOrDefault(peer.name, 0L)) {
                continue;
            }
            if (statusPollInFlight.contains(peer.name)) {
                continue;
            }
            try {
                exec.execute(() -> pollStatusBridge(peer, System.currentTimeMillis(), false));
            } catch (RejectedExecutionException ignored) {
            }
        }
    }

    void nudgeStatusPoll(String peerName) {
        ExecutorService exec = statusPollExecutor;
        if (exec == null) {
            return;
        }
        NetworkConfig.PeerEntry peer = directory.find(peerName);
        if (peer == null || peer.name.equals(getLocalName()) || isRawPeerReady(peerName) || !PeerDirectory.canUseStatusBridge(peer)) {
            return;
        }
        nextStatusAttempt.put(peerName, 0L);
        try {
            exec.execute(() -> pollStatusBridge(peer, System.currentTimeMillis(), true));
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void pollStatusBridge(NetworkConfig.PeerEntry peer, long now, boolean ignoreSchedule) {
        if (isRawPeerReady(peer.name) || !PeerDirectory.canUseStatusBridge(peer)) {
            return;
        }
        if (!ignoreSchedule && now < nextStatusAttempt.getOrDefault(peer.name, 0L)) {
            return;
        }
        if (!statusPollInFlight.add(peer.name)) {
            return;
        }
        try {
            for (int continuation = 0; continuation < STATUS_BRIDGE_MAX_CONTINUATIONS; continuation++) {
                if (isRawPeerReady(peer.name)) {
                    return;
                }
                if (!pollStatusBridgeOnce(peer)) {
                    return;
                }
            }
        } finally {
            statusPollInFlight.remove(peer.name);
        }
    }

    boolean pollStatusBridgeOnce(NetworkConfig.PeerEntry peer) {
        if (isRawPeerReady(peer.name)) {
            return false;
        }
        long started = System.currentTimeMillis();
        nextStatusAttempt.put(peer.name, started + STATUS_BRIDGE_FAST_INTERVAL_MS);
        PendingStatusRequest pendingRequest;
        synchronized (statusPeerGate(peer.name)) {
            if (isRawPeerReady(peer.name)) {
                return false;
            }
            pendingRequest = pendingStatusRequests.get(peer.name);
            if (pendingRequest == null) {
                SidebandOutbox.DrainBatch batch = sideband.drain(peer.name, statusRequestBudgetFor(peer.name));
                try {
                    List<MinecraftStatusBridge.EncodedMessage> drained = batch.messages();
                    MinecraftStatusBridge.StatusPacket packet = createStatusBridgePacket(peer.name, drained);
                    pendingRequest = new PendingStatusRequest(packet, batch, drained, System.currentTimeMillis());
                    pendingStatusRequests.put(peer.name, pendingRequest);
                } catch (RuntimeException e) {
                    batch.requeue();
                    throw e;
                }
            }
        }
        List<MinecraftStatusBridge.EncodedMessage> messages = pendingRequest.messages();
        try {
            MinecraftStatusBridge.PollResult poll = statusBridge.pollWithEndpoint(peer, pendingRequest.packet());
            MinecraftStatusBridge.StatusPacket response = poll.packet();
            if (!handleStatusBridgeResponse(
                peer.name,
                response,
                System.currentTimeMillis() - started,
                poll.host()
            )) {
                throw new IOException("status sideband response was rejected");
            }
            if (isRawPeerReady(peer.name)) {
                return false;
            }
            boolean requestAcknowledged = response.ackNonce() == pendingRequest.packet().nonce();
            synchronized (statusPeerGate(peer.name)) {
                if (isRawPeerReady(peer.name)) {
                    return false;
                }
                PendingStatusRequest current = pendingStatusRequests.get(peer.name);
                if (current == pendingRequest) {
                    if (requestAcknowledged) {
                        pendingStatusRequests.remove(peer.name, pendingRequest);
                        pendingRequest.batch().commit();
                    } else {
                        MinecraftStatusBridge.StatusPacket rotated = createStatusBridgePacket(peer.name, messages);
                        pendingStatusRequests.put(peer.name,
                            new PendingStatusRequest(rotated, pendingRequest.batch(), messages, System.currentTimeMillis()));
                    }
                }
            }
            recordStatusRequestSuccess(peer.name);
            dialer.clearLastError(peer.name);
            if (statusPollFailing.remove(peer.name)) {
                logger.info("net: status sideband to " + peer.name + " recovered");
            }
            boolean dataFlowing = !messages.isEmpty()
                || (response != null && !response.messages().isEmpty())
                || sideband.pending(peer.name)
                || !requestAcknowledged;
            if (!dataFlowing) {
                nextStatusAttempt.put(peer.name, System.currentTimeMillis() + STATUS_BRIDGE_INTERVAL_MS);
                return false;
            }
            return sideband.pending(peer.name)
                || !response.messages().isEmpty()
                || !requestAcknowledged;
        } catch (IOException | RuntimeException e) {
            boolean undelivered = e instanceof MinecraftStatusBridge.RequestUndeliveredException;
            long failedAtMillis = System.currentTimeMillis();
            synchronized (statusPeerGate(peer.name)) {
                if ((undelivered || failedAtMillis - pendingRequest.createdAtMillis() >= STATUS_REQUEST_RETRY_TTL_MS)
                    && pendingStatusRequests.remove(peer.name, pendingRequest)) {
                    pendingRequest.batch().requeue();
                }
            }
            if (isRawPeerReady(peer.name)) {
                return false;
            }
            recordStatusRequestFailure(peer.name);
            nextStatusAttempt.put(peer.name, System.currentTimeMillis() + STATUS_BRIDGE_FAIL_BACKOFF_MS);
            String failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (!isRawPeerReady(peer.name)) {
                dialer.setLastError(peer.name, PeerDirectory.statusBridgeAddress(peer) + " - " + failure);
            }
            if (!messages.isEmpty() && statusPollFailing.add(peer.name)) {
                logger.warning("net: status sideband data poll to " + peer.name + " failed (" + failure + "); request carried "
                    + messages.size() + " message(s) ~" + drainedFrameBytes(messages) + " frame bytes. Large projection/entity payloads can exceed the game-port status limit -- open the raw Wormholes port " + getBoundListenPort() + " on both servers for reliable high-throughput streaming.");
            }
            return false;
        }
    }

    int statusRequestBudgetFor(String peerName) {
        return sideband.budgetFor(peerName);
    }

    void recordStatusRequestSuccess(String peerName) {
        sideband.recordSuccess(peerName);
    }

    void recordStatusRequestFailure(String peerName) {
        sideband.recordFailure(peerName);
    }

    private static int drainedFrameBytes(List<MinecraftStatusBridge.EncodedMessage> messages) {
        int total = 0;
        for (int i = 0; i < messages.size(); i++) {
            total += messages.get(i).frame().length;
        }
        return total;
    }

    private void keepalive() {
        long now = System.currentTimeMillis();
        expireStatusBridgePeers(now);
        fragmenter.expire(now);
        expirePendingStatusDeliveries(now);
        OutboundFrame ping = new OutboundFrame(new WireMessage.Ping(now));
        for (PeerConnection connection : links.readyConnections()) {
            connection.send(ping);
        }
    }

    List<MinecraftStatusBridge.EncodedMessage> drainStatusOutbox(String peerName, int budgetBytes) {
        SidebandOutbox.DrainBatch batch = sideband.drain(peerName, budgetBytes);
        List<MinecraftStatusBridge.EncodedMessage> messages = batch.messages();
        batch.commit();
        return messages;
    }

    long statusOutboxQueuedBytes(String peerName) {
        return sideband.queuedBytes(peerName);
    }

    long statusOutboxQueuedCount(String peerName) {
        return sideband.queuedCount(peerName);
    }

    long statusOutboxDroppedBytes(String peerName) {
        return sideband.droppedBytes(peerName);
    }

    long statusOutboxDroppedCount(String peerName) {
        return sideband.droppedCount(peerName);
    }

    boolean hasPendingStatusResponse(String peerName) {
        return pendingStatusResponses.containsKey(peerName);
    }

    void expirePendingStatusDeliveries(long nowMillis) {
        for (String peerName : new HashSet<>(pendingStatusResponses.keySet())) {
            synchronized (statusPeerGate(peerName)) {
                PendingStatusResponse pendingResponse = pendingStatusResponses.get(peerName);
                if (pendingResponse != null && pendingResponse.createdAtMillis() + STATUS_RESPONSE_ACK_TTL_MS < nowMillis
                    && pendingStatusResponses.remove(peerName, pendingResponse)) {
                    pendingResponse.batch().requeue();
                }
            }
        }
    }

    private Object statusPeerGate(String peerName) {
        return statusPeerGates.computeIfAbsent(peerName, ignored -> new Object());
    }

    private void clearStatusSidebandLocked(String peerName) {
        pendingStatusRequests.remove(peerName);
        pendingStatusResponses.remove(peerName);
        sideband.forget(peerName);
        statusResponseAckNonces.remove(peerName);
        statusRequestNonces.remove(peerName);
        statusResponseNonces.remove(peerName);
        presence.forget(peerName);
        nextStatusAttempt.remove(peerName);
        statusPollFailing.remove(peerName);
        fragmenter.forget(peerName);
    }

    private void expireStatusBridgePeers(long now) {
        for (String peerName : presence.expire(now)) {
            if (!isRawPeerReady(peerName)) {
                logger.info("net: peer " + peerName + " disconnected: game-port status sideband timed out");
                BiConsumer<String, Boolean> sink = peerStateSink;
                if (sink != null) {
                    sink.accept(peerName, false);
                }
            }
        }
    }

    boolean canQueueStatusBridge(NetworkConfig.PeerEntry peer) {
        return peer != null && !isRawPeerReady(peer.name)
            && (PeerDirectory.canUseStatusBridge(peer) || isStatusPeerReady(peer.name));
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    record PendingStatusRequest(MinecraftStatusBridge.StatusPacket packet,
                                SidebandOutbox.DrainBatch batch,
                                List<MinecraftStatusBridge.EncodedMessage> messages,
                                long createdAtMillis) {
    }

    private record PendingStatusResponse(MinecraftStatusBridge.StatusPacket packet,
                                         SidebandOutbox.DrainBatch batch,
                                         long createdAtMillis) {
    }

    static final class NonceWindow {
        private final int capacity;
        private final ArrayDeque<Long> order = new ArrayDeque<>();
        private final Set<Long> seen = new HashSet<>();

        NonceWindow(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            this.capacity = capacity;
        }

        synchronized boolean contains(long nonce) {
            return seen.contains(nonce);
        }

        synchronized boolean add(long nonce) {
            if (!seen.add(nonce)) {
                return false;
            }
            order.addLast(nonce);
            while (order.size() > capacity) {
                seen.remove(order.removeFirst());
            }
            return true;
        }

        synchronized int size() {
            return seen.size();
        }
    }
}

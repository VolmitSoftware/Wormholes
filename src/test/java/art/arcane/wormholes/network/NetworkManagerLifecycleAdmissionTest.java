package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class NetworkManagerLifecycleAdmissionTest {
    private static final Logger LOGGER = Logger.getLogger("NetworkManagerLifecycleAdmissionTest");
    private static final String ALPHA = "alpha";
    private static final String BETA = "beta";

    @TempDir
    Path tempDir;

    private final List<NetworkManager> managers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (NetworkManager manager : managers) {
            manager.stop();
        }
        managers.clear();
    }

    @Test
    void stopWaitsForAnAdmittedStatusRequestBeforeClearingPublishedState() throws Exception {
        NetworkManager alpha = manager(ALPHA);
        NetworkManager beta = manager(BETA);
        CountDownLatch messageEntered = new CountDownLatch(1);
        CountDownLatch releaseMessage = new CountDownLatch(1);
        CountDownLatch stopStarted = new CountDownLatch(1);
        CountDownLatch stopFinished = new CountDownLatch(1);
        AtomicReference<MinecraftStatusBridge.StatusPacket> result = new AtomicReference<>();
        AtomicReference<Throwable> requestFailure = new AtomicReference<>();
        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        beta.setMessageSink((peerName, message) -> {
            messageEntered.countDown();
            awaitUnchecked(releaseMessage);
        });
        MinecraftStatusBridge.StatusPacket request = alpha.createStatusBridgePacket(
            BETA,
            List.of(encoded(new WireMessage.Ping(1L)))
        );

        Thread requestThread = startCaptured("status-request", requestFailure,
            () -> result.set(beta.handleStatusBridgeRequest(request)));
        await(messageEntered);
        Thread stopThread = startCaptured("status-request-stop", stopFailure, () -> {
            stopStarted.countDown();
            beta.stop();
            stopFinished.countDown();
        });
        await(stopStarted);

        assertFalse(stopFinished.await(150L, TimeUnit.MILLISECONDS));
        assertTrue(beta.isPeerReady(ALPHA));

        releaseMessage.countDown();
        await(stopFinished);
        join(requestThread);
        join(stopThread);

        assertNull(requestFailure.get());
        assertNull(stopFailure.get());
        assertNull(result.get());
        assertFalse(beta.isPeerReady(ALPHA));
        assertFalse(beta.hasPendingStatusResponse(ALPHA));
        assertNull(beta.handleStatusBridgeRequest(request));
    }

    @Test
    void stopWaitsForAnAdmittedStatusResponseBeforeClearingPublishedState() throws Exception {
        NetworkManager alpha = manager(ALPHA);
        NetworkManager beta = manager(BETA);
        CountDownLatch messageEntered = new CountDownLatch(1);
        CountDownLatch releaseMessage = new CountDownLatch(1);
        CountDownLatch stopStarted = new CountDownLatch(1);
        CountDownLatch stopFinished = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(false);
        AtomicReference<Throwable> responseFailure = new AtomicReference<>();
        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        beta.setMessageSink((peerName, message) -> {
            messageEntered.countDown();
            awaitUnchecked(releaseMessage);
        });
        MinecraftStatusBridge.StatusPacket response = alpha.createStatusBridgePacket(
            BETA,
            List.of(encoded(new WireMessage.Ping(2L)))
        );

        Thread responseThread = startCaptured("status-response", responseFailure,
            () -> accepted.set(beta.handleStatusBridgeResponse(ALPHA, response, 1L, "192.0.2.10")));
        await(messageEntered);
        Thread stopThread = startCaptured("status-response-stop", stopFailure, () -> {
            stopStarted.countDown();
            beta.stop();
            stopFinished.countDown();
        });
        await(stopStarted);

        assertFalse(stopFinished.await(150L, TimeUnit.MILLISECONDS));
        assertTrue(beta.isPeerReady(ALPHA));

        releaseMessage.countDown();
        await(stopFinished);
        join(responseThread);
        join(stopThread);

        assertNull(responseFailure.get());
        assertNull(stopFailure.get());
        assertFalse(accepted.get());
        assertFalse(beta.isPeerReady(ALPHA));
        assertFalse(beta.handleStatusBridgeResponse(ALPHA, response, 1L, "192.0.2.10"));
    }

    @Test
    void stopWaitsForAnAdmittedRawReadyPublicationBeforeClearingTheLink() throws Exception {
        NetworkManager beta = manager(BETA);
        PeerConnection connection = readyConnection(beta, BETA, ALPHA);
        CountDownLatch readyPublicationEntered = new CountDownLatch(1);
        CountDownLatch releaseReadyPublication = new CountDownLatch(1);
        CountDownLatch stopStarted = new CountDownLatch(1);
        CountDownLatch stopFinished = new CountDownLatch(1);
        AtomicReference<Throwable> readyFailure = new AtomicReference<>();
        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        beta.setPeerStateSink((peerName, ready) -> {
            if (ready) {
                readyPublicationEntered.countDown();
                awaitUnchecked(releaseReadyPublication);
            }
        });

        Thread readyThread = startCaptured("raw-ready", readyFailure, () -> beta.onReady(connection));
        await(readyPublicationEntered);
        Thread stopThread = startCaptured("raw-ready-stop", stopFailure, () -> {
            stopStarted.countDown();
            beta.stop();
            stopFinished.countDown();
        });
        await(stopStarted);

        assertFalse(stopFinished.await(150L, TimeUnit.MILLISECONDS));
        assertTrue(beta.isPeerReady(ALPHA));
        assertEquals(1, beta.connectedPeers());

        releaseReadyPublication.countDown();
        await(stopFinished);
        join(readyThread);
        join(stopThread);

        assertNull(readyFailure.get());
        assertNull(stopFailure.get());
        assertFalse(beta.isPeerReady(ALPHA));
        assertEquals(0, beta.connectedPeers());
        assertEquals(PeerConnection.State.CLOSED, connection.getState());
    }

    @Test
    void statusMessageCallbackCanStopWithoutDeadlockingOrRepublishingState() throws Exception {
        NetworkManager alpha = manager(ALPHA);
        NetworkManager beta = manager(BETA);
        AtomicBoolean callbackReturned = new AtomicBoolean(false);
        beta.setMessageSink((peerName, message) -> {
            beta.stop();
            callbackReturned.set(true);
        });
        MinecraftStatusBridge.StatusPacket request = alpha.createStatusBridgePacket(
            BETA,
            List.of(encoded(new WireMessage.Ping(3L)))
        );

        MinecraftStatusBridge.StatusPacket result = beta.handleStatusBridgeRequest(request);

        assertNull(result);
        assertTrue(callbackReturned.get());
        assertFalse(beta.isRunning());
        assertFalse(beta.isPeerReady(ALPHA));
        assertFalse(beta.hasPendingStatusResponse(ALPHA));
    }

    @Test
    void rawReadyCallbackCanStopWithoutDeadlockingOrLeavingTheLinkPublished() throws Exception {
        NetworkManager beta = manager(BETA);
        PeerConnection connection = readyConnection(beta, BETA, ALPHA);
        AtomicBoolean callbackReturned = new AtomicBoolean(false);
        beta.setPeerStateSink((peerName, ready) -> {
            if (ready) {
                beta.stop();
                callbackReturned.set(true);
            }
        });

        beta.onReady(connection);

        assertTrue(callbackReturned.get());
        assertFalse(beta.isRunning());
        assertFalse(beta.isPeerReady(ALPHA));
        assertEquals(0, beta.connectedPeers());
        assertEquals(PeerConnection.State.CLOSED, connection.getState());
    }

    private NetworkManager manager(String name) throws Exception {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.listenEnabled = false;
        config.serverName = name;
        config.advertiseHostOverride = "127.0.0.1";
        NetworkManager manager = new NetworkManager(LOGGER, config, "26.2", "test", 25565,
            tempDir.resolve(name));
        manager.savePeer(route(ALPHA.equals(name) ? BETA : ALPHA));
        setRunning(manager);
        managers.add(manager);
        return manager;
    }

    private static NetworkConfig.PeerEntry route(String peerName) {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = peerName;
        peer.publicHost = "127.0.0.1";
        peer.publicPort = 25565;
        return peer;
    }

    private static MinecraftStatusBridge.EncodedMessage encoded(WireMessage message) throws Exception {
        return new MinecraftStatusBridge.EncodedMessage(message, WireCodec.encodeFrame(message));
    }

    private static void setRunning(NetworkManager manager) throws Exception {
        Field field = NetworkManager.class.getDeclaredField("running");
        field.setAccessible(true);
        AtomicBoolean running = (AtomicBoolean) field.get(manager);
        running.set(true);
    }

    @SuppressWarnings("unchecked")
    private static PeerConnection readyConnection(NetworkManager manager, String localName, String peerName) throws Exception {
        LocalIdentity identity = new LocalIdentity(localName, "26.2", "test", "127.0.0.1", 8901, 25565,
            new byte[0], null);
        PeerConnection connection = new PeerConnection(new InertPeerChannel(), true, identity, peerName, null,
            manager, manager);
        Field field = PeerConnection.class.getDeclaredField("state");
        field.setAccessible(true);
        AtomicReference<PeerConnection.State> state = (AtomicReference<PeerConnection.State>) field.get(connection);
        state.set(PeerConnection.State.READY);
        return connection;
    }

    private static Thread startCaptured(String name, AtomicReference<Throwable> failure, Runnable action) {
        return Thread.ofVirtual().name(name).start(() -> {
            try {
                action.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5L, TimeUnit.SECONDS));
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for lifecycle race release");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for lifecycle race release", interrupted);
        }
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(5_000L);
        assertFalse(thread.isAlive());
    }

    private static final class InertPeerChannel implements PeerTransport.PeerChannel {
        private final InputStream input = new ByteArrayInputStream(new byte[0]);
        private final OutputStream output = new ByteArrayOutputStream();

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public void setReadTimeout(int millis) {
        }

        @Override
        public void setTcpNoDelay(boolean noDelay) {
        }

        @Override
        public String describeRemote() {
            return "inert:0";
        }

        @Override
        public SocketAddress remoteAddress() {
            return new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public boolean isLoopback() {
            return true;
        }

        @Override
        public void close() {
        }
    }
}

package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(30)
class PeerHandshakeAdmissionTest {
    private static final Logger LOGGER = Logger.getLogger("PeerHandshakeAdmissionTest");
    private static final LocalIdentity IDENTITY = new LocalIdentity(
        "local", "26.2", "test", "127.0.0.1", 8901, 25565, new byte[0], null
    );

    @TempDir
    Path tempDir;

    private NetworkManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    void concurrentInboundAdmissionIsBoundedAndReleaseIsExact() throws Exception {
        PeerLinkRegistry registry = new PeerLinkRegistry();
        int attempts = PeerLinkRegistry.MAX_PENDING_INBOUND_HANDSHAKES * 4;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        ConcurrentLinkedQueue<PeerConnection> acceptedConnections = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>(attempts);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < attempts; index++) {
                futures.add(executor.submit(() -> {
                    await(start);
                    PeerConnection connection = connection(new TrackingPeerChannel(), false, NoopListener.INSTANCE);
                    if (registry.tryAddInboundPending(connection)) {
                        accepted.incrementAndGet();
                        acceptedConnections.add(connection);
                    } else {
                        connection.close("rejected");
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertEquals(PeerLinkRegistry.MAX_PENDING_INBOUND_HANDSHAKES, accepted.get());
        assertEquals(PeerLinkRegistry.MAX_PENDING_INBOUND_HANDSHAKES, registry.pendingInboundCount());
        assertEquals(PeerLinkRegistry.MAX_PENDING_INBOUND_HANDSHAKES, registry.pendingCount());

        PeerConnection released = acceptedConnections.remove();
        registry.removePending(released);
        registry.removePending(released);
        assertEquals(PeerLinkRegistry.MAX_PENDING_INBOUND_HANDSHAKES - 1, registry.pendingInboundCount());

        PeerConnection replacement = connection(new TrackingPeerChannel(), false, NoopListener.INSTANCE);
        assertTrue(registry.tryAddInboundPending(replacement));
        assertEquals(PeerLinkRegistry.MAX_PENDING_INBOUND_HANDSHAKES, registry.pendingInboundCount());

        PeerConnection outbound = connection(new TrackingPeerChannel(), true, NoopListener.INSTANCE);
        registry.addPending(outbound);
        assertEquals(PeerLinkRegistry.MAX_PENDING_INBOUND_HANDSHAKES, registry.pendingInboundCount());
        assertEquals(PeerLinkRegistry.MAX_PENDING_INBOUND_HANDSHAKES + 1, registry.pendingCount());

        released.close("test complete");
        registry.closeAll("test complete");
        assertEquals(0, registry.pendingInboundCount());
        assertEquals(0, registry.pendingCount());
    }

    @Test
    void networkRejectsExcessInboundChannelImmediatelyAndRecoversAfterRestart() {
        manager = manager();
        manager.start();
        PeerLinkRegistry registry = manager.links();
        for (int index = 0; index < PeerLinkRegistry.MAX_PENDING_INBOUND_HANDSHAKES; index++) {
            PeerConnection connection = connection(new TrackingPeerChannel(), false, NoopListener.INSTANCE);
            assertTrue(registry.tryAddInboundPending(connection));
        }

        TrackingPeerChannel rejected = new TrackingPeerChannel();
        assertFalse(manager.acceptInbound(rejected));
        assertTrue(rejected.isClosed());
        assertEquals(PeerLinkRegistry.MAX_PENDING_INBOUND_HANDSHAKES, registry.pendingInboundCount());

        manager.stop();
        assertEquals(0, registry.pendingInboundCount());
        assertEquals(0, registry.pendingCount());

        manager.start();
        BlockingPeerChannel admitted = new BlockingPeerChannel();
        assertTrue(manager.acceptInbound(admitted));
        awaitTrue("restarted manager admitted inbound connection", () -> registry.pendingInboundCount() == 1, 5_000L);
        admitted.closeUnchecked();
        awaitTrue("closed inbound connection released admission", () -> registry.pendingInboundCount() == 0, 5_000L);
    }

    @Test
    void failedHandshakeReleasesInboundAdmission() {
        manager = manager();
        manager.start();
        TrackingPeerChannel channel = new TrackingPeerChannel();

        assertTrue(manager.acceptInbound(channel));
        awaitTrue("failed handshake closed channel", channel::isClosed, 5_000L);
        awaitTrue("failed handshake released admission", () -> manager.links().pendingInboundCount() == 0, 5_000L);
    }

    @Test
    void connectionWorkersUseVirtualThreadsAndStartOnce() throws InterruptedException {
        BlockingPeerChannel channel = new BlockingPeerChannel();
        CloseTrackingListener listener = new CloseTrackingListener();
        PeerConnection connection = connection(channel, false, listener);

        connection.start();
        assertTrue(channel.awaitWorkers(5L, TimeUnit.SECONDS));
        assertTrue(channel.inputThread().isVirtual());
        assertTrue(channel.outputThread().isVirtual());
        assertThrows(IllegalStateException.class, connection::start);

        connection.close("test complete");
        assertTrue(listener.awaitClosed(5L, TimeUnit.SECONDS));
    }

    private NetworkManager manager() {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.listenEnabled = false;
        config.serverName = "admission-test";
        config.advertiseHostOverride = "127.0.0.1";
        return new NetworkManager(LOGGER, config, "26.2", "test", 25565, tempDir.resolve("network"));
    }

    private static PeerConnection connection(PeerTransport.PeerChannel channel, boolean dialer, PeerConnection.Listener listener) {
        return new PeerConnection(channel, dialer, IDENTITY, dialer ? "peer" : null, null, listener, null);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting concurrent admission", e);
        }
    }

    private static void awaitTrue(String operation, BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while awaiting " + operation);
            }
        }
        fail("Timed out awaiting " + operation);
    }

    private enum NoopListener implements PeerConnection.Listener {
        INSTANCE;

        @Override
        public boolean approvePeer(PeerConnection connection, String peerName, String mcVersion, String pluginVersion, byte[] publicKey) {
            return false;
        }

        @Override
        public void onReady(PeerConnection connection) {
        }

        @Override
        public void onMessage(PeerConnection connection, WireMessage message) {
        }

        @Override
        public void onClosed(PeerConnection connection, String reason) {
        }
    }

    private static final class CloseTrackingListener implements PeerConnection.Listener {
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public boolean approvePeer(PeerConnection connection, String peerName, String mcVersion, String pluginVersion, byte[] publicKey) {
            return false;
        }

        @Override
        public void onReady(PeerConnection connection) {
        }

        @Override
        public void onMessage(PeerConnection connection, WireMessage message) {
        }

        @Override
        public void onClosed(PeerConnection connection, String reason) {
            closed.countDown();
        }

        private boolean awaitClosed(long timeout, TimeUnit unit) throws InterruptedException {
            return closed.await(timeout, unit);
        }
    }

    private static class TrackingPeerChannel implements PeerTransport.PeerChannel {
        private final InputStream input = new ByteArrayInputStream(new byte[0]);
        private final OutputStream output = new ByteArrayOutputStream();
        private final AtomicBoolean closed = new AtomicBoolean(false);

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
            return "test:0";
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
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                input.close();
                output.close();
            }
        }

        boolean isClosed() {
            return closed.get();
        }
    }

    private static final class BlockingPeerChannel extends TrackingPeerChannel {
        private final BlockingInputStream input = new BlockingInputStream();
        private final OutputStream output = new ByteArrayOutputStream();
        private final CountDownLatch workers = new CountDownLatch(2);
        private final AtomicReference<Thread> inputThread = new AtomicReference<>();
        private final AtomicReference<Thread> outputThread = new AtomicReference<>();

        @Override
        public InputStream getInputStream() {
            inputThread.set(Thread.currentThread());
            workers.countDown();
            return input;
        }

        @Override
        public OutputStream getOutputStream() {
            outputThread.set(Thread.currentThread());
            workers.countDown();
            return output;
        }

        @Override
        public void close() throws IOException {
            super.close();
            input.close();
            output.close();
        }

        private boolean awaitWorkers(long timeout, TimeUnit unit) throws InterruptedException {
            return workers.await(timeout, unit);
        }

        private Thread inputThread() {
            return inputThread.get();
        }

        private Thread outputThread() {
            return outputThread.get();
        }

        private void closeUnchecked() {
            try {
                close();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class BlockingInputStream extends InputStream {
        private final Object gate = new Object();
        private boolean closed;

        @Override
        public int read() throws IOException {
            synchronized (gate) {
                while (!closed) {
                    try {
                        gate.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while reading", e);
                    }
                }
            }
            return -1;
        }

        @Override
        public void close() {
            synchronized (gate) {
                closed = true;
                gate.notifyAll();
            }
        }
    }
}

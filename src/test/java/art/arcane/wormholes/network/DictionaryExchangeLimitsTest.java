package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DictionaryExchangeLimitsTest {
    private static final Logger LOGGER = Logger.getLogger("DictionaryExchangeLimitsTest");

    @TempDir
    Path tempDir;

    @Test
    void codecRejectsChunkCountsBeforeAllocation() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1);
        out.writeInt(0);
        out.writeInt(WireMessage.DictData.MAX_CHUNKS + 1);
        out.flush();

        assertThrows(java.io.IOException.class,
            () -> WireMessage.DictData.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
    }

    @Test
    void unsolicitedAndIncoherentDataNeverCreateTransfers() {
        TestContext context = context();
        PeerConnection connection = connection(context.manager(), "peer-a");
        byte[] hash = hash((byte) 1);
        byte[] fullChunk = new byte[WireMessage.DictData.MAX_CHUNK_BYTES];

        context.exchange().handleMessage(connection, new WireMessage.DictData(1, 0, 2, hash, fullChunk));
        assertEquals(0, context.exchange().activeInboundTransfers());

        context.exchange().handleMessage(connection,
            new WireMessage.DictOffer(1, hash, WireMessage.DictData.MAX_CHUNK_BYTES + 1));
        context.exchange().handleMessage(connection, new WireMessage.DictData(1, 0, 1, hash, fullChunk));
        assertEquals(0, context.exchange().activeInboundTransfers());

        context.exchange().handleMessage(connection,
            new WireMessage.DictOffer(1, hash, WireMessage.DictData.MAX_CHUNK_BYTES + 1));
        context.exchange().handleMessage(connection,
            new WireMessage.DictData(1, 0, 2, hash((byte) 2), fullChunk));
        assertEquals(0, context.exchange().activeInboundTransfers());
    }

    @Test
    void dictionaryRequestsRequireTheMatchingOutboundOffer() {
        TestContext context = context();
        PeerConnection connection = connection(context.manager(), "peer-a");
        CompressionDictionary dictionary = CompressionDictionary.of(new byte[]{1, 2, 3, 4});
        context.exchange().compression().installDictionary(dictionary);

        context.exchange().handleMessage(connection, new WireMessage.DictRequest(dictionary.version()));
        assertEquals(0, connection.getWriteQueueSize());

        context.exchange().onPeerReady(connection);
        assertEquals(1, connection.getWriteQueueSize());
        context.exchange().handleMessage(connection, new WireMessage.DictRequest(dictionary.version() + 1));
        assertEquals(1, connection.getWriteQueueSize());

        context.exchange().handleMessage(connection, new WireMessage.DictRequest(dictionary.version()));
        assertEquals(2, connection.getWriteQueueSize());

        context.exchange().handleMessage(connection, new WireMessage.DictRequest(dictionary.version()));
        assertEquals(2, connection.getWriteQueueSize());
    }

    @Test
    void disablingCompressionClearsTransfersAndSuppressesOffers() {
        TestContext context = context();
        PeerConnection connection = connection(context.manager(), "peer-a");
        byte[] hash = hash((byte) 7);
        byte[] fullChunk = new byte[WireMessage.DictData.MAX_CHUNK_BYTES];
        offerAndFirstChunk(context.exchange(), connection, 1, hash, fullChunk);
        assertEquals(1, context.exchange().activeInboundTransfers());
        CompressionDictionary dictionary = CompressionDictionary.of(new byte[]{1, 2, 3, 4});
        context.exchange().compression().installDictionary(dictionary);

        NetworkConfig disabled = context.manager().activeConfig();
        disabled.transport.compressionEnabled = false;
        context.exchange().applyConfig(disabled);
        context.exchange().onPeerReady(connection);

        assertEquals(0, context.exchange().activeInboundTransfers());
        assertEquals(1, connection.getWriteQueueSize());
    }

    @Test
    void conflictingDuplicateChunksDiscardTheTransfer() {
        TestContext context = context();
        PeerConnection connection = connection(context.manager(), "peer-a");
        byte[] hash = hash((byte) 6);
        byte[] firstChunk = new byte[WireMessage.DictData.MAX_CHUNK_BYTES];
        byte[] conflictingChunk = firstChunk.clone();
        conflictingChunk[0] = 1;
        context.exchange().handleMessage(connection,
            new WireMessage.DictOffer(1, hash, WireMessage.DictData.MAX_CHUNK_BYTES + 1));
        context.exchange().handleMessage(connection, new WireMessage.DictData(1, 0, 2, hash, firstChunk));
        assertEquals(1, context.exchange().activeInboundTransfers());

        context.exchange().handleMessage(connection, new WireMessage.DictData(1, 0, 2, hash, conflictingChunk));

        assertEquals(0, context.exchange().activeInboundTransfers());
        assertEquals(0L, context.exchange().retainedInboundBytes());
    }

    @Test
    void globalAndPerPeerActiveTransferLimitsAreEnforced() {
        TestContext perPeer = context();
        byte[] hash = hash((byte) 3);
        byte[] fullChunk = new byte[WireMessage.DictData.MAX_CHUNK_BYTES];
        for (int index = 0; index < DictionaryExchange.MAX_ACTIVE_TRANSFERS_PER_PEER + 1; index++) {
            PeerConnection connection = connection(perPeer.manager(), "shared-peer");
            offerAndFirstChunk(perPeer.exchange(), connection, index + 1, hash, fullChunk);
        }
        assertEquals(DictionaryExchange.MAX_ACTIVE_TRANSFERS_PER_PEER, perPeer.exchange().activeInboundTransfers());

        TestContext global = context();
        for (int index = 0; index < DictionaryExchange.MAX_ACTIVE_TRANSFERS_GLOBAL + 1; index++) {
            PeerConnection connection = connection(global.manager(), "peer-" + index);
            offerAndFirstChunk(global.exchange(), connection, index + 1, hash, fullChunk);
        }
        assertEquals(DictionaryExchange.MAX_ACTIVE_TRANSFERS_GLOBAL, global.exchange().activeInboundTransfers());
    }

    @Test
    void retainedByteLimitsRejectTheTransferThatWouldCrossThem() {
        TestContext context = context();
        byte[] hash = hash((byte) 4);
        byte[] fullChunk = new byte[WireMessage.DictData.MAX_CHUNK_BYTES];
        List<PeerConnection> connections = new ArrayList<>();
        for (int index = 0; index < DictionaryExchange.MAX_ACTIVE_TRANSFERS_PER_PEER; index++) {
            PeerConnection connection = connection(context.manager(), "shared-peer");
            connections.add(connection);
            context.exchange().handleMessage(connection,
                new WireMessage.DictOffer(index + 1, hash, WireMessage.DictData.MAX_DICTIONARY_BYTES));
            for (int chunkIndex = 0; chunkIndex < 8; chunkIndex++) {
                context.exchange().handleMessage(connection,
                    new WireMessage.DictData(index + 1, chunkIndex, WireMessage.DictData.MAX_CHUNKS, hash, fullChunk));
            }
        }
        assertEquals(DictionaryExchange.MAX_RETAINED_BYTES_PER_PEER, context.exchange().retainedInboundBytes());

        PeerConnection last = connections.get(connections.size() - 1);
        context.exchange().handleMessage(last,
            new WireMessage.DictData(connections.size(), 8, WireMessage.DictData.MAX_CHUNKS, hash, fullChunk));

        assertTrue(context.exchange().retainedInboundBytes() < DictionaryExchange.MAX_RETAINED_BYTES_PER_PEER);
        assertEquals(DictionaryExchange.MAX_ACTIVE_TRANSFERS_PER_PEER - 1, context.exchange().activeInboundTransfers());
    }

    @Test
    void expiryAndDisconnectReleaseIncompleteTransfers() {
        TestContext context = context();
        byte[] hash = hash((byte) 5);
        byte[] fullChunk = new byte[WireMessage.DictData.MAX_CHUNK_BYTES];
        PeerConnection disconnected = connection(context.manager(), "peer-a");
        offerAndFirstChunk(context.exchange(), disconnected, 1, hash, fullChunk);
        context.exchange().onPeerClosed(disconnected);
        assertEquals(0, context.exchange().activeInboundTransfers());
        assertEquals(0L, context.exchange().retainedInboundBytes());

        PeerConnection expired = connection(context.manager(), "peer-b");
        offerAndFirstChunk(context.exchange(), expired, 2, hash, fullChunk);
        context.exchange().purgeExpired(Long.MAX_VALUE);
        assertEquals(0, context.exchange().activeInboundTransfers());
        assertEquals(0L, context.exchange().retainedInboundBytes());
    }

    private TestContext context() {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = "local";
        config.transport.compressionEnabled = true;
        NetworkManager manager = new NetworkManager(LOGGER, config, "26.2", "test", 25565, tempDir.resolve("manager-" + System.nanoTime()));
        DictionaryExchange exchange = new DictionaryExchange(manager, LOGGER, tempDir.resolve("exchange-" + System.nanoTime()), config);
        return new TestContext(manager, exchange);
    }

    private static PeerConnection connection(NetworkManager manager, String peerName) {
        LocalIdentity identity = new LocalIdentity("local", "26.2", "test", "127.0.0.1", 8901, 25565,
            new byte[0], null);
        return new PeerConnection(new InertPeerChannel(), true, identity, peerName, null, manager, manager);
    }

    private static void offerAndFirstChunk(DictionaryExchange exchange, PeerConnection connection, int version,
                                           byte[] hash, byte[] chunk) {
        exchange.handleMessage(connection,
            new WireMessage.DictOffer(version, hash, WireMessage.DictData.MAX_CHUNK_BYTES + 1));
        exchange.handleMessage(connection, new WireMessage.DictData(version, 0, 2, hash, chunk));
    }

    private static byte[] hash(byte value) {
        byte[] hash = new byte[CompressionDictionary.HASH_LENGTH];
        Arrays.fill(hash, value);
        return hash;
    }

    private record TestContext(NetworkManager manager, DictionaryExchange exchange) {
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

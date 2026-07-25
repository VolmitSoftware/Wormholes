package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

final class DictionaryExchange {
    private static final int RETRAIN_HOLDOUT_STRIDE = 16;
    private static final int RETRAIN_HOLDOUT_CAP_BYTES = 512 * 1024;
    private static final double RETRAIN_ADOPT_RATIO = 0.97D;
    private static final Set<WireMessageType> SAMPLE_EXCLUDED_TYPES = EnumSet.of(
        WireMessageType.HELLO, WireMessageType.CHALLENGE, WireMessageType.AUTH, WireMessageType.READY,
        WireMessageType.PING, WireMessageType.PONG,
        WireMessageType.DICT_OFFER, WireMessageType.DICT_REQUEST, WireMessageType.DICT_DATA);

    private final NetworkManager network;
    private final Logger logger;
    private final Path dataDirectory;
    private final WireCompression wireCompression;
    private final DictionarySampleCollector sampleCollector;
    private final Map<String, DictionaryTransfer> inboundDictionaries = new ConcurrentHashMap<>();
    private final AtomicBoolean retrainInFlight = new AtomicBoolean();

    DictionaryExchange(NetworkManager network, Logger logger, Path dataDirectory, NetworkConfig config) {
        this.network = network;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.wireCompression = new WireCompression(config.transport.compressionLevel);
        this.sampleCollector = new DictionarySampleCollector(Math.max(64 * 1024, config.transport.compressionDictTrainBytes));
    }

    WireCompression compression() {
        return wireCompression;
    }

    DictionarySampleCollector sampleCollector() {
        return sampleCollector;
    }

    CompressionDictionary currentDictionary() {
        return wireCompression.currentDictionary();
    }

    boolean compressionEnabled() {
        return network.activeConfig().transport.compressionEnabled;
    }

    void applyConfig(NetworkConfig next) {
        wireCompression.setCompressionLevel(next.transport.compressionLevel);
        sampleCollector.setBudgetBytes(Math.max(64 * 1024, next.transport.compressionDictTrainBytes));
    }

    void clearInboundTransfers() {
        inboundDictionaries.clear();
    }

    void recordSample(WireMessageType type, byte[] payload) {
        if (!network.activeConfig().transport.compressionEnabled || SAMPLE_EXCLUDED_TYPES.contains(type) || sampleCollector.isFull()) {
            return;
        }
        sampleCollector.record(payload);
    }

    void recordFrameSample(OutboundFrame frame) {
        if (!network.activeConfig().transport.compressionEnabled || sampleCollector.isFull() || SAMPLE_EXCLUDED_TYPES.contains(frame.message().type())) {
            return;
        }
        try {
            sampleCollector.record(frame.payload());
        } catch (IOException ignored) {
        }
    }

    void onDictionaryAdvertised(PeerConnection connection, byte[] peerDictHash, int peerDictVersion) {
        if (peerDictHash == null || peerDictVersion <= 0 || isZeroHash(peerDictHash)) {
            return;
        }
        CompressionDictionary local = wireCompression.currentDictionary();
        if (local != null && local.version() == peerDictVersion && CompressionDictionary.sameHash(local.hash(), peerDictHash)) {
            return;
        }
        if (local != null && CompressionDictionary.sameHash(local.hash(), peerDictHash)) {
            return;
        }
        connection.send(new WireMessage.DictRequest(peerDictVersion));
    }

    void onDictionaryNegotiated(PeerConnection connection, int dictVersion) {
        logger.fine("net: dict v" + dictVersion + " negotiated with " + connection.getPeerName());
    }

    boolean handleMessage(PeerConnection connection, WireMessage message) {
        if (message instanceof WireMessage.DictOffer offer) {
            handleDictOffer(connection, offer);
            return true;
        }
        if (message instanceof WireMessage.DictRequest request) {
            handleDictRequest(connection, request);
            return true;
        }
        if (message instanceof WireMessage.DictData data) {
            handleDictData(connection, data);
            return true;
        }
        return false;
    }

    private void handleDictOffer(PeerConnection connection, WireMessage.DictOffer offer) {
        if (!network.activeConfig().transport.compressionEnabled || offer.version() <= 0) {
            return;
        }
        connection.updatePeerDictionary(offer.hash(), offer.version());
        CompressionDictionary local = wireCompression.currentDictionary();
        if (local != null && CompressionDictionary.sameHash(local.hash(), offer.hash())) {
            if (connection.getNegotiatedDictVersion() != local.version()) {
                connection.enableDictionary(local.version());
                onDictionaryNegotiated(connection, local.version());
            }
            return;
        }
        connection.send(new WireMessage.DictRequest(offer.version()));
    }

    private void handleDictRequest(PeerConnection connection, WireMessage.DictRequest request) {
        CompressionDictionary local = wireCompression.currentDictionary();
        if (local == null || local.version() != request.version()) {
            return;
        }
        byte[] bytes = local.bytes();
        int chunkSize = WireMessage.DictData.MAX_CHUNK_BYTES;
        int total = Math.max(1, (bytes.length + chunkSize - 1) / chunkSize);
        for (int index = 0; index < total; index++) {
            int offset = index * chunkSize;
            int length = Math.min(chunkSize, bytes.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(bytes, offset, chunk, 0, length);
            connection.send(new WireMessage.DictData(local.version(), index, total, local.hash(), chunk));
        }
    }

    private void handleDictData(PeerConnection connection, WireMessage.DictData data) {
        if (data.version() <= 0 || data.chunkTotal() <= 0 || data.chunkIndex() < 0 || data.chunkIndex() >= data.chunkTotal()) {
            return;
        }
        String key = connection.getPeerName() + "@" + data.version();
        byte[] completedBytes = null;
        synchronized (inboundDictionaries) {
            DictionaryTransfer transfer = inboundDictionaries.get(key);
            if (transfer == null || transfer.total != data.chunkTotal() || transfer.version != data.version()) {
                transfer = new DictionaryTransfer(data.version(), data.chunkTotal(), data.hash());
                inboundDictionaries.put(key, transfer);
            }
            transfer.addChunk(data.chunkIndex(), data.chunk());
            if (transfer.isComplete()) {
                completedBytes = transfer.assemble();
                inboundDictionaries.remove(key);
            }
        }
        if (completedBytes == null) {
            return;
        }
        installInboundDictionary(connection, completedBytes, data.version(), data.hash());
    }

    private void installInboundDictionary(PeerConnection connection, byte[] dictBytes, int version, byte[] expectedHash) {
        try {
            CompressionDictionary candidate = CompressionDictionary.of(dictBytes);
            if (!CompressionDictionary.sameHash(candidate.hash(), expectedHash)) {
                logger.warning("net: dict v" + version + " from " + connection.getPeerName() + " failed hash check, ignoring");
                return;
            }
            demoteMismatchedPeers(candidate.version());
            wireCompression.installDictionary(candidate);
            try {
                candidate.save(dataDirectory.resolve("dict"));
            } catch (IOException e) {
                logger.warning("net: could not persist dict v" + version + ": " + e.getMessage());
            }
            connection.updatePeerDictionary(expectedHash, version);
            broadcastDictOffer(candidate);
            renegotiateDictionaryWithPeers(candidate);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "net: failed to install inbound dictionary v" + version + " from " + connection.getPeerName(), e);
        }
    }

    private void demoteMismatchedPeers(int newVersion) {
        for (PeerConnection connection : network.links().readyConnections()) {
            if (connection.isDictionaryNegotiated() && connection.getNegotiatedDictVersion() != newVersion) {
                connection.disableDictionary();
            }
        }
    }

    private void renegotiateDictionaryWithPeers(CompressionDictionary local) {
        for (PeerConnection connection : network.links().readyConnections()) {
            byte[] peerHash = connection.getPeerDictHash();
            if (peerHash != null && CompressionDictionary.sameHash(local.hash(), peerHash)) {
                connection.enableDictionary(local.version());
            }
        }
    }

    private void broadcastDictOffer(CompressionDictionary local) {
        OutboundFrame offer = new OutboundFrame(new WireMessage.DictOffer(local.version(), local.hash(), local.bytes().length));
        for (PeerConnection connection : network.links().readyConnections()) {
            connection.send(offer);
        }
    }

    void loadPersisted(NetworkConfig active) {
        if (!active.transport.compressionEnabled) {
            return;
        }
        Path dictDir = dataDirectory.resolve("dict");
        if (!Files.isDirectory(dictDir)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(dictDir)) {
            Path latest = stream.filter(path -> path.getFileName().toString().endsWith(".zdict"))
                .reduce((a, b) -> a.toFile().lastModified() >= b.toFile().lastModified() ? a : b)
                .orElse(null);
            if (latest == null) {
                return;
            }
            CompressionDictionary loaded = CompressionDictionary.load(latest);
            wireCompression.installDictionary(loaded);
            logger.info("net: restored compression dict v" + loaded.version() + " from " + latest.getFileName());
        } catch (IOException e) {
            logger.warning("net: could not restore compression dict: " + e.getMessage());
        }
    }

    void maybeRetrain() {
        if (!network.activeConfig().transport.compressionEnabled || !sampleCollector.isFull()) {
            return;
        }
        if (!retrainInFlight.compareAndSet(false, true)) {
            return;
        }
        Thread trainer = new Thread(this::retrainNow, "Wormholes-Dict-Train");
        trainer.setDaemon(true);
        trainer.start();
    }

    void retrainNow() {
        try {
            NetworkConfig active = network.activeConfig();
            List<byte[]> snapshot = sampleCollector.snapshot();
            if (snapshot.isEmpty()) {
                return;
            }
            List<byte[]> holdout = new ArrayList<>();
            List<byte[]> training = new ArrayList<>(snapshot.size());
            long holdoutBytes = 0L;
            for (int index = 0; index < snapshot.size(); index++) {
                byte[] sample = snapshot.get(index);
                if (index % RETRAIN_HOLDOUT_STRIDE == 0 && holdoutBytes < RETRAIN_HOLDOUT_CAP_BYTES) {
                    holdout.add(sample);
                    holdoutBytes += sample.length;
                } else {
                    training.add(sample);
                }
            }
            if (training.isEmpty()) {
                training = snapshot;
                holdout = List.of();
            }
            try {
                CompressionDictionary trained = CompressionDictionary.train(training, active.transport.compressionDictTargetSize);
                CompressionDictionary existing = wireCompression.currentDictionary();
                if (existing != null && CompressionDictionary.sameHash(existing.hash(), trained.hash())) {
                    sampleCollector.reset();
                    return;
                }
                boolean adopt;
                if (existing == null || holdout.isEmpty()) {
                    adopt = true;
                } else {
                    long oldBytes = CompressionDictionary.compressedSizeSum(holdout, existing.bytes(), wireCompression.compressionLevel());
                    long newBytes = CompressionDictionary.compressedSizeSum(holdout, trained.bytes(), wireCompression.compressionLevel());
                    adopt = (double) newBytes < (double) oldBytes * RETRAIN_ADOPT_RATIO;
                }
                if (!adopt) {
                    logger.fine("net: dict retrain rejected, holdout shows no measurable improvement");
                    sampleCollector.reset();
                    return;
                }
                if (!network.isRunning()) {
                    return;
                }
                demoteMismatchedPeers(trained.version());
                wireCompression.installDictionary(trained);
                try {
                    trained.save(dataDirectory.resolve("dict"));
                } catch (IOException e) {
                    logger.warning("net: could not persist trained dict v" + trained.version() + ": " + e.getMessage());
                }
                broadcastDictOffer(trained);
                sampleCollector.reset();
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "net: dict training failed", e);
            }
        } finally {
            retrainInFlight.set(false);
        }
    }

    private static boolean isZeroHash(byte[] hash) {
        if (hash == null || hash.length == 0) {
            return true;
        }
        for (byte b : hash) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static final class DictionaryTransfer {
        private final int version;
        private final int total;
        private final byte[] expectedHash;
        private final byte[][] chunks;
        private int received;

        private DictionaryTransfer(int version, int total, byte[] expectedHash) {
            this.version = version;
            this.total = total;
            this.expectedHash = expectedHash;
            this.chunks = new byte[total][];
        }

        private void addChunk(int index, byte[] chunk) {
            if (index < 0 || index >= total) {
                return;
            }
            if (chunks[index] != null) {
                return;
            }
            chunks[index] = chunk;
            received++;
        }

        private boolean isComplete() {
            return received == total;
        }

        private byte[] assemble() {
            int totalBytes = 0;
            for (byte[] chunk : chunks) {
                totalBytes += chunk.length;
            }
            byte[] dict = new byte[totalBytes];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, dict, offset, chunk.length);
                offset += chunk.length;
            }
            return dict;
        }
    }
}

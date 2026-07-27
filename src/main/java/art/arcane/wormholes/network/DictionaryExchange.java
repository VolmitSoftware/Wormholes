package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
    static final int MAX_ACTIVE_TRANSFERS_GLOBAL = 32;
    static final int MAX_ACTIVE_TRANSFERS_PER_PEER = 4;
    static final long MAX_RETAINED_BYTES_GLOBAL = 16L * 1024L * 1024L;
    static final long MAX_RETAINED_BYTES_PER_PEER = 2L * 1024L * 1024L;
    static final long TRANSFER_TTL_MILLIS = 30_000L;
    private static final Set<WireMessageType> SAMPLE_EXCLUDED_TYPES = EnumSet.of(
        WireMessageType.HELLO, WireMessageType.CHALLENGE, WireMessageType.AUTH, WireMessageType.READY,
        WireMessageType.PING, WireMessageType.PONG,
        WireMessageType.DICT_OFFER, WireMessageType.DICT_REQUEST, WireMessageType.DICT_DATA);

    private final NetworkManager network;
    private final Logger logger;
    private final Path dataDirectory;
    private final WireCompression wireCompression;
    private final DictionarySampleCollector sampleCollector;
    private final Map<TransferKey, DictionaryTransfer> inboundDictionaries = new ConcurrentHashMap<>();
    private final Map<PeerConnection, InboundOffer> inboundOffers = new ConcurrentHashMap<>();
    private final Map<PeerConnection, OutboundOffer> outboundOffers = new ConcurrentHashMap<>();
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
        if (!next.transport.compressionEnabled) {
            clearInboundTransfers();
        }
    }

    void clearInboundTransfers() {
        synchronized (inboundDictionaries) {
            inboundDictionaries.clear();
            inboundOffers.clear();
            outboundOffers.clear();
        }
    }

    void onPeerReady(PeerConnection connection) {
        if (!network.activeConfig().transport.compressionEnabled) {
            return;
        }
        CompressionDictionary local = wireCompression.currentDictionary();
        if (local != null) {
            sendOffer(connection, local);
        }
    }

    void onPeerClosed(PeerConnection connection) {
        synchronized (inboundDictionaries) {
            inboundDictionaries.keySet().removeIf(key -> key.connection() == connection);
            inboundOffers.remove(connection);
            outboundOffers.remove(connection);
        }
    }

    void purgeExpired(long nowMillis) {
        synchronized (inboundDictionaries) {
            purgeExpiredLocked(nowMillis);
        }
    }

    int activeInboundTransfers() {
        synchronized (inboundDictionaries) {
            return inboundDictionaries.size();
        }
    }

    long retainedInboundBytes() {
        synchronized (inboundDictionaries) {
            return retainedBytesLocked(null);
        }
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
        long nowMillis = System.currentTimeMillis();
        if (!network.activeConfig().transport.compressionEnabled || !validOffer(offer)) {
            return;
        }
        synchronized (inboundDictionaries) {
            purgeExpiredLocked(nowMillis);
            removeTransfersLocked(connection);
            inboundOffers.put(connection, new InboundOffer(offer.version(), offer.hash().clone(), offer.sizeBytes(), nowMillis + TRANSFER_TTL_MILLIS));
        }
        connection.updatePeerDictionary(offer.hash(), offer.version());
        CompressionDictionary local = wireCompression.currentDictionary();
        if (local != null && CompressionDictionary.sameHash(local.hash(), offer.hash())) {
            synchronized (inboundDictionaries) {
                inboundOffers.remove(connection);
            }
            if (connection.getNegotiatedDictVersion() != local.version()) {
                connection.enableDictionary(local.version());
                onDictionaryNegotiated(connection, local.version());
            }
            return;
        }
        if (!connection.send(new WireMessage.DictRequest(offer.version()))) {
            synchronized (inboundDictionaries) {
                inboundOffers.remove(connection);
            }
        }
    }

    private void handleDictRequest(PeerConnection connection, WireMessage.DictRequest request) {
        if (!network.activeConfig().transport.compressionEnabled) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        CompressionDictionary local = wireCompression.currentDictionary();
        OutboundOffer offer;
        synchronized (inboundDictionaries) {
            purgeExpiredLocked(nowMillis);
            offer = outboundOffers.get(connection);
            if (local == null || offer == null || local.version() != request.version() || !offer.matches(local)) {
                return;
            }
            outboundOffers.remove(connection, offer);
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
        if (!network.activeConfig().transport.compressionEnabled) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        if (data.version() <= 0 || data.chunkTotal() <= 0 || data.chunkTotal() > WireMessage.DictData.MAX_CHUNKS
            || data.chunkIndex() < 0 || data.chunkIndex() >= data.chunkTotal()) {
            return;
        }
        byte[] completedBytes = null;
        byte[] expectedHash = null;
        synchronized (inboundDictionaries) {
            purgeExpiredLocked(nowMillis);
            InboundOffer offer = inboundOffers.get(connection);
            if (offer == null || !offer.matches(data) || !validChunkShape(offer, data)) {
                rejectInboundLocked(connection);
                return;
            }
            TransferKey key = new TransferKey(connection, data.version());
            DictionaryTransfer transfer = inboundDictionaries.get(key);
            if (transfer == null) {
                if (!canStartTransferLocked(connection.getPeerName())) {
                    rejectInboundLocked(connection);
                    return;
                }
                transfer = new DictionaryTransfer(connection.getPeerName(), offer, nowMillis);
                inboundDictionaries.put(key, transfer);
            }
            int additionalBytes = transfer.additionalBytes(data.chunkIndex(), data.chunk());
            if (!canRetainLocked(connection.getPeerName(), additionalBytes)) {
                rejectInboundLocked(connection);
                return;
            }
            if (!transfer.accepts(data.chunkIndex(), data.chunk())) {
                rejectInboundLocked(connection);
                return;
            }
            transfer.addChunk(data.chunkIndex(), data.chunk());
            transfer.touch(nowMillis);
            if (transfer.isComplete()) {
                completedBytes = transfer.assemble();
                inboundDictionaries.remove(key);
                inboundOffers.remove(connection);
                expectedHash = offer.hash();
            }
        }
        if (completedBytes == null) {
            return;
        }
        installInboundDictionary(connection, completedBytes, data.version(), expectedHash);
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
        for (PeerConnection connection : network.links().readyConnections()) {
            sendOffer(connection, local);
        }
    }

    private boolean sendOffer(PeerConnection connection, CompressionDictionary local) {
        if (!network.activeConfig().transport.compressionEnabled) {
            return false;
        }
        int sizeBytes = local.bytes().length;
        if (sizeBytes <= 0 || sizeBytes > WireMessage.DictData.MAX_DICTIONARY_BYTES) {
            return false;
        }
        WireMessage.DictOffer message = new WireMessage.DictOffer(local.version(), local.hash(), sizeBytes);
        OutboundOffer offer = new OutboundOffer(
            local.version(),
            local.hash().clone(),
            sizeBytes,
            System.currentTimeMillis() + TRANSFER_TTL_MILLIS
        );
        synchronized (inboundDictionaries) {
            outboundOffers.put(connection, offer);
        }
        if (!connection.send(message)) {
            synchronized (inboundDictionaries) {
                outboundOffers.remove(connection, offer);
            }
            return false;
        }
        return true;
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

    private boolean canStartTransferLocked(String peerName) {
        if (inboundDictionaries.size() >= MAX_ACTIVE_TRANSFERS_GLOBAL) {
            return false;
        }
        int peerTransfers = 0;
        for (DictionaryTransfer transfer : inboundDictionaries.values()) {
            if (transfer.peerName.equals(peerName)) {
                peerTransfers++;
            }
        }
        return peerTransfers < MAX_ACTIVE_TRANSFERS_PER_PEER;
    }

    private boolean canRetainLocked(String peerName, int additionalBytes) {
        if (additionalBytes < 0) {
            return false;
        }
        return retainedBytesLocked(null) + additionalBytes <= MAX_RETAINED_BYTES_GLOBAL
            && retainedBytesLocked(peerName) + additionalBytes <= MAX_RETAINED_BYTES_PER_PEER;
    }

    private long retainedBytesLocked(String peerName) {
        long total = 0L;
        for (DictionaryTransfer transfer : inboundDictionaries.values()) {
            if (peerName == null || transfer.peerName.equals(peerName)) {
                total += transfer.retainedBytes;
            }
        }
        return total;
    }

    private void purgeExpiredLocked(long nowMillis) {
        inboundDictionaries.values().removeIf(transfer -> transfer.lastActivityMillis + TRANSFER_TTL_MILLIS <= nowMillis);
        inboundOffers.values().removeIf(offer -> offer.expiresAtMillis() <= nowMillis);
        outboundOffers.values().removeIf(offer -> offer.expiresAtMillis() <= nowMillis);
    }

    private void rejectInboundLocked(PeerConnection connection) {
        removeTransfersLocked(connection);
        inboundOffers.remove(connection);
    }

    private void removeTransfersLocked(PeerConnection connection) {
        inboundDictionaries.keySet().removeIf(key -> key.connection() == connection);
    }

    private static boolean validOffer(WireMessage.DictOffer offer) {
        return offer.version() > 0
            && offer.sizeBytes() > 0
            && offer.sizeBytes() <= WireMessage.DictData.MAX_DICTIONARY_BYTES
            && offer.hash() != null
            && offer.hash().length == CompressionDictionary.HASH_LENGTH
            && !isZeroHash(offer.hash());
    }

    private static boolean validChunkShape(InboundOffer offer, WireMessage.DictData data) {
        int expectedChunks = Math.max(1,
            (offer.sizeBytes() + WireMessage.DictData.MAX_CHUNK_BYTES - 1) / WireMessage.DictData.MAX_CHUNK_BYTES);
        if (data.chunkTotal() != expectedChunks) {
            return false;
        }
        int offset = data.chunkIndex() * WireMessage.DictData.MAX_CHUNK_BYTES;
        int expectedLength = Math.min(WireMessage.DictData.MAX_CHUNK_BYTES, offer.sizeBytes() - offset);
        return data.chunk().length == expectedLength;
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

    private record TransferKey(PeerConnection connection, int version) {
    }

    private record InboundOffer(int version, byte[] hash, int sizeBytes, long expiresAtMillis) {
        private boolean matches(WireMessage.DictData data) {
            return version == data.version() && CompressionDictionary.sameHash(hash, data.hash());
        }
    }

    private record OutboundOffer(int version, byte[] hash, int sizeBytes, long expiresAtMillis) {
        private boolean matches(CompressionDictionary dictionary) {
            return version == dictionary.version()
                && sizeBytes == dictionary.bytes().length
                && CompressionDictionary.sameHash(hash, dictionary.hash());
        }
    }

    private static final class DictionaryTransfer {
        private final String peerName;
        private final int total;
        private final int expectedBytes;
        private final byte[][] chunks;
        private int received;
        private long retainedBytes;
        private long lastActivityMillis;

        private DictionaryTransfer(String peerName, InboundOffer offer, long nowMillis) {
            this.peerName = peerName;
            this.total = Math.max(1,
                (offer.sizeBytes() + WireMessage.DictData.MAX_CHUNK_BYTES - 1) / WireMessage.DictData.MAX_CHUNK_BYTES);
            this.expectedBytes = offer.sizeBytes();
            this.chunks = new byte[total][];
            this.lastActivityMillis = nowMillis;
        }

        private int additionalBytes(int index, byte[] chunk) {
            return chunks[index] == null ? chunk.length : 0;
        }

        private boolean accepts(int index, byte[] chunk) {
            return chunks[index] == null || Arrays.equals(chunks[index], chunk);
        }

        private void addChunk(int index, byte[] chunk) {
            if (chunks[index] != null) {
                return;
            }
            chunks[index] = chunk;
            received++;
            retainedBytes += chunk.length;
        }

        private void touch(long nowMillis) {
            lastActivityMillis = nowMillis;
        }

        private boolean isComplete() {
            return received == total;
        }

        private byte[] assemble() {
            byte[] dict = new byte[expectedBytes];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, dict, offset, chunk.length);
                offset += chunk.length;
            }
            if (offset != expectedBytes) {
                throw new IllegalStateException("Dictionary transfer size mismatch: " + offset + " != " + expectedBytes);
            }
            return dict;
        }
    }
}
